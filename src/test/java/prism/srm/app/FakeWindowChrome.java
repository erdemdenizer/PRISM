package prism.srm.app;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import prism.srm.platform.ChromeColors;
import prism.srm.platform.WindowChrome;

/**
 * A frame that records what it is asked for and answers what the test says, by the number of the call
 * (from 1). Used on one thread at a time: the test's, or the JavaFX Application Thread while a lookup runs.
 */
final class FakeWindowChrome implements WindowChrome {

    private final IntFunction<Result> answers;
    private final List<String> calls = new ArrayList<>();

    FakeWindowChrome(IntFunction<Result> answers) {
        this.answers = answers;
    }

    @Override
    public Result apply(String title, ChromeColors colors) {
        calls.add("apply " + title + " " + (colors.dark() ? "dark" : "light") + " "
                + Integer.toHexString(colors.caption()) + " " + Integer.toHexString(colors.text()));
        return answers.apply(calls.size());
    }

    @Override
    public Result reset(String title) {
        calls.add("reset " + title);
        return answers.apply(calls.size());
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String detail() {
        return "fake frame";
    }

    /** The calls so far, as text: "apply title dark 16181d eceef2" (caption, then text) or "reset title". */
    List<String> calls() {
        return List.copyOf(calls);
    }

    /** The calls so far about one window. */
    long callsFor(String title) {
        return calls.stream().filter(call -> call.contains(" " + title)).count();
    }
}
