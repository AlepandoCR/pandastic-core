package pandastic.studio.core.bootstrap.internal.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.testing.BridgeLegacyDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeProfileDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeRarityFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeSkinDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeSmuggledPayloadDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeUnsafeValueFixture;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;
import pandastic.studio.core.util.api.data.Dto;

import java.io.File;
import java.io.InvalidClassException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link Dto} transfer <b>between two real classloaders</b>.
 * <p>
 * Every test here loads the fixture type twice — once in the test's own loader, once in an
 * {@link IsolatedLibraryClassLoader} — so the same fully-qualified name yields two distinct
 * {@link Class} objects, exactly as it does for two plugins on a server. Without that, the
 * assertions would be vacuous: a bridge that returned its argument unchanged would pass.
 */
class BridgeDtoTransferTest {

    private ClassLoader senderLoader;

    @BeforeEach
    void createSenderClassLoader() {
        senderLoader = new IsolatedLibraryClassLoader("sender", getClass().getClassLoader());
    }

    @Test
    @DisplayName("the harness really does produce two distinct Class objects per name")
    void theTwoLoadersDisagreeOnTypeIdentity() throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeSkinDtoFixture.class.getName());

        assertEquals(BridgeSkinDtoFixture.class.getName(), foreignType.getName(), "same name");
        assertNotSame(BridgeSkinDtoFixture.class, foreignType, "but a different Class object");
        assertFalse(BridgeSkinDtoFixture.class.isAssignableFrom(foreignType),
                "and nothing the receiving side holds is assignable from it");

        // Dto itself is doubled too, which is why the transfer cannot rely on isAssignableFrom.
        assertNotSame(Dto.class, senderLoader.loadClass(Dto.class.getName()));
        assertTrue(BridgeTypeContract.isDto(foreignType),
                "a foreign Dto must still be recognised, by interface name");
    }

    @Test
    @DisplayName("a record Dto arrives as the receiving loader's type with equal components")
    void recordDtoRoundTripsAsTheReceivingLoadersType() throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeSkinDtoFixture.class.getName());
        Object foreign = foreignType.getConstructor(String.class, String.class)
                .newInstance("texture-value", "texture-signature");

        Object received = BridgeValueAdapter.adapt(BridgeSkinDtoFixture.class, foreign, senderLoader);

        BridgeSkinDtoFixture typed = assertInstanceOf(BridgeSkinDtoFixture.class, received,
                "the value must come back as the RECEIVING loader's type");
        assertSame(getClass().getClassLoader(), typed.getClass().getClassLoader());
        assertNotSame(foreign, received, "it is a copy, not the sender's instance");
        assertEquals("texture-value", typed.value());
        assertEquals("texture-signature", typed.signature());
    }

    @Test
    @DisplayName("a future SkinDto needs no adapter change: nested Dtos and library enums recurse")
    void recordTransferRecursesThroughNestedDtosAndEnums() throws Exception {
        Class<?> foreignProfile = senderLoader.loadClass(BridgeProfileDtoFixture.class.getName());
        Class<?> foreignSkin = senderLoader.loadClass(BridgeSkinDtoFixture.class.getName());
        Class<?> foreignRarity = senderLoader.loadClass(BridgeRarityFixture.class.getName());

        UUID id = UUID.randomUUID();
        Object foreign = foreignProfile
                .getConstructor(UUID.class, foreignSkin, foreignRarity)
                .newInstance(
                        id,
                        foreignSkin.getConstructor(String.class, String.class).newInstance("v", "s"),
                        enumConstant(foreignRarity, BridgeRarityFixture.LEGENDARY.name()));

        Object received = BridgeValueAdapter.adapt(BridgeProfileDtoFixture.class, foreign, senderLoader);

        BridgeProfileDtoFixture typed = assertInstanceOf(BridgeProfileDtoFixture.class, received);
        assertEquals(id, typed.id(), "parent-visible values cross unchanged");
        assertEquals(new BridgeSkinDtoFixture("v", "s"), typed.skin(), "nested Dto re-created locally");
        assertSame(BridgeRarityFixture.LEGENDARY, typed.rarity(),
                "a library enum resolves to the RECEIVING side's own constant");
    }

    @Test
    @DisplayName("a non-record Serializable Dto round-trips through the guarded stream")
    void nonRecordDtoRoundTripsThroughSerialization() throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeLegacyDtoFixture.class.getName());
        assertFalse(foreignType.isRecord(), "this fixture must exercise the serialization fallback");

        Object foreign = foreignType.getConstructor(String.class, int.class, List.class)
                .newInstance("legacy", 7, List.of("alpha", "beta"));

        Object received = BridgeValueAdapter.adapt(BridgeLegacyDtoFixture.class, foreign, senderLoader);

        BridgeLegacyDtoFixture typed = assertInstanceOf(BridgeLegacyDtoFixture.class, received);
        assertSame(getClass().getClassLoader(), typed.getClass().getClassLoader());
        assertEquals(new BridgeLegacyDtoFixture("legacy", 7, List.of("alpha", "beta")), typed);
    }

    @Test
    @DisplayName("the ObjectInputFilter rejects a class outside the allowlist")
    void serializationFilterRejectsNonAllowlistedClasses() throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeSmuggledPayloadDtoFixture.class.getName());
        // java.io.File is Serializable and perfectly ordinary, and is deliberately NOT allowlisted:
        // a Dto has no business carrying one, and "ordinary" is what gadget chains are built from.
        Object foreign = foreignType.getConstructor(Object.class).newInstance(new File("payload.txt"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BridgeValueAdapter.adapt(BridgeSmuggledPayloadDtoFixture.class, foreign, senderLoader));

        assertTrue(failure.getMessage().contains("Refused to deserialize"), failure.getMessage());
        assertInstanceOf(InvalidClassException.class, failure.getCause(),
                "the filter must stop the stream, not the field assignment");
    }

    @Test
    @DisplayName("a Dto whose payload stays inside the allowlist is not rejected")
    void serializationFilterAdmitsAllowlistedPayloads() throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeSmuggledPayloadDtoFixture.class.getName());
        Object foreign = foreignType.getConstructor(Object.class).newInstance("a plain string");

        Object received = BridgeValueAdapter.adapt(BridgeSmuggledPayloadDtoFixture.class, foreign, senderLoader);

        BridgeSmuggledPayloadDtoFixture typed =
                assertInstanceOf(BridgeSmuggledPayloadDtoFixture.class, received);
        assertEquals("a plain string", typed.payload());
    }

    @Test
    @DisplayName("both sides are validated as Dtos before anything is transferred")
    void nonDtoTypesAreRejectedWithTheAdapterStyleFailure() throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeUnsafeValueFixture.class.getName());
        Object foreign = foreignType.getConstructor(String.class).newInstance("nope");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BridgeValueAdapter.adapt(BridgeUnsafeValueFixture.class, foreign, senderLoader));

        assertTrue(failure.getMessage().contains("Bridge contract violation"), failure.getMessage());
    }

    /**
     * Resolves a constant on a foreign copy of an enum, where the local type token is unavailable
     * by definition.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> foreignEnumType, String name) {
        return Enum.valueOf((Class<? extends Enum>) foreignEnumType, name);
    }
}
