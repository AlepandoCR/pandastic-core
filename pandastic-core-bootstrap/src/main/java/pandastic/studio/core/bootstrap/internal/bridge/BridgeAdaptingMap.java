package pandastic.studio.core.bootstrap.internal.bridge;

import org.jetbrains.annotations.NotNull;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Live view over a foreign classloader's map that translates library-typed elements on the way
 * in and out, so the shared cache semantics survive the boundary: reads and writes hit the
 * leader's backing map, but each side only ever holds its own copy of the element types.
 * <p>
 * {@link #entrySet()}, {@link #values()} and {@link #keySet()} (when keys need translation)
 * return translated <b>snapshots</b>; mutate through {@link #put}, {@link #remove} and
 * {@link #clear}, which are live.
 */
final class BridgeAdaptingMap extends AbstractMap<Object, Object> {

    private final Map<Object, Object> backing;
    private final UnaryOperator<Object> readKey;
    private final UnaryOperator<Object> writeKey;
    private final UnaryOperator<Object> readValue;
    private final UnaryOperator<Object> writeValue;
    private final boolean translatesKeys;

    BridgeAdaptingMap(@NotNull Map<Object, Object> backing,
                      boolean translatesKeys,
                      @NotNull UnaryOperator<Object> readKey,
                      @NotNull UnaryOperator<Object> writeKey,
                      @NotNull UnaryOperator<Object> readValue,
                      @NotNull UnaryOperator<Object> writeValue) {
        this.backing = Objects.requireNonNull(backing, "backing");
        this.readKey = readKey;
        this.writeKey = writeKey;
        this.readValue = readValue;
        this.writeValue = writeValue;
        this.translatesKeys = translatesKeys;
    }

    @Override
    public int size() {
        return backing.size();
    }

    @Override
    public boolean isEmpty() {
        return backing.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return backing.containsKey(writeKey.apply(key));
    }

    @Override
    public boolean containsValue(Object value) {
        return backing.containsValue(writeValue.apply(value));
    }

    @Override
    public Object get(Object key) {
        return readValue.apply(backing.get(writeKey.apply(key)));
    }

    @Override
    public Object put(Object key, Object value) {
        return readValue.apply(backing.put(writeKey.apply(key), writeValue.apply(value)));
    }

    @Override
    public Object remove(Object key) {
        return readValue.apply(backing.remove(writeKey.apply(key)));
    }

    @Override
    public void putAll(@NotNull Map<?, ?> other) {
        for (Entry<?, ?> entry : other.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear() {
        backing.clear();
    }

    @Override
    public @NotNull Set<Object> keySet() {
        if (!translatesKeys) return backing.keySet();
        return backing.keySet().stream()
                .map(readKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public @NotNull Collection<Object> values() {
        return backing.values().stream()
                .map(readValue)
                .toList();
    }

    @Override
    public @NotNull Set<Entry<Object, Object>> entrySet() {
        return backing.entrySet().stream()
                .map(entry -> (Entry<Object, Object>) new SimpleImmutableEntry<>(
                        readKey.apply(entry.getKey()),
                        readValue.apply(entry.getValue())))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
