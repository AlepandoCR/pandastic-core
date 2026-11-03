package pandastic.studio.core.bootstrap.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeContract;
import pandastic.studio.core.bootstrap.api.GeneratedBridgeIds;
import pandastic.studio.core.bootstrap.internal.testing.BridgeFakeServiceManagerFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeHostileServiceManagerFixture;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;
import pandastic.studio.core.service.api.PandasticServiceManager;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The leader slot is not the only thing the anchor publishes, and
 * {@link PandasticLeaderValidator#isTrustedLeader} is not the only place trust is decided.
 * <p>
 * The leader's service manager is cached in its own anchor slot so a follower does not have to
 * fetch it through the bridge on every call. That slot is in the same world-writable
 * {@link System} property as the leader, and whatever comes out of it becomes a reflective
 * invocation target: method names are resolved on its concrete class and invoked through a
 * {@link java.lang.invoke.MethodHandle}. An attacker that writes <em>only</em> that slot leaves a
 * perfectly legitimate leader in place and still receives every service lookup and every
 * {@code registerService} in the JVM.
 * <p>
 * These tests pin the rule that every object read out of the anchor — not just the leader — has to
 * pass the same structural check before anything is called on it.
 */
class PandasticBridgeTargetTrustTest {

    @BeforeEach
    void resetFixtures() {
        BridgeHostileServiceManagerFixture.invoked = false;
    }

    @Test
    @DisplayName("a duck-typed object cannot be bridged onto as the service manager")
    void duckTypingIsNotEnoughToBeTheServiceManager() {
        BridgeHostileServiceManagerFixture hostile = new BridgeHostileServiceManagerFixture();

        assertFalse(PandasticLeaderValidator.isTrustedBridgeTarget(hostile, PandasticServiceManager.class.getName()),
                "having getAllServices()/getService(String) must not make it the service manager");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PandasticBridgeServiceManager(hostile));

        assertTrue(failure.getMessage().contains(PandasticServiceManager.class.getName()), failure.getMessage());
        assertFalse(BridgeHostileServiceManagerFixture.invoked,
                "and nothing may have been invoked on it: the check must be structural");
    }

    @Test
    @DisplayName("the structural check is the only thing standing between the fixture and a live call")
    void withoutTheCheckTheFixtureIsAPerfectlyValidInvocationTarget() {
        // Guards against the check being accidentally load-bearing on something else. Resolution and
        // handle creation succeed against this object: it really does satisfy everything the
        // reflective machinery asks for, so nothing downstream would have stopped the call. That is
        // what makes the trust decision the whole defence rather than one layer of several.
        GeneratedBridgeContract.Spec spec = PandasticBootstrapReflection.loadGeneratedSpec(
                GeneratedBridgeIds.PandasticServiceManager.getAllServices);

        Method resolved = PandasticBootstrapReflection.resolveByGeneratedSpec(
                BridgeHostileServiceManagerFixture.class, spec);
        assertEquals("getAllServices", resolved.getName());
        assertEquals(BridgeHostileServiceManagerFixture.class, resolved.getDeclaringClass(),
                "resolution lands on the hostile object's own method");
        assertNotNull(PandasticBootstrapReflection.unreflectPublic(resolved),
                "and the public-only access gate admits it, because the method really is public");

        assertFalse(BridgeHostileServiceManagerFixture.invoked, "resolution alone must not invoke");
    }

    @Test
    @DisplayName("a service manager from another classloader that really implements the interface is accepted")
    void genuineForeignServiceManagerIsAccepted() throws Exception {
        ClassLoader leaderLoader = new IsolatedLibraryClassLoader("leader", getClass().getClassLoader());
        Class<?> foreignType = leaderLoader.loadClass(BridgeFakeServiceManagerFixture.class.getName());
        assertNotSame(BridgeFakeServiceManagerFixture.class, foreignType,
                "a real leader's service manager always comes from a different classloader");

        Object foreign = foreignType.getConstructor().newInstance();
        assertFalse(foreign instanceof PandasticServiceManager,
                "which is exactly why instanceof cannot be the check");

        assertTrue(PandasticLeaderValidator.isTrustedBridgeTarget(foreign, PandasticServiceManager.class.getName()));
        // The fixture throws from every method, so constructing the bridge proves nothing was called.
        assertDoesNotThrow(() -> new PandasticBridgeServiceManager(foreign));
    }

    @Test
    @DisplayName("a non-library object is never a bridge target, whatever it implements")
    void aNonLibraryObjectIsNeverATarget() {
        assertFalse(PandasticLeaderValidator.isTrustedBridgeTarget("not a service manager",
                PandasticServiceManager.class.getName()));
        assertFalse(PandasticLeaderValidator.isTrustedBridgeTarget(null, PandasticServiceManager.class.getName()));
    }
}
