package pandastic.studio.core.service.api.config.io;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.config.GeneratedConfig;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;

/**
 * Instantiates and loads {@link GeneratedConfig} implementations from their class alone.
 * <p>
 * Every class the annotation processor generates has a public no-arg constructor, which is
 * what makes a {@code Class<G>} a complete recipe: this factory reflects the constructor,
 * binds the fresh instance to its file through {@link GeneratedConfig#init(File)}, makes sure
 * the parent directory exists - {@link ConfigFolder} resolution never creates it - and then
 * delegates to the configuration's own load-or-create logic.
 * <p>
 * Reflection failures are translated into {@link IllegalStateException}s naming the offending
 * type, so a hand-written class that forgot the public no-arg constructor fails with a clear
 * message instead of a bare {@code NoSuchMethodException}.
 *
 * @see ConfigLoader
 */
public final class ConfigFactory {

    private ConfigFactory() {
    }

    /**
     * Creates a {@code type} instance bound to {@code file} and loads or creates it on the
     * calling thread.
     * <p>
     * When the file exists its values are read; otherwise the declared defaults are written
     * out, creating the parent directory first when needed. Blocking, so keep it off the
     * server main thread unless start-up is the right moment to block.
     *
     * @param file the file backing the configuration
     * @param type the generated configuration class, which must expose a public no-arg constructor
     * @param <G>  the generated configuration type
     * @return the loaded configuration
     * @throws IllegalStateException if {@code type} cannot be instantiated, the parent
     *                               directory cannot be created, or the file cannot be read or written
     */
    public static <G extends GeneratedConfig> @NotNull G loadOrCreate(
            @NotNull File file,
            @NotNull Class<G> type
    ) {
        G config = bind(file, type);
        config.loadOrCreate();
        return config;
    }

    /**
     * Asynchronous counterpart of {@link #loadOrCreate(File, Class)}.
     * <p>
     * The file work runs on the pool behind {@link GeneratedConfig#loadOrCreateAsync()} - the
     * common {@link java.util.concurrent.ForkJoinPool} for generated classes - never on the
     * calling thread. This method itself never throws: instantiation and directory failures
     * surface through the returned future as well, which is what lets a batch runner such as
     * {@link ConfigLoader} contain them per task.
     *
     * @param file the file backing the configuration
     * @param type the generated configuration class, which must expose a public no-arg constructor
     * @param <G>  the generated configuration type
     * @return a future completing with the loaded configuration, or exceptionally with an
     *         {@link IllegalStateException} describing what went wrong
     */
    public static <G extends GeneratedConfig> @NotNull CompletableFuture<G> loadOrCreateAsync(
            @NotNull File file,
            @NotNull Class<G> type
    ) {
        G config;
        try {
            config = bind(file, type);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return config.loadOrCreateAsync().thenApply(ignored -> config);
    }

    /**
     * Instantiates {@code type}, binds it to {@code file} and ensures the parent directory
     * exists, without performing the actual load.
     *
     * @param file the file backing the configuration
     * @param type the generated configuration class
     * @param <G>  the generated configuration type
     * @return the bound, not yet loaded configuration
     */
    private static <G extends GeneratedConfig> @NotNull  G bind(@NotNull File file, @NotNull Class<G> type) {
        G config = instantiate(type);
        config.init(file);
        ensureParentDirectory(file);
        return config;
    }

    /**
     * Invokes the public no-arg constructor of {@code type}, translating every reflective
     * failure into an {@link IllegalStateException} that names the type.
     *
     * @param type the generated configuration class
     * @param <G>  the generated configuration type
     * @return the fresh instance, still unbound
     */
    private static <G extends GeneratedConfig> @NotNull  G instantiate(@NotNull Class<G> type) {
        Constructor<G> constructor;
        try {
            constructor = type.getConstructor();
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(type.getName() + " has no public no-arg constructor;"
                    + " is it really a class generated from a @Configurable declaration?", exception);
        }
        try {
            return constructor.newInstance();
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("The no-arg constructor of " + type.getName()
                    + " threw an exception", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to instantiate " + type.getName(), exception);
        }
    }

    /**
     * Creates the parent directory of {@code file} when it is missing.
     * <p>
     * The re-check after a failed {@code mkdirs} keeps the method safe against two callers
     * racing to create the same directory.
     *
     * @param file the file whose parent directory must exist
     */
    private static void ensureParentDirectory(@NotNull File file) {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Unable to create directory " + parent);
        }
    }
}
