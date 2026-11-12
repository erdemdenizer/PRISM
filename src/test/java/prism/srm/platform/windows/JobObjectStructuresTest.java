package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jna.Native;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import prism.srm.domain.ProcessIdentity;

/**
 * The Job Object structures, constants and job names of milestone 10 against their documented
 * 64-bit layout (plan §3.4). Pure JNA layout: it runs on any 64-bit system, since the 8-byte
 * members are Java longs and natural alignment is the same everywhere JNA computes it.
 */
class JobObjectStructuresTest {

    static boolean sixtyFourBit() {
        return Native.POINTER_SIZE == 8;
    }

    @Test
    @EnabledIf("sixtyFourBit")
    void theBasicLimitsHaveTheirDocumentedSizeAndOffsets() {
        JobObjectStructures.BasicLimitInformation basic = new JobObjectStructures.BasicLimitInformation();
        assertThat(basic.size()).isEqualTo(64);
        assertThat(basic.offsetOf("PerProcessUserTimeLimit")).isZero();
        assertThat(basic.offsetOf("PerJobUserTimeLimit")).isEqualTo(8);
        assertThat(basic.offsetOf("LimitFlags")).isEqualTo(16);
        assertThat(basic.offsetOf("MinimumWorkingSetSize")).isEqualTo(24);
        assertThat(basic.offsetOf("MaximumWorkingSetSize")).isEqualTo(32);
        assertThat(basic.offsetOf("ActiveProcessLimit")).isEqualTo(40);
        assertThat(basic.offsetOf("Affinity")).isEqualTo(48);
        assertThat(basic.offsetOf("PriorityClass")).isEqualTo(56);
        assertThat(basic.offsetOf("SchedulingClass")).isEqualTo(60);
    }

    @Test
    @EnabledIf("sixtyFourBit")
    void theExtendedLimitsHaveTheirDocumentedSizeAndOffsets() {
        JobObjectStructures.ExtendedLimitInformation extended = new JobObjectStructures.ExtendedLimitInformation();
        assertThat(extended.size()).isEqualTo(144);
        assertThat(extended.IoInfo.size()).isEqualTo(48);
        assertThat(extended.offsetOf("BasicLimitInformation")).isZero();
        assertThat(extended.offsetOf("IoInfo")).isEqualTo(64);
        assertThat(extended.offsetOf("ProcessMemoryLimit")).isEqualTo(112);
        assertThat(extended.offsetOf("JobMemoryLimit")).isEqualTo(120);
        assertThat(extended.offsetOf("PeakProcessMemoryUsed")).isEqualTo(128);
        assertThat(extended.offsetOf("PeakJobMemoryUsed")).isEqualTo(136);
    }

    @Test
    @EnabledIf("sixtyFourBit")
    void theCpuRateAndTheAccountingHaveTheirDocumentedSizeAndOffsets() {
        JobObjectStructures.CpuRateControlInformation rate = new JobObjectStructures.CpuRateControlInformation();
        assertThat(rate.size()).isEqualTo(8);
        assertThat(rate.offsetOf("CpuRate")).isEqualTo(4);
        JobObjectStructures.BasicAccountingInformation accounting =
                new JobObjectStructures.BasicAccountingInformation();
        assertThat(accounting.size()).isEqualTo(48);
        assertThat(accounting.offsetOf("TotalPageFaultCount")).isEqualTo(32);
        assertThat(accounting.offsetOf("ActiveProcesses")).isEqualTo(40);
        assertThat(accounting.offsetOf("TotalTerminatedProcesses")).isEqualTo(44);
    }

    @Test
    void theConstantsAreTheDocumentedValuesAndTheCapIsEnableAndHardCap() {
        assertThat(JobNative.JOB_OBJECT_ASSIGN_PROCESS).isEqualTo(0x1);
        assertThat(JobNative.JOB_OBJECT_SET_ATTRIBUTES).isEqualTo(0x2);
        assertThat(JobNative.JOB_OBJECT_QUERY).isEqualTo(0x4);
        assertThat(JobNative.JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION).isEqualTo(1);
        assertThat(JobNative.JOB_OBJECT_EXTENDED_LIMIT_INFORMATION).isEqualTo(9);
        assertThat(JobNative.JOB_OBJECT_CPU_RATE_CONTROL_INFORMATION).isEqualTo(15);
        assertThat(JobNative.JOB_OBJECT_LIMIT_PROCESS_MEMORY).isEqualTo(0x100);
        assertThat(WindowsJobLimiter.CAP).isEqualTo(0x5);
        assertThat(WindowsJobLimiter.ASSIGN_RIGHTS).isEqualTo(0x0100 | 0x0001);
        // JOB_OBJECT_QUERY only: recovery reads a reopened job and never changes it (audit, C).
        assertThat(WindowsJobLimiter.REOPEN_RIGHTS).isEqualTo(0x4);
    }

    /** ADR-0002: no job is ever set to take its processes with it, and nothing of the limiter ends a process. */
    @Test
    void theJobCodeNeverNamesKillOnCloseOrATermination() throws IOException {
        Path folder = Path.of("src", "main", "java", "prism", "srm", "platform", "windows");
        for (String file :
                List.of("JobNative.java", "JobObjectStructures.java", "JobNames.java", "WindowsJobLimiter.java")) {
            String source = Files.readString(folder.resolve(file));
            String code = source.replaceAll("(?s)/\\*.*?\\*/", "");
            assertThat(code)
                    .as(file)
                    .doesNotContain(
                            "KILL_ON_JOB_CLOSE",
                            "0x2000",
                            "0x00002000",
                            "TerminateJobObject",
                            "TerminateProcess",
                            "JOB_MEMORY");
        }
    }

    @Test
    void aJobNameIsDerivedFromTheIdentityAloneWhateverTheLocale() {
        ProcessIdentity identity = new ProcessIdentity(4242, Instant.ofEpochMilli(1_791_595_233_064L));
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(JobNames.of(identity)).isEqualTo("Local\\Prism.Job.4242.1791595233064");
        } finally {
            Locale.setDefault(before);
        }
        assertThat(JobNames.of(identity).substring("Local\\".length())).doesNotContain("\\");
        assertThat(JobNames.of(identity).length()).isLessThan(260);
        assertThat(JobNames.parse(JobNames.of(identity))).contains(identity);
        assertThat(JobNames.of(new ProcessIdentity(4242, identity.startTime().plusMillis(1))))
                .isNotEqualTo(JobNames.of(identity));
        assertThat(JobNames.parse("Local\\Prism.Job.12.x")).isEmpty();
        assertThat(JobNames.parse("Global\\Prism.Job.12.34")).isEmpty();
        assertThat(JobNames.parse("Local\\Prism.Job.012.34")).isEmpty();
        assertThat(JobNames.parse("Local\\Prism.Job.12.34.5")).isEmpty();
    }
}
