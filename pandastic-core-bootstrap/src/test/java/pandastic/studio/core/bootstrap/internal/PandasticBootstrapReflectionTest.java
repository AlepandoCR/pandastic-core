package pandastic.studio.core.bootstrap.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two properties of method resolution that the rest of the bridge's safety rests on.
 * <h2>Access is never escalated</h2>
 * Every bridged call ends in {@code unreflectPublic}. If that can be talked into producing a handle
 * to something non-public, the bridge stops being "the declared API surface" and becomes "every
 * method the leader happens to have", reachable by any plugin in the JVM that can read the anchor.
 * It is a one-line regression — a {@code setAccessible} added to make an awkward layout work — so
 * it is asserted rather than assumed.
 * <h2>Resolution does not depend on reflection's enumeration order</h2>
 * {@code Class#getMethods()} reports a method once per type declaring it, and the order is
 * explicitly unspecified. An implementation and the interface it overrides therefore both appear
 * with the same name and erasure, and whichever is picked supplies the <em>generic</em> signature
 * that drives argument adaptation. Picking by enumeration order makes that a coin flip between JVM
 * runs.
 */
class PandasticBootstrapReflectionTest {

    @Test
    @DisplayName("a public method on a public type is bridgeable")
    void publicOnPublicIsReachable() throws Exception {
        Method method = PublicTarget.class.getMethod("visible");
        assertNotNull(PandasticBootstrapReflection.unreflectPublic(method));
    }

    @Test
    @DisplayName("a public method declared only by a non-public type is refused")
    void publicMethodOnANonPublicTypeIsRefused() throws Exception {
        // "public" on a type nobody outside the package can name buys no access. Resolution can
        // land here, because the bridge resolves against the concrete class of whatever object it
        // was handed, and a bridged service's implementation class need not be public.
        Method method = HiddenBase.class.getMethod("declaredOnlyOnTheHiddenBase");
        assertSame(HiddenBase.class, method.getDeclaringClass(), "the premise of this test");
        assertTrue(Modifier.isPublic(method.getModifiers()), "the member itself is public");
        assertFalse(Modifier.isPublic(HiddenBase.class.getModifiers()), "its owner is not");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PandasticBootstrapReflection.unreflectPublic(method));
        assertTrue(failure.getMessage().contains("will not escalate access")
                        || failure.getMessage().contains("not reachable through a public lookup"),
                failure.getMessage());
    }

    @Test
    @DisplayName("a public subclass of a non-public base is reachable, because javac publishes it there")
    void aPublicSubclassRepublishesAnInheritedMethod() throws Exception {
        // Not an escalation and not a hole: javac emits a public synthetic bridge on the public
        // subclass precisely so the method is callable from outside the package, and
        // publicLookup() agrees. Asserted so that a future tightening of the gate does not break
        // this ordinary layout by mistake, and so the reasoning is on the record either way.
        Method method = PublicSubclass.class.getMethod("declaredOnlyOnTheHiddenBase");
        assertSame(PublicSubclass.class, method.getDeclaringClass());
        assertTrue(method.isBridge(), "javac's doing, not this library's");

        assertNotNull(PandasticBootstrapReflection.unreflectPublic(method));
    }

    @Test
    @DisplayName("a package-private method on a public type is refused")
    void packagePrivateMembersAreRefused() throws Exception {
        Method method = PublicTarget.class.getDeclaredMethod("packaged");
        assertThrows(IllegalStateException.class, () -> PandasticBootstrapReflection.unreflectPublic(method));
    }

    @Test
    @DisplayName("an implementation hidden behind a public interface is reached through the interface")
    void aPackagePrivateImplementationIsReachedThroughItsInterface() throws Exception {
        // The ordinary case: the concrete class is an implementation detail, the contract is the
        // interface. No escalation is needed, so nothing may be refused either.
        Method method = HiddenImplementation.class.getDeclaredMethod("contracted");
        assertNotNull(PandasticBootstrapReflection.unreflectPublic(method));
    }

    @Test
    @DisplayName("a pre-opened Method cannot smuggle a non-public member past the gate")
    void settingTheAccessibleFlagDoesNotHelp() throws Exception {
        // MethodHandles.Lookup#unreflect skips its access check when the Method's accessible flag is
        // already set. The gate's own Modifier checks are what make that irrelevant here.
        Method method = PublicTarget.class.getDeclaredMethod("hidden");
        method.setAccessible(true);

        assertThrows(IllegalStateException.class, () -> PandasticBootstrapReflection.unreflectPublic(method));
    }

    @Test
    @DisplayName("resolution prefers the most-derived declaration, not whichever one came first")
    void theMostSpecificDeclarationWins() throws Exception {
        Method declared = Contract.class.getMethod("contracted");

        Method resolved = PandasticBootstrapReflection.resolveByDeclaredSignature(PublicImplementation.class, declared);

        assertEquals(PublicImplementation.class, resolved.getDeclaringClass(),
                "the implementation carries the specific signature; the interface declaration erases it");
    }

    /**
     * A public bridged contract, so the implementations below have something to override.
     */
    public interface Contract {
        String contracted();
    }

    public static class PublicTarget {

        public String visible() {
            return "visible";
        }

        private String hidden() {
            return "hidden";
        }

        String packaged() {
            return "packaged";
        }
    }

    /**
     * Package-private, declaring a {@code public} method. {@code public} on a type nobody outside
     * this package can name buys no access at all, which is the distinction being tested.
     */
    static class HiddenBase {

        public String declaredOnlyOnTheHiddenBase() {
            return "hidden base";
        }
    }

    public static final class PublicSubclass extends HiddenBase {
    }

    /**
     * The ordinary layout: implementation hidden, contract public.
     */
    static final class HiddenImplementation implements Contract {

        @Override
        public String contracted() {
            return "hidden implementation";
        }
    }

    public static final class PublicImplementation implements Contract {

        @Override
        public String contracted() {
            return "public implementation";
        }
    }
}
