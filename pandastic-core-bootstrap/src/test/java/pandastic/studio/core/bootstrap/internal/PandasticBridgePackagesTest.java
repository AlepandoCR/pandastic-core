package pandastic.studio.core.bootstrap.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trusted-bridge-package registry: prefix validation (the rules that keep the name-based type
 * contract meaningful), segment-boundary matching, and the untrusted-slot read discipline — a
 * poisoned anchor slot must degrade to "no trusted prefixes", never throw into a bridge crossing.
 */
class PandasticBridgePackagesTest {

    @AfterEach
    void dropSlot() {
        PandasticJvmAnchor.anchorMap().remove(PandasticBridgePackages.BRIDGE_PACKAGES_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // blank / shape
            "", "   ", "a..b", ".a.b", "a.b.", "com.exa mple", "com.1bad",
            // too broad: single segment
            "org", "java", "javax", "jdk", "sun", "io", "single",
            // the library's own namespace, its ancestors and its descendants
            "pandastic.studio", "pandastic.studio.core", "pandastic.studio.core.service",
            // platform namespaces and their descendants
            "com.sun", "org.bukkit", "org.bukkit.plugin", "io.papermc", "net.kyori"
    })
    @DisplayName("(v) validation rejects blank, malformed, over-broad and reserved prefixes")
    void validationRejects(String prefix) {
        assertThrows(IllegalArgumentException.class, () -> PandasticBridgePackages.validatePrefix(prefix));
        assertThrows(IllegalArgumentException.class,
                () -> PandasticBridgePackages.register(PandasticJvmAnchor.anchorMap(), "Plugin", prefix),
                "register must apply the same validation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.example", "pandastic.studio.market.api", "com.example.market.api"})
    @DisplayName("well-formed consumer prefixes outside every reserved namespace are accepted")
    void validationAccepts(String prefix) {
        assertDoesNotThrow(() -> PandasticBridgePackages.validatePrefix(prefix));
    }

    @Test
    @DisplayName("matching requires a segment boundary, so a prefix never trusts its near-namesakes")
    void matchingIsSegmentBounded() {
        PandasticBridgePackages.register(PandasticJvmAnchor.anchorMap(), "Plugin", "com.example.market");

        assertTrue(PandasticBridgePackages.isTrustedPrefixOf("com.example.market.MarketCache"));
        assertTrue(PandasticBridgePackages.isTrustedPrefixOf("com.example.market.api.Deep"));
        assertFalse(PandasticBridgePackages.isTrustedPrefixOf("com.example.marketplace.Evil"),
                "a longer package sharing the characters but not the segment is not trusted");
        assertFalse(PandasticBridgePackages.isTrustedPrefixOf("com.example.market"),
                "the prefix itself names a package, not a class under it");
        assertFalse(PandasticBridgePackages.isTrustedPrefixOf("com.example.Other"));
    }

    @Test
    @DisplayName("removeAll drops exactly the named plugin's prefixes and is idempotent")
    void removeAllDropsOnlyThatPlugin() {
        Map<String, Object> anchor = PandasticJvmAnchor.anchorMap();
        PandasticBridgePackages.register(anchor, "PluginA", "com.example.aaa");
        PandasticBridgePackages.register(anchor, "PluginB", "com.example.bbb");

        PandasticBridgePackages.removeAll(anchor, "PluginA");

        assertFalse(PandasticBridgePackages.isTrustedPrefixOf("com.example.aaa.Gone"));
        assertTrue(PandasticBridgePackages.isTrustedPrefixOf("com.example.bbb.Stays"),
                "another plugin's registrations must survive");
        assertDoesNotThrow(() -> PandasticBridgePackages.removeAll(anchor, "PluginA"),
                "removing a plugin that has nothing registered is a no-op");
        assertDoesNotThrow(() -> PandasticBridgePackages.removeAll(anchor, "NeverRegistered"));
    }

    @Test
    @DisplayName("a poisoned slot degrades to 'no trusted prefixes' instead of throwing")
    void poisonedSlotDegradesClosed() {
        Map<String, Object> anchor = PandasticJvmAnchor.anchorMap();

        // Wholesale poison: not a map at all.
        anchor.put(PandasticBridgePackages.BRIDGE_PACKAGES_KEY, "not a map");
        assertFalse(assertDoesNotThrow(() -> PandasticBridgePackages.isTrustedPrefixOf("com.example.X")));
        assertDoesNotThrow(() -> PandasticBridgePackages.removeAll(anchor, "Plugin"));

        // Entry-level poison: a map whose values are not sets, next to a healthy entry.
        ConcurrentHashMap<String, Object> poisoned = new ConcurrentHashMap<>();
        poisoned.put("Evil", 42);
        poisoned.put("Honest", Set.of("com.example.honest"));
        anchor.put(PandasticBridgePackages.BRIDGE_PACKAGES_KEY, poisoned);
        assertFalse(PandasticBridgePackages.isTrustedPrefixOf("com.evil.X"));
        assertTrue(PandasticBridgePackages.isTrustedPrefixOf("com.example.honest.Fine"),
                "a poisoned entry must not take healthy entries down with it");

        // A legitimate registration simply replaces unusable shapes.
        anchor.put(PandasticBridgePackages.BRIDGE_PACKAGES_KEY, "not a map");
        assertDoesNotThrow(() -> PandasticBridgePackages.register(anchor, "Plugin", "com.example.fresh"));
        assertTrue(PandasticBridgePackages.isTrustedPrefixOf("com.example.fresh.Type"));
    }
}
