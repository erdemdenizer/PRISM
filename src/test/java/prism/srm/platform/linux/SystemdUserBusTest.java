package prism.srm.platform.linux;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read-only systemd user-bus inspection (milestone 12, S3A, plan Rev 2.2 T-D15/T-D16/T-D24, read part): only
 * read-only methods are sent, every reply is checked before use, every error maps to its status, and every connection
 * and message is released on every path.
 */
class SystemdUserBusTest {

    static final String MANAGER_GROUP = "/user.slice/user-1000.slice/user@1000.service";
    static final String APP_SLICE_PATH = "/org/freedesktop/systemd1/unit/app_2eslice";
    static final String OWN_PATH = "/org/freedesktop/systemd1/unit/app_2dgnome_2dprism_2d42_2escope";
    static final String OWN_SCOPE = "app-gnome-prism-42.scope";
    static final byte[] INVOCATION = {
        0x7c, 0x1f, 0x22, 0x04, 0x5a, 0x6b, 0x7c, 0x0d, 0x1e, 0x2f, 0x30, 0x41, 0x52, 0x63, 0x74, (byte) 0x85
    };
    static final Set<String> READ_ONLY_MEMBERS = Set.of("GetUnit", "GetUnitByPID", "Get");

    private FakeSdBus fake;
    private SystemdUserBus bus;

    @BeforeEach
    void setUp() {
        fake = new FakeSdBus();
        bus = new SystemdUserBus(fake, () -> 4242);
        scriptManager(fake, MANAGER_GROUP);
        scriptUnit(fake, "app.slice", APP_SLICE_PATH, "org.freedesktop.systemd1.Slice", MANAGER_GROUP + "/app.slice");
        fake.answer(
                SystemdUserBus.MANAGER_PATH,
                SystemdUserBus.MANAGER,
                "GetUnitByPID",
                List.of(4242),
                FakeSdBus.Answer.reply("o", FakeSdBus.Item.object(OWN_PATH)));
        scriptUnitProperties(
                fake, OWN_PATH, OWN_SCOPE, "org.freedesktop.systemd1.Scope", MANAGER_GROUP + "/app.slice/" + OWN_SCOPE);
    }

    @AfterEach
    void everythingWasReleased() {
        assertThat(fake.liveMessages()).as("messages left").isZero();
        assertThat(fake.openBuses()).as("connections left").isZero();
        assertThat(fake.closed).isEqualTo(fake.opened);
        assertThat(fake.sent)
                .allSatisfy(
                        call -> assertThat(READ_ONLY_MEMBERS).anyMatch(member -> call.contains("." + member + "[")));
    }

    static void scriptManager(FakeSdBus fake, String controlGroup) {
        String m = SystemdUserBus.MANAGER;
        fake.property(SystemdUserBus.MANAGER_PATH, m, "Version", "s", "255.4-1ubuntu8.18");
        fake.property(SystemdUserBus.MANAGER_PATH, m, "Features", "s", "+PAM +AUDIT +SELINUX");
        fake.property(SystemdUserBus.MANAGER_PATH, m, "SystemState", "s", "running");
        fake.property(SystemdUserBus.MANAGER_PATH, m, "ControlGroup", "s", controlGroup);
        fake.property(SystemdUserBus.MANAGER_PATH, m, "DefaultOOMPolicy", "s", "stop");
    }

    static void scriptUnit(FakeSdBus fake, String name, String path, String kind, String controlGroup) {
        fake.answer(
                SystemdUserBus.MANAGER_PATH,
                SystemdUserBus.MANAGER,
                "GetUnit",
                List.of(name),
                FakeSdBus.Answer.reply("o", FakeSdBus.Item.object(path)));
        scriptUnitProperties(fake, path, name, kind, controlGroup);
    }

    static void scriptUnitProperties(FakeSdBus fake, String path, String id, String kind, String controlGroup) {
        fake.property(path, SystemdUserBus.UNIT, "Id", "s", id);
        fake.property(path, SystemdUserBus.UNIT, "ActiveState", "s", "active");
        fake.property(path, SystemdUserBus.UNIT, "SubState", "s", "running");
        fake.property(path, SystemdUserBus.UNIT, "InvocationID", "ay", INVOCATION);
        fake.property(path, kind, "ControlGroup", "s", controlGroup);
    }

