package prism.srm.platform.linux;

import java.util.ArrayList;
import java.util.Arrays;
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
 * scopes (milestone 12, plan Rev 2.1 §2.3, §2.4, K13; K16 lifted 2026-11-28). Pure: it decides from facts handed to it
 * and gathers none, so nothing here opens a file, calls D-Bus or starts a process. Capability discovery never creates a unit, never
 * moves a process and never hands a pidfd to the system: acceptance of {@code PIDFDs} is not a fact this class can
 * know, and a system is never more than "ready, adoption unconfirmed".
 *
 * <p>Two questions are kept apart: whether the system could provide each kind ({@link #classify}), and whether one
 * process is eligible ({@link #eligibility}). The product's policy is {@link EligibilityPolicy#desktop()} since the
 * product owner lifted K16 on 2026-11-28 (ADR-0016, amendment of that date): only a desktop application's own scope in
 * the user manager's {@code app.slice}, never PRISM or the processes it runs under, and each adoption still needs the
 * user's consent for that request. A test harness names the disposable processes it created ({@link
 * EligibilityPolicy#harnessOwned}); {@link EligibilityPolicy#none()} admits nothing. Apart from eligibility, {@link
 * #sessionService} tells a service of the desktop session, which gets no emulated limit either (owner decision 2 of
 * 2026-11-28).
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

    /** Why a process is refused by a policy that does not admit it: the harness's, for a process it did not start. */
    static final String NOT_NAMED = "it is not one of the processes this limiter was made for";

    /** Why PRISM's own process is refused under the desktop policy. */
    static final String PRISM_ITSELF = "it is PRISM's own process";

    /** Why a process PRISM runs under is refused under the desktop policy. */
    static final String PRISM_ANCESTOR = "PRISM runs under it (it started PRISM, directly or through other processes)";

    /** Why every process is refused when the processes PRISM runs under could not all be read. */
    static final String ANCESTORS_UNKNOWN = "the processes PRISM runs under could not all be read";

    /**
     * Why a process outside the user manager's {@code app.slice} is refused under the desktop policy (risk D17); the
     * place it runs in follows. systemd's desktop convention puts the applications the user runs in {@code app.slice},
     * the services the graphical session needs (the display server, D-Bus, the portals) in {@code session.slice}, and
     * low-priority background work in {@code background.slice}: systemd.special(7),
     * https://www.freedesktop.org/software/systemd/man/latest/systemd.special.html, and
     * https://systemd.io/DESKTOP_ENVIRONMENTS/.
     */
    static final String NOT_AN_APPLICATION = "not a desktop application: it runs in ";

    /**
     * Why a process in a service unit in {@code app.slice} is refused under the desktop policy; the unit goes before it.
     * A service is a process systemd started and supervises (systemd.service(5): "information about a process
     * controlled and supervised by systemd", https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html),
     * and once the processes of a service have left its cgroup systemd may take the service as ended; a scope only groups
     * processes systemd did not start (systemd.scope(5): "Unlike service units, scope units manage externally created
     * processes, and do not fork off processes on their own",
     * https://www.freedesktop.org/software/systemd/man/latest/systemd.scope.html). Observed on Ubuntu 24.04's GNOME
     * session on 2026-11-28: {@code gnome-session-manager@ubuntu.service} and {@code gnome-terminal-server.service} run
     * in {@code app.slice}, and the session manager ending ends the session.
     */
    static final String SERVICE_UNIT = ", a service of your session manager: moving it out of its unit could end it";

    /**
     * How the reason of a desktop session service begins (owner decision 2 of 2026-11-28); where it runs and {@link
     * #SESSION_SERVICE_WHY} follow. The duty cycle stops the process for part of every period, and the session's
     * services (the compositor, the session bus broker) are what draws and connects the desktop.
     */
    static final String SESSION_SERVICE = "it is a desktop session service (";

    /** How the reason of a desktop session service ends. */
    static final String SESSION_SERVICE_WHY = "): stopping it would freeze the desktop";

    /** systemd's name of a user's manager unit, {@code user@UID.service} (user@.service(5)). */
    private static final Pattern USER_MANAGER = Pattern.compile("user@[0-9]{1,10}\\.service");

    /** How many parents are followed from PRISM's process at most; a chain longer than this is not expected. */
    static final int MOST_ANCESTORS = 64;

    /** Which processes native operations may touch at all. */
    static final class EligibilityPolicy {

        private final Set<ProcessIdentity> harnessOwned;
        /**
         * The desktop policy: PRISM's own PID and the PIDs of the processes it runs under, read at each call. Its
         * presence also restricts the cgroup to the user manager's {@code app.slice} ({@link #applicationsOnly}).
         */
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

        /**
         * A policy that admits no process. The product used it while K16 held (milestone 12 to 2026-11-28); a limiter
         * made with it reports every kind unavailable, opens no bus and looks for no earlier session's limits.
         */
        static EligibilityPolicy none() {
            return new EligibilityPolicy(Set.of(), Optional.empty());
        }

        /** A test harness's policy: exactly the disposable processes it created and names here. */
        static EligibilityPolicy harnessOwned(Set<ProcessIdentity> identities) {
            return new EligibilityPolicy(identities, Optional.empty());
        }

        /**
         * The product's policy for desktop applications, since the product owner lifted K16 on 2026-11-28 ({@code
         * LinuxScopeLimiter.desktop}). It admits every process except PRISM's own and the processes PRISM runs under: its
         * parent, that one's parent, and so on ({@link ProcessHandle#parent()}, read again at every call, as a process can be given a new
         * parent when its parent exits). They are compared by PID alone, within the walk of that call: a PID reused in
         * the middle of a walk can only add a process that is not an ancestor, which errs on the safe side. A chain
         * longer than {@link #MOST_ANCESTORS} refuses every process. Only a process whose cgroup is a scope unit's own
         * cgroup inside the user manager's {@code app.slice} is admitted (risk D17): the session bus broker, the
         * compositor and the other session services run in {@code session.slice}, background work in {@code
         * background.slice}, and none of them is a desktop application ({@link #NOT_AN_APPLICATION}); a service, in
         * {@code app.slice} too, could end when its process is moved out of its unit ({@link #SERVICE_UNIT}). Every
         * other rule of {@link #eligibility} and of the limiter still applies: the user manager and its own {@code init.scope}, the user and group IDs, PRISM's
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
            return harnessOwned.contains(identity) ? Optional.empty() : Optional.of(NOT_NAMED);
        }

        boolean admits(ProcessIdentity identity) {
            return refusal(identity).isEmpty();
        }

        /** Whether only scopes in the user manager's {@code app.slice} are admitted: the desktop policy's rule. */
        boolean applicationsOnly() {
            return desktop.isPresent();
        }

        /** Whether the policy admits no process at all ({@link #none()}). */
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
        if (policy.applicationsOnly()) {
            return notAnApplication(path.substring(manager.length()));
        }
        return Optional.empty();
    }

    /**
     * Under the desktop policy (risk D17): empty when the cgroup, relative to the user manager's, is a scope unit's own
     * cgroup in {@code app.slice}: {@code app.slice/}, any number of nested {@code .slice} segments, then exactly one
     * {@code .scope} segment, and nothing below it. {@code app.slice} is where systemd's desktop convention places the
     * applications the user runs ("all normal applications that the user is running",
     * https://systemd.io/DESKTOP_ENVIRONMENTS/; systemd.special(7): "By default, all user services and applications
     * managed by systemd are found in this slice"), and a launcher or a terminal starts each in a scope of its own
     * ({@code app-gnome-<name>-<pid>.scope}, {@code vte-spawn-<uuid>.scope}). A {@code .service} there is refused ({@link
     * #SERVICE_UNIT}): systemd started and supervises it, and moving its process out of its unit could end it, as for the
     * GNOME session manager, which Ubuntu 24.04 runs in {@code app.slice} (observed 2026-11-28; systemd.service(5),
     * systemd.scope(5)). A slice holds no processes itself, and a cgroup below a unit exists
     * only in a subtree delegated to another program, which systemd itself does not move processes across
     * (https://systemd.io/CGROUP_DELEGATION/); both are refused. Otherwise why not.
     * {@code session.slice} holds the services the graphical session depends on, among them the session bus broker
     * ({@code dbus.service} of dbus-daemon and {@code dbus-broker.service}, both {@code Slice=session.slice} upstream:
     * https://sources.debian.org/src/dbus/1.14.10-1~deb12u1/bus/systemd-user/dbus.service.in/,
     * https://github.com/bus1/dbus-broker/blob/main/src/units/user/dbus-broker.service.in) and GNOME's compositor
     * ({@code org.gnome.Shell@.service}, {@code Slice=session.slice}:
     * https://github.com/GNOME/gnome-shell/blob/main/data/org.gnome.Shell@.service.in, GNOME's mirror); {@code
     * background.slice} holds background work; a unit directly under the manager is in none of these slices. A unit
     * that names no slice is put in {@code app.slice} by default (systemd.special(7)), so the rule relies on the
     * session's own units naming {@code session.slice}, which the manual desktop validation observes. It is decided
     * from the same cgroup read that the check after the pidfd is open proves (risk D2).
     */
    private static Optional<String> notAnApplication(String relative) {
        int slash = relative.indexOf('/');
        String first = slash < 0 ? relative : relative.substring(0, slash);
        if (first.equals("app.slice")) {
            String[] below =
                    slash < 0 ? new String[0] : relative.substring(slash + 1).split("/", -1);
            int unit = 0;
            while (unit < below.length && below[unit].endsWith(".slice")) {
                unit++;
            }
            if (unit == below.length || below[unit].isEmpty()) {
                return Optional.of(NOT_AN_APPLICATION + (unit == 0 ? "app.slice itself" : below[unit - 1])
                        + ", in no application's unit");
            }
            if (!below[unit].endsWith(".scope") && !below[unit].endsWith(".service")) {
                return Optional.of(NOT_AN_APPLICATION + below[unit] + ", which is not a scope unit");
            }
            if (unit != below.length - 1) {
                return Optional.of("it runs in a cgroup below " + below[unit]
                        + ", a subtree another program manages, which PRISM does not move processes out of");
            }
            return below[unit].endsWith(".service")
                    ? Optional.of("it runs in " + below[unit] + SERVICE_UNIT)
                    : Optional.empty();
        }
        if (first.endsWith(".slice")) {
            return Optional.of(NOT_AN_APPLICATION + first);
        }
        return Optional.of(NOT_AN_APPLICATION
                + (first.isEmpty() ? "the cgroup of your systemd user manager" : first)
                + ", directly under your systemd user manager, not in app.slice");
    }

    /**
     * Whether the cgroup is in a user manager's {@code session.slice} (owner decision 2 of 2026-11-28): why such a process
     * gets no emulated limit and none is offered, or empty. systemd's desktop convention puts the services the graphical
     * session needs there (systemd.special(7), https://systemd.io/DESKTOP_ENVIRONMENTS/); observed on Ubuntu 24.04's
     * GNOME session on 2026-11-28: {@code session.slice/org.gnome.Shell@wayland.service} (the compositor) and {@code
     * session.slice/dbus.service} (the session bus). Decided from the path alone: a segment {@code user@UID.service}
     * (user@.service(5)) followed by {@code session.slice}, for any user, so it holds whether or not native limits are
     * ready, with or without an answer from the user manager. A {@code session.slice} anywhere else does not count.
     *
     * @param processCgroup the process's unified-hierarchy path
     */
    static Optional<String> sessionService(String processCgroup) {
        String[] segments = processCgroup.split("/", -1);
        for (int i = 0; i + 1 < segments.length; i++) {
            if (USER_MANAGER.matcher(segments[i]).matches() && segments[i + 1].equals("session.slice")) {
                String where = String.join("/", Arrays.copyOfRange(segments, i + 1, segments.length));
                return Optional.of(SESSION_SERVICE + where + SESSION_SERVICE_WHY);
            }
        }
        return Optional.empty();
    }
}
