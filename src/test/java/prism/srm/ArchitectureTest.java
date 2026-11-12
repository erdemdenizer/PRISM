package prism.srm;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import prism.srm.platform.HostPlatform;
import prism.srm.platform.HostSystem;
import prism.srm.platform.UserFolders;
import prism.srm.ui.Typefaces;

/**
 * Enforces the layer boundaries described in docs/ARCHITECTURE.md against the compiled production
 * classes. A failure here means the dependency direction was broken, not that the test is wrong.
 */
class ArchitectureTest {

    private static final String APP = "prism.srm.app..";
    private static final String UI = "prism.srm.ui..";
    private static final String APPLICATION = "prism.srm.application..";
    private static final String DOMAIN = "prism.srm.domain..";
    private static final String PLATFORM = "prism.srm.platform..";
    private static final String DEMO = "prism.srm.demo..";
    private static final String[] PLATFORM_IMPLEMENTATIONS = {
        "prism.srm.platform.oshi..",
        "prism.srm.platform.windows..",
        "prism.srm.platform.linux..",
        "prism.srm.platform.macos.."
    };

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("prism.srm");
    }

    @Test
    void everyClassLivesInAKnownLayer() {
        // Also keeps the layer rule below from passing vacuously after a package rename.
        classes()
                .should()
                .resideInAnyPackage(APP, UI, APPLICATION, DOMAIN, PLATFORM, DEMO)
                .check(production);
    }

    @Test
    void layersDependOnlyInTheAllowedDirection() {
        // The demo workload is a layer of its own that nothing may reach; that it reaches nothing
        // is the rule below, which also names the only packages outside Prism it may use.
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("App")
                .definedBy(APP)
                .layer("Ui")
                .definedBy(UI)
                .layer("Application")
                .definedBy(APPLICATION)
                .layer("Domain")
                .definedBy(DOMAIN)
                .layer("Platform")
                .definedBy(PLATFORM)
                .layer("Demo")
                .definedBy(DEMO)
                .whereLayer("Demo")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("App")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Ui")
                .mayOnlyBeAccessedByLayers("App")
                .whereLayer("Application")
                .mayOnlyBeAccessedByLayers("App", "Ui")
                .whereLayer("Platform")
                .mayOnlyBeAccessedByLayers("App", "Application")
                .whereLayer("Domain")
                .mayOnlyBeAccessedByLayers("App", "Ui", "Application", "Platform")
                .check(production);
    }

    @Test
    void nativeAndOshiCodeStaysInsidePlatformImplementations() {
        noClasses()
                .that()
                .resideOutsideOfPackages(PLATFORM_IMPLEMENTATIONS)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.sun.jna..", "oshi..")
                .check(production);
    }

    @Test
    void platformImplementationsDoNotDependOnEachOther() {
        slices().matching("prism.srm.platform.(*)..")
                .should()
                .notDependOnEachOther()
                .check(production);
    }

    @Test
    void domainDependsOnlyOnTheJdk() {
        classes()
                .that()
                .resideInAPackage(DOMAIN)
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage("java..", DOMAIN)
                .check(production);
    }

    /**
     * SAFE-5: no shell command is ever assembled. No production class starts a process at all, so
     * none can run one built from process names or arguments: process control goes through the JDK
     * and documented native calls only.
     */
    @Test
    void noProductionCodeStartsAProcess() {
        assertThat(processStarts(production)).isEmpty();
    }

    /** The scan finds what it looks for: a test class that starts the demo workload, a mapping of system(3). */
    @Test
    void theScanForStartedProcessesFindsOne() {
        JavaClasses demoStarter = new ClassFileImporter().importClasses(prism.srm.platform.DemoProcess.class);
        JavaClasses shell = new ClassFileImporter().importClasses(ShellMapping.class);

        assertThat(processStarts(demoStarter)).isNotEmpty();
        assertThat(processStarts(shell)).containsExactly(ShellMapping.class.getName() + " maps system");
    }

    /** What the scan must catch: a JNA mapping of the C library's system(3). Never loaded. */
    interface ShellMapping extends com.sun.jna.Library {
        int system(String command);
    }

    /** What starts a process: ProcessBuilder, Runtime.exec, or a native mapping of the C calls for it. */
    private static List<String> processStarts(JavaClasses classes) {
        Set<String> nativeStarts = Set.of(
                "system",
                "popen",
                "posix_spawn",
                "posix_spawnp",
                "fork",
                "vfork",
                "execv",
                "execve",
                "execvp",
                "execl",
                "execlp",
                "execle");
        List<String> starts = new ArrayList<>();
        for (JavaClass type : classes) {
            for (JavaAccess<?> access : type.getAccessesFromSelf()) {
                String owner = access.getTargetOwner().getName();
                String name = access.getName();
                if (owner.equals(ProcessBuilder.class.getName())
                        || owner.equals(Runtime.class.getName()) && name.equals("exec")
                        // Windows, through jna-platform: CreateProcess*, ShellExecute*, WinExec.
                        || name.startsWith("CreateProcess")
                        || name.startsWith("ShellExecute")
                        || name.equals("WinExec")) {
                    starts.add(type.getName() + " -> " + owner + "." + access.getName());
                }
            }
            if (type.isAssignableTo("com.sun.jna.Library")) {
                type.getMethods().stream()
                        .filter(method -> nativeStarts.contains(method.getName()))
                        .forEach(method -> starts.add(type.getName() + " maps " + method.getName()));
            }
        }
        return starts;
    }

    @Test
    void theDemoWorkloadUsesOnlyTheJdk() {
        // DEMO-1: a separate program. It is started on its own, so it may use nothing of Prism, no
        // logger and no library; com.sun.management is the JDK's way to the physical memory size.
        classes()
                .that()
                .resideInAPackage(DEMO)
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "com.sun.management..", DEMO)
                .check(production);
        // Kept from matching nothing after a rename: the package must contain the entry point.
        classes()
                .that()
                .resideInAPackage(DEMO)
                .and()
                .haveSimpleName("DemoWorkload")
                .should()
                .bePublic()
                .check(production);
    }

    @Test
    void javaFxStaysInsideUiAndApp() {
        noClasses()
                .that()
                .resideOutsideOfPackages(UI, APP)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("javafx..")
                .check(production);
    }

    @Test
    void onlyTheEntryPointSelectsAnImplementation() {
        noClasses()
                .that()
                .resideInAPackage("prism.srm.platform")
                .and()
                .doNotHaveFullyQualifiedName("prism.srm.platform.HostPlatform")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(PLATFORM_IMPLEMENTATIONS)
                .check(production);
    }

    @Test
    void oshiIsSetUpInOnePlaceOnly() {
        // SystemInfo is the root of OSHI's long-lived objects: one owner, so they are created once.
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName("prism.srm.platform.oshi.OshiHost")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("oshi.SystemInfo")
                .check(production);
    }

    @Test
    void theOperatingSystemFamilyIsKnownOnlyInsidePlatform() {
        // Referenced as a class, so that renaming or moving it cannot make this rule match nothing.
        noClasses()
                .that()
                .resideOutsideOfPackage(PLATFORM)
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(HostSystem.class)
                .check(production);
    }

    @Test
    void implementationsDoNotReachBackToTheEntryPoint() {
        noClasses()
                .that()
                .resideInAnyPackage(PLATFORM_IMPLEMENTATIONS)
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(HostPlatform.class)
                .check(production);
    }

    @Test
    void whatRunsBeforeLoggingIsSetUpUsesNoLogger() {
        // The log folder comes from these classes and must reach Logback before the first logger
        // exists; a logger in one of them would configure logging without the folder.
        noClasses()
                .that()
                .implement(UserFolders.class)
                .or()
                .belongToAnyOf(HostPlatform.class, HostSystem.class, UserFolders.class)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.slf4j..")
                .check(production);
    }

    @Test
    void onlyTheLoggingSetupCreatesALoggerInTheCompositionRoot() {
        // The log folder must reach the logging configuration before the first logger exists.
        // One class sets the folder and creates that logger, in this order; a second place that
        // creates loggers could run first. Named as text: the class is not visible from here,
        // and after a rename this rule fails instead of matching nothing.
        noClasses()
                .that()
                .resideInAPackage(APP)
                .and()
                .doNotHaveFullyQualifiedName("prism.srm.app.LoggingSetup")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.slf4j.LoggerFactory")
                .check(production);
    }

    @Test
    void theApplicationClassDoesNothingBeforeItsInitMethod() {
        // JavaFX constructs the application class before it calls init(), where logging is set
        // up. A field initializer that creates a service would create its logger too early, and
        // nothing would fail: the log file would just be somewhere else.
        JavaClass app = production.get("prism.srm.app.App");
        List<JavaAccess<?>> early = new ArrayList<>();
        app.getConstructors().forEach(constructor -> early.addAll(constructor.getAccessesFromSelf()));
        app.getStaticInitializer().ifPresent(initializer -> early.addAll(initializer.getAccessesFromSelf()));

        assertThat(early).as("the constructor calls at least its superclass").isNotEmpty();
        assertThat(early)
                .extracting(access -> access.getTargetOwner().getName())
                .filteredOn(owner -> owner.startsWith("org.slf4j.")
                        || (owner.startsWith("prism.srm.") && !owner.equals("prism.srm.app.App")))
                .isEmpty();
    }

    @Test
    void uiPackagesHaveNoCycles() {
        slices().matching("prism.srm.ui.(*)..").should().beFreeOfCycles().check(production);
    }

    @Test
    void pagesDoNotDependOnEachOtherAndNothingDependsOnTheShell() {
        // The shell puts the pages into the window, so it is at the top: nothing else in the user
        // interface knows it. A page knows no other page; what two pages share lives in a package
        // of its own, below both.
        noClasses()
                .that()
                .resideInAPackage(UI)
                .and()
                .resideOutsideOfPackage("prism.srm.ui.shell..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("prism.srm.ui.shell..")
                .check(production);
        slices().matching("prism.srm.ui.(overview|performance|processes|limits|oom|logs|settings)..")
                .should()
                .notDependOnEachOther()
                .check(production);
        // What the pages share lies below them: a shared package never reaches up into a page.
        noClasses()
                .that()
                .resideInAnyPackage(
                        "prism.srm.ui.format..",
                        "prism.srm.ui.graph..",
                        "prism.srm.ui.metric..",
                        "prism.srm.ui.icon..",
                        "prism.srm.ui.control..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "prism.srm.ui.overview..",
                        "prism.srm.ui.performance..",
                        "prism.srm.ui.processes..",
                        "prism.srm.ui.limits..",
                        "prism.srm.ui.oom..",
                        "prism.srm.ui.logs..",
                        "prism.srm.ui.settings..")
                .check(production);
    }

    @Test
    void theBundledTypefaceIsLoadedInOnePlaceOnly() {
        // The font registry of the toolkit is process-wide. One owner loads the faces, pays the
        // cost once at start-up, and decides the fallback in one place; nothing else reads a font
        // file.
        noClasses()
                .that()
                .doNotBelongToAnyOf(Typefaces.class)
                .should()
                .callMethod(javafx.scene.text.Font.class, "loadFont", String.class, double.class)
                .orShould()
                .callMethod(javafx.scene.text.Font.class, "loadFont", java.io.InputStream.class, double.class)
                .check(production);
    }

    @Test
    void theSuspensionCoordinatorIsTheOnlyOwnerOfSuspensions() {
        // CTL-9, ADR-0004: one owner keeps the reasons a process is suspended, so the user, the
        // limiter and OOM Guard never resume each other's suspensions. They ask the coordinator.
        noClasses()
                .that()
                .doNotBelongToAnyOf(prism.srm.application.SuspensionCoordinator.class)
                .and()
                .resideOutsideOfPackage("prism.srm.platform..")
                .should()
                .callMethod(
                        prism.srm.platform.ProcessController.class, "suspend", prism.srm.domain.ProcessIdentity.class)
                .orShould()
                .callMethod(prism.srm.platform.ProcessController.class, "resume", prism.srm.platform.Suspension.class)
                .orShould()
                .callMethod(prism.srm.platform.Suspension.class, "close")
                .check(production);
    }

    @Test
    void onlyTheUsersActionsAndOomGuardTerminateCloseOrRePrioritizeAProcess() {
        // Milestone 11: OOM Guard acts on processes itself, through the controller, like the service of the
        // user's actions; nothing else does. Suspensions go through the coordinator (rule above).
        noClasses()
                .that()
                .doNotBelongToAnyOf(
                        prism.srm.application.ProcessControlService.class, prism.srm.application.OomGuard.class)
                .and()
                .resideOutsideOfPackage("prism.srm.platform..")
                .should()
                .callMethod(
                        prism.srm.platform.ProcessController.class, "terminate", prism.srm.domain.ProcessIdentity.class)
                .orShould()
                .callMethod(
                        prism.srm.platform.ProcessController.class,
                        "changePriority",
                        prism.srm.domain.ProcessIdentity.class,
                        prism.srm.domain.PriorityLevel.class)
                .orShould()
                .callMethod(
                        prism.srm.platform.ProcessController.class,
                        "requestClose",
                        prism.srm.domain.ProcessIdentity.class)
                .check(production);
    }

    @Test
    void theOomStateMachineIsAPureFunctionOfItsInputs() {
        // ADR-0015: no thread, no log, no platform; the JDK and the domain only.
        classes()
                .that()
                .belongToAnyOf(prism.srm.application.OomStateMachine.class)
                .should()
                .onlyDependOnClassesThat(resideInAnyPackage("java..", "prism.srm.domain..")
                        .or(com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf(
                                prism.srm.application.OomStateMachine.class))
                        .or(new com.tngtech.archunit.base.DescribedPredicate<JavaClass>("arrays of its own types") {
                            @Override
                            public boolean test(JavaClass type) {
                                return type.isArray()
                                        && type.getBaseComponentType()
                                                .getName()
                                                .startsWith("prism.srm.application.OomStateMachine");
                            }
                        }))
                .check(production);
    }

    @Test
    void onlyTheCoordinatorTakesOverASuspensionAnEarlierSessionLeft() {
        // ADR-0011: taking over a stop found after a crash is the one act that may end a stop that is
        // not Prism's. It happens in one place, on the user's request, after the coordinator's checks.
        noClasses()
                .that()
                .doNotBelongToAnyOf(prism.srm.application.SuspensionCoordinator.class)
                .and()
                .resideOutsideOfPackage("prism.srm.platform..")
                .should()
                .callMethod(
                        prism.srm.platform.ProcessController.class,
                        "adopt",
                        prism.srm.domain.ProcessIdentity.class,
                        java.util.Optional.class)
                .check(production);
    }

    @Test
    void everyPackageOfTheUserInterfaceIsKnownToTheseRules() {
        // The rule above names the pages. A new package must be added here, and if it is a page,
        // to that rule: otherwise it would be outside the rule without anything failing.
        Set<String> shared = Set.of("format", "graph", "metric", "icon", "control");
        Set<String> pages = Set.of("overview", "performance", "processes", "limits", "oom", "logs", "settings");
        Set<String> found = new TreeSet<>();
        for (JavaClass type :
                production.that(resideInAPackage("prism.srm.ui.(*)..")).stream().toList()) {
            found.add(type.getPackageName().substring("prism.srm.ui.".length()).split("\\.")[0]);
        }

        Set<String> known = new TreeSet<>(shared);
        known.addAll(pages);
        known.add("shell");
        assertThat(found).isEqualTo(known);
    }

    @Test
    void theColorSchemeOfASceneIsSetByTheThemeControllerOnly() {
        // ADR-0009: one owner applies the appearance mode to every scene; a view that forced a
        // scheme of its own would stop following the mode.
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName("prism.srm.app.ThemeController")
                .should()
                .callMethod(
                        javafx.scene.Scene.Preferences.class, "setColorScheme", javafx.application.ColorScheme.class)
                .check(production);
    }

    @Test
    void theRootOfTheUserInterfaceReachesIntoNoneOfItsPackages() {
        // Stylesheets, Typefaces, Motion and the relay are what every ui package leans on. The
        // slice rule above sees only the packages; a root class that reached into one would be
        // a cycle it cannot see.
        noClasses()
                .that()
                .resideInAPackage("prism.srm.ui")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("prism.srm.ui.(*)..")
                .check(production);
    }

    @Test
    void theUiNeverSchedulesItsOwnWorkOnTheApplicationThread() {
        // Every hand-over to the JavaFX Application Thread goes through the one relay that the
        // composition root wires, which keeps at most one update pending. A view that called
        // Platform.runLater itself would be outside that guarantee.
        noClasses()
                .that()
                .resideInAPackage(UI)
                .should()
                .callMethod(Platform.class, "runLater", Runnable.class)
                .check(production);
    }

    @Test
    void platformImplementationsAreReachedOnlyThroughPlatform() {
        noClasses()
                .that()
                .resideOutsideOfPackage(PLATFORM)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(PLATFORM_IMPLEMENTATIONS)
                .check(production);
    }
}
