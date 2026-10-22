package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import prism.srm.domain.ProcessIdentity;
import prism.srm.platform.windows.WindowsProcessVirtualSizes.ProcessCounter;

/**
 * Covers how the counters of the process instances become virtual sizes by identity, which needs
 * no Windows call. The read itself is checked on Windows with {@code HostProcessProbe}.
 */
class WindowsProcessVirtualSizesTest {

    /**
     * The creation time of the Registry process on the development machine as the counter held
     * it, and the start time the process listing gave for the same process.
     */
    private static final long CREATED_FILETIME = 134_353_157_065_738_911L;

    private static final long CREATED_MILLIS = 1_790_842_106_573L;

    private final List<Map<ProcessCounter, Object>> instances = new ArrayList<>();

    @Test
    void anInstanceBecomesTheVirtualSizeOfTheProcessWithThatPidAndCreationTime() {
        instances.add(instance("Registry", 280, CREATED_FILETIME, 176_472_064L));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances))
                .containsExactly(Map.entry(identity(280, CREATED_MILLIS), 176_472_064L));
    }

    @Test
    void theCreationTimeIsCutToMillisecondsAsTheProcessListingCutsIt() {
        // 0.8911 ms past the millisecond: rounding would give the next millisecond, and the
        // identity would then never equal the one in the process listing.
        instances.add(instance("a", 100, CREATED_FILETIME, 1L));
        instances.add(instance("b", 200, CREATED_FILETIME + 1_088, 2L));
        instances.add(instance("c", 300, CREATED_FILETIME + 1_089, 3L));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances))
                .containsOnlyKeys(
                        identity(100, CREATED_MILLIS),
                        identity(200, CREATED_MILLIS),
                        identity(300, CREATED_MILLIS + 1));
    }

    @Test
    void twoProcessesThatHeldTheSamePidAreTwoEntries() {
        instances.add(instance("old", 100, CREATED_FILETIME, 1_000L));
        instances.add(instance("new", 100, CREATED_FILETIME + 50_000_000, 2_000L));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances))
                .containsOnly(
                        Map.entry(identity(100, CREATED_MILLIS), 1_000L),
                        Map.entry(identity(100, CREATED_MILLIS + 5_000), 2_000L));
    }

    @Test
    void theTotalInstanceIsNotAProcessAndDoesNotTakeTheIdleProcessWithIt() {
        // Windows gives the sums over all processes as an instance that carries PID 0, which is
        // also the PID of the idle process. Only the name tells the two apart.
        instances.add(instance("_Total", 0, CREATED_FILETIME, 778_881_804_881_920L));
        instances.add(instance("Idle", 0, CREATED_FILETIME, 8_192L));
        instances.add(instance("System", 4, CREATED_FILETIME, 50_393_088L));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances))
                .containsOnly(
                        Map.entry(identity(0, CREATED_MILLIS), 8_192L),
                        Map.entry(identity(4, CREATED_MILLIS), 50_393_088L));
    }

    @Test
    void shouldTheSystemListOneIdentityTwiceTheFirstInstanceIsTheOneKept() {
        instances.add(instance("first", 100, CREATED_FILETIME, 1_000L));
        instances.add(instance("second", 100, CREATED_FILETIME + 1, 2_000L));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances))
                .containsExactly(Map.entry(identity(100, CREATED_MILLIS), 1_000L));
    }

    @Test
    void aSizeFarBeyondFourGibibytesIsKeptToTheByte() {
        instances.add(instance("big", 100, CREATED_FILETIME, 3_971_686_010_880L));
        instances.add(instance("biggest", 200, CREATED_FILETIME, Long.MAX_VALUE));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances))
                .containsOnly(
                        Map.entry(identity(100, CREATED_MILLIS), 3_971_686_010_880L),
                        Map.entry(identity(200, CREATED_MILLIS), Long.MAX_VALUE));
    }

    @Test
    void anUnsignedSizeThatDoesNotFitALongIsLeftOutInsteadOfTurningNegative() {
        // 2^63 as an unsigned 64-bit counter is read as Long.MIN_VALUE.
        instances.add(instance("impossible", 100, CREATED_FILETIME, Long.MIN_VALUE));
        instances.add(instance("all bits set", 200, CREATED_FILETIME, -1L));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances)).isEmpty();
    }

    @Test
    void aSizeOfZeroIsAValue() {
        instances.add(instance("empty", 100, CREATED_FILETIME, 0L));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances))
                .containsExactly(Map.entry(identity(100, CREATED_MILLIS), 0L));
    }

    @Test
    void thePidIsReadAsAnUnsignedNumber() {
        instances.add(instance("high", 0xFFFF_FFFC, CREATED_FILETIME, 1L));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances))
                .containsOnlyKeys(identity(4_294_967_292L, CREATED_MILLIS));
    }

    @Test
    void anInstanceWithoutACreationTimeIsLeftOutBecauseAPidAloneIsNotAnIdentity() {
        instances.add(instance("no time", 100, 0L, 1_000L));
        // The first FILETIME that is not after January 1, 1970.
        instances.add(instance("epoch", 200, 116_444_736_000_000_000L, 1_000L));
        instances.add(instance("fine", 300, 116_444_736_000_010_000L, 1_000L));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances)).containsOnlyKeys(identity(300, 1));
    }

    @Test
    void aCreationTimeThatIsNotAFiletimeIsLeftOutAndNotReadAsMilliseconds() {
        // The same instant written as milliseconds since 1970 instead of as a FILETIME.
        instances.add(instance("not a filetime", 100, CREATED_MILLIS, 1_000L));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances)).isEmpty();
    }

    @Test
    void anInstanceWithAMissingCounterIsLeftOutAndDoesNotFailTheOthers() {
        Map<ProcessCounter, Object> noPid = instance("no pid", 100, CREATED_FILETIME, 1_000L);
        noPid.remove(ProcessCounter.ID_PROCESS);
        Map<ProcessCounter, Object> noTime = instance("no time", 200, CREATED_FILETIME, 1_000L);
        noTime.remove(ProcessCounter.ELAPSED_TIME);
        Map<ProcessCounter, Object> noSize = instance("no size", 300, CREATED_FILETIME, 1_000L);
        noSize.remove(ProcessCounter.VIRTUAL_BYTES);
        // The reader gives a counter of four bytes as an Integer: not the eight-byte size.
        Map<ProcessCounter, Object> narrowSize = instance("narrow", 400, CREATED_FILETIME, 1_000L);
        narrowSize.put(ProcessCounter.VIRTUAL_BYTES, 1_000);
        instances.add(noPid);
        instances.add(noTime);
        instances.add(noSize);
        instances.add(narrowSize);
        instances.add(instance("fine", 500, CREATED_FILETIME, 5_000L));

        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances))
                .containsExactly(Map.entry(identity(500, CREATED_MILLIS), 5_000L));
    }

    @Test
    void noInstancesGiveNoSizes() {
        assertThat(WindowsProcessVirtualSizes.virtualSizes(instances)).isEmpty();
    }

    @Test
    void theInstanceNameIsTheFirstConstantBecauseOshisReaderPutsTheNameThere() {
        // OSHI's reader fills the first constant with the instance name and looks up all others
        // as counters. That the counter names are right is checked on Windows with the probe.
        assertThat(ProcessCounter.values()[0]).isEqualTo(ProcessCounter.NAME);
    }

    private static Map<ProcessCounter, Object> instance(String name, int pid, long createdFiletime, long virtualBytes) {
        Map<ProcessCounter, Object> instance = new EnumMap<>(ProcessCounter.class);
        instance.put(ProcessCounter.NAME, name);
        instance.put(ProcessCounter.ID_PROCESS, pid);
        instance.put(ProcessCounter.ELAPSED_TIME, createdFiletime);
        instance.put(ProcessCounter.VIRTUAL_BYTES, virtualBytes);
        return instance;
    }

    private static ProcessIdentity identity(long pid, long startMillis) {
        return new ProcessIdentity(pid, Instant.ofEpochMilli(startMillis));
    }
}
