package pandastic.studio.core.service.api;

/**
 * A service whose operations must run on the server's main thread.
 * <p>
 * This is a marker, the counterpart to {@link AsyncPandasticService}: it carries no members because
 * the constraint it expresses is about the <em>caller's</em> thread, not about a resource the
 * service owns. Implementing it documents that every method on the service touches Bukkit state
 * (worlds, entities, inventories) and therefore may not be invoked from a pool thread.
 * <p>
 * Marking a service explicitly matters more than usual here: a follower reaches the service
 * through a reflective proxy, so a wrong-thread call surfaces as an exception thrown inside the
 * <em>leader</em> plugin and gets blamed on the wrong plugin.
 *
 * @see AsyncPandasticService for services that expose a pool to submit work to
 */
public interface SyncPandasticService extends PandasticService {
}
