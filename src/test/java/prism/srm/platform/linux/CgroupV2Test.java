package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The cgroup v2 and procfs parsers (S2) on the texts the CI-0 probe read from GitHub's runner, and on malformed ones:
 * a text that is not exactly what the kernel writes answers empty, never a guess.
 */
class CgroupV2Test {

    /** /proc/self/mountinfo, the cgroup line as CI-0 read it (run 38533752820). */
    private static final String RUNNER_MOUNTINFO =
            "25 30 0:23 / /proc rw,nosuid,nodev,noexec,relatime shared:12 - proc proc rw\n"
                    + "36 30 0:30 / /sys/fs/cgroup rw,nosuid,nodev,noexec,relatime shared:8 - cgroup2 cgroup2 rw,nsdelegate,"
                    + "memory_recursiveprot\n";

    private static final String HYBRID_MOUNTINFO =
            "33 24 0:28 / /sys/fs/cgroup ro,nosuid,nodev,noexec shared:9 - tmpfs tmpfs ro,mode=755\n"
                    + "34 33 0:29 / /sys/fs/cgroup/unified rw,nosuid,nodev,noexec,relatime shared:10 - cgroup2 cgroup2 rw\n"
                    + "35 33 0:30 / /sys/fs/cgroup/memory rw,nosuid,nodev,noexec,relatime shared:11 - cgroup cgroup rw,memory\n";

    @Test
    void theUnifiedMountIsReadFromMountinfo() {
        assertThat(CgroupV2.unifiedMount(RUNNER_MOUNTINFO)).contains("/sys/fs/cgroup");
        assertThat(CgroupV2.v1Mounted(RUNNER_MOUNTINFO)).isFalse();
        assertThat(CgroupV2.unifiedMount(HYBRID_MOUNTINFO)).contains("/sys/fs/cgroup/unified");
        assertThat(CgroupV2.v1Mounted(HYBRID_MOUNTINFO)).isTrue();
        assertThat(CgroupV2.unifiedMount("25 30 0:23 / /proc rw - proc proc rw\n"))
                .isEmpty();
        assertThat(CgroupV2.unifiedMount("")).isEmpty();
        assertThat(CgroupV2.unifiedMount(RUNNER_MOUNTINFO
                        + RUNNER_MOUNTINFO.lines().skip(1).findFirst().orElseThrow()))
                .as("two cgroup2 mounts are not one answer")
                .isEmpty();
    }

    @Test
    void mountPointsAreUnescapedAndBadEscapesRefused() {
        assertThat(CgroupV2.unifiedMount("36 30 0:30 / /mnt/a\\040b rw - cgroup2 cgroup2 rw\n"))
                .contains("/mnt/a b");
        assertThat(CgroupV2.unifiedMount("36 30 0:30 / /mnt/a\\04 rw - cgroup2 cgroup2 rw\n"))
                .isEmpty();
        assertThat(CgroupV2.unifiedMount("36 30 0:30 / /mnt/a\\089 rw - cgroup2 cgroup2 rw\n"))
                .isEmpty();
        assertThat(CgroupV2.unifiedMount("36 30 0:30 / rw - cgroup2 cgroup2 rw\n"))
                .as("too few fields before the separator")
                .isEmpty();
    }

    @Test
    void theUnifiedPathOfAProcessIsTheOneZeroLine() {
        assertThat(CgroupV2.unifiedPath("0::/system.slice/hosted-compute-agent.service\n"))
                .contains("/system.slice/hosted-compute-agent.service");
        assertThat(CgroupV2.unifiedPath("12:memory:/user.slice\n0::/user.slice/user-1001.slice/session-2.scope\n"))
                .contains("/user.slice/user-1001.slice/session-2.scope");
        assertThat(CgroupV2.unifiedPath("0::/\n")).contains("/");
        for (String bad : List.of(
                "",
                "12:memory:/user.slice\n",
                "0::/a\n0::/b\n",
                "0::relative\n",
                "0::/a/../b\n",
                "0::/a b\n",
                "0::\n",
                "0::/a/\n")) {
            assertThat(CgroupV2.unifiedPath(bad)).as(bad.replace("\n", "\\n")).isEmpty();
        }
    }

    @Test
    void controllerListsAreNamesOnce() {
        assertThat(CgroupV2.controllers("cpu memory pids\n")).contains(Set.of("cpu", "memory", "pids"));
        assertThat(CgroupV2.controllers("cpuset cpu io memory hugetlb pids rdma misc dmem\n"))
                .hasValueSatisfying(set -> assertThat(set).hasSize(9));
        assertThat(CgroupV2.controllers("\n")).contains(Set.of());
        for (String bad : List.of("cpu cpu", "CPU", "cpu,memory", "cpu memory-x", "cpu \u0000")) {
            assertThat(CgroupV2.controllers(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void cpuMaxIsAQuotaOrMaxOverAPeriodTheKernelAccepts() {
        assertThat(CgroupV2.cpuMax("max 100000\n")).hasValueSatisfying(max -> {
            assertThat(max.unlimited()).isTrue();
            assertThat(max.text()).isEqualTo("max 100000");
        });
        assertThat(CgroupV2.cpuMax("20000 100000\n")).hasValueSatisfying(max -> {
            assertThat(max.quotaUsec()).isEqualTo(OptionalLong.of(20_000));
            assertThat(max.periodUsec()).isEqualTo(100_000);
            assertThat(max.text()).isEqualTo("20000 100000");
        });
        assertThat(CgroupV2.cpuMax("1000 1000")).isPresent();
        assertThat(CgroupV2.cpuMax("1000 1000000")).isPresent();
        for (String bad : List.of(
                "",
                "max",
                "0 100000",
                "-1 100000",
                "020000 100000",
                "20000 999",
                "20000 1000001",
                "20000  100000",
                "20000 100000 1",
                "abc 100000",
                "99999999999999999999 100000",
                "max max",
                "20000 0")) {
            assertThat(CgroupV2.cpuMax(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void memoryLimitsAreBytesOrMax() {
        assertThat(CgroupV2.memoryLimit("max\n")).contains(OptionalLong.empty());
        assertThat(CgroupV2.memoryLimit("536870912\n")).contains(OptionalLong.of(536_870_912));
        assertThat(CgroupV2.memoryLimit("0\n")).contains(OptionalLong.of(0));
        for (String bad : List.of("", "-1", "1e9", "512M", "0512", "99999999999999999999")) {
            assertThat(CgroupV2.memoryLimit(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void cgroupProcsArePidsOnce() {
        assertThat(CgroupV2.procs("2071\n2076\n")).contains(List.of(2071, 2076));
        assertThat(CgroupV2.procs("")).contains(List.of());
        for (String bad : List.of("2071\n2071\n", "0\n", "-5\n", "4194305\n", "abc\n", "2071 2076\n", "02071\n")) {
            assertThat(CgroupV2.procs(bad)).as(bad.replace("\n", "\\n")).isEmpty();
        }
        assertThat(CgroupV2.procs("4194304\n")).contains(List.of(4_194_304));
    }
}
