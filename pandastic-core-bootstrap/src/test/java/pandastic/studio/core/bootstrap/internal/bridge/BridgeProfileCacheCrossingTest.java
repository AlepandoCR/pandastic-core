package pandastic.studio.core.bootstrap.internal.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.testing.BridgeProfileCacheSurfaceFixture;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;
import pandastic.studio.core.util.api.data.SkinDto;
import pandastic.studio.core.util.internal.data.SkinDtoImpl;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the profile API's return shapes — {@code Optional<SkinDto>},
 * {@code CompletableFuture<Optional<SkinDto>>} and the shared cache's three maps — cross a real
 * classloader boundary, using the production {@link SkinDto}/{@link SkinDtoImpl} types doubled by
 * an {@link IsolatedLibraryClassLoader}.
 * <p>
 * The Optional cases are regression tests: {@code Optional} is parent-visible, so without a
 * dedicated adapter branch a foreign Optional passed the generic holdability check <em>intact</em>
 * and the foreign element inside only surfaced as a {@link ClassCastException} at the caller's
 * {@code opt.get()}. The map cases are the first real exercise of {@code BridgeAdaptingMap}'s
 * value translation.
 */
class BridgeProfileCacheCrossingTest {

    private ClassLoader senderLoader;

    @BeforeEach
    void createSenderClassLoader() {
        senderLoader = new IsolatedLibraryClassLoader("sender", getClass().getClassLoader());
    }

    @Test
    @DisplayName("a foreign Optional<SkinDto> arrives re-wrapped around the receiving loader's SkinDto")
    void foreignOptionalSkinArrivesAsTheReceivingLoadersType() throws Exception {
        Optional<Object> foreignOptional = Optional.of(foreignSkin("texture-value", "texture-signature"));

        Object received = BridgeValueAdapter.adapt(declaredTypeOf("optionalSkin"), foreignOptional, senderLoader);

        Optional<?> typed = assertInstanceOf(Optional.class, received);
        SkinDto skin = assertInstanceOf(SkinDto.class, typed.orElseThrow(),
                "the element must be assignable to the RECEIVING loader's SkinDto");
        assertSame(getClass().getClassLoader(), skin.getClass().getClassLoader());
        assertEquals("texture-value", skin.value());
        assertEquals("texture-signature", skin.signature());
    }

    @Test
    @DisplayName("an empty Optional passes through as empty")
    void emptyOptionalCrossesAsEmpty() throws Exception {
        Object received = BridgeValueAdapter.adapt(declaredTypeOf("optionalSkin"), Optional.empty(), senderLoader);

        Optional<?> typed = assertInstanceOf(Optional.class, received);
        assertTrue(typed.isEmpty());
    }

    @Test
    @DisplayName("CompletableFuture<Optional<SkinDto>> adapts the element inside the Optional, not just the Optional")
    void futureWrappedOptionalAdaptsTheElementInside() throws Exception {
        CompletableFuture<Optional<Object>> foreignFuture =
                CompletableFuture.completedFuture(Optional.of(foreignSkin("v", "s")));

        Object received = BridgeValueAdapter.adapt(declaredTypeOf("futureOptionalSkin"), foreignFuture, senderLoader);

        CompletableFuture<?> typedFuture = assertInstanceOf(CompletableFuture.class, received);
        Optional<?> payload = assertInstanceOf(Optional.class, typedFuture.join());
        // This is where the bug lived: canHold(Optional.class, foreignOptional) is true, so the
        // Optional used to pass through with the FOREIGN SkinDtoImpl still inside.
        SkinDto skin = assertInstanceOf(SkinDto.class, payload.orElseThrow());
        assertSame(getClass().getClassLoader(), skin.getClass().getClassLoader());
        assertEquals(new SkinDtoImpl("v", "s"), skin);
    }

    @Test
    @DisplayName("the shared skin map is one live map whose values translate in both directions")
    void sharedSkinMapTranslatesValuesBothWays() throws Exception {
        // The backing map belongs to loader A (the sender): a JDK map holding A's SkinDtoImpls.
        Map<Object, Object> leaderBacking = new ConcurrentHashMap<>();
        UUID resolvedByLeader = UUID.randomUUID();
        leaderBacking.put(resolvedByLeader, foreignSkin("leader-value", "leader-signature"));

        Object received = BridgeValueAdapter.adapt(
                declaredTypeOf("uniqueIdentifierToSkinMap"), leaderBacking, senderLoader);
        assertNotSame(leaderBacking, received, "library-typed values force a translating view");
        @SuppressWarnings("unchecked")
        Map<Object, Object> followerView = (Map<Object, Object>) assertInstanceOf(Map.class, received);

        // Follower reads the leader's entry as its OWN SkinDtoImpl with equal components.
        SkinDto read = assertInstanceOf(SkinDto.class, followerView.get(resolvedByLeader));
        assertSame(getClass().getClassLoader(), read.getClass().getClassLoader());
        assertEquals(new SkinDtoImpl("leader-value", "leader-signature"), read);

        // Follower writes its own SkinDtoImpl; the leader's map must end up holding the LEADER's
        // type, equal component by component.
        UUID resolvedByFollower = UUID.randomUUID();
        followerView.put(resolvedByFollower, new SkinDtoImpl("follower-value", "follower-signature"));

        Object landed = leaderBacking.get(resolvedByFollower);
        assertSame(senderLoader, landed.getClass().getClassLoader(),
                "the write must land as the owning loader's own type");
        assertEquals(SkinDtoImpl.class.getName(), landed.getClass().getName());
        assertEquals("follower-value", landed.getClass().getMethod("value").invoke(landed));
        assertEquals("follower-signature", landed.getClass().getMethod("signature").invoke(landed));

        // And a later write by the leader is read back as the follower's type: the view is live.
        UUID resolvedLater = UUID.randomUUID();
        leaderBacking.put(resolvedLater, foreignSkin("later-value", "later-signature"));
        assertEquals(new SkinDtoImpl("later-value", "later-signature"), followerView.get(resolvedLater));
        assertEquals(3, followerView.size(), "the view reflects the backing map's state, not a snapshot");
    }

    @Test
    @DisplayName("the two parent-visible name/UUID maps cross as the same live instance")
    void parentVisibleMapsCrossAsTheSameLiveInstance() throws Exception {
        Map<UUID, String> idToName = new ConcurrentHashMap<>();
        Map<String, UUID> nameToId = new ConcurrentHashMap<>();

        Object idToNameReceived = BridgeValueAdapter.adapt(
                declaredTypeOf("uniqueIdentifierToNameMap"), idToName, senderLoader);
        Object nameToIdReceived = BridgeValueAdapter.adapt(
                declaredTypeOf("nameToUniqueIdentifierMap"), nameToId, senderLoader);

        assertSame(idToName, idToNameReceived, "no library types involved: shared state stays one map");
        assertSame(nameToId, nameToIdReceived, "no library types involved: shared state stays one map");
    }

    /**
     * Constructs the sender loader's own {@code SkinDtoImpl}, which is deliberately not assignable
     * to anything this test holds.
     */
    private Object foreignSkin(String value, String signature) throws Exception {
        Class<?> foreignType = senderLoader.loadClass(SkinDtoImpl.class.getName());
        assertNotSame(SkinDtoImpl.class, foreignType, "the sender must really be another classloader");
        return foreignType.getConstructor(String.class, String.class).newInstance(value, signature);
    }

    private static Type declaredTypeOf(String methodName) throws NoSuchMethodException {
        return BridgeProfileCacheSurfaceFixture.class.getMethod(methodName).getGenericReturnType();
    }
}
