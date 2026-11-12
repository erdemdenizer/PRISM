package prism.srm.platform.linux;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * {@link ScopeManager} over the calling user's systemd manager (milestone 12, S3B). It sends exactly two
 * state-changing methods ({@link WriteMethod}) and reads through {@link SystemdUserBus}.
 *
 * <p>{@code StartTransientUnit(in s name, in s mode, in a(sv) properties, in a(sa(sv)) aux, out o job)} with mode
 * {@code "fail"}, the properties {@code PIDFDs} ({@code ah}, one descriptor), {@code Slice}, {@code Description},
 * {@code OOMPolicy}, {@code ManagedOOMPreference} and {@code CollectMode} ({@code s}), and an empty {@code aux}.
 * {@code org.freedesktop.systemd1.Unit.SetProperties(in b runtime, in a(sv) properties)} with {@code runtime = true} and
 * one property ({@code t}) per call, so that each kind takes effect as a whole or not at all, sent to the unit object
 * addressed by its <em>invocation id</em> ({@code /org/freedesktop/systemd1/unit/} + the escaped id): systemd v255
 * resolves a unit path whose last element is a 128-bit id through its table of invocation ids and answers {@code
 * org.freedesktop.systemd1.NoUnitForInvocationID} when no unit has it (src/core/manager.c,
 * {@code manager_load_unit_from_dbus_path}; the path element is unescaped by {@code bus_label_unescape},
 * src/basic/unit-def.c and src/basic/bus-label.c). A limit therefore reaches the invocation Prism proved, or nothing;
 * a unit that took the name later is never changed. Sources: org.freedesktop.systemd1(5)
 * and systemd.resource-control(5) of systemd v255; {@code PIDFDs} for scopes: systemd NEWS 255; error names: v255
 * bus-common-errors.h ({@code UnitExists}, {@code NoSuchUnit}) and sd-bus-protocol.h; the {@code 'h'} argument is
 * duplicated by sd-bus (sd_bus_message_append_basic(3)), so the caller's descriptor stays the caller's.
 * https://github.com/systemd/systemd/blob/v255/man/org.freedesktop.systemd1.xml
 *
 * <p>An answer counts as a refusal only when the manager replied with a D-Bus error (then it did nothing); a timeout,
 * a broken connection or a locally made {@code NoReply}/{@code Timeout}/{@code Disconnected} error is {@link
 * ScopeManager.StartStatus#UNKNOWN}: the request may have been carried out. Each request opens its own connection,
 * bounds its calls to two seconds (the connection's {@code Hello} keeps libsystemd's default of 25 s, see {@link
 * SystemdUserBus}), and closes it before returning; one request at a time.
 */
final class SystemdScopeManager implements ScopeManager {

    static final String SLICE = "app.slice";
    static final String UNIT_EXISTS = "org.freedesktop.systemd1.UnitExists";
    static final String DISCONNECTED = "org.freedesktop.DBus.Error.Disconnected";
    static final String PROPERTY_READ_ONLY = "org.freedesktop.DBus.Error.PropertyReadOnly";
    /**
     * The manager's answer when the descriptor's process has exited (observed on systemd 255.4 in CI run 38799770641:
     * "Failed to set unit properties: No such process"; no unit was created).
     */
    static final String UNIX_PROCESS_ID_UNKNOWN = "org.freedesktop.DBus.Error.UnixProcessIdUnknown";

    /** systemd's answer when no unit has the invocation id of the path (v255 bus-common-errors.h). */
    static final String NO_UNIT_FOR_INVOCATION_ID = "org.freedesktop.systemd1.NoUnitForInvocationID";

    private static final Pattern INVOCATION = Pattern.compile("[0-9a-f]{32}");

    /** The properties Prism sets at runtime, and only these. */
    static final Set<String> LIMIT_PROPERTIES = Set.of("CPUQuotaPerSecUSec", "MemoryHigh", "MemoryMax");

    private static final Pattern SCOPE = Pattern.compile("prism-srm-limit-[0-9]{1,7}-[0-9]{1,15}\\.scope");
    private static final Pattern JOB_PATH = Pattern.compile("/org/freedesktop/systemd1/job/[0-9]{1,10}");
    private static final Pattern DESCRIPTION = Pattern.compile("[ -~]{1,200}");

    /** The only state-changing methods this class can send. */
    enum WriteMethod {
        START_TRANSIENT_UNIT(SystemdUserBus.MANAGER, "StartTransientUnit", "o"),
        UNIT_SET_PROPERTIES(SystemdUserBus.UNIT, "SetProperties", "");

        final String iface;
        final String member;
        final String replySignature;

        WriteMethod(String iface, String member, String replySignature) {
            this.iface = iface;
            this.member = member;
            this.replySignature = replySignature;
        }
    }

    /**
     * The object path of the unit with this invocation id: {@code bus_label_escape} of systemd (src/basic/bus-label.c)
     * keeps letters, and digits except as the first character, and writes every other byte as {@code _} and two
     * lowercase hex digits.
     */
    static String invocationPath(String invocationId) {
        StringBuilder path = new StringBuilder("/org/freedesktop/systemd1/unit/");
        for (int i = 0; i < invocationId.length(); i++) {
            char c = invocationId.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            boolean digit = c >= '0' && c <= '9';
            if (letter || (digit && i > 0)) {
                path.append(c);
            } else {
                path.append('_').append(Character.forDigit((c >> 4) & 0xf, 16)).append(Character.forDigit(c & 0xf, 16));
            }
        }
        return path.toString();
    }

    private final SdBusApi api;
    private final SystemdUserBus reads;
    private final ReentrantLock lock = new ReentrantLock();

    SystemdScopeManager(SdBusApi api) {
        this.api = Objects.requireNonNull(api, "api");
        this.reads = new SystemdUserBus(api);
    }

    @FunctionalInterface
    private interface Body {
        /** Appends the method's arguments; a negative sd-bus code when one could not be appended. */
        int append(long message);
    }

    /** What one write call came to: a reply, a refusal by the manager, or no answer. */
    private record Outcome(Kind kind, String errorName, String detail) {
        enum Kind {
            REPLIED,
            REFUSED,
            UNKNOWN
        }
    }

    @Override
    public Start startScope(String unit, int pidfd, String description) {
        if (!SCOPE.matcher(unit).matches()
                || pidfd < 0
                || !DESCRIPTION.matcher(description).matches()) {
            return new Start(StartStatus.REFUSED, "not a request Prism makes: " + unit + " fd " + pidfd);
        }
        Outcome outcome = send(WriteMethod.START_TRANSIENT_UNIT, message -> {
            int code = api.appendString(message, unit);
            code = code < 0 ? code : api.appendString(message, "fail");
            code = code < 0 ? code : api.openContainer(message, 'a', "(sv)");
            code = code < 0 ? code : pidfdProperty(message, pidfd);
            code = code < 0 ? code : stringProperty(message, "Slice", SLICE);
            code = code < 0 ? code : stringProperty(message, "Description", description);
            code = code < 0 ? code : stringProperty(message, "OOMPolicy", "continue");
            code = code < 0 ? code : stringProperty(message, "ManagedOOMPreference", "omit");
            code = code < 0 ? code : stringProperty(message, "CollectMode", "inactive-or-failed");
            code = code < 0 ? code : api.closeContainer(message);
            code = code < 0 ? code : api.openContainer(message, 'a', "(sa(sv))");
            return code < 0 ? code : api.closeContainer(message);
        });
        return switch (outcome.kind()) {
            case REPLIED -> new Start(StartStatus.STARTED, outcome.detail());
            case UNKNOWN -> new Start(StartStatus.UNKNOWN, outcome.detail());
            case REFUSED -> {
                String name = outcome.errorName();
                if (name.equals(UNIT_EXISTS)) {
                    yield new Start(StartStatus.UNIT_EXISTS, outcome.detail());
                }
                if (name.equals(SystemdUserBus.ACCESS_DENIED)) {
                    yield new Start(StartStatus.ACCESS_DENIED, outcome.detail());
                }
                if ((name.equals(SystemdUserBus.INVALID_ARGS) || name.equals(PROPERTY_READ_ONLY))
                        && outcome.detail().contains("PIDFDs")) {
                    yield new Start(StartStatus.PIDFDS_REFUSED, outcome.detail());
                }
                yield new Start(StartStatus.REFUSED, outcome.detail());
            }
        };
    }

    private int pidfdProperty(long message, int pidfd) {
        int code = api.openContainer(message, 'r', "sv");
        code = code < 0 ? code : api.appendString(message, "PIDFDs");
        code = code < 0 ? code : api.openContainer(message, 'v', "ah");
        code = code < 0 ? code : api.openContainer(message, 'a', "h");
        code = code < 0 ? code : api.appendUnixFd(message, pidfd);
        code = code < 0 ? code : api.closeContainer(message);
        code = code < 0 ? code : api.closeContainer(message);
        return code < 0 ? code : api.closeContainer(message);
    }

    private int stringProperty(long message, String name, String value) {
        int code = api.openContainer(message, 'r', "sv");
        code = code < 0 ? code : api.appendString(message, name);
        code = code < 0 ? code : api.openContainer(message, 'v', "s");
        code = code < 0 ? code : api.appendString(message, value);
        code = code < 0 ? code : api.closeContainer(message);
        return code < 0 ? code : api.closeContainer(message);
    }

    @Override
    public SystemdUserBus.Reading<SystemdUserBus.UnitFacts> unit(String unit) {
        return reads.unit(unit);
    }

    @Override
    public SystemdUserBus.Reading<String> scopeProperty(String unit, String property) {
        return reads.scopeStringProperty(unit, property);
    }

    @Override
    public LimitSet setLimit(String unit, String invocationId, String property, long value) {
        if (!SCOPE.matcher(unit).matches()
                || !INVOCATION.matcher(invocationId).matches()
                || !LIMIT_PROPERTIES.contains(property)) {
            return new LimitSet(
                    SetStatus.REFUSED, "not a request Prism makes: " + unit + " " + invocationId + " " + property);
        }
        Outcome outcome = send(WriteMethod.UNIT_SET_PROPERTIES, invocationPath(invocationId), message -> {
            int code = api.appendBoolean(message, true);
            code = code < 0 ? code : api.openContainer(message, 'a', "(sv)");
            code = code < 0 ? code : api.openContainer(message, 'r', "sv");
            code = code < 0 ? code : api.appendString(message, property);
            code = code < 0 ? code : api.openContainer(message, 'v', "t");
            code = code < 0 ? code : api.appendUint64(message, value);
            code = code < 0 ? code : api.closeContainer(message);
            code = code < 0 ? code : api.closeContainer(message);
            return code < 0 ? code : api.closeContainer(message);
        });
        return switch (outcome.kind()) {
            case REPLIED -> new LimitSet(SetStatus.SET, outcome.detail());
            case UNKNOWN -> new LimitSet(SetStatus.UNKNOWN, outcome.detail());
            case REFUSED ->
                outcome.errorName().equals(SystemdUserBus.NO_SUCH_UNIT)
                                || outcome.errorName().equals(NO_UNIT_FOR_INVOCATION_ID)
                        ? new LimitSet(SetStatus.NO_SUCH_UNIT, outcome.detail())
                        : new LimitSet(SetStatus.REFUSED, outcome.detail());
        };
    }

    /** Sends one write method on a connection of its own; nothing is sent when building the message fails. */
    private Outcome send(WriteMethod method, Body body) {
        return send(method, SystemdUserBus.MANAGER_PATH, body);
    }

    private Outcome send(WriteMethod method, String path, Body body) {
        lock.lock();
        try {
            SdBusApi.Handle opened = api.openUser();
            if (opened.code() < 0 || opened.handle() == 0) {
                api.closeUnref(opened.handle());
                return new Outcome(
                        Outcome.Kind.REFUSED,
                        "",
                        "the user bus cannot be reached: " + SystemdUserBus.errno(opened.code()));
            }
            long bus = opened.handle();
            try {
                int bounded = api.setMethodCallTimeout(bus, SystemdUserBus.CALL_TIMEOUT_USEC);
                if (bounded < 0) {
                    return new Outcome(Outcome.Kind.REFUSED, "", "the call timeout could not be set");
                }
                SdBusApi.Handle created =
                        api.newMethodCall(bus, SystemdUserBus.DESTINATION, path, method.iface, method.member);
                if (created.code() < 0 || created.handle() == 0) {
                    api.messageUnref(created.handle());
                    return new Outcome(Outcome.Kind.REFUSED, "", method.member + " could not be built");
                }
                long message = created.handle();
                try {
                    int appended = body.append(message);
                    if (appended < 0) {
                        return new Outcome(
                                Outcome.Kind.REFUSED,
                                "",
                                method.member + " arguments: " + SystemdUserBus.errno(appended) + "; nothing was sent");
                    }
                    SdBusApi.CallReply reply = api.call(bus, message, SystemdUserBus.CALL_TIMEOUT_USEC);
                    try {
                        return outcome(method, reply);
                    } finally {
                        api.messageUnref(reply.reply());
                    }
                } finally {
                    api.messageUnref(message);
                }
            } finally {
                api.closeUnref(bus);
            }
        } finally {
            lock.unlock();
        }
    }

    private Outcome outcome(WriteMethod method, SdBusApi.CallReply reply) {
        Optional<String> name = reply.errorName();
        String text = method.member + ": " + name.orElse(SystemdUserBus.errno(reply.code()))
                + reply.errorMessage().map(m -> " (" + m + ")").orElse("");
        if (reply.code() >= 0 && reply.reply() != 0) {
            if (api.hasSignature(reply.reply(), method.replySignature) <= 0) {
                // The manager answered, so it carried the request out; only the answer's form is wrong.
                return new Outcome(Outcome.Kind.REPLIED, "", method.member + " replied with another signature");
            }
            if (method == WriteMethod.START_TRANSIENT_UNIT) {
                SdBusApi.Read<String> job = api.readString(reply.reply(), 'o');
                String path = job.value().orElse("");
                return new Outcome(
                        Outcome.Kind.REPLIED, "", JOB_PATH.matcher(path).matches() ? path : "job " + path);
            }
            return new Outcome(Outcome.Kind.REPLIED, "", method.member + " done");
        }
        if (name.isEmpty() || !refusal(name.get())) {
            // No answer, or an error sd-bus may have made locally after the request was sent (sd_bus_call fills the
            // error for local failures too: I/O, memory, a malformed reply): the request may have been carried out.
            return new Outcome(Outcome.Kind.UNKNOWN, name.orElse(""), text + "; the outcome is unknown");
        }
        return new Outcome(Outcome.Kind.REFUSED, name.get(), text);
    }

    /**
     * An error the manager (or the bus, before delivery) answers with when it did not carry the request out: systemd's
     * own errors and the standard ones it and the bus return for a refused call.
     */
    private static boolean refusal(String name) {
        return name.startsWith("org.freedesktop.systemd1.")
                || name.equals(SystemdUserBus.ACCESS_DENIED)
                || name.equals(SystemdUserBus.INVALID_ARGS)
                || name.equals(PROPERTY_READ_ONLY)
                || name.equals(UNIX_PROCESS_ID_UNKNOWN)
                || name.equals(SystemdUserBus.SERVICE_UNKNOWN)
                || name.equals(SystemdUserBus.NAME_HAS_NO_OWNER)
                || name.equals(SystemdUserBus.UNKNOWN_OBJECT)
                || name.equals(SystemdUserBus.UNKNOWN_PROPERTY)
                || name.equals("org.freedesktop.DBus.Error.UnknownMethod")
                || name.equals("org.freedesktop.DBus.Error.LimitsExceeded")
                || name.equals("org.freedesktop.DBus.Error.NotSupported");
    }
}
