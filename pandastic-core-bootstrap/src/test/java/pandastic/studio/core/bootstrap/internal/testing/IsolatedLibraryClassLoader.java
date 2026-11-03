package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.bootstrap.internal.bridge.BridgeTypeContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Reproduces, inside a single test JVM, the one condition that makes this whole library necessary:
 * the same fully-qualified name resolving to two different {@link Class} objects.
 * <p>
 * On a real server each plugin gets its own classloader and each shades its own unrelocated copy of
 * this library, so {@code pandastic.studio.core.service.api.PandasticCore} is a <em>different</em> type in every
 * plugin. A test running on a single classpath cannot observe any of the bugs that follow from
 * that — every cast succeeds, every {@code isInstance} matches, and a bridge that simply returned
 * its argument would pass. This loader restores the real condition.
 * <h2>How</h2>
 * It is child-first for, and <em>only</em> for, names under
 * {@link BridgeTypeContract#LIBRARY_PACKAGE_PREFIX}: those are read as bytes from the parent's
 * classpath and defined here, producing a second, unrelated copy of the type. Everything else —
 * the JDK, Bukkit, Adventure, JUnit — is delegated to the parent, so it stays shared. That split
 * mirrors a Paper server exactly: the server provides the common types, each plugin provides its
 * own copy of this library.
 * <p>
 * Consequently a type loaded through this loader is assignable to nothing the test holds, and the
 * only identity the two copies share is the name — which is precisely the constraint the bridge is
 * built around and the reason it compares type names rather than {@code Class} objects.
 * <h2>Secondary child-first prefixes: modelling a consumer's own API</h2>
 * A consuming plugin does not only shade this library — its <em>own</em> API types (a
 * {@code com.example.market.MarketCache}, say) live in its classloader too, extending its copy of
 * the library's interfaces. To reproduce that, extra prefixes can be nominated as child-first: a
 * fixture under such a prefix is re-defined here just like a library class, so its superinterface
 * chain resolves entirely inside this loader (the consumer interface extends <em>this copy's</em>
 * {@code PandasticService}, exactly as a shaded consumer API would on a real server). Without extra
 * prefixes the fixture would be loaded by the shared parent and could only ever extend the
 * test classpath's library copy, which is the one shape a consumer API never has on the foreign
 * side of a boundary.
 */
public final class IsolatedLibraryClassLoader extends ClassLoader {

    private final ClassLoader byteSource;
    private final String[] extraChildFirstPrefixes;

    /**
     * @param name   diagnostic name, so a failure message says which side a type came from
     * @param parent loader providing the shared types and the class bytes to re-define
     */
    public IsolatedLibraryClassLoader(String name, ClassLoader parent) {
        this(name, parent, new String[0]);
    }

    /**
     * @param name                    diagnostic name, so a failure message says which side a type
     *                                came from
     * @param parent                  loader providing the shared types and the class bytes to
     *                                re-define
     * @param extraChildFirstPrefixes additional package prefixes (each including its trailing dot)
     *                                re-defined child-first, for consumer-API fixtures that must
     *                                live in this loader alongside its library copy
     */
    public IsolatedLibraryClassLoader(String name, ClassLoader parent, String... extraChildFirstPrefixes) {
        super(name, parent);
        this.byteSource = parent;
        this.extraChildFirstPrefixes = extraChildFirstPrefixes.clone();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!isChildFirst(name)) {
            return super.loadClass(name, resolve);
        }

        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                byte[] bytes = readClassBytes(name);
                if (bytes == null) {
                    // Not on the classpath as a resource; let the parent try so the failure message
                    // comes from the normal loading path.
                    return super.loadClass(name, resolve);
                }
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }

    private boolean isChildFirst(String name) {
        if (name.startsWith(BridgeTypeContract.LIBRARY_PACKAGE_PREFIX)) return true;
        for (String prefix : extraChildFirstPrefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private byte[] readClassBytes(String name) {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream in = byteSource.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read class bytes for " + name, e);
        }
    }
}
