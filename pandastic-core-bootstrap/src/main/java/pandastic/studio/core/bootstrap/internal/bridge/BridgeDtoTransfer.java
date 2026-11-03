package pandastic.studio.core.bootstrap.internal.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.bootstrap.internal.PandasticCoreLog;
import pandastic.studio.core.util.api.data.Dto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transfers a {@link Dto} from one classloader's copy of a type to another's.
 * <p>
 * This is the generic replacement for what used to be a hardcoded, per-DTO reflective branch in
 * {@link BridgeValueAdapter}. Nothing here knows any concrete type: a DTO is transferable because
 * it implements {@link Dto}, and adding a new one requires no change to this class.
 * <h2>Two paths, in order of preference</h2>
 * <ol>
 *     <li><b>Record reconstruction (primary).</b> For a {@code record} the receiving side reads the
 *     foreign instance's record component accessors and invokes its <em>own</em> canonical
 *     constructor. Only public accessors on a public record are called, nothing is deserialized,
 *     and component values recurse through {@link BridgeValueAdapter} so nested DTOs, library
 *     interfaces and collections are handled by the same rules as any other bridged value. This is
 *     why {@link Dto} implementations should be records.</li>
 *     <li><b>Serialization round-trip (fallback).</b> A non-record DTO is written with
 *     {@link ObjectOutputStream} and read back with a stream that resolves classes
 *     <em>exclusively</em> in the receiving classloader, refuses proxy classes outright, and is
 *     guarded by {@link BridgeSerialFilter}. See that class for the threat model; in short,
 *     deserializing arbitrary classes handed over by another plugin's classloader is a
 *     remote-code-execution class of bug, so the stream is allowlisted and size-limited. Taking
 *     this path logs a once-per-type warning naming the class to convert, because the record path
 *     has none of its limits.</li>
 * </ol>
 * <h2>Validation</h2>
 * Both the declared target type and the foreign instance's own type must implement {@link Dto}
 * before anything is read or written, and the reconstructed value is re-checked against the
 * declared type before being returned. Every failure is an {@link IllegalStateException} that
 * names both types and both classloaders, matching the style {@link BridgeValueAdapter} already
 * uses; nothing degrades into a later {@link ClassCastException}.
 * <h2>Budgets</h2>
 * Both paths are bounded, because the shape of the graph being copied is chosen by the other side:
 * <ul>
 *     <li>the record path counts {@link #MAX_TRANSFER_DEPTH nesting depth} and
 *     {@link #MAX_TRANSFERRED_OBJECTS total objects} per top-level transfer. A record cannot
 *     contain itself at construction time, but the {@link java.util.List} handed to its constructor
 *     can be mutated afterwards to close a cycle, and a graph that merely shares one child at every
 *     level is exponential to copy while being linear to build;</li>
 *     <li>the serialization path bounds the bytes it will <em>write</em>, not just the bytes it
 *     reads back. The JDK has deserialization filters but no serialization equivalent, and
 *     {@code writeObject} belongs to the sending side, so a limit applied to the finished array
 *     arrives after the heap has already been spent.</li>
 * </ul>
 */
final class BridgeDtoTransfer {

    /**
     * Maximum nesting depth of a single transfer, mirroring {@link BridgeSerialFilter#MAX_DEPTH} so
     * the record path and the serialization path refuse the same graphs.
     */
    private static final int MAX_TRANSFER_DEPTH = (int) BridgeSerialFilter.MAX_DEPTH;

    /**
     * Maximum number of DTOs re-created by one top-level transfer.
     * <p>
     * Depth alone does not bound the work: a record with {@code k} DTO components that all point at
     * the same child is {@code k^depth} to copy naively. A node budget bounds it outright, and it
     * is set far above anything a real value object reaches so that legitimate DTOs carrying lists
     * of nested DTOs are unaffected.
     */
    private static final int MAX_TRANSFERRED_OBJECTS = 10_000;

    /**
     * Per-thread transfer budget: {@code [depth, objectsTransferred]}.
     * <p>
     * A thread-local rather than a parameter because the recursion runs back through
     * {@link BridgeValueAdapter#adapt}, which is the general entry point for every bridged value
     * and must not grow a budget parameter for this one case. Removed at depth zero so nothing is
     * left behind on a long-lived pool thread.
     */
    private static final ThreadLocal<int[]> BUDGET = ThreadLocal.withInitial(() -> new int[2]);

    /**
     * Names of non-record {@link Dto} types that have already been nagged about crossing through
     * the serialization fallback, so the nag in {@link #warnNonRecordFallback} fires once per type
     * per JVM instead of once per transfer. Concurrent because transfers can run on any thread.
     */
    private static final Set<String> FALLBACK_WARNED_TYPES = ConcurrentHashMap.newKeySet();

    /**
     * How many fallback warnings have actually been emitted.
     * <p>
     * Package-private test seam: the warning goes through {@link PandasticCoreLog}, whose
     * {@link System.Logger} backend is not reliably capturable in a unit test, so the once-per-type
     * guarantee is asserted against this counter instead. Monotonic; never reset.
     */
    static final AtomicLong FALLBACK_WARNINGS_EMITTED = new AtomicLong();

    private BridgeDtoTransfer() {
    }

    /**
     * Re-creates {@code foreign} as an instance the receiving side can hold through
     * {@code declaredType}.
     *
     * @param declaredType the receiving side's declared type; may be the concrete DTO type, a DTO
     *                     super-interface, or {@link Dto} itself
     * @param foreign      an instance produced by another classloader
     * @return a value assignable to {@code declaredType}, owned by the receiving classloader
     * @throws IllegalStateException if either side is not a {@link Dto}, if the DTO cannot be
     *                               reconstructed, or if the stream carries a class the bridge
     *                               refuses to deserialize
     */
    static @NotNull Object transfer(@NotNull Class<?> declaredType, @NotNull Object foreign) {
        Class<?> foreignType = foreign.getClass();
        requireDto(declaredType, "declared target type");
        requireDto(foreignType, "incoming value type");

        // Same classloader after all: nothing to transfer.
        if (declaredType.isInstance(foreign)) return foreign;

        int[] budget = BUDGET.get();
        if (budget[0] >= MAX_TRANSFER_DEPTH) {
            throw new IllegalStateException("Refusing to transfer " + foreignType.getName()
                    + " across the bridge: its object graph is nested too deeply (over "
                    + MAX_TRANSFER_DEPTH + " levels of Dto). A value object is a handful of levels"
                    + " deep; a deeper one is either a bug or a graph built to exhaust the stack.");
        }
        if (budget[1] >= MAX_TRANSFERRED_OBJECTS) {
            throw new IllegalStateException("Refusing to transfer " + foreignType.getName()
                    + " across the bridge: it expands into too many objects (over "
                    + MAX_TRANSFERRED_OBJECTS + "). A graph that shares a child at every level is"
                    + " cheap to build and exponential to copy, so the copy is budgeted.");
        }

        budget[0]++;
        budget[1]++;
        try {
            Class<?> localType = localCounterpartOf(declaredType, foreignType);
            Object recreated = localType.isRecord() && foreignType.isRecord()
                    ? fromRecordComponents(localType, foreign)
                    : fromSerializedForm(localType, foreign);

            if (!declaredType.isInstance(recreated)) {
                throw new IllegalStateException(describe(
                        "re-created a " + recreated.getClass().getName() + " that is still not assignable to",
                        declaredType, foreignType));
            }
            return recreated;
        } finally {
            budget[0]--;
            // Back at the top of a transfer: drop the counter so a pool thread keeps nothing.
            if (budget[0] == 0) BUDGET.remove();
        }
    }

    private static void requireDto(@NotNull Class<?> type, @NotNull String role) {
        if (BridgeTypeContract.isDto(type)) return;
        throw new IllegalStateException(
                "Bridge contract violation: " + role + " " + type.getName() + " (loader "
                        + type.getClassLoader() + ") does not implement " + BridgeTypeContract.DTO_FQN
                        + ", so it cannot cross the classloader boundary by value. Bridged signatures may"
                        + " only expose parent-visible types, library interfaces, or Dtos"
                        + " (see BridgeTypeContract).");
    }

    /**
     * Resolves the receiving side's copy of the foreign instance's concrete type.
     * <p>
     * The declared type is often a super-interface such as {@link Dto} itself, in which case the
     * concrete type is what has to be rebuilt. Resolution goes through the declared type's
     * classloader, which is the receiving side by construction.
     */
    private static @NotNull Class<?> localCounterpartOf(@NotNull Class<?> declaredType, @NotNull Class<?> foreignType) {
        if (declaredType.getName().equals(foreignType.getName())) return declaredType;

        ClassLoader receiverLoader = declaredType.getClassLoader();
        Class<?> localType;
        try {
            localType = Class.forName(foreignType.getName(), false, receiverLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException(describe(
                    "is not present in the receiving classloader, so it cannot be re-created as",
                    declaredType, foreignType), e);
        }
        if (!declaredType.isAssignableFrom(localType)) {
            throw new IllegalStateException(describe(
                    "resolves locally to a type that is not assignable to", declaredType, foreignType));
        }
        return localType;
    }

    /**
     * Rebuilds a record by reading the foreign accessors and invoking the local canonical
     * constructor.
     */
    private static @NotNull Object fromRecordComponents(@NotNull Class<?> localType, @NotNull Object foreign) {
        Class<?> foreignType = foreign.getClass();
        RecordComponent[] components = localType.getRecordComponents();

        Class<?>[] canonicalParameters = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            canonicalParameters[i] = component.getType();
            Object foreignValue = readForeignComponent(foreignType, foreign, component);
            arguments[i] = convertComponent(component.getGenericType(), foreignValue, foreignType.getClassLoader());
        }

        Constructor<?> canonical;
        try {
            canonical = localType.getDeclaredConstructor(canonicalParameters);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Cannot re-create record " + localType.getName()
                    + ": no canonical constructor " + Arrays.toString(canonicalParameters) + " found.", e);
        }
        requirePublic(localType, canonical.getModifiers(), "canonical constructor of");

        try {
            return canonical.newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("The canonical constructor of " + localType.getName()
                    + " rejected the values transferred from " + foreignType.getName() + ".", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to re-create record " + localType.getName()
                    + " from " + foreignType.getName() + ".", e);
        }
    }

    private static @Nullable Object readForeignComponent(@NotNull Class<?> foreignType,
                                                         @NotNull Object foreign,
                                                         @NotNull RecordComponent component) {
        Method accessor;
        try {
            accessor = foreignType.getMethod(component.getName());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Cannot transfer " + foreignType.getName()
                    + ": it has no public accessor for record component '" + component.getName()
                    + "'. The two library copies disagree on this Dto's shape.", e);
        }
        requirePublic(accessor.getDeclaringClass(), accessor.getModifiers(), "record accessor '"
                + component.getName() + "' of");

        try {
            return accessor.invoke(foreign);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Accessor " + foreignType.getName() + "#"
                    + component.getName() + "() failed while transferring a Dto across the bridge.",
                    e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read record component '" + component.getName()
                    + "' from " + foreignType.getName() + ".", e);
        }
    }

    /**
     * Converts one record component value into something the local component type can hold.
     * <p>
     * Library enums are handled here rather than in {@link BridgeValueAdapter}: an enum is a
     * library <em>class</em>, so it is deliberately not allowed on a bridged method signature, but
     * it is a perfectly ordinary thing to find inside a value object, and mapping it by constant
     * name is both exact and side-effect free. Everything else defers to the general adapter, so
     * nested DTOs, library interfaces and collections follow the same rules as anywhere else.
     */
    private static @Nullable Object convertComponent(@NotNull Type declaredComponentType,
                                                     @Nullable Object foreignValue,
                                                     @Nullable ClassLoader sourceLoader) {
        if (foreignValue == null) return null;

        Class<?> declared = BridgeTypeContract.erase(declaredComponentType);
        if (declared.isEnum() && BridgeTypeContract.isLibraryType(declared) && foreignValue instanceof Enum<?> foreignConstant) {
            return localEnumConstant(declared, foreignConstant);
        }
        return BridgeValueAdapter.adapt(declaredComponentType, foreignValue, sourceLoader);
    }

    private static @NotNull Object localEnumConstant(@NotNull Class<?> localEnumType, @NotNull Enum<?> foreignConstant) {
        Class<?> foreignEnumType = foreignConstant.getDeclaringClass();
        if (!foreignEnumType.getName().equals(localEnumType.getName())) {
            throw new IllegalStateException("Cannot transfer enum constant " + foreignEnumType.getName()
                    + "." + foreignConstant.name() + " as " + localEnumType.getName() + ".");
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object local = Enum.valueOf((Class<? extends Enum>) localEnumType, foreignConstant.name());
        return local;
    }

    /**
     * Rebuilds a non-record DTO through a locked-down serialization round-trip.
     */
    private static @NotNull Object fromSerializedForm(@NotNull Class<?> localType, @NotNull Object foreign) {
        warnNonRecordFallback(localType);
        byte[] serialized = serialize(localType, foreign);

        ClassLoader receiverLoader = localType.getClassLoader();
        try (InputStream bytes = new ByteArrayInputStream(serialized);
             ObjectInputStream in = new ReceiverObjectInputStream(bytes, receiverLoader)) {
            return in.readObject();
        } catch (InvalidClassException e) {
            throw new IllegalStateException("Refused to deserialize part of " + localType.getName()
                    + " while transferring it across the bridge; see the preceding log line for the"
                    + " rejected class. Prefer a record Dto, which needs no deserialization at all.", e);
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to re-create " + localType.getName() + " from "
                    + foreign.getClass().getName() + " through serialization.", e);
        }
    }

    /**
     * Writes the sending side's object, refusing to spend more than
     * {@link BridgeSerialFilter#MAX_BYTES} of heap on it.
     * <p>
     * The limit has to be enforced <em>during</em> the write. {@code writeObject} is the sending
     * side's own code — on a server that is another plugin's code — and it decides how much it
     * hands to the stream. Checking the finished byte array instead reports the violation only
     * after however many megabytes it chose have already been allocated, twice (the growing buffer
     * and its copy), which turns a size limit into an out-of-memory vector rather than a defence
     * against one. The JDK offers no serialization-side filter, so the bound lives in the buffer.
     */
    private static byte @NotNull [] serialize(@NotNull Class<?> localType, @NotNull Object foreign) {
        BoundedBuffer buffer = new BoundedBuffer();
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeObject(foreign);
        } catch (BufferLimitExceeded e) {
            throw new IllegalStateException("Refusing to transfer " + foreign.getClass().getName()
                    + " across the bridge: it wrote more than the " + BridgeSerialFilter.MAX_BYTES
                    + " byte limit and was stopped mid-write. Dtos are small values; a large one"
                    + " belongs behind a service interface, not in a copy.", e);
        } catch (NotSerializableException e) {
            throw new IllegalStateException("Dto " + foreign.getClass().getName()
                    + " is not actually serializable (" + e.getMessage() + "), so it cannot cross the"
                    + " classloader boundary as " + localType.getName()
                    + ". Make it a record, or make every field serializable.", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Dto " + foreign.getClass().getName()
                    + " for transfer to " + localType.getName() + ".", e);
        }
        return buffer.toByteArray();
    }

    /**
     * Logs, once per type per JVM, that a {@link Dto} is using the serialization fallback.
     * <p>
     * The fallback is a compatibility path, not an equal citizen: it depends on
     * {@link BridgeSerialFilter}'s JDK-calibrated allowlist and hard resource caps, none of which a
     * record transfer needs, because the record path never deserializes anything. Nothing is
     * refused here — existing non-record DTOs keep working — but the owner of the type should hear
     * about it exactly once, with the class to change named, rather than on every transfer or
     * never.
     */
    private static void warnNonRecordFallback(@NotNull Class<?> localType) {
        if (!FALLBACK_WARNED_TYPES.add(localType.getName())) return;
        FALLBACK_WARNINGS_EMITTED.incrementAndGet();
        PandasticCoreLog.warn("Dto " + localType.getName() + " crossed the bridge through the"
                + " Java-serialization fallback because it is not a record. That path is a"
                + " compatibility shim with hard limits (" + BridgeSerialFilter.MAX_BYTES
                + " bytes, depth " + BridgeSerialFilter.MAX_DEPTH + ", a JDK-dependent class"
                + " allowlist); a record crosses by its component accessors with no serialization"
                + " at all. Convert " + localType.getName() + " to a record. This warning is"
                + " logged once per type.");
    }

    private static void requirePublic(@NotNull Class<?> owner, int modifiers, @NotNull String what) {
        if (Modifier.isPublic(modifiers) && Modifier.isPublic(owner.getModifiers())) return;
        throw new IllegalStateException("Refusing to escalate access across the bridge: the " + what
                + " " + owner.getName() + " is not public. Only the public declared surface may be"
                + " reached across the classloader boundary.");
    }

    private static @NotNull String describe(@NotNull String problem,
                                            @NotNull Class<?> declaredType,
                                            @NotNull Class<?> foreignType) {
        return "Bridge contract violation: Dto " + foreignType.getName() + " (loader "
                + foreignType.getClassLoader() + ") " + problem + " " + declaredType.getName()
                + " (loader " + declaredType.getClassLoader() + ").";
    }

    /**
     * Signals that a write exceeded {@link BridgeSerialFilter#MAX_BYTES}.
     * <p>
     * Unchecked because {@link ByteArrayOutputStream#write} cannot declare {@link IOException}, and
     * private so it cannot be confused with a transport failure by anything but
     * {@link #serialize}.
     */
    private static final class BufferLimitExceeded extends RuntimeException {

        private static final long serialVersionUID = 1L;

        BufferLimitExceeded(long attempted) {
            super("the serialized Dto exceeded " + BridgeSerialFilter.MAX_BYTES + " bytes at "
                    + attempted, null, false, false);
        }
    }

    /**
     * {@link ByteArrayOutputStream} that refuses to grow past
     * {@link BridgeSerialFilter#MAX_BYTES}, so the write stops at the limit instead of being
     * measured against it afterwards.
     */
    private static final class BoundedBuffer extends ByteArrayOutputStream {

        BoundedBuffer() {
            super(512);
        }

        @Override
        public synchronized void write(int b) {
            requireRoomFor(1);
            super.write(b);
        }

        @Override
        public synchronized void write(byte @NotNull [] source, int offset, int length) {
            requireRoomFor(length);
            super.write(source, offset, length);
        }

        private void requireRoomFor(int extra) {
            long total = (long) count + extra;
            if (total > BridgeSerialFilter.MAX_BYTES) throw new BufferLimitExceeded(total);
        }
    }

    /**
     * {@link ObjectInputStream} that resolves every class in the receiving plugin's classloader,
     * refuses dynamic proxies, and applies {@link BridgeSerialFilter}.
     * <p>
     * Resolving in a fixed loader matters as much as the filter does: the default behaviour picks
     * the "latest user-defined classloader" off the call stack, which is exactly the kind of
     * implicit, stack-shape-dependent decision that must not drive class resolution at a trust
     * boundary.
     */
    private static final class ReceiverObjectInputStream extends ObjectInputStream {

        private final ClassLoader receiverLoader;

        ReceiverObjectInputStream(@NotNull InputStream in, @Nullable ClassLoader receiverLoader) throws IOException {
            super(in);
            this.receiverLoader = receiverLoader;
            setObjectInputFilter(BridgeSerialFilter.forReceiver(receiverLoader));
        }

        @Override
        protected Class<?> resolveClass(@NotNull ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            try {
                return Class.forName(desc.getName(), false, receiverLoader);
            } catch (ClassNotFoundException e) {
                // Platform classes are reachable from any loader, so resolve them explicitly rather
                // than through super.resolveClass, which picks the "latest user-defined classloader"
                // off the call stack. That is exactly the implicit, stack-shape-dependent decision
                // this class exists to avoid, and it is also a second route by which a class the
                // receiver never shipped could enter the stream.
                return Class.forName(desc.getName(), false, ClassLoader.getPlatformClassLoader());
            }
        }

        @Override
        protected Class<?> resolveProxyClass(String @NotNull [] interfaces) throws IOException {
            throw new InvalidClassException("java.lang.reflect.Proxy",
                    "dynamic proxies may not cross the bridge inside a Dto: " + Arrays.toString(interfaces));
        }
    }
}
