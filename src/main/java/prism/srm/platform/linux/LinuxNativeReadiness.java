package prism.srm.platform.linux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import prism.srm.domain.ProcessIdentity;

/**
 * Read-only classification of whether a Linux system, and a process on it, could have native limits through systemd
 * scopes (milestone 12, plan Rev 2.1 §2.3, §2.4, K13, K16). Pure: it decides from facts handed to it and gathers none,
 * so nothing here opens a file, calls D-Bus or starts a process. Capability discovery never creates a unit, never
 * moves a process and never hands a pidfd to the system: acceptance of {@code PIDFDs} is not a fact this class can
 * know, and a system is never more than "ready, adoption unconfirmed".
 *
 * <p>Two questions are kept apart: whether the system could provide each kind ({@link #classify}), and whether one
 * process is eligible ({@link #eligibility}). In milestone 12 the product's policy is {@link EligibilityPolicy#none()}:
 * every process is refused, because desktop application ownership and lifecycle are not validated (K16); only a test
 * harness names the disposable processes it created ({@link EligibilityPolicy#harnessOwned}).
 */
final class LinuxNativeReadiness {

    /** The systemd version that accepts {@code PIDFDs} for a transient scope (systemd NEWS, version 255). */
    static final int PIDFD_SCOPE_VERSION = 255;

    /** The systemd version from which {@code OOMPolicy=} can be set on a scope (systemd NEWS, version 253). */
    static final int SCOPE_OOM_POLICY_VERSION = 253;

    static final String UNIFIED_MOUNT = "/sys/fs/cgroup";

    private static final Pattern VERSION = Pattern.compile("([1-9][0-9]{2,3})(?:[.~\\-+][A-Za-z0-9.~\\-+]*)?");

    private LinuxNativeReadiness() {}

    /**
     * What the system shows, each fact optional because it may not have been read.
     *
     * @param unifiedMount the cgroup v2 mount point from mountinfo
     * @param v1Mounted whether a cgroup v1 hierarchy is mounted too
     * @param systemdVersion the user manager's version string (systemd's {@code Version} property)
     * @param appSliceControllers {@code cgroup.controllers} of the user manager's {@code app.slice}: the controllers
     *     delegated there
     * @param libsystemdPresent whether {@code libsystemd.so.0} and every symbol the bindings need were found
     */
    record Facts(
            Optional<String> unifiedMount,
            Optional<Boolean> v1Mounted,
            Optional<String> systemdVersion,
            Optional<Set<String>> appSliceControllers,
            Optional<Boolean> libsystemdPresent) {

        Facts {
            Objects.requireNonNull(unifiedMount, "unifiedMount");
            Objects.requireNonNull(v1Mounted, "v1Mounted");
            Objects.requireNonNull(systemdVersion, "systemdVersion");
            Objects.requireNonNull(appSliceControllers, "appSliceControllers");
            Objects.requireNonNull(libsystemdPresent, "libsystemdPresent");
        }
    }

    /**
     * Whether one kind could be provided: ready (with what is still unconfirmed), or not, with every reason.
     *
     * @param unconfirmed what only an authorized adoption on a test-owned process can confirm (never empty when ready)
     */
    record Readiness(boolean ready, List<String> reasons, List<String> unconfirmed) {

        Readiness {
            reasons = List.copyOf(reasons);
            unconfirmed = List.copyOf(unconfirmed);
            if (ready != reasons.isEmpty()) {
                throw new IllegalArgumentException("A kind is ready exactly when nothing stands against it");
            }
        }
    }

    /** The three kinds a Linux native limit can be. */
    record SystemReadiness(Readiness cpu, Readiness memoryThrottle, Readiness memoryHard) {}

    /** Which processes native operations may touch at all. */
    static final class EligibilityPolicy {

        private final Set<ProcessIdentity> harnessOwned;

        private EligibilityPolicy(Set<ProcessIdentity> harnessOwned) {
            this.harnessOwned = Set.copyOf(harnessOwned);
        }

        /** The product's policy in milestone 12: no process; desktop behaviour is not validated (K16). */
        static EligibilityPolicy none() {
            return new EligibilityPolicy(Set.of());
        }

        /** A test harness's policy: exactly the disposable processes it created and names here. */
        static EligibilityPolicy harnessOwned(Set<ProcessIdentity> identities) {
            return new EligibilityPolicy(identities);
        }

        boolean admits(ProcessIdentity identity) {
            return harnessOwned.contains(identity);
        }

        /** Whether the policy admits no process at all: the product's. */
        boolean admitsNone() {
            return harnessOwned.isEmpty();
        }
    }

