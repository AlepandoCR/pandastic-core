package pandastic.studio.core.dialog.api.factory;

import io.papermc.paper.dialog.Dialog;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.dialog.internal.factory.DialogFactoryImp;

public interface DialogFactory {

    /**
     * Builds a Paper {@link Dialog} from an annotated controller instance.
     */
    @Contract("_ -> new")
    static @NotNull Dialog create(@NotNull Object controller) {
        return DialogFactoryImp.create(controller);
    }

}
