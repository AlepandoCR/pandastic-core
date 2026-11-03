package pandastic.studio.core.bootstrap.internal.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.testing.BridgeSmuggledPayloadDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.UncheckedIOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the self-calibrating half of {@link BridgeSerialFilter}: the JDK serialization classes
 * it admits are <em>discovered</em> from the running JDK rather than hardcoded, and a discovered
 * name is only honoured for a class the platform actually defined.
 * <p>
 * The first half proves availability — ordinary non-record DTO payloads ({@code List.of},
 * {@code Map.of}, an {@link Instant}, an {@link EnumSet}) round-trip across two real classloaders
 * no matter what the running JDK calls its serialization proxies. The second half proves the
 * widening is safe: because the discovered names are observed rather than specified, the filter
 * must refuse a same-name class defined by anything but the bootstrap or platform loader.
 */
class BridgeSerialFilterDiscoveryTest {

    private ClassLoader senderLoader;

    @BeforeEach
    void createSenderClassLoader() {
        senderLoader = new IsolatedLibraryClassLoader("sender", getClass().getClassLoader());
    }

    @Test
    @DisplayName("probing the running JDK finds a non-empty, platform-defined set of serial forms")
    void discoveryFindsThisJdksSerializationMachinery() throws Exception {
        Set<String> discovered = BridgeSerialFilter.DISCOVERED_JDK_SERIAL_FORMS;
        assertFalse(discovered.isEmpty(), "probing a working JDK must discover something");

        // Quoted in the hardening report; sorted so the output is stable across runs.
        System.out.println("[BridgeSerialFilterDiscoveryTest] discovered JDK serial forms on "
                + System.getProperty("java.vm.name") + " " + Runtime.version() + ": "
                + new TreeSet<>(discovered));

        BridgeSerialFilter filter = BridgeSerialFilter.forReceiver(getClass().getClassLoader());
        for (String name : discovered) {
            Class<?> resolved = Class.forName(name, false, ClassLoader.getPlatformClassLoader());
            ClassLoader definer = resolved.getClassLoader();
            assertTrue(definer == null || definer == ClassLoader.getPlatformClassLoader(),
                    name + " must be platform-defined: it came out of a stream the JDK itself wrote");
            assertEquals(ObjectInputFilter.Status.ALLOWED, filter.checkInput(new StubFilterInfo(resolved)),
                    name + " was discovered on this JDK, so the production filter must admit it");
        }
    }

