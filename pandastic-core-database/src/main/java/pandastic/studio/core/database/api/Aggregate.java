package pandastic.studio.core.database.api;

/**
 * Closed set of SQL aggregate functions a repository can evaluate over one declared column via
 * {@code aggregateAsync(Aggregate, column, DaoQuery)}.
 * <p>
 * The set being a closed enum is part of the injection defence: the function name interpolated
 * into the statement can only ever be one of these four constants, and the column it applies to
 * is resolved against the repository's own declared columns.
 * <p>
 * <strong>Zero rows / SQL NULL.</strong> SQL defines {@code SUM}, {@code AVG}, {@code MIN} and
 * {@code MAX} over zero rows as {@code NULL}, and the repository surfaces that as
 * {@code Optional.empty()} — never a silent {@code 0.0}. Treat an empty result as "nothing
 * matched", not as a zero total.
 * <p>
 * <strong>GROUP BY is deliberately out.</strong> These aggregates evaluate over the whole
 * filtered row set; group in Java until a real need appears — a grouping API would drag result
 * shapes and HAVING semantics into a surface that so far only needs scalars.
 */
public enum Aggregate {

    /** SQL {@code SUM(column)}. */
    SUM,

    /** SQL {@code AVG(column)}. */
    AVG,

    /** SQL {@code MIN(column)}. */
    MIN,

    /** SQL {@code MAX(column)}. */
    MAX
}
