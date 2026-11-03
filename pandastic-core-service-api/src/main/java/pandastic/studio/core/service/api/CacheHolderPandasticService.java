package pandastic.studio.core.service.api;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.cache.PandasticCache;

/**
 * A service that owns (or shares) an {@link PandasticCache}.
 *
 * <p>Several services may legitimately return the <em>same</em> cache instance — the profile
 * services do exactly that — so callers must not assume exclusive ownership. Across the
 * classloader bridge the returned cache is a follower-local proxy over the leader's instance,
 * which is what makes the sharing hold between plugins.
 *
 * @param <PandasticServiceType> the service type the cache belongs to
 * @param <CacheType>      the concrete cache contract exposed to callers
 */
public interface CacheHolderPandasticService<PandasticServiceType extends PandasticService, CacheType extends PandasticCache<PandasticServiceType>> extends PandasticService {

    /**
     * The cache backing this service. Stable for the lifetime of the service.
     *
     * @return the cache, possibly shared with sibling services
     */
    @NotNull CacheType getCache();
}