    @Test
    @DisplayName("List.of, Set.of, Map.of, Instant and EnumSet payloads round-trip across two loaders")
    void ordinaryJdkValuePayloadsCrossThroughTheDiscoveredAllowlist() throws Exception {
        // Both capacity variants matter: the JDK backs one-or-two-element immutable collections
        // and larger ones with different classes (List12 vs ListN, …), and this JDK asks the
        // filter about those concrete classes too, not only about the serialization proxy.
        assertPayloadRoundTrips(List.of("alpha", "beta"));
        assertPayloadRoundTrips(List.of("alpha", "beta", "gamma", "delta"));
        assertPayloadRoundTrips(Set.of("gamma"));
        assertPayloadRoundTrips(Set.of("gamma", "delta", "epsilon"));
        assertPayloadRoundTrips(Map.of("key", "value"));
        assertPayloadRoundTrips(Map.of("k1", "v1", "k2", "v2", "k3", "v3"));
        assertPayloadRoundTrips(Instant.parse("2026-01-02T03:04:05Z"));
        assertPayloadRoundTrips(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
        assertPayloadRoundTrips(EnumSet.range(Character.UnicodeScript.COMMON, Character.UnicodeScript.HAN));
    }

    @Test
    @DisplayName("a discovered name is refused when the class was not defined by the platform loader")
    void discoveredNamesAreOnlyHonouredForPlatformDefinedClasses() throws Exception {
        String stolenName = fakejdk.util.CollSer.class.getName();
        ClassLoader thief = new NameStealingClassLoader(getClass().getClassLoader(), stolenName);
        Class<?> lookalike = thief.loadClass(stolenName);
        assertNotSame(fakejdk.util.CollSer.class, lookalike, "the lookalike must be a second copy");
        assertSame(thief, lookalike.getClassLoader(), "defined by the child loader, not the platform");

        // Simulate a JDK on which discovery found exactly this name. Same branch, two loaders:
        // the platform-defined BitSet (not otherwise allowlisted, not an enum) is admitted, the
        // child-defined lookalike is refused. The only difference is the defining loader, which is
        // precisely the enforcement this test pins.
        BridgeSerialFilter admitting = BridgeSerialFilter.forReceiver(
                getClass().getClassLoader(), Set.of(BitSet.class.getName()));
        assertEquals(ObjectInputFilter.Status.ALLOWED, admitting.checkInput(new StubFilterInfo(BitSet.class)),
                "control: a platform-defined class under a discovered name is admitted");

        BridgeSerialFilter refusing = BridgeSerialFilter.forReceiver(
                getClass().getClassLoader(), Set.of(stolenName));
        assertEquals(ObjectInputFilter.Status.REJECTED, refusing.checkInput(new StubFilterInfo(lookalike)),
                "a matching name is not a credential: the class was defined by a plugin-like loader");
    }

    /**
     * Sends a non-record {@link BridgeSmuggledPayloadDtoFixture} built in the sender loader through
     * the serialization fallback and asserts the payload arrives equal, as the receiving loader's
     * types.
     */
    private void assertPayloadRoundTrips(Object payload) throws Exception {
        Class<?> foreignType = senderLoader.loadClass(BridgeSmuggledPayloadDtoFixture.class.getName());
        assertFalse(foreignType.isRecord(), "the fixture must exercise the serialization fallback");
        Object foreign = foreignType.getConstructor(Object.class).newInstance(payload);

        Object received = BridgeDtoTransfer.transfer(BridgeSmuggledPayloadDtoFixture.class, foreign);

        BridgeSmuggledPayloadDtoFixture typed =
                assertInstanceOf(BridgeSmuggledPayloadDtoFixture.class, received);
        assertSame(getClass().getClassLoader(), typed.getClass().getClassLoader(),
                "the copy must belong to the receiving loader");
        assertEquals(payload, typed.payload(),
                payload.getClass().getName() + " must survive the round-trip intact");
    }

    /**
     * A {@link ObjectInputFilter.FilterInfo} carrying one class and innocuous resource counters, so
     * a test can ask {@link BridgeSerialFilter#checkInput} about a specific {@link Class} object —
     * including one defined by a loader that a real stream in this test JVM could never produce.
     */
    private record StubFilterInfo(Class<?> serialClass) implements ObjectInputFilter.FilterInfo {

        @Override
        public long arrayLength() {
            return -1;
        }

        @Override
        public long depth() {
            return 1;
        }

        @Override
        public long references() {
            return 1;
        }

        @Override
        public long streamBytes() {
            return 64;
        }
    }

    /**
     * Defines exactly one nominated class itself, child-first, from the parent's class bytes —
     * the smallest possible model of "another plugin shipped a class with that name". Everything
     * else delegates to the parent.
     */
    private static final class NameStealingClassLoader extends ClassLoader {

        private final ClassLoader byteSource;
        private final String stolenName;

        NameStealingClassLoader(ClassLoader parent, String stolenName) {
            super("name-stealer", parent);
            this.byteSource = parent;
            this.stolenName = stolenName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(stolenName)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = readClassBytes(name);
                    if (bytes == null) throw new ClassNotFoundException(name);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        private byte[] readClassBytes(String name) {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream in = byteSource.getResourceAsStream(resource)) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read class bytes for " + name, e);
            }
        }
    }
}
