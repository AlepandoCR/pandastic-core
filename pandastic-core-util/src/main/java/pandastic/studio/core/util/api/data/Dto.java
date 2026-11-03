package pandastic.studio.core.util.api.data;

import java.io.Serializable;

/**
 * Marker for an immutable data carrier that is allowed to cross a classloader boundary
 * <b>by value</b>.
 * <p>
 * This library is shaded unrelocated into every consuming plugin, so the same fully-qualified
 * name resolves to a different {@link Class} in every plugin classloader. A leader-owned object
 * can therefore never be handed to a follower directly unless it is either a parent-visible type
 * or an interface (which the bridge wraps in a follower-local proxy). {@code Dto} is the third
 * option: a plain value that the receiving side re-creates as <em>its own</em> copy of the type.
 * <h2>How a {@code Dto} crosses the bridge</h2>
 * <ol>
 *     <li><b>Records (preferred).</b> If the implementation is a {@code record}, the receiving side
 *     reads the foreign instance's record component accessors and invokes its own canonical
 *     constructor. No serialization is involved, nothing is executed on the sending side beyond
 *     public accessors, and component values that are themselves {@code Dto}s recurse.</li>
 *     <li><b>Serialization (compatibility fallback).</b> Non-record implementations are
 *     round-tripped through Java serialization, which is why this interface extends
 *     {@link Serializable}. This path exists for compatibility, not parity, and it has hard
 *     limits: the receiving side installs a strict {@link java.io.ObjectInputFilter} that only
 *     admits this library's own classes plus an allowlist of JDK value types — part of which is
 *     calibrated against the running JDK's internal serialization classes — under hard
 *     depth/reference/size caps. A graph that exceeds a cap, or carries a type outside the
 *     allowlist, is refused outright. Each non-record type is also called out once in the log
 *     when it first takes this path.</li>
 * </ol>
 * <h2>Implementation rules</h2>
 * <ul>
 *     <li>Implement a {@code public record}. Records are the supported first-class citizens of
 *     the bridge: they cross without any serialization, are free of Java-deserialization risk,
 *     are immune to the fallback's caps and JDK-dependent allowlist, and need no
 *     {@code serialVersionUID} bookkeeping. Reserve a non-record implementation for code that
 *     cannot yet be converted, and treat it as a migration state rather than a design
 *     choice.</li>
 *     <li>Keep components to parent-visible value types, library interfaces, library enums or
 *     other {@code Dto}s. Anything else cannot be reconstructed on the far side.</li>
 *     <li>Be immutable. A {@code Dto} is copied across the boundary, so mutations made by one side
 *     are invisible to the other; sharing mutable state is what library <em>interfaces</em> are for.</li>
 *     <li>If you must use a non-record implementation, declare an explicit
 *     {@code serialVersionUID} and keep every field serializable.</li>
 * </ul>
 * There is no registry to update: any type implementing this interface is transferable, and the
 * bridge needs no per-type code.
 */
public interface Dto extends Serializable {
}
