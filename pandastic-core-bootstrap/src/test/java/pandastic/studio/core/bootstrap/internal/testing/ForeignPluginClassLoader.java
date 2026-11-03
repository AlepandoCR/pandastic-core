package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.bootstrap.internal.bridge.BridgeTypeContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Models the one Paper behaviour that turns a package-name allowlist into an attacker-controlled
 * one: <b>a plugin classloader can resolve another plugin's classes</b>.
 * <p>
 * {@link IsolatedLibraryClassLoader} gives each side its own copy of the library, which is what
 * the bridge is built around. It cannot express the attack on a name-based allowlist, because in
 * that harness every library-prefixed name resolves inside the receiver itself — so a class the
 * receiver would never have shipped still looks like one of its own.
 * <p>
 * This loader restores the missing half. It is a receiving side that:
 * <ul>
 *     <li>defines its own copy of every library class, as a real plugin does; <em>except</em></li>
 *     <li>for one nominated class name, which it resolves by delegating to a <em>different</em>
 *     loader — standing in for Bukkit's global, cross-plugin class lookup.</li>
 * </ul>
 * A class arriving through that second route carries the other plugin's classloader, which is the
 * only evidence available that it is not code this plugin chose to ship.
 */
public final class ForeignPluginClassLoader extends ClassLoader {

    private final ClassLoader byteSource;
    private final String foreignName;
    private final ClassLoader foreignOwner;

    /**
     * @param parent       loader providing the shared types and the class bytes to re-define
     * @param foreignName  the one class name resolved through another plugin's loader
     * @param foreignOwner that other plugin's loader
     */
    public ForeignPluginClassLoader(ClassLoader parent, String foreignName, ClassLoader foreignOwner) {
        super("receiver", parent);
        this.byteSource = parent;
        this.foreignName = foreignName;
        this.foreignOwner = foreignOwner;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.equals(foreignName)) {
            // Bukkit's cross-plugin lookup: not ours, but visible to us all the same.
            return foreignOwner.loadClass(name);
        }
        if (!name.startsWith(BridgeTypeContract.LIBRARY_PACKAGE_PREFIX)) {
            return super.loadClass(name, resolve);
        }

        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                byte[] bytes = readClassBytes(name);
                if (bytes == null) return super.loadClass(name, resolve);
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
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
