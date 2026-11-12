package prism.srm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import prism.srm.domain.ActivityEntry;
import prism.srm.domain.ActivitySeverity;
import prism.srm.domain.AppSettings;
import prism.srm.domain.OomSettings;
import prism.srm.domain.OomSwitches;
import prism.srm.domain.ThemeMode;

/**
 * The settings file on a real disk, with faults (SET-1, SET-2, ADR-0014): nothing ever prevents a
 * start, a damaged file is kept aside once, a later version is never overwritten, and a save that
 * fails leaves the last good file.
 */
class SettingsStoreTest {

    @RegisterExtension
    final JournalFolder folder = new JournalFolder();

    private final FaultyJournalFiles files = new FaultyJournalFiles();
    private final ActivityLog activity = new ActivityLog(100, () -> Instant.EPOCH);
    private final QueuedExecutor writes = new QueuedExecutor();
    private Path settingsFolder;
    private SettingsStore store;

    private static final AppSettings CHANGED = new AppSettings(
            OomSettings.DEFAULTS.withSwitches(OomSwitches.DEFAULTS.withAutoSuspend(true)), ThemeMode.DARK);

    @BeforeEach
    void setUp() {
        settingsFolder = folder.path().resolve("settings");
        store = newStore();
    }

    private SettingsStore newStore() {
        return new SettingsStore(settingsFolder, files, activity, () -> Instant.ofEpochMilli(1_234), writes);
    }

    private List<ActivityEntry> entries(ActivitySeverity severity) {
        return activity.entries().stream()
                .filter(entry -> entry.severity() == severity)
                .toList();
    }

    private void writeFile(String text) throws Exception {
        Files.createDirectories(settingsFolder);
        Files.writeString(store.file(), text, StandardCharsets.UTF_8);
    }

    private List<Path> filesInFolder() throws Exception {
        try (Stream<Path> listed = Files.list(settingsFolder)) {
            return listed.map(Path::getFileName).sorted().toList();
        }
    }

    @Test
    void aMissingFileGivesTheDefaultsAndOneInfoLine() {
        assertThat(store.load()).isEqualTo(AppSettings.DEFAULTS);
        assertThat(activity.entries()).hasSize(1);
        assertThat(entries(ActivitySeverity.INFO).get(0).message()).contains("no settings file yet");
    }

    @Test
    void whatIsSavedIsReadByTheNextStart() {
        store.save(CHANGED);
        writes.runAll();

        assertThat(newStore().load()).isEqualTo(CHANGED);
        assertThat(entries(ActivitySeverity.WARNING)).isEmpty();
    }

    @Test
    void aCorruptFileIsMovedAsideOnceAndTheNextLoadDoesNotCopyAgain() throws Exception {
        writeFile("prism-settings 1\noom.monitoring=false\nend 1 deadbeef\n");

        assertThat(store.load()).isEqualTo(AppSettings.DEFAULTS);
        assertThat(entries(ActivitySeverity.WARNING)).hasSize(1);
        assertThat(entries(ActivitySeverity.WARNING).get(0).message()).contains("damaged", "corrupt-1234");
        assertThat(filesInFolder()).containsExactly(Path.of("prism.settings.corrupt-1234"));

        assertThat(newStore().load()).isEqualTo(AppSettings.DEFAULTS);
        assertThat(filesInFolder()).containsExactly(Path.of("prism.settings.corrupt-1234"));
        assertThat(entries(ActivitySeverity.WARNING)).hasSize(1);
    }

    @Test
    void aNewerVersionDisablesSavingAndLeavesTheFileUntouched() throws Exception {
        String newer = "prism-settings 7\nwhatever=1\nend 1 0\n";
        writeFile(newer);

        assertThat(store.load()).isEqualTo(AppSettings.DEFAULTS);
        assertThat(store.saveAllowed()).isFalse();
        store.save(CHANGED);
        writes.runAll();

        assertThat(Files.readString(store.file(), StandardCharsets.UTF_8)).isEqualTo(newer);
        assertThat(entries(ActivitySeverity.WARNING)).hasSize(2);
    }

