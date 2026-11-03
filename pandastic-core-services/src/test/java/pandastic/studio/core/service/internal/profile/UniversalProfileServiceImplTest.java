package pandastic.studio.core.service.internal.profile;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.util.api.data.SkinDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link UniversalProfileServiceImpl}'s resolution strategy with recording fakes and no
 * HTTP anywhere: a cache hit consults nobody, a miss consults Bedrock strictly before Java, a
 * Bedrock hit short-circuits Java, and a Bedrock-prefixed name never reaches Java at all.
 */
class UniversalProfileServiceImplTest {

    private static final String BEDROCK_PREFIX = ".";

    private final Plugin plugin = FakePlugins.named("UniversalTest");
    private final PandasticProfileCacheImpl<Plugin> cache = new PandasticProfileCacheImpl<>();
    private final List<String> callLog = new ArrayList<>();
    private RecordingProfileService bedrock;
    private RecordingProfileService java;
    private UniversalProfileServiceImpl<Plugin> universal;

    @BeforeEach
    void composeTheUniversalService() {
        bedrock = new RecordingProfileService("bedrock", plugin, callLog);
        java = new RecordingProfileService("java", plugin, callLog);
        universal = new UniversalProfileServiceImpl<>(
                plugin, cache, bedrock, java,
                name -> name.startsWith(BEDROCK_PREFIX) && name.length() > BEDROCK_PREFIX.length(),
                ForkJoinPool.commonPool());
    }

    @Test
    @DisplayName("a cache hit completes immediately and consults no delegate")
    void cacheHitConsultsNoDelegate() {
        UUID uniqueIdentifier = UUID.randomUUID();
        cache.rememberName(uniqueIdentifier, "Steve");
        cache.rememberSkin(uniqueIdentifier, SkinDto.of("v", "s"));

        assertEquals(Optional.of("Steve"), universal.getName(uniqueIdentifier).join());
        assertEquals(Optional.of(uniqueIdentifier), universal.getUniqueIdentifier("STEVE").join(),
                "the cache peek must lowercase the name key");
        assertEquals(Optional.of(SkinDto.of("v", "s")), universal.getSkin(uniqueIdentifier).join());

        assertEquals(0, bedrock.lookups(), "cache hits must not reach Bedrock");
        assertEquals(0, java.lookups(), "cache hits must not reach Java");
    }

    @Test
    @DisplayName("on a miss, Bedrock is consulted strictly before Java")
    void missConsultsBedrockBeforeJava() {
        UUID uniqueIdentifier = UUID.randomUUID();
        java.answerName("Steve");

        assertEquals(Optional.of("Steve"), universal.getName(uniqueIdentifier).join());

        assertEquals(List.of("bedrock:getName", "java:getName"), callLog,
                "the documented order is Bedrock first, then Java");
    }

    @Test
    @DisplayName("a Bedrock hit short-circuits: Java is never consulted")
    void bedrockHitShortCircuitsJava() {
        UUID bedrockId = new UUID(0L, 1234L);
        bedrock.answerUniqueIdentifier(bedrockId);

        assertEquals(Optional.of(bedrockId), universal.getUniqueIdentifier("Steve77").join());

        assertEquals(List.of("bedrock:getUniqueIdentifier"), callLog);
        assertEquals(0, java.lookups());
    }

    @Test
    @DisplayName("a Bedrock-prefixed name is resolved through Bedrock only, even on a miss")
    void prefixedNameNeverReachesJava() {
        assertEquals(Optional.empty(), universal.getUniqueIdentifier(".Steve77").join());

        assertEquals(List.of("bedrock:getUniqueIdentifier"), callLog,
                "a prefixed name is Bedrock by construction; Java must not be asked");
    }

    @Test
    @DisplayName("an unprefixed miss falls back to Java, in order, for all three lookups")
    void unprefixedMissFallsBackToJavaInOrder() {
        UUID uniqueIdentifier = UUID.randomUUID();
        java.answerUniqueIdentifier(uniqueIdentifier);
        java.answerSkin(SkinDto.of("v", "s"));

        assertEquals(Optional.of(uniqueIdentifier), universal.getUniqueIdentifier("Steve").join());
        assertEquals(Optional.of(SkinDto.of("v", "s")), universal.getSkin(uniqueIdentifier).join());

        assertEquals(List.of(
                        "bedrock:getUniqueIdentifier", "java:getUniqueIdentifier",
                        "bedrock:getSkin", "java:getSkin"),
                callLog);
    }

    @Test
    @DisplayName("a full miss resolves to empty, never exceptionally")
    void fullMissResolvesEmpty() {
        UUID uniqueIdentifier = UUID.randomUUID();

        assertTrue(universal.getName(uniqueIdentifier).join().isEmpty());
        assertTrue(universal.getUniqueIdentifier("Nobody").join().isEmpty());
        assertTrue(universal.getSkin(uniqueIdentifier).join().isEmpty());
    }
}
