package pandastic.studio.core.dialog.internal.factory;

import io.papermc.paper.dialog.DialogResponseView;
import net.kyori.adventure.audience.Audience;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.dialog.api.factory.DialogContext;

/**
 * Default {@link DialogContext}, created once per button click by
 * {@link DialogFactoryImp}.
 *
 * <p>Not part of the public API — handlers only ever see the interface.
 */
public final class DialogContextImp implements DialogContext {

    private final Audience audience;
    private final DialogResponseView dialogResponseView;

    /**
     * @param audience           who clicked the button
     * @param dialogResponseView the values held by the dialog's inputs at click time
     */
    public DialogContextImp(@NotNull Audience audience, @NotNull DialogResponseView dialogResponseView) {
        this.audience = audience;
        this.dialogResponseView = dialogResponseView;
    }

    @Override
    public @NotNull Audience getAudience() {
        return this.audience;
    }

    @Override
    public @NotNull DialogResponseView dialogResponseView() {
        return this.dialogResponseView;
    }
}
