package pandastic.studio.core.service.api;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.util.api.data.Dto;
import pandastic.studio.core.util.api.identification.Describable;

/**
 * A unit of shared functionality owned by the elected leader and reachable from every plugin.
 * <p>
 * A service is the only kind of library object a follower may hold by reference: it is an
 * interface, so the bridge can hand out a follower-local proxy over the leader's instance.
 * Implementations must therefore keep their public surface to interfaces, parent-visible types
 * and {@link Dto}s.
 * <h2>Two names, on purpose</h2>
 * A service carries both a {@link Describable#getName() display name} and a
 * {@link #getServiceName() service name}, and they are not interchangeable:
 * <ul>
 *     <li>{@link Describable#getName()} is an Adventure {@code Component}. It is presentation:
 *     translatable, styled, and free to change between versions. Two services may legitimately
 *     render the same text, so comparing components for identity is meaningless.</li>
 *     <li>{@link #getServiceName()} is a plain, stable {@link String} <em>identifier</em>. It is
 *     what {@link PandasticServiceManager#getService(String)} resolves and what the registry uses as a
 *     uniqueness key. It must survive library versions and must never be localised.</li>
 * </ul>
 * The identifier also has to be a {@code String} for a structural reason: it is read across the
 * classloader boundary, and a {@code String} is parent-visible everywhere, whereas a
 * {@code Component} produced by a foreign copy of Adventure may not be holdable locally.
 */
public interface PandasticService extends Describable {

    /**
     * Stable identifier for this service, unique within a single leader runtime.
     * <p>
     * Use a constant, non-localised, machine-friendly value, typically the service type's simple
     * name or a {@code plugin:service} style token. This is the key
     * {@link PandasticServiceManager#getService(String)} resolves.
     *
     * @return the identifier of this service, never blank
     */
    @NotNull String getServiceName();
}
