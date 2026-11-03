package pandastic.studio.core.bootstrap.internal.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.testing.BridgeCyclicDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeGadgetFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeHostileDtoGadgetFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeOversizedDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeSmuggledPayloadDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.ForeignPluginClassLoader;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;
import pandastic.studio.core.util.api.data.Dto;

import java.io.InvalidClassException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adversarial half of {@link BridgeDtoTransferTest}: what a hostile or merely careless sender
 * can push through the {@link Dto} transfer paths.
 * <p>
 * {@code BridgeDtoTransferTest} proves the transfer works. These tests prove it refuses, and
 * refuses <em>before</em> running anything: each one asserts not only that a failure happened but
 * that the sender's code never executed, or that the work was bounded. A guard that reports a
 * violation after the payload has already run is not a guard.
 */
class BridgeDeserializationGuardTest {

    private ClassLoader senderLoader;

    @BeforeEach
    void createSenderClassLoader() {
        senderLoader = new IsolatedLibraryClassLoader("sender", getClass().getClassLoader());
        BridgeGadgetFixture.detonated = false;
        BridgeHostileDtoGadgetFixture.detonated = false;
    }

    @Test
    @DisplayName("a library-prefixed class that is not a Dto is rejected before its readObject runs")
    void theLibraryPrefixIsNotACredential() throws Exception {
        Class<?> foreignCarrier = senderLoader.loadClass(BridgeSmuggledPayloadDtoFixture.class.getName());
        Class<?> foreignGadget = senderLoader.loadClass(BridgeGadgetFixture.class.getName());
        Object foreign = foreignCarrier.getConstructor(Object.class)
                .newInstance(foreignGadget.getConstructor().newInstance());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BridgeValueAdapter.adapt(BridgeSmuggledPayloadDtoFixture.class, foreign, senderLoader));

        assertInstanceOf(InvalidClassException.class, failure.getCause(),
                "the filter must stop the stream");
        assertFalse(BridgeGadgetFixture.detonated,
                "readObject must never run: a name under the library prefix is something any plugin"
                        + " can mint, so it cannot be what admits a class to the stream");
        assertFalse(booleanStaticOf(foreignGadget, "detonated"), "nor on the sender's copy");
    }

    @Test
    @DisplayName("a library-prefixed Dto defined by another plugin's classloader is still rejected")
    void aForeignPluginsDtoIsNotTheReceiversOwnCode() throws Exception {
        // The attacker ships a class that passes every structural test: library prefix, implements
        // Dto, hostile readObject. The receiver can *see* it, because Bukkit lets one plugin
        // classloader resolve another's classes, so resolution succeeds and only the class's origin
        // distinguishes it from the receiver's own code.
        ClassLoader attackerLoader = new IsolatedLibraryClassLoader("attacker", getClass().getClassLoader());
        Class<?> attackerGadget = attackerLoader.loadClass(BridgeHostileDtoGadgetFixture.class.getName());

        ClassLoader receiverLoader = new ForeignPluginClassLoader(
                getClass().getClassLoader(), BridgeHostileDtoGadgetFixture.class.getName(), attackerLoader);
        Class<?> receiverCarrier = receiverLoader.loadClass(BridgeSmuggledPayloadDtoFixture.class.getName());

        Object sent = new BridgeSmuggledPayloadDtoFixture(new BridgeHostileDtoGadgetFixture());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BridgeValueAdapter.adapt(receiverCarrier, sent, getClass().getClassLoader()));

        assertInstanceOf(InvalidClassException.class, failure.getCause());
        assertFalse(booleanStaticOf(attackerGadget, "detonated"),
                "the gadget the receiver would have resolved must not have been instantiated");
        assertFalse(BridgeHostileDtoGadgetFixture.detonated, "nor the sender's copy");
    }

    @Test
    @DisplayName("an oversized Dto is cut off during the write, not measured after it")
    void theByteLimitIsEnforcedWhileWriting() throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeOversizedDtoFixture.class.getName());
        Object foreign = foreignType.getConstructor().newInstance();
        Field counter = foreignType.getField("bytesWritten");
        counter.set(null, 0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BridgeValueAdapter.adapt(BridgeOversizedDtoFixture.class, foreign, senderLoader));

        int written = (int) counter.get(null);
        assertTrue(written <= 4 * (int) BridgeSerialFilter.MAX_BYTES,
                "the sending side's writeObject is the sender's own code, so the byte limit has to"
                        + " stop it as it writes; it got " + written + " bytes in");
        assertTrue(failure.getMessage().contains("byte limit"), failure.getMessage());
    }

    @Test
    @DisplayName("a self-referential record Dto is refused instead of overflowing the stack")
    void cyclicDtoGraphsAreBounded() throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeCyclicDtoFixture.class.getName());

        List<Object> children = new ArrayList<>();
        Object foreign = foreignType.getConstructor(List.class).newInstance(children);
        children.add(foreign);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BridgeValueAdapter.adapt(BridgeCyclicDtoFixture.class, foreign, senderLoader));

        assertTrue(failure.getMessage().contains("nested too deeply"), failure.getMessage());
    }

    @Test
    @DisplayName("a shared-child Dto graph cannot be amplified into exponential work")
    void sharedChildDtoGraphsAreBounded() throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeCyclicDtoFixture.class.getName());

        // Each level holds the level below it twice. Building it is linear; copying it naively is
        // 2^depth, which is how a few hundred bytes of DTO becomes an unbounded amount of work.
        Object level = foreignType.getConstructor(List.class).newInstance(List.of());
        for (int i = 0; i < 40; i++) {
            level = foreignType.getConstructor(List.class).newInstance(List.of(level, level));
        }
        Object deep = level;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BridgeValueAdapter.adapt(BridgeCyclicDtoFixture.class, deep, senderLoader));

        assertTrue(failure.getMessage().contains("nested too deeply")
                        || failure.getMessage().contains("too many objects"),
                failure.getMessage());
    }

    private static boolean booleanStaticOf(Class<?> type, String fieldName) throws ReflectiveOperationException {
        return (boolean) type.getField(fieldName).get(null);
    }
}
