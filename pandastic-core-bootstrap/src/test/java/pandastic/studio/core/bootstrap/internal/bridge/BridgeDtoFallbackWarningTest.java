package pandastic.studio.core.bootstrap.internal.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.testing.BridgeFallbackNoticeDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Pins the once-per-type nag that steers {@code Dto} implementers toward records: the first
 * serialization-fallback transfer of a non-record type warns, the second does not, and the
 * transfer itself keeps working both times — the nag is advice, not a behaviour change.
 * <p>
 * The warning text goes through {@code PandasticCoreLog}, whose {@link System.Logger} backend is not
 * reliably capturable here, so the test observes
 * {@link BridgeDtoTransfer#FALLBACK_WARNINGS_EMITTED}, the package-private seam incremented if and
 * only if a warning is actually emitted. The counter is JVM-global and monotonic, which is why this
 * test owns {@link BridgeFallbackNoticeDtoFixture} exclusively: a delta is only attributable to
 * this test if no other test ever sends the same type through the fallback.
 */
class BridgeDtoFallbackWarningTest {

    @Test
    @DisplayName("two fallback transfers of the same non-record Dto type warn exactly once")
    void fallbackWarningFiresOncePerType() throws Exception {
        ClassLoader senderLoader = new IsolatedLibraryClassLoader("sender", getClass().getClassLoader());
        Class<?> foreignType = senderLoader.loadClass(BridgeFallbackNoticeDtoFixture.class.getName());

        long before = BridgeDtoTransfer.FALLBACK_WARNINGS_EMITTED.get();

        Object first = BridgeDtoTransfer.transfer(BridgeFallbackNoticeDtoFixture.class,
                foreignType.getConstructor(String.class).newInstance("first"));
        long afterFirst = BridgeDtoTransfer.FALLBACK_WARNINGS_EMITTED.get();

        Object second = BridgeDtoTransfer.transfer(BridgeFallbackNoticeDtoFixture.class,
                foreignType.getConstructor(String.class).newInstance("second"));
        long afterSecond = BridgeDtoTransfer.FALLBACK_WARNINGS_EMITTED.get();

        assertEquals(before + 1, afterFirst,
                "the first fallback transfer of a type must emit exactly one warning");
        assertEquals(afterFirst, afterSecond,
                "a repeat transfer of the same type must not warn again");

        assertEquals("first",
                assertInstanceOf(BridgeFallbackNoticeDtoFixture.class, first).note(),
                "the nag must not change behaviour: the first transfer still works");
        assertEquals("second",
                assertInstanceOf(BridgeFallbackNoticeDtoFixture.class, second).note(),
                "nor the second");
    }
}
