package prism.srm.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jna.platform.win32.Kernel32;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessReading;

/**
 * The table of levels of the controller and the numbers of the process listing come from two
 * implementations that may not know each other; this ties them on the real system: the listing's
 * number for this JVM stands, in the table, for the level this JVM actually runs at. That level is
 * read on its own, not assumed: the runner decides it (GitHub's Windows runner starts jobs below
 * normal). Reads only. Not on macOS, whose listing shows no level.
 *
 * <ul>
 *   <li>Windows: GetPriorityClass of this process, through jna-platform, with the class values of
 *       Microsoft's SetPriorityClass page.
 *   <li>Linux: the nice value, field 19 of /proc/self/stat; the listing shows field 18.
 * </ul>
 *
 * A class or a nice value that is none of the five levels must stand for no level in the table.
 */
@EnabledOnOs({OS.WINDOWS, OS.LINUX})
class ListedPriorityTest {

    /** https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-setpriorityclass */
    private static final Map<Integer, PriorityLevel> WINDOWS_CLASSES = Map.of(
            0x00000040, PriorityLevel.LOW,
            0x00004000, PriorityLevel.BELOW_NORMAL,
            0x00000020, PriorityLevel.NORMAL,
            0x00008000, PriorityLevel.ABOVE_NORMAL,
            0x00000080, PriorityLevel.HIGH);

    @Test
    void theListingNumbersThisProcessAsTheLevelItActuallyRunsAt() throws IOException {
        HostPlatform platform = HostPlatform.create();
        long own = ProcessHandle.current().pid();

        OptionalInt listed = platform.processSource().processes().stream()
                .filter(reading -> reading.identity().pid() == own)
                .map(ProcessReading::priority)
                .findFirst()
                .orElseThrow();
        Optional<PriorityLevel> actual = OS.WINDOWS.isCurrentOs() ? windowsLevel() : linuxLevel();
        System.out.println("listed priority " + listed + ", actual level " + actual);

        assertThat(listed).isPresent();
        assertThat(Optional.ofNullable(
                        platform.processController().listedPriorities().get(listed.getAsInt())))
                .isEqualTo(actual);
    }

    private static Optional<PriorityLevel> windowsLevel() {
        int priorityClass = Kernel32.INSTANCE
                .GetPriorityClass(Kernel32.INSTANCE.GetCurrentProcess())
                .intValue();
        assertThat(priorityClass).as("GetPriorityClass").isNotZero();
        return Optional.ofNullable(WINDOWS_CLASSES.get(priorityClass));
    }

    private static Optional<PriorityLevel> linuxLevel() throws IOException {
        String stat = Files.readString(Path.of("/proc/self/stat"), StandardCharsets.UTF_8);
        String[] fields = stat.substring(stat.lastIndexOf(')') + 1).trim().split("\\s+");
        // fields[0] is field 3, so field 19 is fields[16].
        return SpecNice.levelOf(Integer.parseInt(fields[16]));
    }
}
