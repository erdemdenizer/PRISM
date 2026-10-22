package prism.srm.ui.processes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.ProcessState;

/** Listings of processes with known values, for the tests and the rendered snapshots of the Processes page. */
public final class ProcessListings {

    public static final long KIB = 1024;
    public static final long MIB = 1024 * KIB;
    public static final long GIB = 1024 * MIB;

    /** When the machine of the listings was started; processes start some seconds after it. */
    public static final Instant BOOT = Instant.parse("2026-10-06T06:00:00Z");

    private static final String[] NAMES = {
        "svchost.exe", "chrome.exe", "RuntimeBroker.exe", "conhost.exe", "msedgewebview2.exe", "Code.exe",
        "explorer.exe", "java.exe", "SearchHost.exe", "dwm.exe", "csrss.exe", "powershell.exe",
        "audiodg.exe", "fontdrvhost.exe", "ctfmon.exe", "sihost.exe", "taskhostw.exe", "spoolsv.exe",
    };

    private ProcessListings() {}

    /** A process under construction: every value present and ordinary until a test says otherwise. */
    public static final class Sample {

        private final long pid;
        private Instant started;
        private String name;
        private OptionalLong parentPid = OptionalLong.of(4);
        private Optional<ProcessState> state = Optional.empty();
        private OptionalDouble cpuPercent = OptionalDouble.of(0);
        private OptionalLong residentBytes = OptionalLong.of(24 * MIB);
        private OptionalLong privateResidentBytes = OptionalLong.of(16 * MIB);
        private OptionalLong virtualBytes = OptionalLong.of(2 * GIB);
        private Optional<String> user = Optional.of("alice");
        private OptionalInt threadCount = OptionalInt.of(8);
        private OptionalInt priority = OptionalInt.of(8);
        private OptionalDouble ioRead = OptionalDouble.of(0);
        private OptionalDouble ioWrite = OptionalDouble.of(0);

        private Sample(long pid, String name) {
            this.pid = pid;
            this.name = name;
            this.started = BOOT.plusSeconds(pid);
        }

        /** The same PID in the hands of a process that started later: another identity. */
        public Sample startedSecondsAfterBoot(long seconds) {
            started = BOOT.plusSeconds(seconds);
            return this;
        }

        public Sample name(String newName) {
            name = newName;
            return this;
        }

        public Sample cpu(double percent) {
            cpuPercent = OptionalDouble.of(percent);
            return this;
        }

        public Sample noCpu() {
            cpuPercent = OptionalDouble.empty();
            return this;
        }

        public Sample memory(long bytes) {
            residentBytes = OptionalLong.of(bytes);
            return this;
        }

        public Sample noMemory() {
            residentBytes = OptionalLong.empty();
            return this;
        }

        public Sample privateMemory(long bytes) {
            privateResidentBytes = OptionalLong.of(bytes);
            return this;
        }

        public Sample virtualSize(long bytes) {
            virtualBytes = OptionalLong.of(bytes);
            return this;
        }

        public Sample io(double readBytesPerSecond, double writeBytesPerSecond) {
            ioRead = OptionalDouble.of(readBytesPerSecond);
            ioWrite = OptionalDouble.of(writeBytesPerSecond);
            return this;
        }

        public Sample noIo() {
            ioRead = OptionalDouble.empty();
            ioWrite = OptionalDouble.empty();
            return this;
        }

        /** Only the read rate is known, as after a write counter that went backwards. */
        public Sample ioReadOnly(double readBytesPerSecond) {
            ioRead = OptionalDouble.of(readBytesPerSecond);
            ioWrite = OptionalDouble.empty();
            return this;
        }

        public Sample user(String newUser) {
            user = Optional.of(newUser);
            return this;
        }

        public Sample noUser() {
            user = Optional.empty();
            return this;
        }

        public Sample threads(int count) {
            threadCount = OptionalInt.of(count);
            return this;
        }

        public Sample noThreads() {
            threadCount = OptionalInt.empty();
            return this;
        }

        public Sample priority(int number) {
            priority = OptionalInt.of(number);
            return this;
        }

        public Sample noPriority() {
            priority = OptionalInt.empty();
            return this;
        }

        public Sample state(ProcessState newState) {
            state = Optional.of(newState);
            return this;
        }

        public Sample parent(long newParentPid) {
            parentPid = OptionalLong.of(newParentPid);
            return this;
        }

        /** Takes away everything a system may not report: only identity and name remain. */
        public Sample nothingElse() {
            parentPid = OptionalLong.empty();
            privateResidentBytes = OptionalLong.empty();
            virtualBytes = OptionalLong.empty();
            return noCpu().noMemory().noIo().noUser().noThreads().noPriority();
        }

        public ProcessSnapshot build() {
            return new ProcessSnapshot(
                    new ProcessIdentity(pid, started),
                    name,
                    parentPid,
                    state,
                    cpuPercent,
                    residentBytes,
                    privateResidentBytes,
                    virtualBytes,
                    user,
                    threadCount,
                    priority,
                    ioRead,
                    ioWrite);
        }
    }

    /** A process with this PID that started that many seconds after boot. */
    public static Sample process(long pid, String name) {
        return new Sample(pid, name);
    }

