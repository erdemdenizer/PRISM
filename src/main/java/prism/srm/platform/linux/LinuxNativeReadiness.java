package prism.srm.platform.linux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Supplier;
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
 * harness names the disposable processes it created ({@link EligibilityPolicy#harnessOwned}). The policy a product
 * without K16 would use, {@link EligibilityPolicy#desktop()}, exists and is tested, and nothing in the product makes it.
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

    /** Why a process is refused while desktop applications are not validated (K16). */
    static final String NOT_VALIDATED = "Linux native limits are not validated for desktop applications";

    /** Why PRISM's own process is refused under the desktop policy. */
    static final String PRISM_ITSELF = "it is PRISM's own process";

    /** Why a process PRISM runs under is refused under the desktop policy. */
    static final String PRISM_ANCESTOR = "PRISM runs under it (it started PRISM, directly or through other processes)";

    /** Why every process is refused when the processes PRISM runs under could not all be read. */
    static final String ANCESTORS_UNKNOWN = "the processes PRISM runs under could not all be read";

    /** How many parents are followed from PRISM's process at most; a chain longer than this is not expected. */
    static final int MOST_ANCESTORS = 64;

    /** Which processes native operations may touch at all. */
    static final class EligibilityPolicy {

        private final Set<ProcessIdentity> harnessOwned;
        /** The desktop policy: PRISM's own PID and the PIDs of the processes it runs under, read at each call. */
        private final Optional<Supplier<Excluded>> desktop;

        /**
         * The processes the desktop policy never admits.
         *
         * @param complete whether the walk reached the top; when it did not, every process is refused (fail closed)
         */
        record Excluded(long own, Set<Long> ancestors, boolean complete) {
            Excluded {
                ancestors = Set.copyOf(ancestors);
            }

            Excluded(long own, Set<Long> ancestors) {
                this(own, ancestors, true);
            }
        }

        private EligibilityPolicy(Set<ProcessIdentity> harnessOwned, Optional<Supplier<Excluded>> desktop) {
            this.harnessOwned = Set.copyOf(harnessOwned);
            this.desktop = Objects.requireNonNull(desktop, "desktop");
        }

        /** The product's policy in milestone 12: no process; desktop behaviour is not validated (K16). */
        static EligibilityPolicy none() {
            return new EligibilityPolicy(Set.of(), Optional.empty());
        }

        /** A test harness's policy: exactly the disposable processes it created and names here. */
        static EligibilityPolicy harnessOwned(Set<ProcessIdentity> identities) {
            return new EligibilityPolicy(identities, Optional.empty());
        }

        /**
         * The policy for desktop applications, for the day K16 is lifted; nothing in the product uses it while K16
         * holds. It admits every process except PRISM's own and the processes PRISM runs under: its parent, that one's
         * parent, and so on ({@link ProcessHandle#parent()}, read again at every call, as a process can be given a new
         * parent when its parent exits). They are compared by PID alone, within the walk of that call: a PID reused in
         * the middle of a walk can only add a process that is not an ancestor, which errs on the safe side. A chain
         * longer than {@link #MOST_ANCESTORS} refuses every process. Every other rule of {@link #eligibility} and of the
         * limiter still applies: the user manager and its own {@code init.scope}, the user and group IDs, PRISM's
         * scopes, the cgroup.
         */
        static EligibilityPolicy desktop() {
            return new EligibilityPolicy(Set.of(), Optional.of(EligibilityPolicy::prismAndAncestors));
        }

        /** {@link #desktop()} with the excluded processes from elsewhere, for tests. */
        static EligibilityPolicy desktopExcluding(Supplier<Excluded> excluded) {
            return new EligibilityPolicy(Set.of(), Optional.of(excluded));
        }

        /** PRISM's PID and its ancestors' PIDs, now: a few reads of {@code /proc/<pid>/stat} through the JDK. */
        private static Excluded prismAndAncestors() {
            ProcessHandle self = ProcessHandle.current();
            List<Long> ancestors = new ArrayList<>();
            Optional<ProcessHandle> parent = self.parent();
            while (parent.isPresent() && ancestors.size() < MOST_ANCESTORS) {
                long pid = parent.get().pid();
                if (ancestors.contains(pid)) {
                    break;
                }
                ancestors.add(pid);
                parent = parent.get().parent();
            }
            return new Excluded(
                    self.pid(), Set.copyOf(ancestors), parent.isEmpty() || ancestors.size() < MOST_ANCESTORS);
        }

        /** Why the policy refuses the process; empty when it admits it. */
        Optional<String> refusal(ProcessIdentity identity) {
            if (desktop.isPresent()) {
                Excluded excluded = desktop.get().get();
                if (!excluded.complete()) {
                    return Optional.of(ANCESTORS_UNKNOWN);
                }
                if (identity.pid() == excluded.own()) {
                    return Optional.of(PRISM_ITSELF);
                }
                if (excluded.ancestors().contains(identity.pid())) {
                    return Optional.of(PRISM_ANCESTOR);
                }
                return Optional.empty();
            }
            return harnessOwned.contains(identity) ? Optional.empty() : Optional.of(NOT_VALIDATED);
        }

        boolean admits(ProcessIdentity identity) {
            return refusal(identity).isEmpty();
        }

        /** Whether the policy admits no process at all: the product's. */
        boolean admitsNone() {
            return desktop.isEmpty() && harnessOwned.isEmpty();
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
        Optional<String> refused = policy.refusal(identity);
        if (refused.isPresent()) {
            return refused;
        }
        if (processCgroup.isEmpty()) {
            return Optional.of("the process's cgroup could not be read");
        }
        String path = processCgroup.get();
        String manager = "/user.slice/user-" + uid + ".slice/user@" + uid + ".service/";
        if (!path.startsWith(manager)) {
            return Optional.of("it runs outside your session manager (in " + path + ")");
        }
        // The user manager's own process lives in its init.scope (user@.service(5), example 1); it is never moved.
        if (path.equals(manager + "init.scope") || path.startsWith(manager + "init.scope/")) {
            return Optional.of("it is your systemd user manager itself");
        }
        if (ScopeNames.enclosingPrismScope(path).isPresent()) {
            return Optional.of("it is in a scope with PRISM's name; an earlier session may limit it");
        }
        return Optional.empty();
    }
}
