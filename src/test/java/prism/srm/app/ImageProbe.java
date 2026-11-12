package prism.srm.app;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import prism.srm.application.ActivityLog;
import prism.srm.domain.ControlAction;
import prism.srm.domain.ControlRequest;
import prism.srm.domain.LimitAction;
import prism.srm.domain.LimitRequest;
import prism.srm.domain.LimitResult;
import prism.srm.domain.PriorityLevel;
import prism.srm.domain.ProcessControlResult;
import prism.srm.domain.ProcessIdentity;
import prism.srm.domain.ProcessSnapshot;
import prism.srm.domain.SystemSnapshot;
import prism.srm.platform.HostPlatform;
import prism.srm.ui.format.ValueFormatter;

/**
 * Runs on the application image's own runtime (ADR-0017), through a launcher copied for the test, and
 * prints {@code key=value} lines for {@code verify-image.ps1}. Not part of the image.
 *
 * <ul>
 *   <li>{@code check}: what the runtime provides (modules, Turkish formats, JavaFX, JNA and OSHI through
 *       the real platform, Logback), and SAFE-2 as a result: the real runtime asked to act on its own
 *       process, with no button in between. A probe that survives and has every answer proves it.
 *   <li>{@code grow <goFile> <mostMiB>}: holds native memory in steps once the file exists, for the
 *       proof that a native memory limit stops a real process and that removing it lets it go on.
 * </ul>
 */
public final class ImageProbe {

    private static final long MIB = 1024 * 1024;
    private static final long STEP_BYTES = 16 * MIB;

    private ImageProbe() {}

    public static void main(String[] args) throws Exception {
        switch (args.length == 0 ? "" : args[0]) {
            case "check" -> check();
            case "grow" -> grow(Path.of(args[1]), Long.parseLong(args[2]));
            default -> throw new IllegalArgumentException("usage: check | grow <goFile> <mostMiB>");
        }
    }

    private static void line(String key, Object value) {
        System.out.println(key + "=" + value);
        System.out.flush();
    }

    private static void check() throws Exception {
        line("pid", ProcessHandle.current().pid());
        line("java.version", System.getProperty("java.version"));
        line("java.vendor", System.getProperty("java.vendor"));
        line("default.locale", Locale.getDefault());
        line("modules", ModuleLayer.boot().modules().size());
        for (String module : List.of("jdk.localedata", "java.desktop", "jdk.management", "jdk.unsupported")) {
            line("module." + module, ModuleLayer.boot().findModule(module).isPresent());
        }
        turkish();
        javaFx();
        line(
                "logging.factory",
                org.slf4j.LoggerFactory.getILoggerFactory().getClass().getName());
        line("oom.signal", HostPlatform.oomSignalStatus());
        safe2();
        line("probe.end", "ok");
        // JavaFX's own non-daemon threads must not outlive the answer.
        System.exit(0);
    }

    /** Values that only {@code jdk.localedata} gives; the product's own formatter among them. */
    private static void turkish() {
        Locale tr = Locale.forLanguageTag("tr-TR");
        line("tr.number", NumberFormat.getInstance(tr).format(1_234_567.5));
        line("tr.percent", NumberFormat.getPercentInstance(tr).format(0.25));
        line(
                "tr.date",
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                        .withLocale(tr)
                        .format(LocalDate.of(2026, 1, 15)));
        line("tr.upper-i", "i".toUpperCase(tr));
        line("tr.product-bytes", new ValueFormatter(tr).bytes(1_288_490_189L));
    }

