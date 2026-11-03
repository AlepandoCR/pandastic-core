package pandastic.studio.core.dialog.api.factory;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * Supplies the parts of a dialog that come from configuration rather than from code.
 *
 * <p>A controller exposes one of these through a field annotated
 * {@code @DialogConfigProvider}. {@link DialogFactory} re-reads that field on every
 * build, so an implementation backed by a config file — or one whose values contain
 * placeholders — resolves afresh each time the dialog is shown.
 */
public interface DialogConfig {

    /**
     * The dialog's title. Resolved per build, so placeholders are re-rendered.
     *
     * @return the title component
     */
    @NotNull Component title();
}
