package pandastic.studio.core.bootstrap.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.bridge.BridgeTypeContract;
import pandastic.studio.core.bootstrap.internal.testing.BridgeFakeLeaderFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeHostileLeaderFixture;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;
import pandastic.studio.core.service.api.PandasticCore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that an object found in the shared anchor is validated before it is ever invoked.
 * <p>
 * The anchor lives in {@link System#getProperties()}, so its contents are untrusted input: anything
 * in the JVM can overwrite the leader slot, and a follower that resolved methods reflectively on
 * whatever it found there would forward every bridged call — and its arguments — to the attacker's
 * object.
 * <p>
 * These tests work on their own map rather than the real anchor, so the JVM-global rendezvous of
 * the surrounding build is left untouched.
 */
class PandasticLeaderTrustTest {

    private Map<String, Object> anchor;

    @BeforeEach
    void freshAnchor() {
        anchor = new ConcurrentHashMap<>();
        BridgeHostileLeaderFixture.invoked = false;
    }

    @Test
    @DisplayName("a duck-typed object in a library package is not a leader")
    void duckTypingIsNotEnoughToBeTrusted() {
        Object hostile = new BridgeHostileLeaderFixture();

        assertTrue(BridgeTypeContract.isLibraryType(hostile.getClass()),
                "the fixture deliberately sits inside the library package prefix");
        assertFalse(PandasticLeaderValidator.isTrustedLeader(hostile),
                "having getServiceManager()/shutdown() must not make it a leader");
        assertTrue(PandasticLeaderValidator.describeRejection(hostile).contains("does not implement"),
                PandasticLeaderValidator.describeRejection(hostile));
    }

    @Test
    @DisplayName("a hostile object in the leader slot is rejected and cleared, never invoked")
    void hostileLeaderIsRejectedAndTheSlotCleared() {
        Object hostile = new BridgeHostileLeaderFixture();
        anchor.put(PandasticBootstrap.KEY_LEADER, hostile);
        anchor.put(PandasticBootstrap.KEY_LEADER_CLASS, hostile.getClass().getName());
        anchor.put(PandasticBootstrap.KEY_PROTOCOL, 2);

        assertNull(PandasticBootstrap.readTrustedLeader(anchor), "the bridge must refuse it");
        assertFalse(BridgeHostileLeaderFixture.invoked, "and must not have called anything on it");
        assertFalse(anchor.containsKey(PandasticBootstrap.KEY_LEADER),
                "the slot is cleared so the caller re-elects instead of retrying the same object");
        assertFalse(anchor.containsKey(PandasticBootstrap.KEY_PROTOCOL));
    }

    @Test
    @DisplayName("an object from another classloader that really implements PandasticCore is accepted")
    void genuineForeignLeaderIsAccepted() throws Exception {
        ClassLoader leaderLoader = new IsolatedLibraryClassLoader("leader", getClass().getClassLoader());
        Class<?> foreignLeaderType = leaderLoader.loadClass(BridgeFakeLeaderFixture.class.getName());
        assertNotSame(BridgeFakeLeaderFixture.class, foreignLeaderType,
                "a real leader always comes from a different classloader");

        Object foreignLeader = foreignLeaderType.getConstructor().newInstance();
        assertFalse(foreignLeader instanceof PandasticCore,
                "which is exactly why instanceof cannot be the check");

        assertTrue(PandasticLeaderValidator.isTrustedLeader(foreignLeader));

        anchor.put(PandasticBootstrap.KEY_LEADER, foreignLeader);
        anchor.put(PandasticBootstrap.KEY_LEADER_CLASS, foreignLeaderType.getName());
        assertSame(foreignLeader, PandasticBootstrap.readTrustedLeader(anchor));
    }

    @Test
    @DisplayName("a leader whose recorded class name does not match is treated as tampering")
    void mismatchedRecordedClassIsRejected() throws Exception {
        ClassLoader leaderLoader = new IsolatedLibraryClassLoader("leader", getClass().getClassLoader());
        Object foreignLeader = leaderLoader.loadClass(BridgeFakeLeaderFixture.class.getName())
                .getConstructor().newInstance();

        anchor.put(PandasticBootstrap.KEY_LEADER, foreignLeader);
        // Publication writes the object and its class name together, under the anchor lock, so a
        // disagreement means the two were written by different parties.
        anchor.put(PandasticBootstrap.KEY_LEADER_CLASS, "pandastic.studio.core.service.internal.SomethingElse");

        assertNull(PandasticBootstrap.readTrustedLeader(anchor));
        assertFalse(anchor.containsKey(PandasticBootstrap.KEY_LEADER));
    }

    @Test
    @DisplayName("an empty leader slot reads as absent, not as a rejection")
    void emptySlotReadsAsNull() {
        assertNull(PandasticBootstrap.readTrustedLeader(anchor));
    }
}
