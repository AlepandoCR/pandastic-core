package pandastic.studio.core.service.api.cache;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.util.api.data.Dto;

/**
 * A cache that is itself a shared service, owned by the service whose data it caches.
 * <p>
 * Caches are the main reason the leader/follower design exists: two plugins asking for the same
 * remote data should hit one cache, not two. Because a cache is an interface it crosses the
 * classloader boundary as a follower-local proxy over the leader's instance, so every plugin
 * observes the same entries.
 * <p>
 * A cache that exposes its backing map through a bridged method gets a live translating view, not
 * a copy: writes from a follower land in the leader's map. Entry types must still obey the bridge
 * rules, meaning interfaces, {@link Dto}s or parent-visible types.
 *
 * @param <PandasticServiceType> the service that owns this cache
 */
public interface PandasticCache<PandasticServiceType extends PandasticService> extends PandasticService {

    /**
     * Drops every entry.
     * <p>
     * This affects every plugin sharing the runtime, so treat it as an administrative operation
     * rather than per-plugin cleanup.
     */
    void clear();

    /**
     * The service this cache stores data for.
     */
    @NotNull PandasticServiceType getOwnerService();
}
