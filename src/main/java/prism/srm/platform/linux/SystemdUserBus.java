package prism.srm.platform.linux;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Read-only inspection of the calling user's systemd manager over its D-Bus API (milestone 12, S3A, plan Rev 2.2 §2.3
 * P4–P7, §5.2). It can send exactly the methods of {@link ReadOnlyMethod} — {@code GetUnit}, {@code GetUnitByPID} for
 * Prism's own process only, and {@code org.freedesktop.DBus.Properties.Get} — none of which changes anything. There is
 * no way here to start, stop, kill, change or attach a unit, to pass a file descriptor, or to name another process.
 *
 * <p>Every reply is checked before it is read: its whole signature must equal the method's ({@code o} or {@code v}),
 * a property's variant must hold exactly the expected type ({@code enter_container} refuses anything else), an object
 * path must be a systemd unit path, a cgroup path must be a valid one, and an invocation id must be 16 bytes. Anything
 * else is {@link Status#MALFORMED_REPLY} and no value of it is used.
 *
 * <p>Each inspection opens its own connection, uses it on the calling thread only and closes it before returning;
 * every message is released on every path. One inspection at a time (sd-bus objects are not thread-safe). Every method
 * call Prism makes is bounded by {@link #CALL_TIMEOUT_USEC}. The {@code Hello} call that brings the connection up is
 * not: {@code sd_bus_open_user} queues it inside {@code sd_bus_start}, sealed with libsystemd's default method-call
 * timeout ({@code SYSTEMD_BUS_TIMEOUT}, else 25 s; v255 sd-bus.c {@code bus_send_hello}, {@code bus_seal_message}),
 * before Prism can set its own; the first call waits for it. The SASL authentication before it is bounded only by
 * libsystemd's own authentication timeout. So a hung bus broker can block an inspection for longer (final review,
 * 2026-10-16). It must never run on the JavaFX Application Thread.
 *
 * <p>Interfaces, methods, properties and types: org.freedesktop.systemd1(5) of systemd v255 (Manager {@code Version},
 * {@code Features}, {@code ControlGroup}, {@code SystemState}, {@code DefaultOOMPolicy}: {@code s}; {@code GetUnit(in s
 * name, out o unit)}; {@code GetUnitByPID(in u pid, out o unit)}; Unit {@code Id}, {@code ActiveState}, {@code
 * SubState}: {@code s}, {@code InvocationID}: {@code ay}; Slice, Scope and Service {@code ControlGroup}: {@code s}).
 * Error names: {@code org.freedesktop.systemd1.NoSuchUnit} and {@code NoUnitForPID} (v255
 * src/libsystemd/sd-bus/bus-common-errors.h); {@code org.freedesktop.DBus.Error.AccessDenied}, {@code Timeout},
 * {@code NoReply}, {@code ServiceUnknown}, {@code NameHasNoOwner}, {@code UnknownObject}, {@code UnknownProperty} and
 * {@code InvalidArgs} (v255 src/systemd/sd-bus-protocol.h).
 * https://github.com/systemd/systemd/blob/v255/man/org.freedesktop.systemd1.xml
 */
final class SystemdUserBus {

    static final String DESTINATION = "org.freedesktop.systemd1";
    static final String MANAGER_PATH = "/org/freedesktop/systemd1";
    static final String MANAGER = "org.freedesktop.systemd1.Manager";
    static final String UNIT = "org.freedesktop.systemd1.Unit";
    static final String PROPERTIES = "org.freedesktop.DBus.Properties";

    /** The bound on every call: two seconds (plan §5.3). Zero would mean sd-bus's default, so it is never passed. */
    static final long CALL_TIMEOUT_USEC = 2_000_000L;

    static final String NO_SUCH_UNIT = "org.freedesktop.systemd1.NoSuchUnit";
    static final String NO_UNIT_FOR_PID = "org.freedesktop.systemd1.NoUnitForPID";
    static final String ACCESS_DENIED = "org.freedesktop.DBus.Error.AccessDenied";
    static final String TIMEOUT = "org.freedesktop.DBus.Error.Timeout";
    static final String NO_REPLY = "org.freedesktop.DBus.Error.NoReply";
    static final String SERVICE_UNKNOWN = "org.freedesktop.DBus.Error.ServiceUnknown";
    static final String NAME_HAS_NO_OWNER = "org.freedesktop.DBus.Error.NameHasNoOwner";
    static final String UNKNOWN_OBJECT = "org.freedesktop.DBus.Error.UnknownObject";
    static final String UNKNOWN_PROPERTY = "org.freedesktop.DBus.Error.UnknownProperty";
    static final String INVALID_ARGS = "org.freedesktop.DBus.Error.InvalidArgs";

    static final int ETIMEDOUT = 110;
    static final int ECONNREFUSED = 111;
    static final int ENOMEDIUM = 123;

    /** A unit name Prism may ask about: systemd's unit-name characters, a kind whose cgroup it can read. */
    private static final Pattern UNIT_NAME = Pattern.compile("[A-Za-z0-9:_.@\\\\-]{1,240}\\.(slice|scope|service)");

    /** A unit object path as the manager hands it out (bus_label_escape: [A-Za-z0-9_] only). */
    private static final Pattern UNIT_PATH = Pattern.compile("/org/freedesktop/systemd1/unit/[A-Za-z0-9_]{1,1024}");

    private static final int INVOCATION_ID_BYTES = 16;

    /** A D-Bus property name: letters only, as systemd's are. */
    private static final Pattern PROPERTY = Pattern.compile("[A-Za-z]{1,64}");

    /** Every method this class can send; each one only reads. */
    enum ReadOnlyMethod {
        GET_UNIT(MANAGER, "GetUnit", "o"),
        GET_UNIT_BY_PID(MANAGER, "GetUnitByPID", "o"),
        GET_PROPERTY(PROPERTIES, "Get", "v");

        final String iface;
        final String member;
        final String replySignature;

        ReadOnlyMethod(String iface, String member, String replySignature) {
            this.iface = iface;
            this.member = member;
            this.replySignature = replySignature;
        }
    }

    /** What an inspection came to. */
    enum Status {
        OK,
        /** No connection to the user bus ({@code sd_bus_open_user} failed), or no user manager on it. */
        NO_BUS,
        NO_SUCH_UNIT,
        /** Prism's own process is in no unit of this manager (it runs outside it). */
        NO_UNIT_FOR_PROCESS,
        ACCESS_DENIED,
        TIMEOUT,
        MALFORMED_REPLY,
        /** The question itself was refused before anything was sent (a name Prism does not ask about). */
        REFUSED,
        FAILED
    }

    /** One inspection's answer. */
    record Reading<T>(Status status, Optional<T> value, String detail) {

        Reading {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(detail, "detail");
            if ((status == Status.OK) != value.isPresent()) {
                throw new IllegalArgumentException("A reading has a value exactly when it is OK");
            }
        }

        static <T> Reading<T> ok(T value) {
            return new Reading<>(Status.OK, Optional.of(value), "");
        }

        static <T> Reading<T> not(Status status, String detail) {
            return new Reading<>(status, Optional.empty(), detail);
        }
    }

    /**
     * The manager's own properties. Version and control group are required; the others are empty where this manager
     * does not have them ({@code DefaultOOMPolicy} exists since systemd 243), so an older manager still reports its
     * version.
     */
    record ManagerFacts(
            String version,
            Optional<String> features,
            Optional<String> systemState,
            String controlGroup,
            Optional<String> defaultOomPolicy) {}

    /**
     * One unit's properties.
     *
     * @param controlGroup empty when the unit has none (not running) or is of a kind without one
     * @param invocationId lower-case hex, empty when the unit has none (all-zero or empty array)
     */
    record UnitFacts(
            String objectPath,
            String id,
            String activeState,
            String subState,
            Optional<String> controlGroup,
            Optional<String> invocationId) {}

    /** A failed step, carried to the inspection's end. Internal control flow only. */
    private static final class Failure extends Exception {

        private static final long serialVersionUID = 1L;

        private final Status status;
        private final String errorName;

        Failure(Status status, String detail) {
            this(status, detail, "");
        }

        Failure(Status status, String detail, String errorName) {
            super(detail, null, false, false);
            this.status = status;
            this.errorName = errorName;
        }
    }

    private final SdBusApi api;
    private final LongSupplier ownPid;
    private final ReentrantLock lock = new ReentrantLock();

    SystemdUserBus(SdBusApi api) {
        this(api, () -> ProcessHandle.current().pid());
    }

    SystemdUserBus(SdBusApi api, LongSupplier ownPid) {
        this.api = Objects.requireNonNull(api, "api");
        this.ownPid = Objects.requireNonNull(ownPid, "ownPid");
    }

    /** The manager's version, features, state, cgroup and default OOM policy. */
    Reading<ManagerFacts> manager() {
        return inspect(bus -> new ManagerFacts(
                stringProperty(bus, MANAGER_PATH, MANAGER, "Version"),
                optionalStringProperty(bus, MANAGER_PATH, MANAGER, "Features"),
                optionalStringProperty(bus, MANAGER_PATH, MANAGER, "SystemState"),
                cgroupPath(stringProperty(bus, MANAGER_PATH, MANAGER, "ControlGroup"))
                        .orElseThrow(() -> new Failure(Status.MALFORMED_REPLY, "the manager has no control group")),
                optionalStringProperty(bus, MANAGER_PATH, MANAGER, "DefaultOOMPolicy")));
    }

    /** A loaded unit by name; a name that is not a slice, scope or service name is refused without a call. */
    Reading<UnitFacts> unit(String name) {
        if (!UNIT_NAME.matcher(name).matches()) {
            return Reading.not(Status.REFUSED, "not a unit name Prism asks about: " + name);
        }
        return inspect(bus -> unitFacts(bus, objectPath(bus, ReadOnlyMethod.GET_UNIT, name, 0)));
    }

    /**
     * One string property of a loaded scope unit ({@code org.freedesktop.systemd1.Scope}, for example {@code
     * OOMPolicy}); a name or property Prism does not ask about is refused without a call.
     */
    Reading<String> scopeStringProperty(String name, String property) {
        if (!UNIT_NAME.matcher(name).matches()
                || !name.endsWith(".scope")
                || !PROPERTY.matcher(property).matches()) {
            return Reading.not(Status.REFUSED, "not a scope property Prism asks about: " + name + " " + property);
        }
        return inspect(bus -> stringProperty(
                bus, objectPath(bus, ReadOnlyMethod.GET_UNIT, name, 0), "org.freedesktop.systemd1.Scope", property));
    }

    /** The unit Prism's own process is in, if this manager has one for it. */
    Reading<UnitFacts> ownUnit() {
        long pid = ownPid.getAsLong();
        if (pid <= 0 || pid > CgroupV2.MOST_PID) {
            return Reading.not(Status.REFUSED, "Prism's own PID cannot be named: " + pid);
        }
        return inspect(bus -> unitFacts(bus, objectPath(bus, ReadOnlyMethod.GET_UNIT_BY_PID, null, (int) pid)));
    }

    @FunctionalInterface
    private interface Step<T> {
        T on(long bus) throws Failure;
    }

    private <T> Reading<T> inspect(Step<T> step) {
        lock.lock();
        try {
            SdBusApi.Handle opened = api.openUser();
            if (opened.code() < 0 || opened.handle() == 0) {
                api.closeUnref(opened.handle());
                return Reading.not(Status.NO_BUS, "the user bus cannot be reached: " + errno(opened.code()));
            }
            try {
                int bounded = api.setMethodCallTimeout(opened.handle(), CALL_TIMEOUT_USEC);
                if (bounded < 0) {
                    return Reading.not(Status.FAILED, "the call timeout could not be set: " + errno(bounded));
                }
                return Reading.ok(step.on(opened.handle()));
            } catch (Failure failure) {
                return Reading.not(failure.status, failure.getMessage());
            } finally {
                api.closeUnref(opened.handle());
            }
        } finally {
            lock.unlock();
        }
    }

    private String objectPath(long bus, ReadOnlyMethod method, String name, int pid) throws Failure {
        long reply = call(bus, method, MANAGER_PATH, message -> {
            if (method == ReadOnlyMethod.GET_UNIT) {
                return api.appendString(message, name);
            }
            return api.appendUint32(message, pid);
        });
        try {
            String path = read(reply, 'o');
            if (!UNIT_PATH.matcher(path).matches()) {
                throw new Failure(Status.MALFORMED_REPLY, "not a unit object path: " + path);
            }
            return path;
        } finally {
            api.messageUnref(reply);
        }
    }

    private UnitFacts unitFacts(long bus, String path) throws Failure {
        String id = stringProperty(bus, path, UNIT, "Id");
        Optional<String> kind = cgroupInterface(id);
        Optional<String> cgroup = Optional.empty();
        if (kind.isPresent()) {
            cgroup = cgroupPath(stringProperty(bus, path, kind.get(), "ControlGroup"));
        }
        return new UnitFacts(
                path,
                id,
                stringProperty(bus, path, UNIT, "ActiveState"),
                stringProperty(bus, path, UNIT, "SubState"),
                cgroup,
                invocationId(bus, path));
    }

    private static Optional<String> cgroupInterface(String unitId) {
        if (!UNIT_NAME.matcher(unitId).matches()) {
            return Optional.empty();
        }
        String suffix = unitId.substring(unitId.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (suffix) {
            case "slice" -> Optional.of("org.freedesktop.systemd1.Slice");
            case "scope" -> Optional.of("org.freedesktop.systemd1.Scope");
            case "service" -> Optional.of("org.freedesktop.systemd1.Service");
            default -> Optional.empty();
        };
    }

    /** An empty control group means none (an inactive unit); anything else must be a valid cgroup path. */
    private static Optional<String> cgroupPath(String value) throws Failure {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (!CgroupV2.validPath(value)) {
            throw new Failure(Status.MALFORMED_REPLY, "not a cgroup path: " + value);
        }
        return Optional.of(value);
    }

    private Optional<String> invocationId(long bus, String path) throws Failure {
        long reply = property(bus, path, UNIT, "InvocationID");
        try {
            enter(reply, "ay");
            SdBusApi.Read<byte[]> read = api.readBytes(reply);
            if (read.code() <= 0 || read.value().isEmpty()) {
                throw new Failure(Status.MALFORMED_REPLY, "InvocationID could not be read: " + errno(read.code()));
            }
            exit(reply);
            byte[] bytes = read.value().get();
            if (bytes.length == 0) {
                return Optional.empty();
            }
            if (bytes.length != INVOCATION_ID_BYTES) {
                throw new Failure(Status.MALFORMED_REPLY, "InvocationID has " + bytes.length + " bytes, not 16");
            }
            boolean zero = true;
            for (byte b : bytes) {
                zero &= b == 0;
            }
            return zero ? Optional.empty() : Optional.of(HexFormat.of().formatHex(bytes));
        } finally {
            api.messageUnref(reply);
        }
    }

    private String stringProperty(long bus, String path, String iface, String name) throws Failure {
        long reply = property(bus, path, iface, name);
        try {
            enter(reply, "s");
            String value = read(reply, 's');
            exit(reply);
            return value;
        } finally {
            api.messageUnref(reply);
        }
    }

    /** A property this manager may not have: an unknown property is empty, any other failure stays a failure. */
    private Optional<String> optionalStringProperty(long bus, String path, String iface, String name) throws Failure {
        try {
            return Optional.of(stringProperty(bus, path, iface, name));
        } catch (Failure failure) {
            if (failure.errorName.equals(UNKNOWN_PROPERTY) || failure.errorName.equals(INVALID_ARGS)) {
                return Optional.empty();
            }
            throw failure;
        }
    }

    private long property(long bus, String path, String iface, String name) throws Failure {
        return call(bus, ReadOnlyMethod.GET_PROPERTY, path, message -> {
            int code = api.appendString(message, iface);
            return code < 0 ? code : api.appendString(message, name);
        });
    }

    @FunctionalInterface
    private interface Arguments {
        int append(long message);
    }

    /** Sends one read-only method; the reply's whole signature is checked before it is handed back. */
    private long call(long bus, ReadOnlyMethod method, String path, Arguments arguments) throws Failure {
        SdBusApi.Handle created = api.newMethodCall(bus, DESTINATION, path, method.iface, method.member);
        if (created.code() < 0 || created.handle() == 0) {
            api.messageUnref(created.handle());
            throw new Failure(Status.FAILED, method.member + " could not be built: " + errno(created.code()));
        }
        long message = created.handle();
        try {
            int appended = arguments.append(message);
            if (appended < 0) {
                throw new Failure(Status.FAILED, method.member + " arguments: " + errno(appended));
            }
            SdBusApi.CallReply reply = api.call(bus, message, CALL_TIMEOUT_USEC);
            if (reply.code() < 0 || reply.reply() == 0) {
                api.messageUnref(reply.reply());
                throw failure(method, reply);
            }
            if (api.hasSignature(reply.reply(), method.replySignature) <= 0) {
                api.messageUnref(reply.reply());
                throw new Failure(
                        Status.MALFORMED_REPLY,
                        method.member + " replied with another signature than " + method.replySignature);
            }
            return reply.reply();
        } finally {
            api.messageUnref(message);
        }
    }

    private static Failure failure(ReadOnlyMethod method, SdBusApi.CallReply reply) {
        String name = reply.errorName().orElse("");
        String text = method.member + " failed: " + (name.isEmpty() ? errno(reply.code()) : name)
                + reply.errorMessage().map(m -> " (" + m + ")").orElse("");
        Status status;
        if (name.equals(NO_SUCH_UNIT) || name.equals(UNKNOWN_OBJECT)) {
            // UnknownObject: the unit was unloaded between GetUnit and a property read.
            status = Status.NO_SUCH_UNIT;
        } else if (name.equals(SERVICE_UNKNOWN) || name.equals(NAME_HAS_NO_OWNER)) {
            // The bus is there but no user manager owns org.freedesktop.systemd1 on it.
            status = Status.NO_BUS;
        } else if (name.equals(NO_UNIT_FOR_PID)) {
            status = Status.NO_UNIT_FOR_PROCESS;
        } else if (name.equals(ACCESS_DENIED)
                || reply.code() == -LinuxErrors.EACCES
                || reply.code() == -LinuxErrors.EPERM) {
            status = Status.ACCESS_DENIED;
        } else if (name.equals(TIMEOUT) || name.equals(NO_REPLY) || reply.code() == -ETIMEDOUT) {
            status = Status.TIMEOUT;
        } else {
            status = Status.FAILED;
        }
        return new Failure(status, text, name);
    }

    private void enter(long reply, String contents) throws Failure {
        int code = api.enterContainer(reply, 'v', contents);
        if (code <= 0) {
            throw new Failure(
                    Status.MALFORMED_REPLY, "the property is not a variant of " + contents + ": " + errno(code));
        }
    }

    private void exit(long reply) throws Failure {
        int code = api.exitContainer(reply);
        if (code <= 0) {
            throw new Failure(Status.MALFORMED_REPLY, "the variant holds more than one value: " + errno(code));
        }
    }

    private String read(long reply, char type) throws Failure {
        SdBusApi.Read<String> read = api.readString(reply, type);
        if (read.code() <= 0 || read.value().isEmpty()) {
            throw new Failure(Status.MALFORMED_REPLY, "no " + type + " value: " + errno(read.code()));
        }
        return read.value().get();
    }

    /** A negative sd-bus return code as an errno name where Prism knows it. */
    static String errno(int code) {
        return switch (-code) {
            case 0 -> "no value";
            case LinuxErrors.ENOENT -> "ENOENT";
            case LinuxErrors.EPERM -> "EPERM";
            case LinuxErrors.EACCES -> "EACCES";
            case LinuxErrors.EINVAL -> "EINVAL";
            case ETIMEDOUT -> "ETIMEDOUT";
            case ECONNREFUSED -> "ECONNREFUSED";
            case ENOMEDIUM -> "ENOMEDIUM (no user bus address)";
            default -> "error " + code;
        };
    }
}
