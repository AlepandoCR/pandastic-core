package pandastic.studio.core.util.api.identification;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * Something that can present itself to a player.
 *
 * <p>Both values are {@link Component}s because they are meant for display: they may
 * carry colour, formatting and translation. Anything that needs a stable machine
 * identity should expose that separately — a rendered name is presentation, not a key.
 *
 * @see KeyedDescribable
 */
public interface Describable {

    /**
     * The display name.
     *
     * @return a component suitable for showing to a player
     */
    @NotNull Component getName();

    /**
     * The display description, typically a line or two of explanatory text.
     *
     * @return a component suitable for showing to a player
     */
    @NotNull Component getDescription();
}
