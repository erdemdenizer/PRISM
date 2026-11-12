package prism.srm.platform.linux;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * {@link SdBusApi} through JNA and {@code libsystemd.so.0}. Created only by {@link LibsystemdCheck} after every symbol
 * in {@link SdBusApi#SYMBOLS} was found with {@code dlsym} on a verified 64-bit architecture, so loading the library
 * here cannot fail for a missing symbol (F16). No varargs function is mapped.
 *
 * <p>Sources (systemd v255): {@code src/systemd/sd-bus.h} for every prototype and for {@code sd_bus_error}
 * ({@code const char *name; const char *message; int _need_free;}, initialised to all zero as {@code
 * SD_BUS_ERROR_NULL}), and the man pages sd_bus_default(3), sd_bus_message_new_method_call(3),
 * sd_bus_message_append_basic(3), sd_bus_call(3), sd_bus_message_get_signature(3) (has_signature),
 * sd_bus_message_open_container(3) (enter/exit_container), sd_bus_message_read_basic(3) (for a string type {@code p} is
 * a {@code const char **}; the string is borrowed from the message and copied here), sd_bus_message_read_array(3) (size
 * in bytes; the data belongs to the message) and sd_bus_error(3).
 * https://github.com/systemd/systemd/blob/v255/src/systemd/sd-bus.h
 * https://www.freedesktop.org/software/systemd/man/latest/sd-bus.html
 *
 * <p>{@code size_t} is mapped as {@link NativeLong}: both are 64 bits on the LP64 architectures (amd64, aarch64) this
 * class is created for. {@code char} arguments are passed as {@code byte}. The library is opened with the same flags
 * the symbol check used ({@code RTLD_NOW | RTLD_LOCAL}), and strings cross in UTF-8, D-Bus's encoding, whatever the
 * process locale.
 */
final class JnaSdBusApi implements SdBusApi {

    /** The C error structure, read after a failed call and then freed with {@code sd_bus_error_free}. */
    @Structure.FieldOrder({"name", "message", "_need_free"})
    public static final class SdBusError extends Structure {
        public Pointer name;
        public Pointer message;
        public int _need_free;
    }

    /** The mapped functions. */
    interface LibSystemd extends Library {

        int sd_bus_open_user(PointerByReference ret);

        Pointer sd_bus_close_unref(Pointer bus);

        int sd_bus_set_method_call_timeout(Pointer bus, long usec);

        int sd_bus_message_new_method_call(
                Pointer bus, PointerByReference m, String destination, String path, String iface, String member);

        int sd_bus_message_append_basic(Pointer m, byte type, String p);

        int sd_bus_message_append_basic(Pointer m, byte type, IntByReference p);

        int sd_bus_message_append_basic(Pointer m, byte type, LongByReference p);

        int sd_bus_message_open_container(Pointer m, byte type, String contents);

        int sd_bus_message_close_container(Pointer m);

        int sd_bus_call(Pointer bus, Pointer m, long usec, SdBusError retError, PointerByReference reply);

        Pointer sd_bus_message_unref(Pointer m);

        int sd_bus_message_has_signature(Pointer m, String signature);

        int sd_bus_message_enter_container(Pointer m, byte type, String contents);

        int sd_bus_message_exit_container(Pointer m);

        int sd_bus_message_read_basic(Pointer m, byte type, PointerByReference p);

        int sd_bus_message_read_array(Pointer m, byte type, PointerByReference ptr, NativeLongByReference size);

        void sd_bus_error_free(SdBusError e);
    }

    private final LibSystemd lib;

    private JnaSdBusApi(LibSystemd lib) {
        this.lib = lib;
    }

    /** Loads the library by its soname; only {@link LibsystemdCheck} calls this, after the symbol check passed. */
    static JnaSdBusApi load(String soname) {
        Map<String, Object> options = Map.of(
                Library.OPTION_OPEN_FLAGS,
                JnaSystemLibraries.RTLD_NOW | JnaSystemLibraries.RTLD_LOCAL,
                Library.OPTION_STRING_ENCODING,
                StandardCharsets.UTF_8.name());
        return new JnaSdBusApi(Native.load(soname, LibSystemd.class, options));
    }

    private static Pointer pointer(long handle) {
        return handle == 0 ? null : new Pointer(handle);
    }

    private static long handle(Pointer pointer) {
        return pointer == null ? 0 : Pointer.nativeValue(pointer);
    }

    @Override
    public Handle openUser() {
        PointerByReference bus = new PointerByReference();
        int code = lib.sd_bus_open_user(bus);
        return new Handle(code, code < 0 ? 0 : handle(bus.getValue()));
    }

    @Override
    public void closeUnref(long bus) {
        if (bus != 0) {
            lib.sd_bus_close_unref(pointer(bus));
        }
    }

    @Override
    public int setMethodCallTimeout(long bus, long usec) {
        return lib.sd_bus_set_method_call_timeout(pointer(bus), usec);
    }

    @Override
    public Handle newMethodCall(long bus, String destination, String path, String iface, String member) {
        PointerByReference message = new PointerByReference();
        int code = lib.sd_bus_message_new_method_call(pointer(bus), message, destination, path, iface, member);
        return new Handle(code, code < 0 ? 0 : handle(message.getValue()));
    }

    @Override
    public int appendString(long message, String value) {
        return lib.sd_bus_message_append_basic(pointer(message), (byte) 's', value);
    }

    @Override
    public int appendUint32(long message, int value) {
        return lib.sd_bus_message_append_basic(pointer(message), (byte) 'u', new IntByReference(value));
    }

    @Override
    public int appendBoolean(long message, boolean value) {
        return lib.sd_bus_message_append_basic(pointer(message), (byte) 'b', new IntByReference(value ? 1 : 0));
    }

    @Override
    public int appendUint64(long message, long value) {
        return lib.sd_bus_message_append_basic(pointer(message), (byte) 't', new LongByReference(value));
    }

    @Override
    public int appendUnixFd(long message, int fd) {
        return lib.sd_bus_message_append_basic(pointer(message), (byte) 'h', new IntByReference(fd));
    }

    @Override
    public int openContainer(long message, char type, String contents) {
        return lib.sd_bus_message_open_container(pointer(message), (byte) type, contents);
    }

    @Override
    public int closeContainer(long message) {
        return lib.sd_bus_message_close_container(pointer(message));
    }

    @Override
    public CallReply call(long bus, long message, long timeoutUsec) {
        SdBusError error = new SdBusError();
        PointerByReference reply = new PointerByReference();
        int code = lib.sd_bus_call(pointer(bus), pointer(message), timeoutUsec, error, reply);
        long handle = code < 0 ? 0 : handle(reply.getValue());
        try {
            error.read();
            Optional<String> name = Optional.ofNullable(error.name).map(p -> p.getString(0, "UTF-8"));
            Optional<String> text = Optional.ofNullable(error.message).map(p -> p.getString(0, "UTF-8"));
            return new CallReply(code, handle, name, text);
        } catch (RuntimeException e) {
            // The reply would otherwise be lost to its caller (native-api review, S3A).
            messageUnref(handle);
            throw e;
        } finally {
            lib.sd_bus_error_free(error);
        }
    }

    @Override
    public void messageUnref(long message) {
        if (message != 0) {
            lib.sd_bus_message_unref(pointer(message));
        }
    }

    @Override
    public int hasSignature(long message, String signature) {
        return lib.sd_bus_message_has_signature(pointer(message), signature);
    }

    @Override
    public int enterContainer(long message, char type, String contents) {
        return lib.sd_bus_message_enter_container(pointer(message), (byte) type, contents);
    }

    @Override
    public int exitContainer(long message) {
        return lib.sd_bus_message_exit_container(pointer(message));
    }

    @Override
    public Read<String> readString(long message, char type) {
        if (type != 's' && type != 'o') {
            throw new IllegalArgumentException("Not a string type: " + type);
        }
        PointerByReference value = new PointerByReference();
        int code = lib.sd_bus_message_read_basic(pointer(message), (byte) type, value);
        if (code <= 0 || value.getValue() == null) {
            return new Read<>(code, Optional.empty());
        }
        return new Read<>(code, Optional.of(value.getValue().getString(0, StandardCharsets.UTF_8.name())));
    }

    @Override
    public Read<byte[]> readBytes(long message) {
        PointerByReference data = new PointerByReference();
        NativeLongByReference size = new NativeLongByReference();
        int code = lib.sd_bus_message_read_array(pointer(message), (byte) 'y', data, size);
        if (code <= 0) {
            return new Read<>(code, Optional.empty());
        }
        long length = size.getValue().longValue();
        if (length < 0 || length > Integer.MAX_VALUE) {
            return new Read<>(-LinuxErrors.EINVAL, Optional.empty());
        }
        // A size of zero comes with a pointer that must not be dereferenced (sd_bus_message_read_array(3)).
        byte[] bytes = length == 0 ? new byte[0] : data.getValue().getByteArray(0, (int) length);
        return new Read<>(code, Optional.of(bytes));
    }
}
