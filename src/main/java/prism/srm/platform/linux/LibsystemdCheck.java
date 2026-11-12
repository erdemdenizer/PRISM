package prism.srm.platform.linux;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether libsystemd's sd-bus can be bound here, decided before anything of it is loaded (milestone 12, S3A, plan Rev
 * 2.2 §2.3 P1/P2, F16): a verified 64-bit architecture, the GNU C library 2.34 or later (whose {@code libc.so.6} holds
 * the loader functions), {@code libsystemd.so.0} that opens, and every symbol of {@link SdBusApi#SYMBOLS} in it. Only
 * then is the binding created; otherwise the answer is the reason, and no library is loaded, so no
 * {@link UnsatisfiedLinkError} can arise from a missing library or symbol.
 */
final class LibsystemdCheck {

    static final String SONAME = "libsystemd.so.0";

    /** The first glibc whose libc.so.6 holds dlopen and dlsym (glibc NEWS 2.34). */
    static final int GLIBC_LOADER_MINOR = 34;

    private static final Pattern GLIBC = Pattern.compile("2\\.([0-9]{1,3})(?:\\..*)?");

    /** What the check found: a binding, or why there is none. */
    record Outcome(Optional<SdBusApi> api, String detail) {

        Outcome {
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(detail, "detail");
        }

        static Outcome unavailable(String why) {
            return new Outcome(Optional.empty(), why);
        }

        boolean available() {
            return api.isPresent();
        }
    }

    private LibsystemdCheck() {}

    /** The check on the running system. */
    static Outcome onThisSystem() {
        return check(System.getProperty("os.arch", ""), new JnaSystemLibraries(), () -> JnaSdBusApi.load(SONAME));
    }

    /**
     * The check with its seams.
     *
     * @param binding creates the binding; called only when everything else passed
     */
    static Outcome check(String architecture, SystemLibraries libraries, Supplier<SdBusApi> binding) {
        String arch = architecture.toLowerCase(Locale.ROOT);
        if (!JnaLinux.VERIFIED_ARCHITECTURES.contains(arch)) {
            return Outcome.unavailable("the architecture " + arch + " is not verified for the sd-bus binding");
        }
        Optional<String> glibc = libraries.glibcVersion();
        if (glibc.isEmpty()) {
            return Outcome.unavailable("the process does not run on the GNU C library");
        }
        Matcher version = GLIBC.matcher(glibc.get().strip());
        if (!version.matches()) {
            return Outcome.unavailable("the GNU C library version cannot be read: " + glibc.get());
        }
        if (Integer.parseInt(version.group(1)) < GLIBC_LOADER_MINOR) {
            return Outcome.unavailable("the GNU C library " + glibc.get() + " is older than 2." + GLIBC_LOADER_MINOR);
        }
        SystemLibraries.Lookup lookup = libraries.lookup(SONAME, SdBusApi.SYMBOLS);
        if (!lookup.opened()) {
            return Outcome.unavailable(SONAME + " is not available: " + lookup.detail());
        }
        if (!lookup.complete()) {
            return Outcome.unavailable(SONAME + " lacks functions the binding needs: " + lookup.detail());
        }
        return new Outcome(
                Optional.of(binding.get()), SONAME + " with every needed function (glibc " + glibc.get() + ")");
    }
}
