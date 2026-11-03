package pandastic.studio.core.service.internal.profile;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.util.api.data.SkinDto;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-UUID-in, identified-head-out composition on {@link pandastic.studio.core.service.profile.PandasticProfileService}:
 * name and skin both come from the service (shared cache in production), the skin gates the
 * result, and the name is best-effort metadata.
 */
class ProfileHeadComponentTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000abcd");
    private static final SkinDto SKIN = SkinDto.of("payload", "signature");

    private RecordingProfileService service() {
        return new RecordingProfileService("test", FakePlugins.named("HeadPlugin"), new ArrayList<>());
    }

    @Test
    @DisplayName("UUID in — head with id, resolved name and textures property out")
    void fullyIdentifiedHead() {
        RecordingProfileService profiles = service().answerName("Alepando").answerSkin(SKIN);

        Optional<Component> head = profiles.getPlayerHeadComponent(PLAYER).join();

        assertTrue(head.isPresent());
        PlayerHeadObjectContents contents = assertInstanceOf(PlayerHeadObjectContents.class,
                assertInstanceOf(ObjectComponent.class, head.get()).contents());
        assertEquals(PLAYER, contents.id());
        assertEquals("Alepando", contents.name());
        assertEquals(SKIN.value(), contents.profileProperties().getFirst().value());
        assertEquals(SKIN.signature(), contents.profileProperties().getFirst().signature());
    }

    @Test
    @DisplayName("an unresolved name does not block the head")
    void nameIsBestEffort() {
        RecordingProfileService profiles = service().answerSkin(SKIN);

        Optional<Component> head = profiles.getPlayerHeadComponent(PLAYER).join();

        assertTrue(head.isPresent());
        PlayerHeadObjectContents contents =
                (PlayerHeadObjectContents) ((ObjectComponent) head.get()).contents();
        assertEquals(PLAYER, contents.id(), "the id is always known: it is the input");
        assertNull(contents.name());
        assertEquals(SKIN.value(), contents.profileProperties().getFirst().value());
    }

    @Test
    @DisplayName("no skin, no head — the skin is the gate")
    void skinGatesTheResult() {
        RecordingProfileService profiles = service().answerName("Alepando");

        assertTrue(profiles.getPlayerHeadComponent(PLAYER).join().isEmpty());
    }
}
