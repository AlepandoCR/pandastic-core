package pandastic.studio.core.bootstrap.internal.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.PandasticBootstrapReflection;
import pandastic.studio.core.bootstrap.internal.testing.BridgeOverloadFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeOverloadFixtureImpl;
import pandastic.studio.core.bootstrap.internal.testing.IsolatedLibraryClassLoader;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a call crossing the bridge reaches the overload the caller actually named.
 * <p>
 * The target lives in a separate classloader, so its parameter types are different {@link Class}
 * objects from the ones on the local interface method. Resolution therefore has to match on type
 * <em>names</em> — and the point of the test is that it matches on them at all, rather than
 * settling for "same name, same count" and invoking whichever declaration reflection listed first.
 */
class BridgeOverloadResolutionTest {

    private BridgeOverloadFixture proxy;
    private Class<?> foreignImplType;

    @BeforeEach
    void bridgeOntoAForeignImplementation() throws Exception {
        ClassLoader leaderLoader = new IsolatedLibraryClassLoader("leader", getClass().getClassLoader());
        foreignImplType = leaderLoader.loadClass(BridgeOverloadFixtureImpl.class.getName());
        assertNotSame(BridgeOverloadFixtureImpl.class, foreignImplType,
                "the target must really come from another classloader");

        Object foreign = foreignImplType.getConstructor().newInstance();
        proxy = (BridgeOverloadFixture) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{BridgeOverloadFixture.class},
                new GenericPandasticBootstrapHandler(foreign));
    }

    @Test
    @DisplayName("each same-arity overload reaches its own implementation")
    void overloadsAreResolvedByParameterTypeNotArity() {
        assertEquals("String:x", proxy.describe("x"));
        assertEquals("Integer:7", proxy.describe(Integer.valueOf(7)));
        assertEquals("CharSequence:z", proxy.describe(new StringBuilder("z")));
    }

    @Test
    @DisplayName("an unmatched signature fails loudly instead of falling back to a same-arity method")
    void unmatchedSignaturesAreRefused() throws Exception {
        // A name and arity the target does have, with a parameter type it does not.
        Method callerSide = UnmatchedOverloadCaller.class.getMethod("describe", Double.class);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PandasticBootstrapReflection.resolveByDeclaredSignature(foreignImplType, callerSide));

        assertTrue(failure.getMessage().contains("refuses to fall back"), failure.getMessage());
    }

    /**
     * Declares {@code describe(Double)} — the same name and arity as the fixture's overloads, with
     * a parameter type none of them accepts.
     */
    private interface UnmatchedOverloadCaller {
        String describe(Double value);
    }
}
