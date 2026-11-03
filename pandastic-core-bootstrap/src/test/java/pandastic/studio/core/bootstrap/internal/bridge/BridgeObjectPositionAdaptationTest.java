package pandastic.studio.core.bootstrap.internal.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.testing.BridgeGenericPayloadDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeOpaquePayloadDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeOverloadFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeOverloadFixtureImpl;
import pandastic.studio.core.bootstrap.internal.testing.BridgeSkinDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeUnsafeValueFixture;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;

import java.io.File;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the adapter's rule for positions declared as {@code java.lang.Object}, across two real
 * classloaders.
 * <p>
 * {@code BridgeTypeContract.canHold(Object.class, x)} is trivially true for every {@code x}, so an
 * {@code Object}-typed position — here, a record Dto component — used to pass a <em>foreign</em>
 * library object through by reference: a reference nothing on the receiving side could downcast,
 * failing far from the bridge with a cross-classloader {@link ClassCastException}, and a quiet
 * bypass of the rule that library objects cross only as proxies or as {@code Dto} copies. These
 * tests pin the replacement behaviour — foreign values adapt by their runtime shape or fail at the
 * bridge — and, just as deliberately, the two behaviours that must <b>not</b> change: parent-visible
 * values and receiver-local library objects still pass through untouched.
 */
class BridgeObjectPositionAdaptationTest {

    private ClassLoader senderLoader;

    @BeforeEach
    void createSenderClassLoader() {
        senderLoader = new IsolatedLibraryClassLoader("sender", getClass().getClassLoader());
    }

    @Test
    @DisplayName("a foreign Dto inside an Object component arrives as the RECEIVING loader's type")
    void foreignDtoInObjectComponentArrivesAsTheReceivingLoadersType() throws Exception {
        Object foreignCarrier = foreignCarrier(foreignSkin("texture-value", "texture-signature"));

        Object received = BridgeValueAdapter.adapt(
                BridgeOpaquePayloadDtoFixture.class, foreignCarrier, senderLoader);

        BridgeOpaquePayloadDtoFixture typed =
                assertInstanceOf(BridgeOpaquePayloadDtoFixture.class, received);
        BridgeSkinDtoFixture payload = assertInstanceOf(BridgeSkinDtoFixture.class, typed.payload(),
                "the Object component must arrive as the RECEIVING loader's type, not the sender's");
        assertSame(getClass().getClassLoader(), payload.getClass().getClassLoader());
        assertEquals(new BridgeSkinDtoFixture("texture-value", "texture-signature"), payload);
    }

    @Test
    @DisplayName("a foreign Dto in a top-level Object position adapts with no declared context at all")
    void foreignDtoInATopLevelObjectPositionAdapts() throws Exception {
        Object received = BridgeValueAdapter.adapt(Object.class, foreignSkin("v", "s"), senderLoader);

        BridgeSkinDtoFixture typed = assertInstanceOf(BridgeSkinDtoFixture.class, received,
                "an Object-declared return value must arrive as the RECEIVING loader's type");
        assertEquals(new BridgeSkinDtoFixture("v", "s"), typed);
    }

    @Test
    @DisplayName("a foreign interface-bearing object in an Object component arrives as a working proxy")
    void foreignInterfaceBearingObjectArrivesAsAWorkingProxy() throws Exception {
        Class<?> foreignImplType = senderLoader.loadClass(BridgeOverloadFixtureImpl.class.getName());
        Object foreignCarrier = foreignCarrier(foreignImplType.getConstructor().newInstance());

        Object received = BridgeValueAdapter.adapt(
                BridgeOpaquePayloadDtoFixture.class, foreignCarrier, senderLoader);

        Object payload = assertInstanceOf(BridgeOpaquePayloadDtoFixture.class, received).payload();
        assertTrue(Proxy.isProxyClass(payload.getClass()),
                "a foreign implementation crosses as a proxy, never by reference");
        assertNotSame(foreignImplType, payload.getClass());
        assertFalse(foreignImplType.isInstance(payload),
                "the proxy belongs to the receiver, not to the sender's classloader");
        BridgeOverloadFixture local = assertInstanceOf(BridgeOverloadFixture.class, payload,
                "the proxy implements the RECEIVING loader's interface");
        assertEquals("String:ping", local.describe("ping"), "and calls reach the foreign object");
    }

