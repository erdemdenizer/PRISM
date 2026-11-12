package prism.srm.platform.linux;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The dynamic-loader questions the Linux native bindings ask before any library of theirs is loaded (milestone 12,
 * S3A, plan Rev 2.2 §2.3 P2, F16): which C library runs, and whether a shared object and every symbol a binding needs
 * can be found. Asking loads nothing for use: a library is opened, looked into and closed again. A seam: tests give a
 * fake that follows the man pages.
 */
interface SystemLibraries {

    /** What looking into one shared object found. */
    record Lookup(boolean opened, List<String> missing, String detail) {

        public Lookup {
            missing = List.copyOf(missing);
            Objects.requireNonNull(detail, "detail");
            if (!opened && !missing.isEmpty()) {
                throw new IllegalArgumentException("Symbols are looked up only in a library that opened");
            }
        }

        static Lookup notOpened(String detail) {
            return new Lookup(false, List.of(), detail);
        }

        static Lookup found(List<String> missing) {
            return new Lookup(true, missing, missing.isEmpty() ? "" : "missing " + String.join(", ", missing));
        }

        boolean complete() {
            return opened && missing.isEmpty();
        }
    }

    /**
     * The version of the GNU C library the process runs on ({@code gnu_get_libc_version()}), or empty when the process
     * does not run on the GNU C library, in which case nothing else here is asked.
     */
    Optional<String> glibcVersion();

    /**
     * Opens the shared object by its soname, looks up each symbol, and closes it again. Called only on a GNU C library
     * of version 2.34 or later, whose {@code libc.so.6} holds the dynamic-loader functions.
     */
    Lookup lookup(String soname, List<String> symbols);
}