    @Test
    void theManagerIsReadWithReadOnlyCallsAndOneConnection() {
        SystemdUserBus.Reading<SystemdUserBus.ManagerFacts> read = bus.manager();

        assertThat(read.status()).isEqualTo(SystemdUserBus.Status.OK);
        SystemdUserBus.ManagerFacts facts = read.value().orElseThrow();
        assertThat(facts.version()).isEqualTo("255.4-1ubuntu8.18");
        assertThat(facts.systemState()).contains("running");
        assertThat(facts.controlGroup()).isEqualTo(MANAGER_GROUP);
        assertThat(facts.defaultOomPolicy()).contains("stop");
        assertThat(fake.opened).isEqualTo(1);
        assertThat(fake.callTimeouts).containsExactly(SystemdUserBus.CALL_TIMEOUT_USEC);
        assertThat(fake.sent).hasSize(5).allMatch(call -> call.contains("org.freedesktop.DBus.Properties.Get"));
    }

    @Test
    void aSliceIsReadByNameWithItsCgroupAndInvocationId() {
        SystemdUserBus.UnitFacts slice = bus.unit("app.slice").value().orElseThrow();

        assertThat(slice.objectPath()).isEqualTo(APP_SLICE_PATH);
        assertThat(slice.id()).isEqualTo("app.slice");
        assertThat(slice.activeState()).isEqualTo("active");
        assertThat(slice.controlGroup()).contains(MANAGER_GROUP + "/app.slice");
        assertThat(slice.invocationId()).contains("7c1f22045a6b7c0d1e2f304152637485");
        assertThat(fake.sent).anyMatch(call -> call.contains("[org.freedesktop.systemd1.Slice, ControlGroup]"));
    }

    @Test
    void prismsOwnUnitIsAskedForByPrismsOwnPidOnly() {
        SystemdUserBus.UnitFacts own = bus.ownUnit().value().orElseThrow();

        assertThat(own.id()).isEqualTo(OWN_SCOPE);
        assertThat(own.controlGroup()).contains(MANAGER_GROUP + "/app.slice/" + OWN_SCOPE);
        assertThat(fake.sent).anyMatch(call -> call.endsWith("GetUnitByPID[4242]"));
        assertThat(fake.sent).filteredOn(call -> call.contains("GetUnitByPID")).hasSize(1);
    }

    @Test
    void eachBusErrorHasItsStatus() {
        expectError("org.freedesktop.systemd1.NoSuchUnit", -2, SystemdUserBus.Status.NO_SUCH_UNIT);
        expectError("org.freedesktop.DBus.Error.AccessDenied", -13, SystemdUserBus.Status.ACCESS_DENIED);
        expectError(null, -1, SystemdUserBus.Status.ACCESS_DENIED);
        expectError("org.freedesktop.DBus.Error.Timeout", -110, SystemdUserBus.Status.TIMEOUT);
        expectError("org.freedesktop.DBus.Error.NoReply", -110, SystemdUserBus.Status.TIMEOUT);
        expectError(null, -SystemdUserBus.ETIMEDOUT, SystemdUserBus.Status.TIMEOUT);
        expectError("org.freedesktop.DBus.Error.Failed", -5, SystemdUserBus.Status.FAILED);
        expectError("org.freedesktop.DBus.Error.ServiceUnknown", -113, SystemdUserBus.Status.NO_BUS);
        expectError("org.freedesktop.DBus.Error.NameHasNoOwner", -113, SystemdUserBus.Status.NO_BUS);
        expectError("org.freedesktop.DBus.Error.UnknownObject", -6, SystemdUserBus.Status.NO_SUCH_UNIT);
    }

    private void expectError(String name, int code, SystemdUserBus.Status status) {
        fake.answer(
                SystemdUserBus.MANAGER_PATH,
                SystemdUserBus.MANAGER,
                "GetUnit",
                List.of("gone.scope"),
                FakeSdBus.Answer.error(code, name));
        SystemdUserBus.Reading<SystemdUserBus.UnitFacts> read = bus.unit("gone.scope");
        assertThat(read.status()).as(name + " " + code).isEqualTo(status);
        assertThat(read.value()).isEmpty();
        assertThat(fake.liveMessages()).isZero();
    }

