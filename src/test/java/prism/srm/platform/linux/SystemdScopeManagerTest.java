package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two state-changing manager calls (milestone 12, S3B, plan Rev 2.2 T-D15, T-D16): their exact message layout, the
 * single descriptor passed as {@code PIDFDs} and never a PID, every answer told apart as a refusal (nothing done) or an
 * unknown outcome, names and properties Prism does not use refused without a call, and every handle released.
 */
class SystemdScopeManagerTest {

    private static final String UNIT = "prism-srm-limit-2071-1791970282990.scope";

    private FakeSdBus bus;
    private SystemdScopeManager manager;

    @BeforeEach
    void setUp() {
        bus = new FakeSdBus();
        manager = new SystemdScopeManager(bus);
        bus.byMember.put(
                "StartTransientUnit",
                FakeSdBus.Answer.reply("o", FakeSdBus.Item.object("/org/freedesktop/systemd1/job/42")));
        bus.byMember.put("SetProperties", FakeSdBus.Answer.reply(""));
    }

    @AfterEach
    void everythingWasReleased() {
        assertThat(bus.liveMessages()).isZero();
        assertThat(bus.openBuses()).isZero();
        assertThat(bus.callTimeouts).allMatch(t -> t == SystemdUserBus.CALL_TIMEOUT_USEC);
    }

    @Test
    void aScopeIsRequestedWithExactlyOnePidfdAndTheAgreedProperties() {
        ScopeManager.Start start = manager.startScope(UNIT, 17, "Prism limit for PID 2071");

        assertThat(start.status()).isEqualTo(ScopeManager.StartStatus.STARTED);
        assertThat(start.detail()).isEqualTo("/org/freedesktop/systemd1/job/42");
        assertThat(bus.sent)
                .singleElement()
                .satisfies(call -> assertThat(call)
                        .startsWith("/org/freedesktop/systemd1 org.freedesktop.systemd1.Manager.StartTransientUnit"));
        List<Object> arguments = bus.sentArguments.get(0);
        assertThat(arguments)
                .containsExactly(
                        UNIT,
                        "fail",
                        "a{(sv)",
                        "r{sv",
                        "PIDFDs",
                        "v{ah",
                        "a{h",
                        "h:17",
                        "}",
                        "}",
                        "}",
                        "r{sv",
                        "Slice",
                        "v{s",
                        "app.slice",
                        "}",
                        "}",
                        "r{sv",
                        "Description",
                        "v{s",
                        "Prism limit for PID 2071",
                        "}",
                        "}",
                        "r{sv",
                        "OOMPolicy",
                        "v{s",
                        "continue",
                        "}",
                        "}",
                        "r{sv",
                        "ManagedOOMPreference",
                        "v{s",
                        "omit",
                        "}",
                        "}",
                        "r{sv",
                        "CollectMode",
                        "v{s",
                        "inactive-or-failed",
                        "}",
                        "}",
                        "}",
                        "a{(sa(sv))",
                        "}");
        assertThat(arguments).noneMatch(a -> a.toString().equals("PIDs"));
        long opened = arguments.stream()
                .filter(a -> a.toString().endsWith("{") || a.toString().contains("{"))
                .count();
        long closed = arguments.stream().filter("}"::equals).count();
        assertThat(opened).as("every container closed").isEqualTo(closed);
    }

    @Test
    void eachStartAnswerIsARefusalOrAnUnknownOutcome() {
        expectStart(-17, "org.freedesktop.systemd1.UnitExists", "exists", ScopeManager.StartStatus.UNIT_EXISTS);
        expectStart(
                -22,
                "org.freedesktop.DBus.Error.InvalidArgs",
                "Unknown assignment PIDFDs",
                ScopeManager.StartStatus.PIDFDS_REFUSED);
        expectStart(-22, "org.freedesktop.DBus.Error.InvalidArgs", "bad Slice", ScopeManager.StartStatus.REFUSED);
        expectStart(-13, "org.freedesktop.DBus.Error.AccessDenied", "no", ScopeManager.StartStatus.ACCESS_DENIED);
        expectStart(-110, "org.freedesktop.DBus.Error.NoReply", "timeout", ScopeManager.StartStatus.UNKNOWN);
        expectStart(-110, "org.freedesktop.DBus.Error.Timeout", "timeout", ScopeManager.StartStatus.UNKNOWN);
        expectStart(-104, null, null, ScopeManager.StartStatus.UNKNOWN);
        // sd_bus_call fills the error for local failures too (sd-bus.c, bus-error.c): only the manager's own errors and
        // the standard refusals mean nothing was done; anything else may have followed a sent request.
        expectStart(-5, "org.freedesktop.DBus.Error.Failed", "boom", ScopeManager.StartStatus.UNKNOWN);
        expectStart(-5, "org.freedesktop.DBus.Error.IOError", "I/O", ScopeManager.StartStatus.UNKNOWN);
        expectStart(-104, "org.freedesktop.DBus.Error.Disconnected", "gone", ScopeManager.StartStatus.UNKNOWN);
        expectStart(-12, "org.freedesktop.DBus.Error.NoMemory", "oom", ScopeManager.StartStatus.UNKNOWN);
        expectStart(
                -16,
                "org.freedesktop.systemd1.TransactionIsDestructive",
                "destructive",
                ScopeManager.StartStatus.REFUSED);
        // The descriptor of a reaped process: the manager refuses (observed on systemd 255.4, T-L10).
        expectStart(
                -3,
                "org.freedesktop.DBus.Error.UnixProcessIdUnknown",
                "Failed to set unit properties: No such process",
                ScopeManager.StartStatus.REFUSED);
        // A manager older than 255 refuses the unknown transient property this way (dbus-unit.c).
        expectStart(
                -22,
                "org.freedesktop.DBus.Error.PropertyReadOnly",
                "Cannot set property PIDFDs, or unknown property.",
                ScopeManager.StartStatus.PIDFDS_REFUSED);
    }

