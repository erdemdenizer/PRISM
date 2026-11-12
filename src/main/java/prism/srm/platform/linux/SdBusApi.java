package prism.srm.platform.linux;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The subset of libsystemd's sd-bus API milestone 12 uses (plan Rev 2.2 §5.2), one method per C function, each returning
 * what the function returned: the read-only calls of stage S3A, and the message building stage S3B needs for its two
 * state-changing methods (a transient scope by pidfd, and runtime unit properties). Bus and message objects are opaque handles ({@code long}, zero
 * for NULL); a caller that receives a handle releases it with {@link #closeUnref} or {@link #messageUnref}. Return
 * codes follow sd-bus: negative errno-style values on failure. Nothing here decides what a result means; which methods
 * are sent is decided by {@link SystemdUserBus} (read-only) and {@link SystemdScopeManager} (the two state-changing
 * ones). A seam: tests give a fake that follows the man pages.
 *
 * <p>Prototypes as declared in systemd v255 {@code src/systemd/sd-bus.h}.
 */
interface SdBusApi {

    /** Every libsystemd symbol this interface binds; all must be found before the library is loaded (P2). */
    List<String> SYMBOLS = List.of(
            "sd_bus_open_user",
            "sd_bus_close_unref",
            "sd_bus_set_method_call_timeout",
            "sd_bus_message_new_method_call",
            "sd_bus_message_append_basic",
            "sd_bus_message_open_container",
            "sd_bus_message_close_container",
            "sd_bus_call",
            "sd_bus_message_unref",
            "sd_bus_message_has_signature",
            "sd_bus_message_enter_container",
            "sd_bus_message_exit_container",
            "sd_bus_message_read_basic",
            "sd_bus_message_read_array",
            "sd_bus_error_free");

    /** A return code and the handle the call produced (zero when none). */
    record Handle(int code, long handle) {}

    /** A return code and the value read (empty when the call failed or read nothing). */
    record Read<T>(int code, Optional<T> value) {

        public Read {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * What {@code sd_bus_call} returned: its code, the reply handle on success, and the D-Bus error the reply carried
     * on failure (copied out; the {@code sd_bus_error} is freed before this returns).
     */
    record CallReply(int code, long reply, Optional<String> errorName, Optional<String> errorMessage) {

        public CallReply {
            Objects.requireNonNull(errorName, "errorName");
            Objects.requireNonNull(errorMessage, "errorMessage");
        }
    }

    /** {@code int sd_bus_open_user(sd_bus **ret)}: a new connection to the calling user's bus. */
    Handle openUser();

    /**
     * {@code sd_bus *sd_bus_close_unref(sd_bus *bus)}: closes and releases the connection without flushing; every call
     * here is synchronous, so nothing is ever queued (a flush would first wait for the connection to come up).
     */
    void closeUnref(long bus);

    /**
     * {@code int sd_bus_set_method_call_timeout(sd_bus *bus, uint64_t usec)}: the default timeout of method calls on
     * the connection (sd_bus_set_method_call_timeout(3)). Set after {@code sd_bus_open_user}, it does not reach the
     * {@code Hello} call, which that function has already sealed with libsystemd's default (25 s).
     */
    int setMethodCallTimeout(long bus, long usec);

    /**
     * {@code int sd_bus_message_new_method_call(sd_bus *bus, sd_bus_message **m, const char *destination, const char
     * *path, const char *interface, const char *member)}.
     */
    Handle newMethodCall(long bus, String destination, String path, String iface, String member);

    /** {@code int sd_bus_message_append_basic(sd_bus_message *m, char type, const void *p)} with type {@code 's'}. */
    int appendString(long message, String value);

    /** {@code int sd_bus_message_append_basic(sd_bus_message *m, char type, const void *p)} with type {@code 'u'}. */
    int appendUint32(long message, int value);

    /** {@code sd_bus_message_append_basic} with type {@code 'b'}: {@code p} points to an {@code int}, 0 or 1. */
    int appendBoolean(long message, boolean value);

    /** {@code sd_bus_message_append_basic} with type {@code 't'}: {@code p} points to a {@code uint64_t}. */
    int appendUint64(long message, long value);

    /**
     * {@code sd_bus_message_append_basic} with type {@code 'h'}: {@code p} points to an {@code int} descriptor, which
     * sd-bus duplicates; the caller keeps its own descriptor and closes it (sd_bus_message_append_basic(3)).
     */
    int appendUnixFd(long message, int fd);

    /** {@code int sd_bus_message_open_container(sd_bus_message *m, char type, const char *contents)}. */
    int openContainer(long message, char type, String contents);

    /** {@code int sd_bus_message_close_container(sd_bus_message *m)}. */
    int closeContainer(long message);

    /**
     * {@code int sd_bus_call(sd_bus *bus, sd_bus_message *m, uint64_t usec, sd_bus_error *ret_error, sd_bus_message
     * **reply)}; {@code usec} zero would mean the default timeout, so callers always pass a bound.
     */
    CallReply call(long bus, long message, long timeoutUsec);

    /** {@code sd_bus_message *sd_bus_message_unref(sd_bus_message *m)}. */
    void messageUnref(long message);

    /** {@code int sd_bus_message_has_signature(sd_bus_message *m, const char *signature)}: positive when equal. */
    int hasSignature(long message, String signature);

    /** {@code int sd_bus_message_enter_container(sd_bus_message *m, char type, const char *contents)}. */
    int enterContainer(long message, char type, String contents);

    /** {@code int sd_bus_message_exit_container(sd_bus_message *m)}. */
    int exitContainer(long message);

    /**
     * {@code int sd_bus_message_read_basic(sd_bus_message *m, char type, void *p)} for a string-like type ({@code 's'}
     * or {@code 'o'}): {@code p} is a {@code const char **} whose string is copied before this returns.
     */
    Read<String> readString(long message, char type);

    /**
     * {@code int sd_bus_message_read_array(sd_bus_message *m, char type, const void **ptr, size_t *size)} with type
     * {@code 'y'}: the bytes, copied before this returns.
     */
    Read<byte[]> readBytes(long message);
}
