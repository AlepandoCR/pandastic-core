package fakejdk.util;

/**
 * A class <em>named like</em> a JDK serialization proxy but defined by a test classloader, for
 * pinning the platform-loader enforcement on the bridge filter's discovered-name allowlist.
 * <p>
 * The names in {@code BridgeSerialFilter.DISCOVERED_JDK_SERIAL_FORMS} are observed from the running
 * JDK, not specified, so the filter must not treat a matching name as a credential: it additionally
 * requires the resolved class to have been defined by the bootstrap or platform loader. That check
 * cannot be exercised against a real discovered name — they are all {@code java.*}, and
 * {@link ClassLoader#defineClass} refuses to define {@code java.*} classes in a user loader — so
 * the test injects <em>this</em> class's name as if discovery had found it, then presents the copy
 * a child loader defined. Deliberately not under the library package prefix, so the only filter
 * branch that could admit it is the discovered-name one.
 * <p>
 * It carries no payload and is not even {@link java.io.Serializable}: the filter decides on name
 * and defining loader before any serialization machinery would touch the class.
 */
public final class CollSer {

    /**
     * Creates the lookalike; it has no state.
     */
    public CollSer() {
    }
}