    private static void javaFx() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        Platform.startup(ran::countDown);
        line("javafx.toolkit", ran.await(30, TimeUnit.SECONDS));
    }

    private static void safe2() throws Exception {
        List<SystemSnapshot> system = new CopyOnWriteArrayList<>();
        List<List<ProcessSnapshot>> listings = new CopyOnWriteArrayList<>();
        List<ProcessControlResult> controls = new CopyOnWriteArrayList<>();
        List<LimitResult> limits = new CopyOnWriteArrayList<>();
        MonitoringRuntime runtime = new MonitoringRuntime(
                PlatformSources::ofRunningSystem,
                System::nanoTime,
                new ActivityLog(1_000, Instant::now),
                new MonitoringRuntime.Listeners(
                        system::add,
                        listing -> {
                            listings.clear();
                            listings.add(listing);
                        },
                        details -> {},
                        controls::add,
                        profile -> line("control.profile", profile),
                        held -> {},
                        failures -> line("runtime.failures", failures),
                        snapshot -> {},
                        limits::add,
                        recovered -> {},
                        recoveryCheck -> {},
                        recovery -> {},
                        oom -> {}),
                Duration.ofSeconds(5),
                (thread, failure) -> line("runtime.uncaught", thread.getName() + " " + failure),
                MonitoringRuntime.RECOVERY_RETRIES);
        runtime.start();
        long own = ProcessHandle.current().pid();
        ProcessIdentity identity = null;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (identity == null && System.nanoTime() < end) {
            Thread.sleep(100);
            if (!listings.isEmpty()) {
                identity = listings.get(0).stream()
                        .filter(process -> process.identity().pid() == own)
                        .map(ProcessSnapshot::identity)
                        .findFirst()
                        .orElse(null);
            }
        }
        line("listing.processes", listings.isEmpty() ? 0 : listings.get(0).size());
        line("listing.system-snapshots", system.size());
        line("listing.own-row", identity != null);
        if (identity == null) {
            runtime.shutdown();
            return;
        }
        List<ControlRequest> requests = new ArrayList<>();
        // In the order that would do the most harm last: a process that survives each can report the next.
        requests.add(ControlRequest.priority(identity, "self", PriorityLevel.values()[0]));
        requests.add(ControlRequest.of(ControlAction.SUSPEND, identity, "self"));
        requests.add(ControlRequest.of(ControlAction.END_TASK, identity, "self"));
        requests.add(ControlRequest.of(ControlAction.FORCE_KILL, identity, "self"));
        for (ControlRequest request : requests) {
            int before = controls.size();
            runtime.requestProcessAction(request);
            await(() -> controls.size() > before);
            ProcessControlResult result = controls.get(before);
            line("safe2." + request.action(), result.result() + " | " + result.text());
            Thread.sleep(1_000);
            line("safe2." + request.action() + ".alive", true);
        }
        int before = limits.size();
        runtime.requestLimit(LimitRequest.of(LimitAction.MANAGE, identity, "self"));
        await(() -> limits.size() > before);
        line("safe2.MANAGE", limits.get(before).outcome().result() + " | " + limits.get(before));
        runtime.shutdown();
        line("runtime.stopped", runtime.awaitStopped(20, TimeUnit.SECONDS));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        if (!condition.getAsBoolean()) {
            throw new IllegalStateException("No answer within 30 s");
        }
    }

    /**
     * Native memory the JVM's heap limit does not cap, each page written with its own value so that the
     * system cannot combine them. A failed step is what a limit looks like: reported, then tried again,
     * so that the removal of the limit shows as further growth.
     */
    private static void grow(Path goFile, long mostMiB) throws InterruptedException {
        line("pid", ProcessHandle.current().pid());
        while (!Files.exists(goFile)) {
            Thread.sleep(100);
        }
        long held = 0;
        boolean failedBefore = false;
        Arena arena = Arena.global();
        while (held < mostMiB * MIB) {
            try {
                MemorySegment step = arena.allocate(STEP_BYTES);
                for (long page = 0; page < STEP_BYTES; page += 4096) {
                    step.set(ValueLayout.JAVA_LONG_UNALIGNED, page, held + page);
                }
                held += STEP_BYTES;
                if (failedBefore) {
                    line("grow.after-failure", held / MIB);
                    failedBefore = false;
                }
                line("grow.held", held / MIB);
            } catch (OutOfMemoryError refused) {
                if (!failedBefore) {
                    line("grow.failed", (held / MIB) + " | " + refused.getMessage());
                }
                failedBefore = true;
                Thread.sleep(500);
            }
            Thread.sleep(50);
        }
        line("grow.end", held / MIB);
        Thread.sleep(Duration.ofMinutes(10));
    }
}