    @Test
    void aFileThatCannotBeReadGivesTheDefaultsAndStaysWhereItIs() throws Exception {
        store.save(CHANGED);
        writes.runAll();
        files.readsFail = 1;

        assertThat(newStore().load()).isEqualTo(AppSettings.DEFAULTS);
        assertThat(entries(ActivitySeverity.WARNING)).hasSize(1);
        assertThat(filesInFolder()).containsExactly(Path.of("prism.settings"));
    }

    @Test
    void whatWasWrongWithTheFileIsToldToTheSettingsPage() throws Exception {
        assertThat(store.load()).isEqualTo(AppSettings.DEFAULTS);
        assertThat(store.loadProblem()).as("no file yet is nothing wrong").isEmpty();

        writeFile("not a settings file\n");
        store = newStore();
        store.load();
        assertThat(store.loadProblem().orElseThrow()).contains("damaged").contains("prism.settings.corrupt-1234");

        writeFile("prism-settings 7\nwhatever=1\nend 1 0\n");
        store = newStore();
        store.load();
        assertThat(store.loadProblem().orElseThrow()).contains("later version");

        Files.delete(store.file());
        store = newStore();
        store.save(CHANGED);
        writes.runAll();
        store = newStore();
        files.readsFail = 1;
        store.load();
        assertThat(store.loadProblem().orElseThrow()).contains("could not be read");

        store = newStore();
        assertThat(store.load()).isEqualTo(CHANGED);
        assertThat(store.loadProblem()).as("a file that was read").isEmpty();
    }

