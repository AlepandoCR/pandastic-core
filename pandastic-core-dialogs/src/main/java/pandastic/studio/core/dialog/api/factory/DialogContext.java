package pandastic.studio.core.dialog.api.factory;

import io.papermc.paper.dialog.DialogResponseView;
import net.kyori.adventure.audience.Audience;
import org.jetbrains.annotations.NotNull;

/**
 * What a {@code @DialogButtonHandler} method receives when its button is clicked.
 *
 * <p>This is the single parameter such a method must declare. It carries who clicked
 * and what they had entered into the dialog's inputs at that moment.
 */
public interface DialogContext {

    /**
     * Who clicked the button.
     *
     * @return the audience that triggered the action
     */
    @NotNull Audience getAudience();

    /**
     * The values held by the dialog's inputs at click time.
     *
     * @return a view over the submitted input values
     */
    @NotNull DialogResponseView dialogResponseView();
}
