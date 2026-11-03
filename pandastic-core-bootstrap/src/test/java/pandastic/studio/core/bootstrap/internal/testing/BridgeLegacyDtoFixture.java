package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

import java.util.List;
import java.util.Objects;

/**
 * A non-record {@link Dto}, which forces the serialization fallback.
 * <p>
 * Declared {@code serialVersionUID} and allowlist-friendly field types only: a {@link String}, an
 * {@code int} and a {@link List} of strings, the last one to prove the filter admits the immutable
 * collections {@code List.of} actually serializes as.
 */
public final class BridgeLegacyDtoFixture implements Dto {

    private static final long serialVersionUID = 1L;

    private final String label;
    private final int count;
    private final List<String> tags;

    public BridgeLegacyDtoFixture(String label, int count, List<String> tags) {
        this.label = label;
        this.count = count;
        this.tags = List.copyOf(tags);
    }

    public String label() {
        return label;
    }

    public int count() {
        return count;
    }

    public List<String> tags() {
        return tags;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BridgeLegacyDtoFixture that)) return false;
        return count == that.count && label.equals(that.label) && tags.equals(that.tags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, count, tags);
    }

    @Override
    public String toString() {
        return "BridgeLegacyDtoFixture[label=" + label + ", count=" + count + ", tags=" + tags + "]";
    }
}