    @Test
    void theEndOfEverySaveIsTold() throws Exception {
        List<Optional<String>> outcomes = new ArrayList<>();
        store.onSaveOutcome(outcomes::add);

        store.save(CHANGED);
        writes.runAll();
        files.diskFull = true;
        store.save(AppSettings.DEFAULTS);
        writes.runAll();
        files.diskFull = false;
        writes.refusing = true;
        store.save(AppSettings.DEFAULTS);
        writes.refusing = false;

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.get(0)).isEmpty();
        assertThat(outcomes.get(1).orElseThrow()).contains("could not save");
        assertThat(outcomes.get(2).orElseThrow()).contains("could not save");
    }

    @Test
    void aFileOfALaterVersionIsToldAsLastingForTheSession() throws Exception {
        writeFile("prism-settings 7\nwhatever=1\nend 1 0\n");
        store.load();
        List<Optional<String>> outcomes = new ArrayList<>();
        store.onSaveOutcome(outcomes::add);

        store.save(CHANGED);
        writes.runAll();

        assertThat(outcomes)
                .singleElement()
                .satisfies(outcome -> assertThat(outcome.orElseThrow()).contains("this session only"));
    }

    /** UI-7: the choice of the page survives a restart, whatever else is in the file. */
    @Test
    void everyAppearanceIsReadByTheNextStartAndTheOtherSettingsStay() {
        for (ThemeMode mode : ThemeMode.values()) {
            store.save(CHANGED.withAppearance(mode));
            writes.runAll();

            AppSettings read = newStore().load();
            assertThat(read.appearance()).isEqualTo(mode);
            assertThat(read.oom()).isEqualTo(CHANGED.oom());
        }
    }

    @Test
    void aMetadataProblemIsNotTakenForAMissingFile() throws Exception {
        store.save(CHANGED);
        writes.runAll();
        files.metadataUnknown.add(SettingsStore.FILE_NAME);

        assertThat(newStore().load()).isEqualTo(AppSettings.DEFAULTS);
        assertThat(entries(ActivitySeverity.INFO))
                .noneSatisfy(entry -> assertThat(entry.message()).contains("no settings file"));
        assertThat(entries(ActivitySeverity.WARNING)).hasSize(1);
    }

    @Test
    void aSaveThatFailsOnAFullDiskLeavesTheLastGoodFileReadable() throws Exception {
        store.save(CHANGED);
        writes.runAll();
        files.diskFull = true;
        store.save(AppSettings.DEFAULTS);
        writes.runAll();
        files.diskFull = false;

        assertThat(entries(ActivitySeverity.WARNING)).hasSize(1);
        assertThat(newStore().load()).isEqualTo(CHANGED);
        assertThat(filesInFolder()).containsExactly(Path.of("prism.settings"));
    }

    @Test
    void aMoveThatFailsTwiceSucceedsTheThirdTimeAndThreeFailuresLeaveTheOldFile() {
        store.save(CHANGED);
        writes.runAll();
        files.failingMoves = 2;
        store.save(AppSettings.DEFAULTS);
        writes.runAll();
        assertThat(newStore().load()).isEqualTo(AppSettings.DEFAULTS);

        files.failingMoves = 3;
        store.save(CHANGED);
        writes.runAll();
        assertThat(newStore().load()).isEqualTo(AppSettings.DEFAULTS);
        assertThat(entries(ActivitySeverity.WARNING)).hasSize(1);
    }

    @Test
    void aFileThatReadsBackOtherwiseIsAFailedSave() {
        files.readsBackOtherwise = true;
        store.save(CHANGED);
        writes.runAll();

        assertThat(entries(ActivitySeverity.WARNING)).hasSize(1);
        assertThat(entries(ActivitySeverity.WARNING).get(0).message()).contains("did not read back");
    }

    @Test
    void aRefusedSaveIsRetriedWithTheNextChange() {
        writes.refusing = true;
        store.save(CHANGED);
        assertThat(entries(ActivitySeverity.WARNING)).hasSize(1);
        writes.refusing = false;
        AppSettings later = CHANGED.withOom(OomSettings.DEFAULTS);
        store.save(later);
        writes.runAll();

        assertThat(newStore().load()).isEqualTo(later);
    }

    @Test
    void twoQueuedSavesEndWithTheLastOneWrittenOnce() {
        store.save(CHANGED);
        store.save(AppSettings.DEFAULTS);
        assertThat(writes.waiting())
                .as("one task on the shared journal thread, whatever the burst")
                .isEqualTo(1);
        writes.runAll();

        assertThat(files.writes).isEqualTo(1);
        assertThat(newStore().load()).isEqualTo(AppSettings.DEFAULTS);
    }

    /** A burst of changes never fills the queue of the journal thread it shares (architecture review). */
    @Test
    void aBurstOfChangesQueuesOneTaskAndAChangeAfterItQueuesAnother() {
        for (int i = 0; i < 100; i++) {
            store.save(i % 2 == 0 ? CHANGED : AppSettings.DEFAULTS);
        }
        assertThat(writes.waiting()).isEqualTo(1);
        writes.runAll();
        store.save(CHANGED);
        assertThat(writes.waiting()).isEqualTo(1);
        writes.runAll();

        assertThat(files.writes).isEqualTo(2);
        assertThat(newStore().load()).isEqualTo(CHANGED);
    }

    @Test
    void noFileIsWrittenOnTheCallersThread() {
        store.save(CHANGED);

        assertThat(files.writes).isZero();
        assertThat(Files.exists(store.file())).isFalse();
    }

    @Test
    void aMigrationNeverEnablesTermination() throws Exception {
        // A file of this version without the key, as a future migration would leave it: termination stays off.
        writeFile(new String(
                SettingsCodecTest.file("oom.monitoring=true", "oom.auto.suspend=true"), StandardCharsets.UTF_8));

        AppSettings loaded = store.load();

        assertThat(loaded.oom().switches().termination()).isFalse();
        assertThat(loaded.oom().switches().autoSuspend()).isTrue();
    }
}
