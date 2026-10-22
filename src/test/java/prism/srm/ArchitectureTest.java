package prism.srm;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
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
                .resideInAnyPackage(APP, UI, APPLICATION, DOMAIN, PLATFORM)
                .check(production);
    }

    @Test
    void layersDependOnlyInTheAllowedDirection() {
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
        slices().matching("prism.srm.ui.(overview|performance|processes)..")
                .should()
                .notDependOnEachOther()
                .check(production);
    }

    @Test
    void everyPackageOfTheUserInterfaceIsKnownToTheseRules() {
        // The rule above names the pages. A new package must be added here, and if it is a page,
        // to that rule: otherwise it would be outside the rule without anything failing.
        Set<String> shared = Set.of("format", "graph", "metric");
        Set<String> pages = Set.of("overview", "performance", "processes");
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
