package pandastic.studio.core.bootstrap.internal.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.internal.PandasticCoreLog;
import pandastic.studio.core.util.api.data.Dto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allowlist {@link ObjectInputFilter} guarding the only place this library deserializes anything.
 * <h2>Why this file exists</h2>
 * {@link Dto} extends {@link java.io.Serializable}, so a non-record DTO crosses the classloader
 * boundary through a serialization round-trip. Java deserialization of attacker-influenced bytes
 * is a remote-code-execution class of bug: {@code readObject}, {@code readResolve} and
 * {@code finalize} of <em>any</em> class reachable in the stream run before the caller sees a
 * single value, and the classic gadget chains live entirely in ordinary JDK and library types. The
 * bytes here are produced inside the same JVM rather than read off a socket, but the sending side
 * is another plugin's classloader, which this library does not control and must not trust.
 * <h2>The policy</h2>
 * Deny by default. A class is admitted only if it is:
 * <ul>
 *     <li>a primitive, or an array whose element type is admitted; or</li>
 *     <li>a member of {@link #ALLOWED_JDK_CLASSES}, a hand-picked list of immutable JDK value and
 *     container types with no deserialization side effects; or</li>
 *     <li>a member of {@link #DISCOVERED_JDK_SERIAL_FORMS}, the serialization machinery the
 *     running JDK was observed to use for immutable collections, {@code java.time} values and enum
 *     sets — honoured <b>only</b> when the class resolved for the name was defined by the bootstrap
 *     or platform loader, so a matching name minted by a plugin buys nothing; or</li>
 *     <li>a JDK {@code enum}, which deserializes by name through {@code valueOf} and executes no
 *     user code; or</li>
 *     <li>one of this library's own classes — the DTO itself and any nested DTO — which means
 *     <b>all three</b> of: a name under {@link BridgeTypeContract#isLibraryName}, a shape that a
 *     DTO graph legitimately contains ({@link BridgeTypeContract#isDto} or an {@code enum}), and an
 *     origin inside the receiving plugin's own classloader or one of its ancestors.</li>
 * </ul>
 * Everything else is {@link ObjectInputFilter.Status#REJECTED}, including
 * {@code java.lang.invoke.SerializedLambda}, every proxy
 * ({@link BridgeDtoTransfer} additionally refuses to resolve proxy classes at all), and
 * innocuous-looking carriers such as {@code java.io.File} whose only purpose in a stream is to
 * feed a gadget.
 * <h2>Why the JDK serialization proxies are probed, not hardcoded</h2>
 * {@code List.of}/{@code Set.of}/{@code Map.of} results, every {@code java.time} value and every
 * {@code EnumSet} do not serialize as themselves: the JDK substitutes internal proxy classes
 * ({@code java.time.Ser}, {@code java.util.CollSer}, {@code java.util.EnumSet$SerializationProxy}
 * on current JDKs) whose names are implementation details, not API — {@code CollSer} has already
 * moved once, and nothing stops a future JDK moving it again. Hardcoding those names means such a
 * rename silently strands every non-record DTO that carries a list, a timestamp or an enum set: an
 * availability failure that presents as data loss far from its cause. So instead of trusting the
 * names, {@link #DISCOVERED_JDK_SERIAL_FORMS} is calibrated once, at class initialization, by
 * serializing small probe graphs of exactly those shapes and recording every class the running
 * JDK's own deserialization asks a filter about while reading them back — the stream descriptors
 * and, on JDKs that filter them, the {@code readResolve} substitution results.
 * <p>
 * The trade-off is that the discovered set is marginally wider than three blessed names: it admits
 * whatever classes the platform used to serialize the probe graphs. That adds no user-code
 * execution surface, for two reasons. First, the probe bytes are produced by this class from fixed
 * inputs, so everything recorded is definitionally the platform's own serialization machinery for
 * value and immutable-collection types. Second, the property is <em>enforced</em> rather than
 * assumed: a discovered name is only honoured when the class the stream resolved for it was defined
 * by the bootstrap or platform loader — the one origin no plugin can forge, since user-defined
 * loaders cannot define platform classes. If probing itself fails, the filter falls back to
 * {@link #FALLBACK_JDK_SERIAL_FORMS}, the previously hardcoded names, rather than letting a probe
 * problem break class initialization.
 * <h2>Why a library name is not a credential</h2>
 * The package prefix identifies this library's <em>code</em>, not its <em>author</em>. Any plugin
 * can ship a class named {@code pandastic.studio.core.anything}, and a Bukkit plugin classloader resolves
 * other plugins' classes, so the receiving side will happily find it. Admitting a class on the
 * strength of its name therefore admits attacker-authored bytecode whose {@code readObject} runs
 * inside the receiving plugin before it sees a single value. Two further conditions close that:
 * <ul>
 *     <li><b>shape</b> — a DTO graph contains DTOs and enums, so a {@code Serializable} library
 *     class that is neither has no business in the stream. This is cheap for an attacker to
 *     satisfy (one {@code implements Dto}), which is why it is not the only condition;</li>
 *     <li><b>origin</b> — the class must have been defined by the receiving plugin's own
 *     classloader or an ancestor of it. That is the one property a name cannot forge: code the
 *     receiving plugin shipped is code it chose, and trusting it is tautological, whereas a class
 *     arriving from a <em>different</em> plugin's loader is exactly the thing being defended
 *     against.</li>
 * </ul>
 * Four hard resource limits are enforced on top of the allowlist, so a malformed or hostile stream
 * cannot turn into an out-of-memory or a quadratic blow-up before any class check happens.
 * {@link BridgeDtoTransfer} enforces the byte limit on the <em>write</em> side too, because the
 * sending side's {@code writeObject} is the sending side's own code and the JDK has no equivalent
 * filter for serialization.
 * <h2>Known limitation</h2>
 * The JDK resolves a stream class <em>before</em> handing it to the filter, so a rejected class is
 * loaded (with {@code initialize=false}, so no static initialiser runs) but never instantiated.
 * That ordering is the platform's, not this library's; it is why the filter is paired with a
 * resolver that only ever looks classes up in the receiving plugin's own classloader, and why the
 * origin check above is stated in terms of the loader that actually defined the class.
 */
final class BridgeSerialFilter implements ObjectInputFilter {

    /**
     * Maximum nesting depth. A DTO graph is a handful of levels deep; anything deeper is either a
     * bug or an attempt to exhaust the stack.
     */
    static final long MAX_DEPTH = 16;

    /**
     * Maximum number of object references in the stream.
     */
    static final long MAX_REFERENCES = 512;

    /**
     * Maximum stream size. DTOs are small values; 64 KiB is already generous.
     */
    static final long MAX_BYTES = 64L * 1024L;

    /**
     * Maximum length of any single array in the stream.
     */
    static final long MAX_ARRAY_LENGTH = 1024;

    /**
     * Immutable JDK value and container types a DTO may legitimately be built out of, listed by
     * name.
     * <p>
     * These are the types that appear in a stream as themselves — either as stream descriptors or,
     * on JDKs that filter {@code readResolve} substitution results, as the concrete classes a
     * proxy resolves to. The JDK-internal serialization <em>proxies</em> that stand in for
     * {@code List.of}-style collections, {@code java.time} values and enum sets are deliberately
     * absent, because their names are not stable API — they live in
     * {@link #DISCOVERED_JDK_SERIAL_FORMS}, calibrated against the running JDK instead. The
     * {@code java.util.Collections$…} and {@code java.util.ImmutableCollections$…} entries are
     * internal names too, but they are the actual runtime classes of containers whose names have
     * held still while the proxies moved; if one ever moves, the probe-based discovery below
     * covers it automatically and this backstop is the only thing to update.
     */
    private static final Set<String> ALLOWED_JDK_CLASSES = Set.of(
            // Core values.
            "java.lang.Object",
            "java.lang.String",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Character",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Number",
            "java.lang.Enum",
            "java.lang.Record",
            "java.math.BigInteger",
            "java.math.BigDecimal",
            "java.util.UUID",
            "java.util.Date",
            "java.net.URI",
            // java.time values. These serialize through a proxy (see the class comment), but the
            // public names stay allowlisted as a backstop in case a JDK ever writes them directly.
            "java.time.Instant",
            "java.time.Duration",
            "java.time.LocalDate",
            "java.time.LocalTime",
            "java.time.LocalDateTime",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime",
            "java.time.ZoneOffset",
            "java.time.ZoneRegion",
            "java.time.Period",
            "java.time.Year",
            "java.time.YearMonth",
            "java.time.MonthDay",
            // Collections that serialize as themselves.
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.EnumMap",
            "java.util.EnumSet",
            "java.util.Arrays$ArrayList",
            // Concrete classes behind List.of/Set.of/Map.of and EnumSet. They never appear as
            // stream descriptors — a serialization proxy carries them — but JDKs that filter
            // readResolve substitution results (this one does; see the discovery test's printout)
            // ask the filter about them when the proxy resolves. Probing discovers them too; these
            // entries are the backstop for a probe failure, and their names have been stable far
            // longer than the proxies'.
            "java.util.ImmutableCollections$List12",
            "java.util.ImmutableCollections$ListN",
            "java.util.ImmutableCollections$Set12",
            "java.util.ImmutableCollections$SetN",
            "java.util.ImmutableCollections$Map1",
            "java.util.ImmutableCollections$MapN",
            "java.util.RegularEnumSet",
            "java.util.JumboEnumSet",
            "java.util.Collections$EmptyList",
            "java.util.Collections$EmptySet",
            "java.util.Collections$EmptyMap",
            "java.util.Collections$SingletonList",
            "java.util.Collections$SingletonSet",
            "java.util.Collections$SingletonMap",
            "java.util.Collections$UnmodifiableCollection",
            "java.util.Collections$UnmodifiableList",
            "java.util.Collections$UnmodifiableRandomAccessList",
            "java.util.Collections$UnmodifiableSet",
            "java.util.Collections$UnmodifiableSortedSet",
            "java.util.Collections$UnmodifiableMap",
            "java.util.Collections$UnmodifiableSortedMap"
    );

    /**
     * The JDK-internal serialization proxy names as hardcoded before probing existed, used only
     * when {@link #discoverJdkSerialForms()} fails.
     * <p>
     * On the JDKs this library has run on, {@code java.time.Ser} carries every {@code java.time}
     * value, {@code java.util.CollSer} (formerly
     * {@code java.util.ImmutableCollections$CollSer}) carries {@code List.of}/{@code Set.of}/
     * {@code Map.of} collections, and {@code java.util.EnumSet$SerializationProxy} carries enum
     * sets. A fallback can be stale — that is the whole reason probing exists — but a stale
     * allowlist only ever fails <em>closed</em>: a renamed proxy is rejected, never a hostile class
     * admitted.
     */
    private static final Set<String> FALLBACK_JDK_SERIAL_FORMS = Set.of(
            "java.time.Ser",
            "java.util.CollSer",
            "java.util.ImmutableCollections$CollSer",
            "java.util.EnumSet$SerializationProxy"
    );

    /**
     * Every class name the running JDK writes into the serial streams of the probe graphs in
     * {@link #discoverJdkSerialForms()} — the self-calibrated replacement for hardcoding
     * JDK-internal proxy names.
     * <p>
     * A name from this set is only honoured for a class defined by the bootstrap or platform
     * loader (see {@link #definedByPlatform(Class)}); package-private so tests can print and pin
     * what discovery found on the JDK they run on.
     */
    static final Set<String> DISCOVERED_JDK_SERIAL_FORMS;

    static {
        Set<String> discovered;
        try {
            discovered = discoverJdkSerialForms();
        } catch (Throwable failure) {
            // Never let a probe failure break class initialization: a filter that cannot calibrate
            // must still filter. The hardcoded names only fail closed (see their comment).
            PandasticCoreLog.warn("Could not probe the running JDK for the classes it uses to serialize"
                    + " immutable collections, java.time values and EnumSets; falling back to the"
                    + " hardcoded proxy names " + FALLBACK_JDK_SERIAL_FORMS + ". If this JDK has"
                    + " renamed those internals, non-record Dtos carrying such values will be"
                    + " rejected at the bridge; record Dtos are unaffected.", failure);
            discovered = FALLBACK_JDK_SERIAL_FORMS;
        }
        DISCOVERED_JDK_SERIAL_FORMS = discovered;
    }

    /**
     * Serializes one probe graph per serial shape the bridge supports — {@code List.of},
     * {@code Set.of}, {@code Map.of}, a {@code java.time} value, an {@code EnumSet} and an
     * unmodifiable-wrapper collection — then deserializes each through a recording
     * {@link ObjectInputFilter} that allows everything and collects every {@code serialClass()}
     * it is shown.
     * <p>
     * The result is the exact set of class names <em>this</em> runtime uses for those graphs, so a
     * JDK that renames {@code CollSer} again recalibrates the filter instead of breaking it.
     * Recording through a real deserialization rather than by parsing stream bytes keeps this
     * correct by construction: the names collected are precisely the names the production filter
     * will later be asked about, delivered by the same machinery — which, on this JDK, includes
     * the concrete classes {@code readResolve} substitutes for the proxies ({@code List12},
     * {@code RegularEnumSet}, {@code Instant}, …), not just the proxies themselves. Allowing
     * everything is safe here because the bytes being read were serialized three lines up from
     * compile-time constants — there is no foreign input on this path.
     *
     * @return the discovered names, including the probe values' own classes (which the static
     *         allowlist or the enum rule would admit anyway); never empty on a working JDK
     * @throws IOException            if a probe fails to serialize or deserialize
     * @throws ClassNotFoundException if a probe's stream names a class that cannot be resolved,
     *                                which cannot happen for bytes written in the same JVM
     */
    private static @NotNull Set<String> discoverJdkSerialForms() throws IOException, ClassNotFoundException {
        // Both capacity variants of each immutable collection are probed, because the JDK backs
        // one-or-two-element collections and larger ones with different classes (List12 vs ListN
        // and so on), and an EnumSet over an enum with more than 64 constants with a jumbo
        // variant; all of those surface as separate filter callbacks on JDKs that filter
        // readResolve results. Character.UnicodeScript is the stock java.base enum with well over
        // 64 constants.
        List<Object> probes = List.of(
                List.of("x"),
                List.of("x", "y", "z"),
                Set.of("x"),
                Set.of("x", "y", "z"),
                Map.of("k", "v"),
                Map.of("k1", "v1", "k2", "v2"),
                Instant.EPOCH,
                EnumSet.of(DayOfWeek.MONDAY),
                EnumSet.allOf(Character.UnicodeScript.class),
                Collections.unmodifiableList(new ArrayList<>()));

        Set<String> names = new HashSet<>();
        for (Object probe : probes) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
                out.writeObject(probe);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                in.setObjectInputFilter(info -> {
                    Class<?> streamClass = info.serialClass();
                    if (streamClass != null) {
                        Class<?> element = streamClass;
                        while (element.isArray()) {
                            element = element.getComponentType();
                        }
                        if (!element.isPrimitive()) names.add(element.getName());
                    }
                    return Status.ALLOWED;
                });
                in.readObject();
            }
        }
        return Set.copyOf(names);
    }

    /**
     * Classloader of the plugin the stream is being read into. Every library class in the stream
     * has to have been defined by it or by one of its ancestors.
     */
    private final @Nullable ClassLoader receiverLoader;

    /**
     * Names honored as the JDK's own serialization machinery, subject to
     * {@link #definedByPlatform(Class)}. In production this is always
     * {@link #DISCOVERED_JDK_SERIAL_FORMS}; tests inject other names to pin the loader check.
     */
    private final @NotNull Set<String> jdkSerialFormNames;

    private BridgeSerialFilter(@Nullable ClassLoader receiverLoader, @NotNull Set<String> jdkSerialFormNames) {
        this.receiverLoader = receiverLoader;
        this.jdkSerialFormNames = jdkSerialFormNames;
    }

    /**
     * A filter for a stream being read into {@code receiverLoader}.
     * <p>
     * The filter is per-receiver rather than a singleton because the origin check needs to know
     * which plugin is receiving: "defined by us" is the only statement about a library-named class
     * that an attacker cannot arrange.
     *
     * @param receiverLoader the receiving plugin's classloader, {@code null} for the bootstrap
     *                       loader
     */
    static @NotNull BridgeSerialFilter forReceiver(@Nullable ClassLoader receiverLoader) {
        return new BridgeSerialFilter(receiverLoader, DISCOVERED_JDK_SERIAL_FORMS);
    }

    /**
     * Test seam: a filter whose discovered-name set is {@code jdkSerialFormNames} instead of
     * {@link #DISCOVERED_JDK_SERIAL_FORMS}.
     * <p>
     * The platform-loader enforcement on discovered names cannot be exercised with the real set:
     * every genuinely discovered name is {@code java.*}, and {@link ClassLoader#defineClass}
     * refuses to define {@code java.*} classes in a user loader, so a hostile same-name class can
     * only be simulated under an injected name. Production code must use
     * {@link #forReceiver(ClassLoader)}.
     *
     * @param receiverLoader     the receiving plugin's classloader, {@code null} for the bootstrap
     *                           loader
     * @param jdkSerialFormNames the names to treat as discovered JDK serialization machinery
     */
    static @NotNull BridgeSerialFilter forReceiver(@Nullable ClassLoader receiverLoader,
                                                   @NotNull Set<String> jdkSerialFormNames) {
        return new BridgeSerialFilter(receiverLoader, Set.copyOf(jdkSerialFormNames));
    }

    @Override
    public Status checkInput(@NotNull FilterInfo info) {
        if (info.depth() > MAX_DEPTH
                || info.references() > MAX_REFERENCES
                || info.streamBytes() > MAX_BYTES
                || (info.arrayLength() >= 0 && info.arrayLength() > MAX_ARRAY_LENGTH)) {
            PandasticCoreLog.warn("Rejected a Dto stream that exceeded the bridge deserialization limits"
                    + " (depth=" + info.depth() + ", refs=" + info.references()
                    + ", bytes=" + info.streamBytes() + ", arrayLength=" + info.arrayLength() + ").");
            return Status.REJECTED;
        }

        Class<?> serialClass = info.serialClass();
        // A null serialClass means this callback only carries the resource counters checked above.
        if (serialClass == null) return Status.UNDECIDED;

        Class<?> element = serialClass;
        while (element.isArray()) {
            element = element.getComponentType();
        }
        if (element.isPrimitive()) return Status.ALLOWED;

        String name = element.getName();
        if (ALLOWED_JDK_CLASSES.contains(name)) return Status.ALLOWED;
        if (jdkSerialFormNames.contains(name)) {
            if (definedByPlatform(element)) return Status.ALLOWED;
            PandasticCoreLog.warn("Rejected " + name + " while transferring a Dto across the bridge: its"
                    + " name matches a JDK serialization class discovered by probing, but it was"
                    + " defined by " + element.getClassLoader() + " rather than by the bootstrap or"
                    + " platform loader. A discovered name is only evidence when the platform"
                    + " defined the class; this one is other code wearing a JDK name.");
            return Status.REJECTED;
        }
        // JDK enums resolve by name through valueOf and run no user code.
        if (isEnumType(element) && name.startsWith("java.")) return Status.ALLOWED;

        if (BridgeTypeContract.isLibraryName(name)) {
            if (!isDtoOrEnum(element)) {
                PandasticCoreLog.warn("Rejected " + name + " while transferring a Dto across the bridge: it"
                        + " is Serializable and sits under " + BridgeTypeContract.LIBRARY_PACKAGE_PREFIX
                        + "*, but it is neither a " + BridgeTypeContract.DTO_FQN + " nor an enum, and"
                        + " a package name is not a credential: any plugin can ship a class under this"
                        + " prefix. Make it a Dto, or keep it out of the Dto's object graph.");
                return Status.REJECTED;
            }
            if (!definedByReceiverOrAncestor(element)) {
                PandasticCoreLog.warn("Rejected " + name + " while transferring a Dto across the bridge: it"
                        + " was defined by " + element.getClassLoader() + " rather than by the receiving"
                        + " plugin (" + receiverLoader + ") or one of its ancestors, so it is another"
                        + " plugin's code wearing this library's package name. Its readObject will not"
                        + " be run here.");
                return Status.REJECTED;
            }
            return Status.ALLOWED;
        }

        PandasticCoreLog.warn("Rejected class " + name + " while transferring a Dto across the bridge:"
                + " only the receiving plugin's own " + BridgeTypeContract.LIBRARY_PACKAGE_PREFIX
                + "* Dtos and a minimal JDK value allowlist may be deserialized. Make the Dto a record"
                + " to avoid serialization entirely, or restrict its components to allowlisted types.");
        return Status.REJECTED;
    }

    /**
     * Whether {@code type} has a shape a DTO graph legitimately contains.
     * <p>
     * Answering it means walking {@code type}'s supertypes, which resolves them in the loader that
     * defined it — and a stream can name a class whose supertypes are missing there. A
     * {@link LinkageError} in the middle of a filter callback would escape as an {@code Error}
     * rather than the {@code InvalidClassException} every caller of this stream is prepared for, so
     * an unanswerable question counts as "no".
     */
    private static boolean isDtoOrEnum(@NotNull Class<?> type) {
        try {
            return BridgeTypeContract.isDto(type) || isEnumType(type);
        } catch (LinkageError | RuntimeException e) {
            PandasticCoreLog.warn("Could not determine whether " + type.getName() + " is a "
                    + BridgeTypeContract.DTO_FQN + "; refusing it.", e);
            return false;
        }
    }

    /**
     * Whether {@code type} is an {@code enum}, including a constant with a class body — whose
     * {@code isEnum()} is {@code false} because it is an anonymous subclass of the enum.
     */
    private static boolean isEnumType(@NotNull Class<?> type) {
        if (type.isEnum()) return true;
        Class<?> superclass = type.getSuperclass();
        return superclass != null && superclass.isEnum();
    }

    /**
     * Whether {@code type} was defined by the bootstrap loader ({@code null}) or the platform
     * loader — the only loaders that define JDK serialization machinery.
     * <p>
     * This is what makes the discovered-name allowlist safe to widen: the names in
     * {@link #DISCOVERED_JDK_SERIAL_FORMS} were observed, not specified, so the filter must not
     * treat the <em>name</em> as the credential. The defining loader is checked instead, and it is
     * the one property no plugin can forge — a user-defined loader cannot define platform classes.
     */
    private static boolean definedByPlatform(@NotNull Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader == ClassLoader.getPlatformClassLoader();
    }

    /**
     * Whether {@code type} was defined by the receiving plugin's classloader or one of its
     * ancestors.
     * <p>
     * An ancestor is included on purpose: a deployment that keeps this library in a shared parent
     * loader and the DTOs in the plugin loader is legitimate, and the parent is by definition code
     * the receiver already trusts for everything else. A {@code null} defining loader is the
     * bootstrap loader, which every loader descends from.
     */
    private boolean definedByReceiverOrAncestor(@NotNull Class<?> type) {
        ClassLoader origin = type.getClassLoader();
        if (origin == null) return true;
        for (ClassLoader candidate = receiverLoader; candidate != null; candidate = candidate.getParent()) {
            if (candidate == origin) return true;
        }
        return false;
    }
}
