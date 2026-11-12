package prism.srm.platform.linux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An {@link SdBusApi} that follows the sd-bus man pages for the calls S3A makes: replies come from a script keyed by
 * path, interface, member and arguments; {@code enter_container} refuses a variant whose contents differ ({@code
 * -ENXIO}); {@code read_basic} refuses a value of another type; {@code exit_container} refuses while the value is
 * unread; {@code has_signature} compares whole signatures. It counts open connections and live messages, refuses a
 * double release, and records every call sent.
 */
final class FakeSdBus implements SdBusApi {

    static final int ENXIO = 6;
    static final int EBUSY = 16;
    static final int EIO = 5;

    /** One item of a reply: a plain value of a type, or a variant with its contents signature. */
    record Item(char type, String contents, Object value) {

        static Item object(String path) {
            return new Item('o', "", path);
        }

        static Item variant(String contents, Object value) {
            return new Item('v', contents, value);
        }
    }

    /** A scripted answer: an error, or a reply with a signature and items. */
    record Answer(int code, Optional<String> errorName, String signature, List<Item> items) {

        static Answer reply(String signature, Item... items) {
            return new Answer(1, Optional.empty(), signature, List.of(items));
        }

        static Answer error(int code, String name) {
            return new Answer(code, Optional.ofNullable(name), "", List.of());
        }

        /** An error reply with a message text (kept in the signature slot, which an error has no use for). */
        static Answer error(int code, String name, String message) {
            return new Answer(code, Optional.ofNullable(name), message == null ? "" : message, List.of());
        }
    }

    private static final class Message {
        final List<Object> arguments = new ArrayList<>();
        final String path;
        final String iface;
        final String member;
        String signature = "";
        List<Item> items = List.of();
        int position;
        boolean inVariant;
        boolean variantRead;

        Message(String path, String iface, String member) {
            this.path = path;
            this.iface = iface;
            this.member = member;
        }
    }

    final Map<String, Answer> script = new HashMap<>();
    final List<String> sent = new ArrayList<>();
    int openCode = 1;
    int timeoutCode = 1;
    final List<Long> callTimeouts = new ArrayList<>();
    int opened;
    int closed;
    private long next = 100;
    private final Set<Long> buses = new HashSet<>();
    private final Map<Long, Message> live = new HashMap<>();

    static String key(String path, String iface, String member, List<?> arguments) {
        return path + " " + iface + "." + member + arguments;
    }

    void answer(String path, String iface, String member, List<?> arguments, Answer answer) {
        script.put(key(path, iface, member, arguments), answer);
    }

    /** A property reply: a variant of the given contents holding the value. */
    void property(String path, String iface, String name, String contents, Object value) {
        answer(
                path,
                SystemdUserBus.PROPERTIES,
                "Get",
                List.of(iface, name),
                Answer.reply("v", Item.variant(contents, value)));
    }

    int liveMessages() {
        return live.size();
    }

    int openBuses() {
        return buses.size();
    }

    @Override
    public Handle openUser() {
        if (openCode < 0) {
            return new Handle(openCode, 0);
        }
        long bus = next++;
        buses.add(bus);
        opened++;
        return new Handle(openCode, bus);
    }

    @Override
    public int setMethodCallTimeout(long bus, long usec) {
        requireBus(bus);
        callTimeouts.add(usec);
        return timeoutCode;
    }

    @Override
    public void closeUnref(long bus) {
        if (bus == 0) {
            return;
        }
        if (!buses.remove(bus)) {
            throw new IllegalStateException("bus released twice or never opened: " + bus);
        }
        closed++;
    }

    @Override
    public Handle newMethodCall(long bus, String destination, String path, String iface, String member) {
        requireBus(bus);
        if (!destination.equals(SystemdUserBus.DESTINATION)) {
            throw new IllegalStateException("unexpected destination " + destination);
        }
        long handle = next++;
        live.put(handle, new Message(path, iface, member));
        return new Handle(1, handle);
    }

    @Override
    public int appendString(long message, String value) {
        message(message).arguments.add(value);
        return 1;
    }

    @Override
    public int appendUint32(long message, int value) {
        message(message).arguments.add(value);
        return 1;
    }

    @Override
    public int appendBoolean(long message, boolean value) {
        message(message).arguments.add("b:" + value);
        return 1;
    }

