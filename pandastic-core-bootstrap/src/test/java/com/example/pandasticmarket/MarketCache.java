package com.example.pandasticmarket;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.cache.PandasticCache;

import java.util.Map;

/**
 * A consumer-defined cache interface, deliberately declared <b>outside</b> {@code pandastic.studio.core}.
 * <p>
 * This is the shape the trusted-package feature exists for: a plugin ("PandasticMarket") declares its
 * own {@link PandasticCache} extension in its own namespace, registers the implementation as a shared
 * service, and other plugins want it back <em>as this interface</em>, not as a bare
 * {@code PandasticService} view. In the cross-loader harness this type is loaded through an
 * {@code IsolatedLibraryClassLoader} with {@code com.example.pandasticmarket.} as a secondary
 * child-first prefix, so the foreign side owns its own copy whose superinterfaces are that side's
 * library copy — exactly a consumer API shaded next to its library.
 * <p>
 * The map accessor's element types are parent-visible on purpose: the bridge must hand the backing
 * map across <b>live</b> (no translating view, no copy), which is the consumer-cache guarantee the
 * tests pin with {@code assertSame}.
 */
public interface MarketCache extends PandasticCache<PandasticService> {

    /**
     * The live backing map of this cache. Parent-visible element types, so it crosses by
     * reference and stays genuinely shared.
     */
    @NotNull Map<String, String> marketData();
}