    /** The major number of a systemd version string ("255.4-1ubuntu8.17" is 255); empty when it is not one. */
    static OptionalInt majorVersion(String version) {
        Matcher matcher = VERSION.matcher(version.strip());
        if (!matcher.matches()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(matcher.group(1)));
    }

    /** What the system could provide, from facts alone. */
    static SystemReadiness classify(Facts facts) {
        List<String> common = new ArrayList<>();
        if (facts.unifiedMount().isEmpty()) {
            common.add("the cgroup v2 mount could not be read");
        } else if (!facts.unifiedMount().get().equals(UNIFIED_MOUNT)) {
            common.add("cgroup v2 is mounted at " + facts.unifiedMount().get() + ", not " + UNIFIED_MOUNT
                    + " (a hybrid or legacy hierarchy)");
        }
        if (facts.v1Mounted().orElse(true)) {
            common.add(
                    facts.v1Mounted().isEmpty()
                            ? "whether a cgroup v1 hierarchy is mounted could not be read"
                            : "a cgroup v1 hierarchy is mounted (hybrid system)");
        }
        if (facts.libsystemdPresent().isEmpty()) {
            common.add("libsystemd was not checked");
        } else if (!facts.libsystemdPresent().get()) {
            common.add("libsystemd (or a function of it) is not available");
        }
        OptionalInt major =
                facts.systemdVersion().map(LinuxNativeReadiness::majorVersion).orElse(OptionalInt.empty());
        if (facts.systemdVersion().isEmpty()) {
            common.add("the systemd user manager's version is unknown (no user manager reachable)");
        } else if (major.isEmpty()) {
            common.add("the systemd version cannot be read: "
                    + facts.systemdVersion().get());
        } else if (major.getAsInt() < PIDFD_SCOPE_VERSION) {
            common.add("adopting a process by pidfd needs systemd " + PIDFD_SCOPE_VERSION + " (found "
                    + major.getAsInt() + ")");
        }
        Set<String> delegated = facts.appSliceControllers().orElse(Set.of());
        List<String> cpu = new ArrayList<>(common);
        List<String> memory = new ArrayList<>(common);
        if (facts.appSliceControllers().isEmpty()) {
            cpu.add("the controllers delegated to the session manager could not be read");
            memory.add("the controllers delegated to the session manager could not be read");
        } else {
            if (!delegated.contains("cpu")) {
                cpu.add("the cpu controller is not delegated to your session manager");
            }
            if (!delegated.contains("memory")) {
                memory.add("the memory controller is not delegated to your session manager");
            }
        }
        List<String> hard = new ArrayList<>(memory);
        if (major.isPresent() && major.getAsInt() < SCOPE_OOM_POLICY_VERSION) {
            hard.add("a hard memory limit needs OOMPolicy=continue, which systemd " + major.getAsInt()
                    + " cannot set on a scope");
        }
        List<String> unconfirmed =
                List.of("acceptance of PIDFDs by this user manager", "enabling of the controller in the scope's slice");
        return new SystemReadiness(
                readiness(cpu, unconfirmed), readiness(memory, unconfirmed), readiness(hard, unconfirmed));
    }

    private static Readiness readiness(List<String> reasons, List<String> unconfirmed) {
        return reasons.isEmpty()
                ? new Readiness(true, List.of(), unconfirmed)
                : new Readiness(false, reasons, List.of());
    }

    /**
     * Whether one process is eligible, decided before anything is asked of the system: the policy first, then where it
     * lives. Empty when eligible; otherwise why not.
     *
     * @param processCgroup the process's unified-hierarchy path, empty when it could not be read
     * @param uid the uid of the user whose session manager would adopt the process
     */
    static Optional<String> eligibility(
            EligibilityPolicy policy, ProcessIdentity identity, Optional<String> processCgroup, long uid) {
        if (!policy.admits(identity)) {
            return Optional.of("Linux native limits are not validated for desktop applications");
        }
        if (processCgroup.isEmpty()) {
            return Optional.of("the process's cgroup could not be read");
        }
        String path = processCgroup.get();
        String manager = "/user.slice/user-" + uid + ".slice/user@" + uid + ".service/";
        if (!path.startsWith(manager)) {
            return Optional.of("it runs outside your session manager (in " + path + ")");
        }
        if (ScopeNames.enclosingPrismScope(path).isPresent()) {
            return Optional.of("it is in a scope with Prism's name; an earlier session may limit it");
        }
        return Optional.empty();
    }
}
