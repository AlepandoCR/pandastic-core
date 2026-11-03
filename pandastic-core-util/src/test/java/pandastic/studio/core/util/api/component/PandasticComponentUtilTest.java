package pandastic.studio.core.util.api.component;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.object.SpriteObjectContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.util.api.data.SkinDto;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The object-component builders: a {@link SkinDto} must land as the {@code textures} profile
 * property, and sprites must carry the atlas they were given — the whole reason the single-arg
 * default-atlas factory is not exposed.
 */
class PandasticComponentUtilTest {

    private static final SkinDto SKIN = SkinDto.of("base64-texture-payload", "mojang-signature");

    @Test
    @DisplayName("a head from a SkinDto carries the skin as the textures profile property")
    void headFromSkin() {
        Component head = PandasticComponentUtil.playerHead(SKIN);

        ObjectComponent object = assertInstanceOf(ObjectComponent.class, head);
        PlayerHeadObjectContents contents =
                assertInstanceOf(PlayerHeadObjectContents.class, object.contents());

        assertEquals(1, contents.profileProperties().size());
        PlayerHeadObjectContents.ProfileProperty property = contents.profileProperties().getFirst();
        assertEquals("textures", property.name());
        assertEquals(SKIN.value(), property.value());
        assertEquals(SKIN.signature(), property.signature());

        assertNull(contents.id(), "no identity was supplied");
        assertNull(contents.name(), "no identity was supplied");
        assertEquals(PlayerHeadObjectContents.DEFAULT_HAT, contents.hat());
    }

    @Test
    @DisplayName("identity and hat flag are attached when supplied")
    void headWithIdentity() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000007b");

        Component head = PandasticComponentUtil.playerHead(id, "Steve", SKIN, false);

        PlayerHeadObjectContents contents = (PlayerHeadObjectContents)
                ((ObjectComponent) head).contents();
        assertEquals(id, contents.id());
        assertEquals("Steve", contents.name());
        assertFalse(contents.hat());
        assertEquals(SKIN.value(), contents.profileProperties().getFirst().value());
    }

    @Test
    @DisplayName("a lookup head carries only the id — rendering is the client's problem")
    void headByLookup() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000001c8");

        PlayerHeadObjectContents contents = (PlayerHeadObjectContents)
                ((ObjectComponent) PandasticComponentUtil.playerHeadByLookup(id)).contents();

        assertEquals(id, contents.id());
        assertTrue(contents.profileProperties().isEmpty());
    }

    @Test
    @DisplayName("sprites keep exactly the atlas they were given")
    void spriteKeepsAtlas() {
        Key atlas = Key.key("minecraft", "gui");
        Key texture = Key.key("minecraft", "icon/trial");

        Component component = PandasticComponentUtil.sprite(atlas, texture);

        SpriteObjectContents contents =
                assertInstanceOf(SpriteObjectContents.class, ((ObjectComponent) component).contents());
        assertEquals(atlas, contents.atlas());
        assertEquals(texture, contents.sprite());
        // The guarded-against default: proves we are not silently on Adventure's fallback atlas.
        assertEquals(SpriteObjectContents.DEFAULT_ATLAS, Key.key("minecraft", "blocks"));
    }
}
