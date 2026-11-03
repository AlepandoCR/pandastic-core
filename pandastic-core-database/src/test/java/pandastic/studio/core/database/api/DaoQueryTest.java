package pandastic.studio.core.database.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link DaoQuery.Builder}'s validation contract: every malformed input must fail at the
 * line that wrote it (or at {@code build()} for the one cross-field rule), because by the time a
 * query reaches a repository its shape is assumed well-formed.
 */
class DaoQueryTest {

    @Test
    @DisplayName("a built query exposes immutable, order-preserving state")
    void builtQueryStateIsImmutable() {
        DaoQuery query = DaoQuery.builder()
                .where("name", DaoQuery.Operator.EQUALS, "Ale")
                .where("score", DaoQuery.Operator.BETWEEN, 1, 10)
                .orderBy("score", DaoQuery.Direction.DESC)
                .orderBy("name", DaoQuery.Direction.ASC)
                .limit(5)
                .offset(10)
                .build();

        assertEquals(2, query.conditions().size());
        assertEquals("name", query.conditions().getFirst().column());
        assertEquals(List.of(1, 10), query.conditions().get(1).values());
        assertEquals(2, query.orders().size());
        assertEquals(DaoQuery.Direction.DESC, query.orders().getFirst().direction());
        assertEquals(OptionalInt.of(5), query.limit());
        assertEquals(OptionalInt.of(10), query.offset());
        assertTrue(query.hasPredicate());

        assertThrows(UnsupportedOperationException.class,
                () -> query.conditions().add(query.conditions().getFirst()));
        assertThrows(UnsupportedOperationException.class,
                () -> query.conditions().getFirst().values().add("x"));
        assertThrows(UnsupportedOperationException.class,
                () -> query.orders().clear());
    }

    @Test
    @DisplayName("an empty build() is a valid match-everything query")
    void emptyQueryIsValid() {
        DaoQuery query = DaoQuery.builder().build();
        assertFalse(query.hasPredicate());
        assertTrue(query.conditions().isEmpty());
        assertTrue(query.orders().isEmpty());
        assertEquals(OptionalInt.empty(), query.limit());
        assertEquals(OptionalInt.empty(), query.offset());
    }

    @Test
    @DisplayName("negative limit and offset are rejected immediately")
    void negativePagingRejected() {
        assertThrows(IllegalArgumentException.class, () -> DaoQuery.builder().limit(-1));
        assertThrows(IllegalArgumentException.class, () -> DaoQuery.builder().offset(-1));
    }

    @Test
    @DisplayName("offset without limit fails at build(), naming MySQL's LIMIT requirement")
    void offsetWithoutLimitRejectedAtBuild() {
        DaoQuery.Builder builder = DaoQuery.builder().offset(10);
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(failure.getMessage().contains("LIMIT"), failure.getMessage());
    }

    @Test
    @DisplayName("comparison operators demand exactly one non-null value")
    void comparisonArityEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.EQUALS));
        assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.EQUALS, 1, 2));

        IllegalArgumentException nullFailure = assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.EQUALS, (Object) null));
        assertTrue(nullFailure.getMessage().contains("IS_NULL"), nullFailure.getMessage());
    }

    @Test
    @DisplayName("BETWEEN demands exactly two non-null bounds")
    void betweenArityEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.BETWEEN, 1));
        assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.BETWEEN, 1, 2, 3));
        assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.BETWEEN, 1, null));
    }

    @Test
    @DisplayName("IS_NULL / IS_NOT_NULL take no values")
    void nullOperatorsTakeNoValues() {
        assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.IS_NULL, 1));
        assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.IS_NOT_NULL, 1));
    }

    @Test
    @DisplayName("LIKE accepts only a CharSequence pattern")
    void likeRequiresCharSequence() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.LIKE, 42));
        assertTrue(failure.getMessage().contains("CharSequence"), failure.getMessage());

        DaoQuery query = DaoQuery.builder()
                .where("a", DaoQuery.Operator.LIKE, new StringBuilder("x%")).build();
        assertEquals(List.of("x%"), query.conditions().getFirst().values());
    }

    @Test
    @DisplayName("empty IN fails fast; a single Collection argument is unwrapped; nulls are rejected")
    void inValidation() {
        IllegalArgumentException emptyVarargs = assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.IN));
        assertTrue(emptyVarargs.getMessage().contains("IN"), emptyVarargs.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().whereIn("a", List.of()));

        DaoQuery unwrapped = DaoQuery.builder()
                .where("a", DaoQuery.Operator.IN, Set.of(7)).build();
        assertEquals(List.of(7), unwrapped.conditions().getFirst().values());

        DaoQuery varargs = DaoQuery.builder()
                .where("a", DaoQuery.Operator.IN, 1, 2, 3).build();
        assertEquals(List.of(1, 2, 3), varargs.conditions().getFirst().values());

        IllegalArgumentException nullElement = assertThrows(IllegalArgumentException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.IN, 1, null));
        assertTrue(nullElement.getMessage().contains("IS_NULL"), nullElement.getMessage());
    }

    @Test
    @DisplayName("null column, operator or value array are rejected with NullPointerException")
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class,
                () -> DaoQuery.builder().where(null, DaoQuery.Operator.EQUALS, 1));
        assertThrows(NullPointerException.class,
                () -> DaoQuery.builder().where("a", null, 1));
        assertThrows(NullPointerException.class,
                () -> DaoQuery.builder().where("a", DaoQuery.Operator.IN, (Object[]) null));
        assertThrows(NullPointerException.class,
                () -> DaoQuery.builder().whereIn("a", null));
        assertThrows(NullPointerException.class,
                () -> DaoQuery.builder().orderBy(null, DaoQuery.Direction.ASC));
        assertThrows(NullPointerException.class,
                () -> DaoQuery.builder().orderBy("a", null));
    }
}
