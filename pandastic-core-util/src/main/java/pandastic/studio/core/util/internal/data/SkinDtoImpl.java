package pandastic.studio.core.util.internal.data;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.util.api.data.SkinDto;

public record SkinDtoImpl(@NotNull String value, @NotNull String signature) implements SkinDto {
}