    @Test
    void aProcessOutsideTheManagerHasNoUnit() {
        fake.answer(
                SystemdUserBus.MANAGER_PATH,
                SystemdUserBus.MANAGER,
                "GetUnitByPID",
                List.of(4242),
                FakeSdBus.Answer.error(-6, "org.freedesktop.systemd1.NoUnitForPID"));

        assertThat(bus.ownUnit().status()).isEqualTo(SystemdUserBus.Status.NO_UNIT_FOR_PROCESS);
    }

    @Test
    void anOlderManagerWithoutOptionalPropertiesStillGivesItsVersion() {
        fake.answer(
                SystemdUserBus.MANAGER_PATH,
                SystemdUserBus.PROPERTIES,
                "Get",
                List.of(SystemdUserBus.MANAGER, "DefaultOOMPolicy"),
                FakeSdBus.Answer.error(-22, "org.freedesktop.DBus.Error.UnknownProperty"));
        fake.answer(
                SystemdUserBus.MANAGER_PATH,
                SystemdUserBus.PROPERTIES,
                "Get",
                List.of(SystemdUserBus.MANAGER, "Features"),
                FakeSdBus.Answer.error(-22, "org.freedesktop.DBus.Error.InvalidArgs"));

        SystemdUserBus.ManagerFacts facts = bus.manager().value().orElseThrow();

        assertThat(facts.version()).isEqualTo("255.4-1ubuntu8.18");
        assertThat(facts.defaultOomPolicy()).isEmpty();
        assertThat(facts.features()).isEmpty();
    }

    @Test
    void aRequiredPropertyThatFailsFailsTheReading() {
        fake.answer(
                SystemdUserBus.MANAGER_PATH,
                SystemdUserBus.PROPERTIES,
                "Get",
                List.of(SystemdUserBus.MANAGER, "Version"),
                FakeSdBus.Answer.error(-22, "org.freedesktop.DBus.Error.UnknownProperty"));
        assertThat(bus.manager().status()).isEqualTo(SystemdUserBus.Status.FAILED);
        everythingWasReleased();

        setUp();
        fake.answer(
                SystemdUserBus.MANAGER_PATH,
                SystemdUserBus.PROPERTIES,
                "Get",
                List.of(SystemdUserBus.MANAGER, "SystemState"),
                FakeSdBus.Answer.error(-13, "org.freedesktop.DBus.Error.AccessDenied"));
        assertThat(bus.manager().status())
                .as("only a missing property is optional")
                .isEqualTo(SystemdUserBus.Status.ACCESS_DENIED);
    }

    @Test
    void aTimeoutThatCannotBeSetEndsTheInspectionBeforeAnyCall() {
        fake.timeoutCode = -22;

        assertThat(bus.manager().status()).isEqualTo(SystemdUserBus.Status.FAILED);
        assertThat(fake.sent).isEmpty();
    }

    @Test
    void withoutAUserBusNothingIsSent() {
        fake.openCode = -SystemdUserBus.ENOMEDIUM;

        SystemdUserBus.Reading<SystemdUserBus.ManagerFacts> read = bus.manager();

        assertThat(read.status()).isEqualTo(SystemdUserBus.Status.NO_BUS);
        assertThat(read.detail()).contains("ENOMEDIUM");
        assertThat(fake.sent).isEmpty();
    }

    @Test
    void aReplyWithAnotherSignatureIsNotRead() {
        fake.answer(
                SystemdUserBus.MANAGER_PATH,
                SystemdUserBus.MANAGER,
                "GetUnit",
                List.of("app.slice"),
                FakeSdBus.Answer.reply("s", new FakeSdBus.Item('s', "", APP_SLICE_PATH)));

        SystemdUserBus.Reading<SystemdUserBus.UnitFacts> read = bus.unit("app.slice");

        assertThat(read.status()).isEqualTo(SystemdUserBus.Status.MALFORMED_REPLY);
        assertThat(fake.sent).hasSize(1);
    }