    /** The identity {@link #process} gives a process with this PID. */
    public static ProcessIdentity identity(long pid) {
        return new ProcessIdentity(pid, BOOT.plusSeconds(pid));
    }

    /**
     * A listing as a Windows desktop gives it: no state, a third of the processes without a user,
     * most of them idle. The same listing for the same arguments.
     *
     * @param count the number of processes
     * @param tick which listing of a run this is; values that change over time depend on it
     */
    public static List<ProcessSnapshot> windows(int count, int tick) {
        List<ProcessSnapshot> listing = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long pid = 4L * (index + 1);
            String name = NAMES[index % NAMES.length];
            Sample sample = process(pid, name)
                    .parent(4L * (index / 7 + 1))
                    .threads(2 + (index * 7) % 60)
                    .priority(index % 11 == 0 ? 13 : 8)
                    .memory((4 + (index * 37L) % 900) * MIB + (index * 4099L) % MIB)
                    .privateMemory((2 + (index * 23L) % 500) * MIB)
                    .virtualSize((2 + index % 40) * GIB)
                    // Most processes are idle; a few work, and how much changes from tick to tick.
                    .cpu(index % 9 == 0 ? Math.abs(Math.sin(index + tick / 3.0)) * 6.25 : 0)
                    .io(
                            index % 6 == 0 ? Math.abs(Math.cos(index + tick / 2.0)) * 800 * KIB : 0,
                            index % 10 == 0 ? Math.abs(Math.sin(index + tick / 4.0)) * 90 * KIB : 0);
            if (index % 3 == 0) {
                sample.noUser();
            } else {
                sample.user(index % 3 == 1 ? "alice" : "SYSTEM");
            }
            listing.add(sample.build());
        }
        return listing;
    }

    /** The first listing of a run: no process has a CPU share or an I/O rate yet. */
    public static List<ProcessSnapshot> firstListing(int count) {
        List<ProcessSnapshot> listing = new ArrayList<>(count);
        for (ProcessSnapshot process : windows(count, 0)) {
            listing.add(new ProcessSnapshot(
                    process.identity(),
                    process.name(),
                    process.parentPid(),
                    process.state(),
                    OptionalDouble.empty(),
                    process.residentBytes(),
                    process.privateResidentBytes(),
                    process.virtualBytes(),
                    process.user(),
                    process.threadCount(),
                    process.priority(),
                    OptionalDouble.empty(),
                    OptionalDouble.empty()));
        }
        return listing;
    }

    /** Names and users far longer than their columns, in two alphabets. */
    public static List<ProcessSnapshot> longNames() {
        return List.of(
                process(4120, "a-process-with-a-remarkably-long-executable-name-that-no-column-can-hold.exe")
                        .user("a-user-with-a-rather-long-account-name")
                        .cpu(1.2)
                        .build(),
                process(4124, "Microsoft.Windows.SecHealthUI.Background.Task.Host.Extension.Service.exe")
                        .user("NT VIRTUAL MACHINE\\Virtual Machines")
                        .build(),
                process(4128, "Çok-uzun-bir-işlem-adı-İŞLEM-ığüşöç-denetleyici.exe")
                        .user("Öğrenci Kullanıcısı")
                        .build(),
                process(4132, "short.exe").build(),
                process(4136, "").noUser().build());
    }

    /** The largest values a cell has to hold: all of the machine, a terabyte, a gigabyte a second. */
    public static List<ProcessSnapshot> extreme() {
        return List.of(
                process(4_194_304, "everything-at-once.exe")
                        .cpu(100)
                        .memory(1023 * GIB + 921 * MIB)
                        .privateMemory(1023 * GIB)
                        .virtualSize(131_072 * GIB)
                        .io(1023.9 * GIB, 1023.9 * MIB)
                        .threads(123_456)
                        .priority(-20)
                        .parent(4_194_300)
                        .build(),
                process(999_999, "nearly.exe")
                        .cpu(99.95)
                        .memory(999 * GIB)
                        .io(999.9 * MIB, 0)
                        .threads(9_999)
                        .priority(31)
                        .build(),
                process(8, "idle.exe")
                        .cpu(0)
                        .memory(0)
                        .io(0, 0)
                        .threads(1)
                        .priority(0)
                        .build());
    }

    /** A system that reports nothing but which processes there are. */
    public static List<ProcessSnapshot> nothingAvailable() {
        return List.of(
                process(4, "System").nothingElse().build(),
                process(120, "Registry").nothingElse().build(),
                process(588, "").nothingElse().build());
    }

    /** A system that has a state for a process as a whole, as Linux and macOS have. */
    public static List<ProcessSnapshot> withStates() {
        List<ProcessSnapshot> listing = new ArrayList<>();
        ProcessState[] states = ProcessState.values();
        for (int index = 0; index < states.length; index++) {
            listing.add(process(1000 + index, "state-" + index)
                    .state(states[index])
                    .user(index % 2 == 0 ? "root" : "alice")
                    .priority(index % 2 == 0 ? 20 : 39)
                    .cpu(index)
                    .build());
        }
        return listing;
    }
}
