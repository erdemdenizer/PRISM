package prism.srm.platform.linux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Read-only correlation of what the systemd user manager says with what the cgroup files show (milestone 12, S3A,
 * plan Rev 2.2 §2.3 P1–P7, P9, K13). It gathers the facts {@link LinuxNativeReadiness#classify} decides from and
 * checks that the two sources agree: the manager's control group is the user's {@code user@UID.service}, its
 * {@code app.slice} lies inside it, the controllers delegated there ({@code cgroup.controllers}) and enabled
 * ({@code cgroup.subtree_control}) are reported apart, and Prism's own unit (when this manager has one) names the cgroup
 * {@code /proc/self/cgroup} names.
 *
 * <p>It only reads: files, {@code dlsym}, and the methods of {@link SystemdUserBus.ReadOnlyMethod}. It creates no unit,
 * moves no process, hands no pidfd to anyone and changes no product behaviour: nothing in the product calls it, and its
 * report always carries the product's eligibility policy, under which every process stays {@code UNAVAILABLE} (K16).
 */
final class SystemdCapabilityProbe {

    /** What the probe found. */
    record Report(
            String library,
            Optional<SystemdUserBus.Reading<SystemdUserBus.ManagerFacts>> manager,
            Optional<SystemdUserBus.Reading<SystemdUserBus.UnitFacts>> appSlice,
            Optional<SystemdUserBus.Reading<SystemdUserBus.UnitFacts>> ownUnit,
            Optional<String> ownCgroup,
            Optional<Set<String>> appSliceDelegated,
            Optional<Set<String>> appSliceEnabled,
            LinuxNativeReadiness.Facts facts,
            LinuxNativeReadiness.SystemReadiness readiness,
            List<String> findings,
            String productPolicy) {

        Report {
            findings = List.copyOf(findings);
        }

        /** A plain-text account, one fact per line, for logs and evidence. */
        String describe() {
            List<String> lines = new ArrayList<>();
            lines.add("library: " + library);
            manager.ifPresent(m -> lines.add("manager: " + m.status() + " "
                    + m.value().map(Object::toString).orElse(m.detail())));
            appSlice.ifPresent(a -> lines.add("app.slice: " + a.status() + " "
                    + a.value().map(Object::toString).orElse(a.detail())));
            ownUnit.ifPresent(o -> lines.add("own unit: " + o.status() + " "
                    + o.value().map(Object::toString).orElse(o.detail())));
            lines.add("own cgroup: " + ownCgroup.orElse("(unread)"));
            lines.add("app.slice delegated: "
                    + appSliceDelegated.map(Object::toString).orElse("(unread)"));
            lines.add("app.slice enabled: "
                    + appSliceEnabled.map(Object::toString).orElse("(unread)"));
            lines.add("facts: " + facts);
            lines.add("cpu: " + readiness.cpu());
            lines.add("memory throttle: " + readiness.memoryThrottle());
            lines.add("memory hard: " + readiness.memoryHard());
            findings.forEach(f -> lines.add("finding: " + f));
            lines.add("product: " + productPolicy);
            return String.join(System.lineSeparator(), lines);
        }
    }

    static final String PRODUCT_POLICY =
            "every process UNAVAILABLE: Linux native limits are not validated for desktop applications (K16)";

    private final LibsystemdCheck.Outcome library;
    private final Function<SdBusApi, SystemdUserBus> busFactory;
    private final CgroupFiles files;
    private final long uid;

    SystemdCapabilityProbe(
            LibsystemdCheck.Outcome library,
            Function<SdBusApi, SystemdUserBus> busFactory,
            CgroupFiles files,
            long uid) {
        this.library = Objects.requireNonNull(library, "library");
        this.busFactory = Objects.requireNonNull(busFactory, "busFactory");
        this.files = Objects.requireNonNull(files, "files");
        this.uid = uid;
    }

    /**
     * The probe with the real library check and bus; the cgroup and proc roots come from whoever composes it (only the
     * read-only smoke test in this stage), so no file system path is fixed here.
     */
    static SystemdCapabilityProbe onThisSystem(CgroupFiles files, long uid) {
        return new SystemdCapabilityProbe(LibsystemdCheck.onThisSystem(), SystemdUserBus::new, files, uid);
    }

    /** Reads, correlates and classifies; changes nothing. */
    Report run() {
        List<String> findings = new ArrayList<>();
        ProcFs.Reading<String> mount = files.unifiedMount();
        ProcFs.Reading<Boolean> v1 = files.v1Mounted();
        ProcFs.Reading<String> own =
                files.processCgroup((int) ProcessHandle.current().pid());

        Optional<SystemdUserBus.Reading<SystemdUserBus.ManagerFacts>> manager = Optional.empty();
        Optional<SystemdUserBus.Reading<SystemdUserBus.UnitFacts>> appSlice = Optional.empty();
        Optional<SystemdUserBus.Reading<SystemdUserBus.UnitFacts>> ownUnit = Optional.empty();
        Optional<Set<String>> delegated = Optional.empty();
        Optional<Set<String>> enabled = Optional.empty();

        if (library.available()) {
            SystemdUserBus bus = busFactory.apply(library.api().get());
            manager = Optional.of(bus.manager());
            appSlice = Optional.of(bus.unit("app.slice"));
            ownUnit = Optional.of(bus.ownUnit());

            String expected = "/user.slice/user-" + uid + ".slice/user@" + uid + ".service";
            Optional<String> managerGroup = manager.get().value().map(SystemdUserBus.ManagerFacts::controlGroup);
            if (managerGroup.isPresent() && !managerGroup.get().equals(expected)) {
                findings.add("the manager's control group is " + managerGroup.get() + ", not " + expected);
            }
            Optional<String> sliceGroup = appSlice.get().value().flatMap(SystemdUserBus.UnitFacts::controlGroup);
            if (managerGroup.isPresent()
                    && sliceGroup.isPresent()
                    && !sliceGroup.get().equals(managerGroup.get() + "/app.slice")) {
                findings.add("app.slice's control group " + sliceGroup.get() + " is not inside the manager's");
            }
            if (sliceGroup.isPresent()) {
                ProcFs.Reading<Set<String>> controllers = files.controllers(sliceGroup.get());
                ProcFs.Reading<Set<String>> subtree = files.subtreeControl(sliceGroup.get());
                delegated = controllers.value();
                enabled = subtree.value();
                if (delegated.isEmpty()) {
                    findings.add("app.slice's cgroup.controllers could not be read: " + controllers.detail());
                }
            } else {
                findings.add("app.slice has no control group: " + appSlice.get().detail());
            }
            Optional<String> ownGroup = ownUnit.get().value().flatMap(SystemdUserBus.UnitFacts::controlGroup);
            if (ownGroup.isPresent()
                    && own.value().isPresent()
                    && !ownGroup.get().equals(own.value().get())) {
                findings.add("Prism's unit names the cgroup " + ownGroup.get() + " but /proc/self/cgroup names "
                        + own.value().get());
            }
            if (own.value().isPresent() && !own.value().get().startsWith(expected + "/")) {
                findings.add(
                        "Prism runs outside its user manager (in " + own.value().get() + ")");
            }
        }

        LinuxNativeReadiness.Facts facts = new LinuxNativeReadiness.Facts(
                mount.value(),
                v1.value(),
                manager.flatMap(SystemdUserBus.Reading::value).map(SystemdUserBus.ManagerFacts::version),
                delegated,
                Optional.of(library.available()));
        return new Report(
                library.detail(),
                manager,
                appSlice,
                ownUnit,
                own.value(),
                delegated,
                enabled,
                facts,
                LinuxNativeReadiness.classify(facts),
                findings,
                PRODUCT_POLICY);
    }
}