    @Override
    public int appendUint64(long message, long value) {
        message(message).arguments.add("t:" + value);
        return 1;
    }

    @Override
    public int appendUnixFd(long message, int fd) {
        message(message).arguments.add("h:" + fd);
        return fd < 0 ? -9 : 1;
    }

    @Override
    public int openContainer(long message, char type, String contents) {
        message(message).arguments.add(type + "{" + contents);
        return 1;
    }

    @Override
    public int closeContainer(long message) {
        message(message).arguments.add("}");
        return 1;
    }

    /** Answers by member for the state-changing methods, when no exact script matches. */
    final Map<String, Answer> byMember = new HashMap<>();

    /** The arguments of every message sent, in order. */
    final List<List<Object>> sentArguments = new ArrayList<>();

    @Override
    public CallReply call(long bus, long message, long timeoutUsec) {
        requireBus(bus);
        if (timeoutUsec <= 0) {
            throw new IllegalStateException("an unbounded call");
        }
        Message call = message(message);
        String key = key(call.path, call.iface, call.member, call.arguments);
        sent.add(key);
        sentArguments.add(List.copyOf(call.arguments));
        Answer answer = script.getOrDefault(key, byMember.get(call.member));
        if (answer == null) {
            return new CallReply(
                    -EIO, 0, Optional.of("org.freedesktop.DBus.Error.Failed"), Optional.of("not scripted"));
        }
        if (answer.code() < 0) {
            return new CallReply(
                    answer.code(),
                    0,
                    answer.errorName(),
                    Optional.of(answer.signature().isEmpty() ? "scripted" : answer.signature()));
        }
        long handle = next++;
        Message reply = new Message(call.path, call.iface, call.member);
        reply.signature = answer.signature();
        reply.items = answer.items();
        live.put(handle, reply);
        return new CallReply(1, handle, Optional.empty(), Optional.empty());
    }

    @Override
    public void messageUnref(long message) {
        if (message == 0) {
            return;
        }
        if (live.remove(message) == null) {
            throw new IllegalStateException("message released twice or never made: " + message);
        }
    }

    @Override
    public int hasSignature(long message, String signature) {
        return message(message).signature.equals(signature) ? 1 : 0;
    }

    @Override
    public int enterContainer(long message, char type, String contents) {
        Message m = message(message);
        if (m.inVariant || m.position >= m.items.size()) {
            return 0;
        }
        Item item = m.items.get(m.position);
        if (item.type() != type || !item.contents().equals(contents)) {
            return -ENXIO;
        }
        m.inVariant = true;
        m.variantRead = false;
        return 1;
    }

    @Override
    public int exitContainer(long message) {
        Message m = message(message);
        if (!m.inVariant) {
            return -ENXIO;
        }
        if (!m.variantRead) {
            return -EBUSY;
        }
        m.inVariant = false;
        m.position++;
        return 1;
    }

    @Override
    public Read<String> readString(long message, char type) {
        Message m = message(message);
        if (m.position >= m.items.size()) {
            return new Read<>(0, Optional.empty());
        }
        Item item = m.items.get(m.position);
        if (m.inVariant) {
            if (m.variantRead || !item.contents().equals(String.valueOf(type))) {
                return new Read<>(-ENXIO, Optional.empty());
            }
            m.variantRead = true;
            return new Read<>(1, Optional.of((String) item.value()));
        }
        if (item.type() != type) {
            return new Read<>(-ENXIO, Optional.empty());
        }
        m.position++;
        return new Read<>(1, Optional.of((String) item.value()));
    }

    @Override
    public Read<byte[]> readBytes(long message) {
        Message m = message(message);
        if (!m.inVariant || m.position >= m.items.size()) {
            return new Read<>(-ENXIO, Optional.empty());
        }
        Item item = m.items.get(m.position);
        if (m.variantRead || !item.contents().equals("ay")) {
            return new Read<>(-ENXIO, Optional.empty());
        }
        m.variantRead = true;
        return new Read<>(1, Optional.of(((byte[]) item.value()).clone()));
    }

    private void requireBus(long bus) {
        if (!buses.contains(bus)) {
            throw new IllegalStateException("no such open bus: " + bus);
        }
    }

    private Message message(long handle) {
        Message m = live.get(handle);
        if (m == null) {
            throw new IllegalStateException("no such live message: " + handle);
        }
        return m;
    }
}