    @Test
    void aPropertyOfAnotherTypeIsNotRead() {
        fake.property(SystemdUserBus.MANAGER_PATH, SystemdUserBus.MANAGER, "Version", "u", "255");

        SystemdUserBus.Reading<SystemdUserBus.ManagerFacts> read = bus.manager();

        assertThat(read.status()).isEqualTo(SystemdUserBus.Status.MALFORMED_REPLY);
        assertThat(read.detail()).contains("not a variant of s");
    }

    @Test
    void anInvocationIdOfAnotherLengthIsNotUsed() {
        fake.property(APP_SLICE_PATH, SystemdUserBus.UNIT, "InvocationID", "ay", new byte[15]);

        assertThat(bus.unit("app.slice").status()).isEqualTo(SystemdUserBus.Status.MALFORMED_REPLY);
    }

    @Test
    void anEmptyOrZeroInvocationIdAndAnEmptyCgroupMeanNone() {
        fake.property(APP_SLICE_PATH, SystemdUserBus.UNIT, "InvocationID", "ay", new byte[0]);
        fake.property(APP_SLICE_PATH, "org.freedesktop.systemd1.Slice", "ControlGroup", "s", "");
        SystemdUserBus.UnitFacts empty = bus.unit("app.slice").value().orElseThrow();
        assertThat(empty.invocationId()).isEmpty();
        assertThat(empty.controlGroup()).isEmpty();

        fake.property(APP_SLICE_PATH, SystemdUserBus.UNIT, "InvocationID", "ay", new byte[16]);
        assertThat(bus.unit("app.slice").value().orElseThrow().invocationId()).isEmpty();
    }

    @Test
    void aPathThatIsNotAUnitPathOrACgroupPathIsMalformed() {
        fake.answer(
                SystemdUserBus.MANAGER_PATH,
                SystemdUserBus.MANAGER,
                "GetUnit",
                List.of("app.slice"),
                FakeSdBus.Answer.reply("o", FakeSdBus.Item.object("/org/freedesktop/systemd1")));
        assertThat(bus.unit("app.slice").status()).isEqualTo(SystemdUserBus.Status.MALFORMED_REPLY);
        everythingWasReleased();

        setUp();
        fake.property(APP_SLICE_PATH, "org.freedesktop.systemd1.Slice", "ControlGroup", "s", "user.slice/../etc");
        assertThat(bus.unit("app.slice").status()).isEqualTo(SystemdUserBus.Status.MALFORMED_REPLY);
        everythingWasReleased();

        setUp();
        fake.property(SystemdUserBus.MANAGER_PATH, SystemdUserBus.MANAGER, "ControlGroup", "s", "");
        assertThat(bus.manager().status()).isEqualTo(SystemdUserBus.Status.MALFORMED_REPLY);
    }

    @Test
    void namesPrismDoesNotAskAboutAreRefusedWithoutAConnection() {
        for (String name : List.of(
                "", "app", "x.socket", "../app.slice", "app slice.slice", "a/b.scope", "a".repeat(250) + ".slice")) {
            assertThat(bus.unit(name).status()).as(name).isEqualTo(SystemdUserBus.Status.REFUSED);
        }
        SystemdUserBus noPid = new SystemdUserBus(fake, () -> 0);
        assertThat(noPid.ownUnit().status()).isEqualTo(SystemdUserBus.Status.REFUSED);
        assertThat(fake.opened).isZero();
    }

    @Test
    void anUnscriptedMethodFailsCleanly() {
        Consumer<SystemdUserBus.Reading<?>> failed =
                read -> assertThat(read.status()).isEqualTo(SystemdUserBus.Status.FAILED);
        failed.accept(bus.unit("missing.service"));
    }

    @Test
    void theOnlyMethodsThatCanBeSentAreReadOnly() {
        assertThat(SystemdUserBus.ReadOnlyMethod.values())
                .extracting(method -> method.member)
                .containsExactlyInAnyOrder("GetUnit", "GetUnitByPID", "Get");
        assertThat(SystemdUserBus.CALL_TIMEOUT_USEC).isEqualTo(2_000_000L);
    }
}
