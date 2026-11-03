package pandastic.studio.core.service.internal.profile;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.util.api.data.SkinDto;

import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the runtime's shared profile cache: the single name write path keeps both directions
 * in step (with the lowercase key rule), {@code clear()} really empties all three maps, and
 * ownership goes to the first service that adopts it.
 */
class PandasticProfileCacheImplTest {

    private final PandasticProfileCacheImpl<Plugin> cache = new PandasticProfileCacheImpl<>();

    @Test
    @DisplayName("rememberName writes both directions, lowercasing only the name key")
    void rememberNameWritesBothDirections() {
        UUID uniqueIdentifier = UUID.randomUUID();

        cache.rememberName(uniqueIdentifier, "StEvE");

        assertEquals("StEvE", cache.getUniqueIdentifierToNameMap().get(uniqueIdentifier),
                "the UUID direction keeps the player's real capitalisation");
        assertEquals(uniqueIdentifier, cache.getNameToUniqueIdentifierMap().get("steve"),
                "the name direction is keyed lowercase");
        assertEquals(1, cache.getNameToUniqueIdentifierMap().size(),
                "no entry under the original capitalisation");
    }

    @Test
    @DisplayName("rememberSkin writes the skin direction")
    void rememberSkinWritesTheSkinMap() {
        UUID uniqueIdentifier = UUID.randomUUID();
        SkinDto skin = SkinDto.of("texture-value", "texture-signature");

        cache.rememberSkin(uniqueIdentifier, skin);

        assertSame(skin, cache.getUniqueIdentifierToSkinMap().get(uniqueIdentifier));
    }

    @Test
    @DisplayName("clear() empties all three maps")
    void clearEmptiesAllThreeMaps() {
        UUID uniqueIdentifier = UUID.randomUUID();
        cache.rememberName(uniqueIdentifier, "Steve");
        cache.rememberSkin(uniqueIdentifier, SkinDto.of("v", "s"));

        cache.clear();

        assertTrue(cache.getUniqueIdentifierToNameMap().isEmpty());
        assertTrue(cache.getNameToUniqueIdentifierMap().isEmpty());
        assertTrue(cache.getUniqueIdentifierToSkinMap().isEmpty());
    }

    @Test
    @DisplayName("the first service to adopt the cache owns it; later adoptions are ignored")
    void firstAdopterOwnsTheCache() {
        Plugin plugin = FakePlugins.named("CacheOwnerTest");
        ArrayList<String> callLog = new ArrayList<>();
        RecordingProfileService first = new RecordingProfileService("first", plugin, callLog);
        RecordingProfileService second = new RecordingProfileService("second", plugin, callLog);

        assertThrows(IllegalStateException.class, cache::getOwnerService,
                "an unowned cache must fail loudly rather than return null");

        cache.adoptOwner(first);
        cache.adoptOwner(second);

        assertSame(first, cache.getOwnerService());
    }

    @Test
    @DisplayName("the cache reports its stable registry identifier")
    void reportsItsStableServiceName() {
        assertEquals("ProfileCache", cache.getServiceName());
    }
}
