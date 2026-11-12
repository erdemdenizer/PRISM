package prism.srm.platform.linux;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@link SystemLibraries} on the running system.
 *
 * <p><b>Which C library.</b> The process maps the GNU C library as {@code libc.so.6} (glibc 2.34 and later) or
 * {@code libc-2.NN.so} (earlier), possibly marked {@code (deleted)} after an update; the musl loader is
 * {@code ld-musl-*.so.1}. {@code /proc/self/maps} is read first, so that {@code gnu_get_libc_version} is only called
 * where it exists (glibc csu/version.c; https://man7.org/linux/man-pages/man3/gnu_get_libc_version.3.html).
 *
 * <p><b>Loader functions.</b> Since glibc 2.34 {@code dlopen}, {@code dlsym}, {@code dlerror} and {@code dlclose} are
 * in {@code libc.so.6} (glibc NEWS 2.34: "all functionality formerly implemented in the libraries ... libdl ... has
 * been integrated into libc"); before that they are in {@code libdl.so.2}, which this class does not use: an older
 * glibc is reported, not tried. {@code RTLD_NOW} is {@code 0x00002} and {@code RTLD_LOCAL} {@code 0} (glibc
 * bits/dlfcn.h). {@code dlsym} may legitimately return NULL, so a symbol counts as missing only when {@code dlerror},
 * cleared before the lookup, reports an error after it.
 * https://man7.org/linux/man-pages/man3/dlopen.3.html
 * https://man7.org/linux/man-pages/man3/dlsym.3.html
 * https://man7.org/linux/man-pages/man3/dlerror.3.html
 */
final class JnaSystemLibraries implements SystemLibraries {

    static final int RTLD_NOW = 0x00002;
    static final int RTLD_LOCAL = 0;

    private static final Pattern GLIBC_FILE = Pattern.compile("libc\\.so\\.6|libc-2\\.[0-9]{1,3}\\.so");

    /** The C library's version and loader calls; loaded on first use. */
    interface Libc extends Library {

        Libc INSTANCE = Native.load("c", Libc.class);

        String gnu_get_libc_version();

        Pointer dlopen(String file, int mode);

        Pointer dlsym(Pointer handle, String name);

        String dlerror();

        int dlclose(Pointer handle);
    }

    private final Path maps;

    JnaSystemLibraries() {
        this(Path.of("/proc/self/maps"));
    }

    JnaSystemLibraries(Path maps) {
        this.maps = maps;
    }

    @Override
    public Optional<String> glibcVersion() {
        if (!mapsGlibc()) {
            return Optional.empty();
        }
        return Optional.ofNullable(Libc.INSTANCE.gnu_get_libc_version());
    }

    private boolean mapsGlibc() {
        try {
            return mapsGlibc(Files.readAllLines(maps, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** Whether lines of {@code /proc/self/maps} show the GNU C library mapped. */
    static boolean mapsGlibc(List<String> lines) {
        for (String line : lines) {
            String mapped = line.endsWith(" (deleted)") ? line.substring(0, line.length() - 10) : line;
            int slash = mapped.lastIndexOf('/');
            if (slash >= 0 && GLIBC_FILE.matcher(mapped.substring(slash + 1)).matches()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Lookup lookup(String soname, List<String> symbols) {
        Libc libc = Libc.INSTANCE;
        libc.dlerror();
        Pointer handle = libc.dlopen(soname, RTLD_NOW | RTLD_LOCAL);
        if (handle == null) {
            return Lookup.notOpened(Optional.ofNullable(libc.dlerror()).orElse(soname + " could not be opened"));
        }
        try {
            List<String> missing = new ArrayList<>();
            for (String symbol : symbols) {
                libc.dlerror();
                libc.dlsym(handle, symbol);
                if (libc.dlerror() != null) {
                    missing.add(symbol);
                }
            }
            return Lookup.found(missing);
        } finally {
            libc.dlclose(handle);
        }
    }
}
