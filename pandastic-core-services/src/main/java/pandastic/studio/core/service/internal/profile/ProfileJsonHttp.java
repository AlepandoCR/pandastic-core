package pandastic.studio.core.service.internal.profile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one small JSON {@code GET} both remote profile backends speak, with the swallow-to-null
 * contract the services are built on: any non-200 status, malformed body, bad URL or transport
 * failure resolves to {@code null}, never exceptionally. Rate limiting (HTTP 429) and transport
 * failures are logged — silently returning {@code null} for those would make an outage look like
 * a stream of unknown players — while an ordinary miss stays quiet.
 * <p>
 * Requests go through the leader runtime's shared {@link HttpClient} and the response is parsed on
 * the runtime's shared executor, so profile traffic from every plugin uses one connection pool and
 * one set of worker threads.
 */
final class ProfileJsonHttp {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private ProfileJsonHttp() {
    }

    /**
     * Fetches {@code url} and parses the body as a JSON object.
     *
     * @param httpClient the shared HTTP client to send through
     * @param executor   the shared executor the body is parsed on
     * @param url        the absolute URL to fetch
     * @param userAgent  the identifying {@code User-Agent} both backends ask for
     * @param logger     the requesting plugin's logger, for rate-limit and transport reports
     * @return a future resolving to the parsed object, or {@code null} on any failure
     */
    static @NotNull CompletableFuture<@Nullable JsonObject> getJson(@NotNull HttpClient httpClient,
                                                                    @NotNull Executor executor,
                                                                    @NotNull String url,
                                                                    @NotNull String userAgent,
                                                                    @NotNull Logger logger) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
        } catch (IllegalArgumentException malformedUrl) {
            // A URL built from caller input (a gamertag, say) that does not even parse is a miss,
            // not a crash: the services promise their futures never complete exceptionally.
            logger.log(Level.WARNING, "Profile lookup skipped, malformed URL: " + url, malformedUrl);
            return CompletableFuture.completedFuture(null);
        }

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(response -> {
                    if (response.statusCode() == 429) {
                        logger.warning("Profile API rate limit hit: " + url);
                        return null;
                    }
                    if (response.statusCode() != 200) {
                        return null;
                    }
                    try {
                        JsonElement parsed = JsonParser.parseString(response.body());
                        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
                    } catch (RuntimeException malformedBody) {
                        return null;
                    }
                }, executor)
                .exceptionally(transportFailure -> {
                    logger.log(Level.SEVERE, "Profile lookup failed: " + url, transportFailure);
                    return null;
                });
    }

    /**
     * Encodes one path segment of a lookup URL.
     * <p>
     * Gamertags may legitimately contain spaces; {@link URLEncoder} is form encoding, so the
     * {@code +} it produces for a space is corrected to the path-segment escape {@code %20}.
     *
     * @param value the raw segment, typically a player name or gamertag
     * @return the segment, safe to splice into a URL path
     */
    static @NotNull String encodeSegment(@NotNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