    @Test
    @DisplayName("a foreign library class that is neither Dto nor interface-bearing fails at the bridge")
    void foreignPlainLibraryClassFailsAtTheBridgeNotAtTheDowncast() throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeUnsafeValueFixture.class.getName());
        Object foreignCarrier = foreignCarrier(
                foreignType.getConstructor(String.class).newInstance("nope"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BridgeValueAdapter.adapt(
                        BridgeOpaquePayloadDtoFixture.class, foreignCarrier, senderLoader),
                "the old behaviour was a silent pass-through and a ClassCastException later");

        assertTrue(failure.getMessage().contains("Bridge contract violation"), failure.getMessage());
        assertTrue(failure.getMessage().contains("java.lang.Object"), failure.getMessage());
    }

    @Test
    @DisplayName("a parent-visible value in an Object component still passes by reference")
    void parentVisibleValueStillPassesByReference() throws Exception {
        File sharedFile = new File("payload.txt");

        Object received = BridgeValueAdapter.adapt(
                BridgeOpaquePayloadDtoFixture.class, foreignCarrier(sharedFile), senderLoader);

        assertSame(sharedFile,
                assertInstanceOf(BridgeOpaquePayloadDtoFixture.class, received).payload(),
                "parent-visible values cross by reference, exactly as before");
    }

    @Test
    @DisplayName("a receiver-local library object in an Object position passes through identically")
    void localLibraryObjectPassesThroughIdentically() throws Exception {
        BridgeOverloadFixtureImpl localImpl = new BridgeOverloadFixtureImpl();
        BridgeSkinDtoFixture localDto = new BridgeSkinDtoFixture("local-value", "local-signature");

        assertSame(localImpl, BridgeValueAdapter.adapt(Object.class, localImpl, senderLoader),
                "no spurious proxying of an object the receiver already holds as its own type");
        assertSame(localDto, BridgeValueAdapter.adapt(Object.class, localDto, senderLoader),
                "no spurious copying of a Dto the receiver already holds as its own type");

        Object received = BridgeValueAdapter.adapt(
                BridgeOpaquePayloadDtoFixture.class, foreignCarrier(localImpl), senderLoader);
        assertSame(localImpl,
                assertInstanceOf(BridgeOpaquePayloadDtoFixture.class, received).payload(),
                "a local object carried inside a foreign Dto is handed back untouched");
    }

    @Test
    @DisplayName("an unbounded type-variable component erases to Object and adapts the same way")
    void unboundedTypeVariableComponentAdaptsLikeObject() throws Exception {
        Class<?> foreignGenericType = senderLoader.loadClass(BridgeGenericPayloadDtoFixture.class.getName());
        Object foreignCarrier = foreignGenericType.getConstructor(Object.class)
                .newInstance(foreignSkin("v", "s"));

        Object received = BridgeValueAdapter.adapt(
                BridgeGenericPayloadDtoFixture.class, foreignCarrier, senderLoader);

        BridgeGenericPayloadDtoFixture<?> typed =
                assertInstanceOf(BridgeGenericPayloadDtoFixture.class, received);
        BridgeSkinDtoFixture payload = assertInstanceOf(BridgeSkinDtoFixture.class, typed.payload(),
                "a type variable erasing to Object is the same untyped position in disguise");
        assertSame(getClass().getClassLoader(), payload.getClass().getClassLoader());
        assertEquals(new BridgeSkinDtoFixture("v", "s"), payload);
    }

    /**
     * Constructs the sender loader's own {@code BridgeSkinDtoFixture}, which is deliberately not
     * assignable to anything this test holds.
     */
    private Object foreignSkin(String value, String signature) throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeSkinDtoFixture.class.getName());
        assertNotSame(BridgeSkinDtoFixture.class, foreignType,
                "the sender must really be another classloader");
        return foreignType.getConstructor(String.class, String.class).newInstance(value, signature);
    }

    /**
     * Constructs the sender loader's own {@code BridgeOpaquePayloadDtoFixture} around
     * {@code payload} — the {@code Object}-typed constructor parameter is parent-visible, so any
     * payload fits, which is exactly the hole under test.
     */
    private Object foreignCarrier(Object payload) throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeOpaquePayloadDtoFixture.class.getName());
        assertNotSame(BridgeOpaquePayloadDtoFixture.class, foreignType,
                "the sender must really be another classloader");
        return foreignType.getConstructor(Object.class).newInstance(payload);
    }
}
