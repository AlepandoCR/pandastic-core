package pandastic.studio.core.util.api.identification;

import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link Describable} that also carries a stable machine identity.
 *
 * <p>The inherited name and description are for display and may change with locale or
 * formatting; the {@link NamespacedKey} is the part safe to persist, compare and use as
 * a map key.
 */
public interface KeyedDescribable extends Describable {

    /**
     * The stable identity of this object.
     *
     * @return the namespaced key
     */
    @NotNull NamespacedKey getNamespacedKey();
}
