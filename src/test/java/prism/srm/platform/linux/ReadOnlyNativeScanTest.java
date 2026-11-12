package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What the Linux native code may say to systemd (plan Rev 2.2 T-D20, T-D24, T-D25, F14, F17): no production source
 * names a method that stops, kills, abandons, restarts or attaches to a unit, the {@code PIDs} property, {@code
 * cgroup.kill} or a signal subscription; the two state-changing methods milestone 12 needs ({@code StartTransientUnit}
 * by {@code PIDFDs}, and {@code Unit.SetProperties} on the invocation-id path; {@code SetUnitProperties}, which
 * addresses a unit by name, is forbidden) appear only in {@link SystemdScopeManager}; the read-only classes
 * stay read-only; and the product reaches the Linux limiter only through {@link LinuxScopeLimiter#product}, which admits
 * no process (K16).
 */
class ReadOnlyNativeScanTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path LINUX = MAIN.resolve(Path.of("prism", "srm", "platform", "linux"));
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Set<String> FORBIDDEN = Set.of(
            "AttachProcessesToUnit",
            "StopUnit",
            "KillUnit",
            "AbandonScope",
            "Subscribe",
            "ResetFailedUnit",
            "RestartUnit",
            "PIDs",
            "cgroup.kill",
            "memory.oom.group",
            "sd_bus_add_match",
            // Limits go to a unit by its invocation id (Unit.SetProperties), never by name.
            "SetUnitProperties");

    /** The state-changing names milestone 12 needs, and the only files that may hold them. */
    private static final Map<String, Set<String>> CONFINED = Map.of(
            "StartTransientUnit", Set.of("SystemdScopeManager.java"),
            "SetProperties", Set.of("SystemdScopeManager.java"),
            "PIDFDs", Set.of("SystemdScopeManager.java"),
            "sd_bus_message_open_container", Set.of("SdBusApi.java"),
            "sd_bus_message_close_container", Set.of("SdBusApi.java"));

    private static final Set<String> NATIVE_CLASSES = Set.of(
            "SystemdCapabilityProbe",
            "SystemdUserBus",
            "LibsystemdCheck",
            "JnaSdBusApi",
            "SdBusApi",
            "SystemLibraries",
            "JnaSystemLibraries",
            "ScopeManager",
            "SystemdScopeManager",
            "NativeConsent",
            "LinuxScopeLimiter");

    private static List<Path> sources(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> literals(Path source) throws IOException {
        List<String> found = new ArrayList<>();
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            String code = line.strip();
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                continue;
            }
            Matcher m = STRING.matcher(line);
            while (m.find()) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    @Test
    void noLinuxSourceNamesAForbiddenMethodAndStateChangesStayConfined() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path source : sources(LINUX)) {
            String file = source.getFileName().toString();
            for (String literal : literals(source)) {
                // A D-Bus member or property is passed as exactly its name; prose that mentions one is not a call.
                String name = literal.strip();
                if (FORBIDDEN.contains(name)) {
                    hits.add(file + ": \"" + literal + "\"");
                }
                Set<String> allowed = CONFINED.get(name);
                if (allowed != null && !allowed.contains(file)) {
                    hits.add(file + " (only " + allowed + "): \"" + literal + "\"");
                }
            }
        }
        assertThat(hits).isEmpty();
        assertThat(literals(LINUX.resolve("SystemdScopeManager.java")))
                .contains("StartTransientUnit", "SetProperties", "PIDFDs")
                .doesNotContain("PIDs");
    }

    @Test
    void theScanFindsAForbiddenName() {
        Matcher m = STRING.matcher("call(\"StartTransientUnit\", \"x\\\"y\")");
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group(1));
        }
        assertThat(found).containsExactly("StartTransientUnit", "x\\\"y");
    }

    @Test
    void theReadOnlyClassesStayReadOnly() throws IOException {
        for (String file : List.of("SystemdUserBus.java", "SystemdCapabilityProbe.java", "LibsystemdCheck.java")) {
            assertThat(literals(LINUX.resolve(file)))
                    .as(file)
                    .doesNotContain("StartTransientUnit", "SetProperties", "SetUnitProperties", "PIDFDs");
        }
    }

    @Test
    void theProductReachesTheLinuxLimiterOnlyThroughItsDisabledProductForm() throws IOException {
        List<String> users = new ArrayList<>();
        for (Path source : sources(MAIN)) {
            String name = source.getFileName().toString().replace(".java", "");
            if (NATIVE_CLASSES.contains(name)) {
                continue;
            }
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String code = line.strip();
                if (code.startsWith("import ") || code.startsWith("*") || code.startsWith("//")) {
                    continue;
                }
                for (String type : NATIVE_CLASSES) {
                    Matcher use =
                            Pattern.compile("\\b" + type + "\\b(\\.\\w+)?").matcher(line);
                    while (use.find()) {
                        if (!use.group().equals("LinuxScopeLimiter.product")) {
                            users.add(name + " uses " + use.group());
                        }
                    }
                }
            }
        }
        assertThat(users)
                .as("only LinuxScopeLimiter.product, which admits no process (K16)")
                .isEmpty();
    }

    /**
     * K16 inside the native classes themselves: in the main sources the limiter is constructed once, in
     * {@code product}, and the admitting pieces (a harness policy, harness consent, a scope manager) are never made, so
     * no second factory or hidden switch can compose an admitting limiter.
     */
    @Test
    void mainCodeNeverComposesAnAdmittingLimiter() throws IOException {
        List<String> hits = new ArrayList<>();
        int constructions = 0;
        for (Path source : sources(MAIN)) {
            String file = source.getFileName().toString();
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String code = line.strip();
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                    continue;
                }
                // Any use of the admitting factories other than their own declarations, qualified or not.
                boolean declaration = code.startsWith("static EligibilityPolicy harnessOwned(")
                        || code.startsWith("static NativeConsent harness(");
                for (String admitting : List.of(
                        "harnessOwned(",
                        "::harnessOwned",
                        " harness(",
                        ".harness(",
                        "::harness",
                        "SystemdScopeManager(",
                        "SystemdScopeManager::new",
                        "LinuxScopeLimiter::new")) {
                    if (line.contains(admitting) && !declaration && !code.startsWith("SystemdScopeManager(")) {
                        hits.add(file + ": " + code);
                    }
                }
                if (Pattern.compile("new\\s+(\\w+\\.)*LinuxScopeLimiter\\s*\\(")
                        .matcher(line)
                        .find()) {
                    constructions++;
                    if (!file.equals("LinuxScopeLimiter.java")) {
                        hits.add(file + ": " + code);
                    }
                }
            }
        }
        assertThat(hits).isEmpty();
        assertThat(constructions).as("one construction, in product()").isEqualTo(1);
        String limiter = Files.readString(LINUX.resolve("LinuxScopeLimiter.java"), StandardCharsets.UTF_8);
        String product = limiter.substring(limiter.indexOf("public static LinuxScopeLimiter product("));
        product = product.substring(0, product.indexOf("System::nanoTime));"));
        assertThat(product)
                .contains("new LinuxScopeLimiter(", "EligibilityPolicy.none()", "NativeConsent.none()")
                .doesNotContain("ScopeManager", "SdBus", "System.getProperty", "System.getenv");
        assertThat(product)
                .as("no scope manager: the product can reach no state-changing bus call")
                .containsPattern("CgroupFiles\\.mounted\\(procRoot\\),\\s+Optional\\.empty\\(\\),");
        assertThat(limiter).doesNotContain("System.getProperty", "System.getenv");
        assertThat(limiter)
                .as("what admits a process cannot change after construction")
                .contains(
                        "private final LinuxNativeReadiness.EligibilityPolicy policy;",
                        "private final NativeConsent consent;",
                        "private final Optional<ScopeManager> scopes;");
    }
}
