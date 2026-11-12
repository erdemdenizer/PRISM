package prism.srm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What ADR-0017 binds and a build can see without packaging: the modules of the image, the launcher key,
 * and the rules around it. What the image really does is {@code verify-image.ps1}.
 */
class PackagingConfigTest {

    private static final Path WINDOWS = Path.of("packaging", "windows");

    private static List<String> modules() throws IOException {
        return Files.readAllLines(WINDOWS.resolve("modules.txt")).stream()
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static String text(String name) throws IOException {
        return Files.readString(WINDOWS.resolve(name));
    }

    /** The list itself, so that a name elsewhere in the script does not count as a mandatory check. */
    private static String mandatoryList() throws IOException {
        String verifier = text("verify-image.ps1");
        int start = verifier.indexOf("$mandatory = @(");
        assertThat(start).as("the verifier has a list of mandatory checks").isNotNegative();
        return verifier.substring(start, verifier.indexOf("\n)\n", start));
    }

    /** Nothing fails without jdk.localedata: Turkish numbers silently format as 1,234,567. */
    @Test
    void theImageKeepsTheLocaleDataAndWhatTheApplicationNeeds() throws IOException {
        assertThat(modules())
                .contains(
                        "jdk.localedata",
                        "java.base",
                        "java.desktop",
                        "java.logging",
                        "java.management",
                        "java.xml",
                        "jdk.management",
                        "jdk.unsupported")
                .doesNotHaveDuplicates();
    }

    @Test
    void everyModuleOfTheImageExistsInThisJdk() throws IOException {
        assertThat(modules())
                .allSatisfy(module -> assertThat(ModuleFinder.ofSystem().find(module))
                        .as(module)
                        .isPresent());
    }

    @Test
    void bothLaunchersAreToldNotToRestartAndTheToolChecksThatTheKeyStillExists() throws IOException {
        String tool = text("PackageImage.java");

        assertThat(tool)
                .contains("NO_RESTART = \"win.norestart=true\"")
                .contains("addNoRestart(image.resolve(\"app\").resolve(\"PRISM.cfg\"));")
                .contains("addNoRestart(image.resolve(\"app\").resolve(\"prism-demo.cfg\"));")
                .contains("check(image, modules);")
                .contains("does not name");
    }

    @Test
    void anotherJdkIsRefusedUntilItHasBeenVerified() throws IOException {
        assertThat(text("verified-jdk.txt"))
                .containsPattern("(?m)^java\\.version=\\d")
                .contains("java.vendor=");
        assertThat(text("PackageImage.java"))
                .contains("String jdk = checkJdk(anyJdk);")
                .contains("if (!same && !anyJdk)")
                .contains("JDK_NOT_VERIFIED.txt");
    }

    /** K11: the conditions under which the undocumented key is accepted stay mandatory checks. */
    @Test
    void theMandatoryChecksOfTheOwnerStayInTheVerifier() throws IOException {
        assertThat(mandatoryList())
                .contains(
                        // One real process.
                        "'single-process/no-child-prism'",
                        "'single-process/one-prism-process'",
                        "'single-process/jvm-from-the-image'",
                        "'demo-native/demo-is-one-process'",
                        // Its own process: the window offers nothing (CAP-2), the service refuses (SAFE-2).
                        "'single-process/own-process-alive-and-running'",
                        "'single-process/ui-own-row-actions-disabled'",
                        "'single-process/ui-own-row-says-why'",
                        "'single-process/ui-own-row-menu-disabled'",
                        "'probe/safe2-CHANGE_PRIORITY-refused'",
                        "'probe/safe2-SUSPEND-refused'",
                        "'probe/safe2-END_TASK-refused'",
                        "'probe/safe2-FORCE_KILL-refused'",
                        "'probe/safe2-MANAGE-refused'",
                        // Native limits, measured.
                        "'demo-native/cpu-applied-measured'",
                        "'demo-native/cpu-removed-measured'",
                        "'demo-native/memory-reported-native'",
                        "'demo-native/close-clears-native-cpu'",
                        "'memory-enforced/allocation-refused-at-the-limit'",
                        "'memory-enforced/committed-within-the-limit'",
                        "'memory-enforced/allocation-goes-on-after-removal'",
                        "'memory-clears-at-close/allocation-goes-on-after-close'",
                        // Second launch and closing.
                        "'single-process/second-launch-shows-dialog'",
                        "'single-process/second-launch-exits'",
                        "'single-process/second-launch-one-main-window'",
                        "'closing/never-two-running'",
                        // Shutdown and recovery.
                        "'emulated-close/held-at-close'",
                        "'emulated-close/no-thread-left-suspended'",
                        "'user-suspension/stays-suspended-after-close'",
                        "'user-suspension/next-start-resumes-nothing'",
                        "'hard-kill-native/limit-stays-after-kill'",
                        "'hard-kill-native/next-start-changes-nothing'",
                        "'emulated-hard-kill/killed-while-holding'",
                        "'emulated-hard-kill/next-start-resumes-nothing'",
                        "'ctrl-c/signal-while-held'",
                        "'ctrl-c/hook-released-the-limiter'",
                        "'ctrl-c/hook-left-the-users'",
                        // LIFE-5 when the console closes (milestone 13).
                        "'console-close/close-while-held'",
                        "'console-close/console-close-ends-prism'",
                        "'console-close/hook-released-the-limiter'",
                        "'console-close/hook-left-the-users'",
                        // Ctrl+C to the demo while PRISM limits it (milestone 13).
                        "'demo-ctrl-c/limited-before'",
                        "'demo-ctrl-c/ctrl-c-ends-demo'",
                        "'demo-ctrl-c/prism-goes-on'",
                        "'demo-ctrl-c/prism-noticed'",
                        "'demo-ctrl-c/close-ends'",
                        // Process control on the demo, from the window (milestone 13).
                        "'process-control/priority-changed'",
                        "'process-control/suspended'",
                        "'process-control/resumed'",
                        "'process-control/runs-after-resume'",
                        "'process-control/end-task-honest'",
                        "'process-control/force-kill-enter-declines'",
                        "'process-control/force-kill-escape-declines'",
                        "'process-control/force-kill-ends-demo'",
                        "'process-control/force-kill-logged'",
                        "'process-control/close-ends'",
                        // OOM Guard acting on the demo (milestone 13).
                        "'oom-guard/suspended-at-emergency'",
                        "'oom-guard/action-logged'",
                        "'oom-guard/close-ends'",
                        "'oom-guard/close-releases-the-hold'",
                        "'oom-guard/load-is-back'",
                        // OOM Guard holding the demo when PRISM is killed (milestone 13).
                        "'oom-hard-kill/killed-while-holding'",
                        "'oom-hard-kill/next-start-resumes-nothing'",
                        "'oom-hard-kill/next-start-lists-it'",
                        "'oom-hard-kill/resume-offered'",
                        "'oom-hard-kill/resumed-after-check'",
                        // The keyboard (WP5).
                        "'keyboard/routes-come-back'",
                        "'keyboard/every-stop-named'",
                        "'keyboard/focus-visible'",
                        "'keyboard/context-menu-by-keyboard'",
                        "'keyboard/arrows-move-in-menu'",
                        "'keyboard/escape-closes-menu'",
                        // Modules and locale.
                        "'probe/localedata-module'",
                        "'probe/turkish-number'");
    }

    /**
     * A check that was not carried out refuses the image like one that failed, and a host alone accepts
     * nothing: both ran on the machine with a JDK beside them.
     */
    @Test
    void theVerdictRefusesWhatWasNotProvedAndOnlyACleanRunAccepts() throws IOException {
        assertThat(text("verify-image.ps1"))
                .contains("@('FAIL', 'BLOCKED', 'NOT TESTED') -contains $script:Outcomes[$_]")
                .contains("$proved = $missing.Count -eq 0 -and $unproved.Count -eq 0")
                .contains("elseif (-not $proved) { 'IMAGE NOT ACCEPTED' }")
                .contains("elseif ($Environment -eq 'clean') { 'IMAGE ACCEPTED' }")
                .contains("$mandatory += 'environment/no-jdk'")
                .contains("if ($proved -and -not $Scenarios.Count) { exit 0 } else { exit 1 }");
    }

    /** Windows PowerShell reads a file without a BOM in the ANSI code page. */
    @Test
    void theVerifierIsPlainAscii() throws IOException {
        assertThat(text("verify-image.ps1").chars()).allMatch(character -> character < 128);
    }
}
