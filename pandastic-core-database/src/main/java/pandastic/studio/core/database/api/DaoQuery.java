package pandastic.studio.core.database.api;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Immutable description of a filtered query against one DAO table, assembled through
 * {@link Builder} and executed by a repository ({@code findWhereAsync}, {@code countWhereAsync},
 * {@code deleteWhereAsync}, {@code aggregateAsync}).
 * <p>
 * <strong>Injection posture.</strong> This type never accepts a SQL fragment. Its API takes
 * {@code (column, operator, value...)} tuples only: column names must name columns the DAO
 * interface <em>declares</em>, and the executing repository resolves them against its own column
 * metadata and re-validates them as plain identifiers at execution time — an unknown name fails
 * with {@code IllegalArgumentException("Unknown column: ...")} exactly like
 * {@code findByColumnAsync}. Every value travels as a {@code PreparedStatement} parameter, never
 * as statement text. A {@code DaoQuery} cannot know the entity it will run against, which is why
 * the column check deliberately lives in the repository and not here.
 * <p>
 * <strong>Conjunction is AND-only, by design.</strong> {@code OR} was deliberately left out, not
 * overlooked: a flat AND chain covers the suite's current needs, and a disjunction would force a
 * full predicate tree into this API. If a real need appears, a tree can be added later without
 * breaking this surface.
 * <p>
 * Semantics worth knowing before building one:
 * <ul>
 *   <li>{@link Operator#LIKE}: the pattern is <em>data</em> — the library does <em>not</em>
 *       escape {@code %} or {@code _}. If the pattern embeds user input, escape those two
 *       characters yourself or the user controls the wildcard matching.</li>
 *   <li>{@link Operator#IN} with an empty collection fails fast with
 *       {@code IllegalArgumentException}: SQL {@code IN ()} is invalid and "matches nothing" is
 *       almost never what the caller meant — skip the query instead.</li>
 *   <li>{@link Operator#BETWEEN} is inclusive on both ends, as in SQL.</li>
 *   <li>Comparison operators reject {@code null} values: SQL {@code = NULL} never matches; use
 *       {@link Operator#IS_NULL} / {@link Operator#IS_NOT_NULL}.</li>
 *   <li>{@link Builder#offset(int)} requires {@link Builder#limit(int)}: MySQL has no
 *       {@code OFFSET} without {@code LIMIT}, so {@link Builder#build()} rejects the combination
 *       early instead of letting the server do it later.</li>
 *   <li>One query can drive a whole pagination round-trip: {@code findWhereAsync} honours
 *       ORDER BY / LIMIT / OFFSET while {@code countWhereAsync} ignores them, so the same
 *       instance yields both the page and the total.</li>
 * </ul>
 */
public final class DaoQuery {

    /**
     * Closed set of predicate operators a condition may use. There is deliberately no "raw SQL"
     * escape hatch — the operator set being closed is part of the injection defence.
     */
    public enum Operator {
        /** {@code column = ?} — exactly one non-null value. */
        EQUALS,
        /** {@code column <> ?} — exactly one non-null value. */
        NOT_EQUALS,
        /** {@code column < ?} — exactly one non-null value. */
        LESS_THAN,
        /** {@code column <= ?} — exactly one non-null value. */
        LESS_OR_EQUAL,
        /** {@code column > ?} — exactly one non-null value. */
        GREATER_THAN,
        /** {@code column >= ?} — exactly one non-null value. */
        GREATER_OR_EQUAL,
        /** {@code column BETWEEN ? AND ?} — exactly two non-null values, inclusive bounds. */
        BETWEEN,
        /**
         * {@code column IN (?, ?, ...)} — one or more non-null values; an empty set is rejected
         * at build time. A single {@link Collection} argument is unwrapped into its elements.
         */
        IN,
        /** {@code column IS NULL} — no values. */
        IS_NULL,
        /** {@code column IS NOT NULL} — no values. */
        IS_NOT_NULL,
        /**
         * {@code column LIKE ?} — exactly one {@link CharSequence} pattern. The pattern is bound
         * as data; {@code %} and {@code _} are <em>not</em> escaped by the library.
         */
        LIKE
    }

    /** Sort direction of one {@code ORDER BY} term. */
    public enum Direction {
        /** Ascending, SQL {@code ASC}. */
        ASC,
        /** Descending, SQL {@code DESC}. */
        DESC
    }

    /**
     * One {@code column <operator> values} predicate. Instances are only created by
     * {@link Builder}, which has already enforced the operator's arity and null rules.
     *
     * @param column   declared column name the predicate filters on
     * @param operator the predicate operator
     * @param values   the bound values, in SQL order; empty for the no-value operators
     */
    public record Condition(@NotNull String column, @NotNull Operator operator,
                            @NotNull List<Object> values) {

        /**
         * Defensively copies the value list so a condition can never be mutated after creation.
         */
        public Condition {
            Objects.requireNonNull(column, "column cannot be null");
            Objects.requireNonNull(operator, "operator cannot be null");
            values = List.copyOf(Objects.requireNonNull(values, "values cannot be null"));
        }
    }

    /**
     * One {@code ORDER BY} term.
     *
     * @param column    declared column name to sort by
     * @param direction the sort direction
     */
    public record Order(@NotNull String column, @NotNull Direction direction) {

        /**
         * Validates both components are present.
         */
        public Order {
            Objects.requireNonNull(column, "column cannot be null");
            Objects.requireNonNull(direction, "direction cannot be null");
        }
    }

    private final List<Condition> conditions;
    private final List<Order> orders;
    private final Integer limitRows;
    private final Integer offsetRows;

    private DaoQuery(List<Condition> conditions, List<Order> orders,
                     Integer limitRows, Integer offsetRows) {
        this.conditions = Collections.unmodifiableList(new ArrayList<>(conditions));
        this.orders = Collections.unmodifiableList(new ArrayList<>(orders));
        this.limitRows = limitRows;
        this.offsetRows = offsetRows;
    }

    /**
     * Starts a new query builder.
     *
     * @return an empty builder; {@code builder().build()} is a valid "match everything" query
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Gets the AND-joined predicates of this query, in the order they were added.
     *
     * @return an immutable list of conditions, possibly empty
     */
    public @NotNull List<Condition> conditions() {
        return conditions;
    }

    /**
     * Gets the {@code ORDER BY} terms of this query, in the order they were added.
     *
     * @return an immutable list of order terms, possibly empty
     */
    public @NotNull List<Order> orders() {
        return orders;
    }

    /**
     * Gets the row limit of this query.
     *
     * @return the limit, or empty when none was set
     */
    public @NotNull OptionalInt limit() {
        return limitRows == null ? OptionalInt.empty() : OptionalInt.of(limitRows);
    }

    /**
     * Gets the row offset of this query. When present, a limit is guaranteed to be present too —
     * {@link Builder#build()} enforces it.
     *
     * @return the offset, or empty when none was set
     */
    public @NotNull OptionalInt offset() {
        return offsetRows == null ? OptionalInt.empty() : OptionalInt.of(offsetRows);
    }

    /**
     * Tells whether this query filters at all.
     *
     * @return {@code true} when at least one condition was added
     */
    public boolean hasPredicate() {
        return !conditions.isEmpty();
    }

    @Override
    public String toString() {
        return "DaoQuery{conditions=" + conditions + ", orders=" + orders
                + ", limit=" + limitRows + ", offset=" + offsetRows + '}';
    }

    /**
     * Mutable assembler for {@link DaoQuery}. Every argument is validated at the call that
     * supplies it, so a malformed query fails at the line that wrote it; the one cross-field
     * rule (offset requires limit) is checked in {@link #build()}.
     */
    public static final class Builder {

        private final List<Condition> conditions = new ArrayList<>();
        private final List<Order> orders = new ArrayList<>();
        private Integer limitRows;
        private Integer offsetRows;

        private Builder() {
        }

        /**
         * Adds one predicate, AND-joined with every other predicate of the query.
         * <p>
         * Arity and null rules per operator are documented on {@link Operator}; violating them
         * throws here, immediately. For {@link Operator#IN}, pass the elements as varargs or a
         * single {@link Collection} (which is unwrapped); see also {@link #whereIn}.
         *
         * @param column   declared column name; resolved and validated by the repository at execution
         * @param operator the predicate operator
         * @param values   the values the operator needs, bound as statement parameters
         * @return this builder, for chaining
         * @throws NullPointerException     if {@code column}, {@code operator} or the array is {@code null}
         * @throws IllegalArgumentException if the values violate the operator's arity or null rules
         */
        public @NotNull Builder where(@NotNull String column, @NotNull Operator operator,
                                      Object @NotNull ... values) {
            Objects.requireNonNull(column, "column cannot be null");
            Objects.requireNonNull(operator, "operator cannot be null");
            Objects.requireNonNull(values, "values cannot be null");
            List<Object> boundValues = switch (operator) {
                case EQUALS, NOT_EQUALS, LESS_THAN, LESS_OR_EQUAL, GREATER_THAN, GREATER_OR_EQUAL ->
                        List.of(requireExactlyOne(operator, values));
                case LIKE -> {
                    Object pattern = requireExactlyOne(operator, values);
                    if (!(pattern instanceof CharSequence)) {
                        throw new IllegalArgumentException("LIKE requires a CharSequence pattern, got "
                                + pattern.getClass().getName());
                    }
                    yield List.of(pattern.toString());
                }
                case BETWEEN -> {
                    if (values.length != 2) {
                        throw new IllegalArgumentException("BETWEEN requires exactly two values "
                                + "(low, high), got " + values.length);
                    }
                    if (values[0] == null || values[1] == null) {
                        throw new IllegalArgumentException(
                                "BETWEEN bounds must not be null; use IS_NULL / IS_NOT_NULL for null checks");
                    }
                    yield List.of(values[0], values[1]);
                }
                case IN -> inValues(values);
                case IS_NULL, IS_NOT_NULL -> {
                    if (values.length != 0) {
                        throw new IllegalArgumentException(operator + " takes no values, got "
                                + values.length);
                    }
                    yield List.of();
                }
            };
            conditions.add(new Condition(column, operator, boundValues));
            return this;
        }

        /**
         * Adds an {@link Operator#IN} predicate from a collection.
         *
         * @param column declared column name; resolved and validated by the repository at execution
         * @param values the candidate values; must be non-empty and contain no {@code null}
         * @return this builder, for chaining
         * @throws NullPointerException     if {@code column} or {@code values} is {@code null}
         * @throws IllegalArgumentException if {@code values} is empty or contains {@code null}
         */
        public @NotNull Builder whereIn(@NotNull String column, @NotNull Collection<?> values) {
            Objects.requireNonNull(values, "values cannot be null");
            return where(column, Operator.IN, new Object[] { values });
        }

        /**
         * Adds one {@code ORDER BY} term; repeatable, terms apply in the order they were added.
         *
         * @param column    declared column name; resolved and validated by the repository at execution
         * @param direction the sort direction
         * @return this builder, for chaining
         * @throws NullPointerException if either argument is {@code null}
         */
        public @NotNull Builder orderBy(@NotNull String column, @NotNull Direction direction) {
            orders.add(new Order(column, direction));
            return this;
        }

        /**
         * Caps the number of rows the query returns (or, for {@code deleteWhereAsync}, deletes).
         * Calling it again replaces the previous value.
         *
         * @param limit the maximum number of rows, zero or more
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code limit} is negative
         */
        public @NotNull Builder limit(int limit) {
            if (limit < 0) {
                throw new IllegalArgumentException("limit cannot be negative: " + limit);
            }
            this.limitRows = limit;
            return this;
        }

        /**
         * Skips the first rows of the result; only valid together with {@link #limit(int)},
         * because MySQL has no {@code OFFSET} without {@code LIMIT}. Calling it again replaces
         * the previous value.
         *
         * @param offset the number of rows to skip, zero or more
         * @return this builder, for chaining
         * @throws IllegalArgumentException if {@code offset} is negative
         */
        public @NotNull Builder offset(int offset) {
            if (offset < 0) {
                throw new IllegalArgumentException("offset cannot be negative: " + offset);
            }
            this.offsetRows = offset;
            return this;
        }

        /**
         * Finalises the query.
         *
         * @return an immutable query; the builder may keep being used afterwards
         * @throws IllegalArgumentException if an offset was set without a limit — MySQL requires
         *                                  {@code LIMIT} whenever {@code OFFSET} is used
         */
        public @NotNull DaoQuery build() {
            if (offsetRows != null && limitRows == null) {
                throw new IllegalArgumentException("offset(" + offsetRows + ") without limit: "
                        + "MySQL requires LIMIT whenever OFFSET is used — call limit(int) as well");
            }
            return new DaoQuery(conditions, orders, limitRows, offsetRows);
        }

        /**
         * Enforces the single-non-null-value rule shared by every binary comparison operator.
         *
         * @param operator the operator being validated, for the error message
         * @param values   the caller-supplied values
         * @return the single value
         * @throws IllegalArgumentException if there is not exactly one value, or it is {@code null}
         */
        private static Object requireExactlyOne(Operator operator, Object[] values) {
            if (values.length != 1) {
                throw new IllegalArgumentException(operator + " requires exactly one value, got "
                        + values.length);
            }
            if (values[0] == null) {
                throw new IllegalArgumentException(operator + " with a null value never matches in SQL; "
                        + "use IS_NULL / IS_NOT_NULL instead");
            }
            return values[0];
        }

        /**
         * Normalises and validates the element set of an {@code IN} predicate.
         *
         * @param values the caller-supplied values; a single {@link Collection} is unwrapped
         * @return the immutable element list
         * @throws IllegalArgumentException if the set is empty or contains {@code null}
         */
        private static List<Object> inValues(Object[] values) {
            Object[] elements = values;
            if (values.length == 1 && values[0] instanceof Collection<?> collection) {
                elements = collection.toArray();
            }
            if (elements.length == 0) {
                throw new IllegalArgumentException("IN with no values matches nothing and is not "
                        + "valid SQL; skip the query instead of building an empty IN");
            }
            for (Object element : elements) {
                if (element == null) {
                    throw new IllegalArgumentException("IN must not contain null: SQL "
                            + "'IN (..., NULL)' never matches; add a separate IS_NULL condition instead");
                }
            }
            return List.of(elements);
        }
    }
}