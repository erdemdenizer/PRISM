package prism.srm.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import prism.srm.platform.ChromeColors;
import prism.srm.platform.WindowChrome.Result;
import prism.srm.platform.windows.WindowsWindowChrome.ThreadWindow;

/** UI lab v2: the decisions of the title bar colors. No native call is made. */
class WindowsWindowChromeTest {

    private static final String STAGE = "GlassWndClass-GlassWindowClass-2";
    private static final ChromeColors DARK = new ChromeColors(true, 0x16181D, 0xECEEF2, 0x2A2E36);

    /** A COLORREF is 0x00BBGGRR (the COLORREF page): red in the low byte, the high byte zero. */
    @Test
    void aColorBecomesAColorrefWithRedInTheLowByte() {
        assertThat(WindowsWindowChrome.colorref(0xFF0000)).isEqualTo(0x0000FF);
        assertThat(WindowsWindowChrome.colorref(0x00FF00)).isEqualTo(0x00FF00);
        assertThat(WindowsWindowChrome.colorref(0x0000FF)).isEqualTo(0xFF0000);
        assertThat(WindowsWindowChrome.colorref(0x16181D)).isEqualTo(0x1D1816);
        assertThat(WindowsWindowChrome.colorref(0xFFFFFF)).isEqualTo(0xFFFFFF);
        assertThat(WindowsWindowChrome.colorref(0)).isZero();
    }

