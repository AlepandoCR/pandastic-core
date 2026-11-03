package pandastic.studio.core.database.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.database.internal.DaoTestHarness.QueryRepository;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the JDBC binding layer for the types that used to fall through to the Gson/JSON fallback:
 * {@code short}/{@link Short}, {@code byte}/{@link Byte}, {@code char}/{@link Character},
 * {@link BigDecimal} and {@link LocalTime}. Each must hit its native {@code PreparedStatement}
 * setter and its native {@code ResultSet} getter — a JSON detour would have written
 * {@code "\"a\""} into a CHAR(1) column and rounded the money type through text.
 * <p>
 * The statement and result set are recording/canned {@link Proxy} fakes: that is enough to prove
 * which JDBC method the layer picks and that a bound value read back yields an equal value. What
 * a real MySQL driver does with those calls is deliberately NOT covered here — there is no live
 * database in this suite.
 */
class DaoCrudBindingTest {

    /** One recorded {@code PreparedStatement} setter invocation. */
    private record RecordedCall(String methodName, Object boundValue) {
    }

    private final List<RecordedCall> recordedCalls = new ArrayList<>();
    private QueryRepository repository;

    /** Builds a fresh repository; the manager never connects. */
    @BeforeEach
    void setUp() {
        repository = DaoTestHarness.newRepository(Logger.getAnonymousLogger());
    }

    /**
     * A {@link PreparedStatement} that records every {@code setXxx(index, value)} call and
     * refuses everything else.
     *
     * @return the recording proxy
     */
    private PreparedStatement recordingStatement() {
        InvocationHandler handler = (proxy, method, args) -> {
            String methodName = method.getName();
            if (methodName.startsWith("set") && args != null && args.length >= 2) {
                recordedCalls.add(new RecordedCall(methodName, args[1]));
                return null;
            }
            return switch (methodName) {
                case "toString" -> "RecordingPreparedStatement";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(
                        "binding must not need PreparedStatement#" + methodName);
            };
        };
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class }, handler);
    }

    /**
     * A {@link ResultSet} that serves one canned value from one getter and reports the given
     * SQL-NULL state; any other data access throws.
     *
     * @param getterName the single {@code ResultSet} getter allowed to be called
     * @param value      the value that getter returns
     * @param sqlNull    what {@code wasNull()} reports after the read
     * @return the canned proxy
     */
    private static ResultSet cannedResultSet(String getterName, Object value, boolean sqlNull) {
        InvocationHandler handler = (proxy, method, args) -> {
            String methodName = method.getName();
            if (methodName.equals(getterName)) {
                return value;
            }
            return switch (methodName) {
                case "wasNull" -> sqlNull;
                case "toString" -> "CannedResultSet";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(
                        "reading must not need ResultSet#" + methodName + " here, only " + getterName);
            };
        };
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[] { ResultSet.class }, handler);
    }

    /**
     * Asserts that binding {@code value} produces exactly one call to {@code expectedSetter}
     * with {@code expectedBound}, i.e. the native path and never the JSON fallback (which would
     * surface as {@code setString} with a JSON payload).
     *
     * @param value          the value to bind
     * @param javaType       the declared column type to bind under
     * @param expectedSetter the native setter the layer must pick
     * @param expectedBound  the exact value the setter must receive
     */
    private void assertBindsNatively(Object value, Class<?> javaType,
                                     String expectedSetter, Object expectedBound) throws Exception {
        recordedCalls.clear();
        repository.setStatementParameter(recordingStatement(), 1, value, javaType);
        assertEquals(List.of(new RecordedCall(expectedSetter, expectedBound)), recordedCalls,
                () -> value.getClass().getSimpleName() + " must bind via " + expectedSetter);
    }

    @Test
    @DisplayName("short/Short binds via setShort and reads back via getShort")
    void shortRoundTrip() throws Exception {
        assertBindsNatively((short) 7, short.class, "setShort", (short) 7);
        assertEquals((short) 7, repository.getResultSetValue(
                cannedResultSet("getShort", (short) 7, false), "score", Short.class));
        assertNull(repository.getResultSetValue(
                cannedResultSet("getShort", (short) 0, true), "score", Short.class));
    }

    @Test
    @DisplayName("byte/Byte binds via setByte and reads back via getByte")
    void byteRoundTrip() throws Exception {
        assertBindsNatively((byte) 3, byte.class, "setByte", (byte) 3);
        assertEquals((byte) 3, repository.getResultSetValue(
                cannedResultSet("getByte", (byte) 3, false), "score", byte.class));
        assertNull(repository.getResultSetValue(
                cannedResultSet("getByte", (byte) 0, true), "score", Byte.class));
    }

    @Test
    @DisplayName("char/Character binds as a bare one-character string (CHAR(1)), not JSON-quoted")
    void characterRoundTrip() throws Exception {
        // The regression: the Gson fallback wrote "\"a\"" (three characters) into CHAR(1).
        assertBindsNatively('a', char.class, "setString", "a");
        assertEquals('a', repository.getResultSetValue(
                cannedResultSet("getString", "a", false), "name", char.class));
        assertNull(repository.getResultSetValue(
                cannedResultSet("getString", null, false), "name", Character.class));
        assertNull(repository.getResultSetValue(
                cannedResultSet("getString", "", false), "name", Character.class));
    }

    @Test
    @DisplayName("BigDecimal binds via setBigDecimal and reads back via getBigDecimal, exactly")
    void bigDecimalRoundTrip() throws Exception {
        BigDecimal money = new BigDecimal("12345.67");
        assertBindsNatively(money, BigDecimal.class, "setBigDecimal", money);
        assertEquals(money, repository.getResultSetValue(
                cannedResultSet("getBigDecimal", money, false), "score", BigDecimal.class));
    }

    @Test
    @DisplayName("LocalTime binds via the JDBC 4.2 setObject mapping and reads back via getObject")
    void localTimeRoundTrip() throws Exception {
        LocalTime time = LocalTime.of(23, 59, 58);
        assertBindsNatively(time, LocalTime.class, "setObject", time);
        assertEquals(time, repository.getResultSetValue(
                cannedResultSet("getObject", time, false), "created_at", LocalTime.class));
    }

    @Test
    @DisplayName("sanity: the pre-existing native bindings still hold (int, String)")
    void preExistingBindingsUntouched() throws Exception {
        assertBindsNatively(42, int.class, "setInt", 42);
        assertBindsNatively("Ale", String.class, "setString", "Ale");
    }
}