    private void expectStart(int code, String name, String text, ScopeManager.StartStatus status) {
        bus.byMember.put("StartTransientUnit", FakeSdBus.Answer.error(code, name, text));
        assertThat(manager.startScope(UNIT, 17, "Prism limit for PID 2071").status())
                .as(name + " " + text)
                .isEqualTo(status);
    }

    private static final String INV = "0123456789abcdef0123456789abcdef";

    @Test
    void limitsAreSetOnePropertyPerCallAtRuntimeOnTheUnitsInvocation() {
        ScopeManager.LimitSet set = manager.setLimit(UNIT, INV, "CPUQuotaPerSecUSec", 800_000);

        assertThat(set.status()).isEqualTo(ScopeManager.SetStatus.SET);
        assertThat(bus.sent.get(0))
                .as("Unit.SetProperties on the invocation's object path, never the name")
                .startsWith("/org/freedesktop/systemd1/unit/_30123456789abcdef0123456789abcdef"
                        + " org.freedesktop.systemd1.Unit.SetProperties");
        assertThat(bus.sentArguments.get(0))
                .containsExactly("b:true", "a{(sv)", "r{sv", "CPUQuotaPerSecUSec", "v{t", "t:800000", "}", "}", "}");
        assertThat(manager.setLimit(UNIT, INV, "MemoryMax", ScopeManager.INFINITY)
                        .status())
                .isEqualTo(ScopeManager.SetStatus.SET);
        assertThat(bus.sentArguments.get(1)).contains("t:-1");

        bus.byMember.put("SetProperties", FakeSdBus.Answer.error(-6, "org.freedesktop.systemd1.NoUnitForInvocationID"));
        assertThat(manager.setLimit(UNIT, INV, "MemoryHigh", 1L << 28).status())
                .as("the invocation is gone: nothing was changed")
                .isEqualTo(ScopeManager.SetStatus.NO_SUCH_UNIT);
        bus.byMember.put("SetProperties", FakeSdBus.Answer.error(-110, "org.freedesktop.DBus.Error.NoReply"));
        assertThat(manager.setLimit(UNIT, INV, "MemoryHigh", 1L << 28).status())
                .isEqualTo(ScopeManager.SetStatus.UNKNOWN);
        bus.byMember.put("SetProperties", FakeSdBus.Answer.error(-13, "org.freedesktop.DBus.Error.AccessDenied"));
        assertThat(manager.setLimit(UNIT, INV, "MemoryHigh", 1L << 28).status())
                .isEqualTo(ScopeManager.SetStatus.REFUSED);
    }

    /** systemd's bus_label_escape: letters kept, digits kept except first, everything else as _xx. */
    @Test
    void theInvocationPathIsEscapedAsSystemdEscapesIt() {
        assertThat(SystemdScopeManager.invocationPath("86f1083782564429bf3a1a34ede0c182"))
                .isEqualTo("/org/freedesktop/systemd1/unit/_386f1083782564429bf3a1a34ede0c182");
        assertThat(SystemdScopeManager.invocationPath("abcdef0123456789abcdef0123456789"))
                .isEqualTo("/org/freedesktop/systemd1/unit/abcdef0123456789abcdef0123456789");
    }

    @Test
    void requestsPrismNeverMakesAreRefusedWithoutAConnection() {
        assertThat(manager.startScope("app-gnome-firefox-1.scope", 17, "x").status())
                .isEqualTo(ScopeManager.StartStatus.REFUSED);
        assertThat(manager.startScope(UNIT, -1, "x").status()).isEqualTo(ScopeManager.StartStatus.REFUSED);
        assertThat(manager.startScope(UNIT, 17, "line\nbreak").status()).isEqualTo(ScopeManager.StartStatus.REFUSED);
        assertThat(manager.setLimit(UNIT, INV, "Delegate", 1).status()).isEqualTo(ScopeManager.SetStatus.REFUSED);
        assertThat(manager.setLimit("session-2.scope", INV, "MemoryMax", 1).status())
                .isEqualTo(ScopeManager.SetStatus.REFUSED);
        assertThat(manager.setLimit(UNIT, "self", "MemoryMax", 1).status())
                .as("only a 128-bit invocation id is a path Prism sends to")
                .isEqualTo(ScopeManager.SetStatus.REFUSED);
        assertThat(manager.setLimit(UNIT, INV.toUpperCase(java.util.Locale.ROOT), "MemoryMax", 1)
                        .status())
                .isEqualTo(ScopeManager.SetStatus.REFUSED);
        assertThat(bus.opened).isZero();
    }

    @Test
    void withoutABusNothingIsSentAndTheRequestIsARefusal() {
        bus.openCode = -SystemdUserBus.ENOMEDIUM;
        assertThat(manager.startScope(UNIT, 17, "Prism limit for PID 2071").status())
                .isEqualTo(ScopeManager.StartStatus.REFUSED);
        assertThat(bus.sent).isEmpty();
    }
}
