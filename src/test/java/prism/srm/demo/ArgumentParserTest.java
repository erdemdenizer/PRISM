package prism.srm.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArgumentParserTest {

    /** The development machine: 16 logical processors, 32 GiB, so the cap is the absolute 1 GiB. */
    private static final HostFacts LARGE = new HostFacts(16, 32 * Quantities.GIB);

    /** A small machine, where a quarter of the physical memory is the cap. */
    private static final HostFacts SMALL = new HostFacts(4, 2 * Quantities.GIB);

    /** A machine whose physical memory the JVM does not report. */
    private static final HostFacts UNKNOWN = new HostFacts(8, 0);

    @Test
    void withoutArgumentsTheWorkloadIsIdleForFiveMinutes() throws UsageException {
        DemoArguments arguments = parse(LARGE);
        assertThat(arguments.cpuPercent()).isZero();
        assertThat(arguments.cpuPlan()).isSameAs(CpuPlan.NONE);
        assertThat(arguments.memoryBytes()).isZero();
        assertThat(arguments.duration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(arguments.mode()).isEqualTo("idle");
    }

    @Test
    void readsEveryOption() throws UsageException {
        DemoArguments arguments = parse(LARGE, "--cpu", "25", "--memory", "256MiB", "--duration", "300s");
        assertThat(arguments.cpuPercent()).isEqualTo(25.0);
        assertThat(arguments.cpuPlan()).isEqualTo(new CpuPlan(4, 1.0));
        assertThat(arguments.memoryBytes()).isEqualTo(256 * Quantities.MIB);
        assertThat(arguments.duration()).isEqualTo(Duration.ofSeconds(300));
        assertThat(arguments.mode()).isEqualTo("cpu+memory");

        assertThat(parse(LARGE, "--cpu", "6.25", "--duration", "5m").mode()).isEqualTo("cpu");
        assertThat(parse(LARGE, "--memory", "1GiB").mode()).isEqualTo("memory");
        assertThat(parse(LARGE, "--cpu", "25", "--workers", "8").cpuPlan()).isEqualTo(new CpuPlan(8, 0.5));
    }

    /** Help is answered as soon as it is read; what came before it must still be well-formed. */
    @Test
    void helpIsAnsweredWhereItStands() throws UsageException {
        assertThat(ArgumentParser.parse(new String[] {"--help"}, LARGE)).isEmpty();
        assertThat(ArgumentParser.parse(new String[] {"-h"}, LARGE)).isEmpty();
        assertThat(ArgumentParser.parse(new String[] {"--cpu", "25", "--help"}, LARGE))
                .isEmpty();
        assertThat(ArgumentParser.parse(new String[] {"--help", "--foo"}, LARGE))
                .isEmpty();
        assertUsageError(LARGE, "Unknown option: --foo.", "--foo", "--help");
        assertUsageError(LARGE, "Invalid --cpu value '--help'", "--cpu", "--help");
    }

    @Test
    void theHelpTextNamesTheBoundsOfThisMachine() {
        assertThat(ArgumentParser.help(LARGE))
                .startsWith("Usage: prism-demo")
                .contains("--cpu <percent>", "--memory <size>", "--duration <time>", "--workers <n>")
                .contains("Exit codes: 0 ran for its duration, 1 memory allocation failed, 2 invalid arguments.")
                .contains("This machine: 16 logical processors, 32.00GiB physical memory, memory cap 1.00GiB.");
        assertThat(ArgumentParser.help(SMALL))
                .contains("4 logical processors, 2.00GiB physical memory, memory cap 512MiB.");
        assertThat(ArgumentParser.help(UNKNOWN))
                .contains(
                        "8 logical processors, unknown physical memory, memory cap unknown (no memory can be requested).");
    }

    @Test
    void anOptionWithoutAValueIsAnError() {
        assertUsageError(LARGE, "Option --cpu needs a value.", "--cpu");
        assertUsageError(LARGE, "Option --memory needs a value.", "--cpu", "25", "--memory");
    }

    @Test
    void anOptionGivenTwiceIsAnError() {
        assertUsageError(LARGE, "Option --cpu was given twice.", "--cpu", "1", "--cpu", "2");
    }

    @Test
    void anythingThatIsNotAnOptionIsAnError() {
        assertUsageError(LARGE, "Unknown option: --foo.", "--foo", "1");
        assertUsageError(LARGE, "Unknown option: 25.", "25");
        assertUsageError(LARGE, "Unknown option: --cpu=25.", "--cpu=25");
    }

    @Test
    void aBadCpuValueIsExplained() {
        assertUsageError(
                LARGE,
                "Invalid --cpu value 'abc': expected a number from 0 to 100, for example 25 or 6.25.",
                "--cpu",
                "abc");
        assertUsageError(LARGE, "--cpu must be from 0 to 100.", "--cpu", "101");
        assertUsageError(LARGE, "--cpu must be from 0 to 100.", "--cpu", "100.5");
    }

    @Test
    void aBadMemoryValueIsExplained() {
        assertUsageError(
                LARGE,
                "Invalid --memory value '256': expected <n>MiB or <n>GiB, for example 256MiB.",
                "--memory",
                "256");
        assertUsageError(LARGE, "Invalid --memory value '1MB': unknown unit MB: use MiB or GiB.", "--memory", "1MB");
        assertUsageError(
                LARGE,
                "Invalid --memory value '999999999999999GiB': too large to be a memory size.",
                "--memory",
                "999999999999999GiB");
    }

    /** DEMO-2: a request above the cap is refused, not clamped, and the message says where the cap comes from. */
    @Test
    void memoryAboveTheCapIsRefusedWithTheReason() throws UsageException {
        assertUsageError(
                LARGE,
                "--memory 2GiB is above the safety cap of 1.00GiB on this machine: the smaller of 1 GiB and a quarter"
                        + " of 32.00GiB physical memory.",
                "--memory",
                "2GiB");
        assertUsageError(
                SMALL,
                "--memory 600MiB is above the safety cap of 512MiB on this machine: the smaller of 1 GiB and a quarter"
                        + " of 2.00GiB physical memory.",
                "--memory",
                "600MiB");
        assertThat(parse(LARGE, "--memory", "1GiB").memoryBytes()).isEqualTo(Quantities.GIB);
        assertThat(parse(SMALL, "--memory", "512MiB").memoryBytes()).isEqualTo(512 * Quantities.MIB);
        assertUsageError(LARGE, "--memory 1025MiB is above the safety cap of 1.00GiB", "--memory", "1025MiB");
    }

    @Test
    void withoutAKnownPhysicalMemorySizeNoMemoryCanBeRequested() throws UsageException {
        assertUsageError(
                UNKNOWN,
                "Option --memory cannot be used: the physical memory size of this machine is unknown, so the safety"
                        + " cap (the smaller of 1 GiB and a quarter of physical memory) cannot be computed.",
                "--memory",
                "1MiB");
        assertThat(parse(UNKNOWN, "--memory", "0").memoryBytes()).isZero();
        assertThat(parse(UNKNOWN, "--cpu", "50").cpuPlan()).isEqualTo(new CpuPlan(4, 1.0));
    }

    @Test
    void theDurationIsBoundedOnBothSides() throws UsageException {
        assertThat(parse(LARGE, "--duration", "1s").duration()).isEqualTo(Duration.ofSeconds(1));
        assertThat(parse(LARGE, "--duration", "30m").duration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(parse(LARGE, "--duration", "1800s").duration()).isEqualTo(Duration.ofMinutes(30));
        assertUsageError(
                LARGE,
                "--duration must be at least 1s; 0 does not mean unlimited, there is no unlimited mode.",
                "--duration",
                "0s");
        assertUsageError(LARGE, "--duration 31m is above the maximum of 30m.", "--duration", "31m");
        assertUsageError(LARGE, "--duration 1801s is above the maximum of 30m.", "--duration", "1801s");
        assertUsageError(
                LARGE,
                "Invalid --duration value '5': expected <n>s or <n>m, for example 300s or 5m.",
                "--duration",
                "5");
        assertUsageError(LARGE, "Invalid --duration value '1h'", "--duration", "1h");
    }

    @Test
    void workersAreCheckedAgainstTheShareAndTheMachine() {
        assertUsageError(
                LARGE,
                "--cpu 25.0 on 16 logical processors needs at least 4 workers; --workers 2 cannot reach it.",
                "--cpu",
                "25",
                "--workers",
                "2");
        assertUsageError(LARGE, "--workers needs --cpu above 0.", "--workers", "1");
        assertUsageError(
                LARGE,
                "--workers 17: expected 1 to 16, the logical processors of this machine.",
                "--cpu",
                "1",
                "--workers",
                "17");
        assertUsageError(
                LARGE, "Invalid --workers value '-1': expected a whole number.", "--cpu", "1", "--workers", "-1");
    }

    private static DemoArguments parse(HostFacts host, String... args) throws UsageException {
        Optional<DemoArguments> parsed = ArgumentParser.parse(args, host);
        assertThat(parsed).isPresent();
        return parsed.get();
    }

    private static void assertUsageError(HostFacts host, String message, String... args) {
        assertThatExceptionOfType(UsageException.class)
                .isThrownBy(() -> ArgumentParser.parse(args, host))
                .withMessageContaining(message);
    }
}