    @Test
    void aValueOutsideTwentyFourBitsIsRefused() {
        assertThatThrownBy(() -> WindowsWindowChrome.colorref(0x1000000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindowsWindowChrome.colorref(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChromeColors(true, 0x1000000, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** The values of the DWMWINDOWATTRIBUTE page, not the constants: a wrong constant must fail here. */
    @Test
    void theAttributesAreTheDocumentedOnesInTheirOrder() {
        FakeDwm dwm = new FakeDwm(true, List.of(new ThreadWindow(7, STAGE, "Prism")));

        Result result = new WindowsWindowChrome(dwm).apply("Prism", DARK);

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(dwm.calls).containsExactly("7:20=1", "7:35=1d1816", "7:36=f2eeec", "7:34=362e2a");
        assertThat(dwm.askedBuild).isEqualTo(22_000);
    }

    @Test
    void aLightThemeTurnsTheDarkModeOff() {
        FakeDwm dwm = new FakeDwm(true, List.of(new ThreadWindow(7, STAGE, "Prism")));

        new WindowsWindowChrome(dwm).apply("Prism", new ChromeColors(false, 0xEEF0F3, 0x15181E, 0xD5D9E0));

        assertThat(dwm.calls.get(0)).isEqualTo("7:20=0");
    }

    @Test
    void beforeBuild22000NothingIsLookedForOrSet() {
        FakeDwm dwm = new FakeDwm(false, List.of(new ThreadWindow(7, STAGE, "Prism")));
        WindowsWindowChrome chrome = new WindowsWindowChrome(dwm);

        assertThat(chrome.available()).isFalse();
        assertThat(chrome.apply("Prism", DARK)).isEqualTo(Result.UNAVAILABLE);
        assertThat(chrome.apply("Prism", DARK)).isEqualTo(Result.UNAVAILABLE);

        assertThat(dwm.calls).isEmpty();
        assertThat(dwm.lookups).isZero();
        assertThat(dwm.buildChecks).as("the build is checked once").isEqualTo(1);
        assertThat(chrome.detail()).contains("22000");
    }

    /** The reason is there as soon as availability says no, before any apply (it is logged then). */
    @Test
    void anOldSystemExplainsItselfAtTheAvailabilityCheck() {
        WindowsWindowChrome chrome = new WindowsWindowChrome(new FakeDwm(false, List.of()));

        assertThat(chrome.available()).isFalse();
        assertThat(chrome.detail()).contains("22000");
    }

    /** A reset gives each frame color the documented default 0xFFFFFFFF and turns the dark mode off. */
    @Test
    void aResetGivesTheSystemColorsBack() {
        FakeDwm dwm = new FakeDwm(true, List.of(new ThreadWindow(7, STAGE, "Prism")));

        Result result = new WindowsWindowChrome(dwm).reset("Prism");

        assertThat(result).isEqualTo(Result.APPLIED);
        assertThat(dwm.calls).containsExactly("7:20=0", "7:35=ffffffff", "7:36=ffffffff", "7:34=ffffffff");
    }

    @Test
    void aFailingNativeBindingIsAFailedResultNotAnException() {
        FakeDwm dwm = new FakeDwm(true, List.of(new ThreadWindow(7, STAGE, "Prism")));
        dwm.broken = true;
        WindowsWindowChrome chrome = new WindowsWindowChrome(dwm);

        assertThat(chrome.apply("Prism", DARK)).isEqualTo(Result.FAILED);
        assertThat(chrome.detail()).contains("native call failed");
        assertThat(dwm.calls).isEmpty();
    }

    @Test
    void onlyAStageWindowWithExactlyTheTitleIsSelected() {
        List<ThreadWindow> windows = List.of(
                new ThreadWindow(1, "GlassWndClass-GlassToolkitWindowClass-1", "Prism"),
                new ThreadWindow(2, "MSCTFIME UI", "Prism"),
                new ThreadWindow(3, "Default IME", ""),
                new ThreadWindow(4, STAGE, "Prism - Settings"),
                new ThreadWindow(5, STAGE, "prism"),
                new ThreadWindow(6, STAGE, "Prism"));

        assertThat(WindowsWindowChrome.select(windows, "Prism"))
                .extracting(ThreadWindow::hwnd)
                .containsExactly(6L);
    }

    /** Measured: at onShown the native title is still empty, so the stage is not found yet. */
    @Test
    void aWindowWhoseTitleIsNotSetYetIsNotFound() {
        FakeDwm dwm = new FakeDwm(true, List.of(new ThreadWindow(7, STAGE, "")));

        assertThat(new WindowsWindowChrome(dwm).apply("Prism", DARK)).isEqualTo(Result.NOT_FOUND);
        assertThat(dwm.calls).isEmpty();
    }

    @Test
    void twoWindowsWithTheTitleChangeNeither() {
        FakeDwm dwm = new FakeDwm(
                true, List.of(new ThreadWindow(7, STAGE, "Confirm"), new ThreadWindow(8, STAGE, "Confirm")));

        assertThat(new WindowsWindowChrome(dwm).apply("Confirm", DARK)).isEqualTo(Result.AMBIGUOUS);
        assertThat(dwm.calls).isEmpty();
    }

    @Test
    void theFirstRefusedValueEndsTheAttempt() {
        FakeDwm dwm = new FakeDwm(true, List.of(new ThreadWindow(7, STAGE, "Prism")));
        dwm.refuse = 35;
        WindowsWindowChrome chrome = new WindowsWindowChrome(dwm);

        assertThat(chrome.apply("Prism", DARK)).isEqualTo(Result.FAILED);

        assertThat(dwm.calls).containsExactly("7:20=1", "7:35=1d1816");
        assertThat(chrome.detail()).contains("35").contains("80070057");
    }

    @Test
    void aWindowWithoutATitleIsRefused() {
        WindowsWindowChrome chrome = new WindowsWindowChrome(new FakeDwm(true, List.of()));

        assertThatThrownBy(() -> chrome.apply("", DARK)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeDwm implements WindowsWindowChrome.Dwm {

        private final boolean newEnough;
        private final List<ThreadWindow> windows;
        private final List<String> calls = new ArrayList<>();
        private int askedBuild;
        private int buildChecks;
        private int lookups;
        private int refuse = -1;
        private boolean broken;

        FakeDwm(boolean newEnough, List<ThreadWindow> windows) {
            this.newEnough = newEnough;
            this.windows = windows;
        }

        @Override
        public boolean buildAtLeast(int build) {
            askedBuild = build;
            buildChecks++;
            return newEnough;
        }

        @Override
        public List<ThreadWindow> threadWindows() {
            lookups++;
            if (broken) {
                throw new IllegalStateException("callback mapping");
            }
            return windows;
        }

        @Override
        public int setAttribute(long hwnd, int attribute, int value) {
            calls.add(hwnd + ":" + attribute + "=" + Integer.toHexString(value));
            // E_INVALIDARG
            return attribute == refuse ? 0x80070057 : 0;
        }
    }
}
