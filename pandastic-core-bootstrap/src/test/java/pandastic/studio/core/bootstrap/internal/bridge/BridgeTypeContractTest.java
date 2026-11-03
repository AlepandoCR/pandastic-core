package pandastic.studio.core.bootstrap.internal.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.core.bootstrap.internal.PandasticLibrary;
import pandastic.studio.core.bootstrap.internal.testing.BridgeSkinDtoFixture;
import pandastic.studio.core.bootstrap.internal.testing.BridgeSurfaceFixture;
import pandastic.studio.core.service.api.PandasticService;
import pandastic.studio.core.service.api.PandasticServiceManager;
import pandastic.studio.core.util.api.data.Dto;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the predicate that decides what may appear on a bridged signature at all.
 * <p>
 * Everything the bridge does downstream assumes this gate held: the adapter has exactly three
 * strategies (pass through, proxy, copy as a {@link Dto}), and a type that fits none of them must
 * never reach it.
 */
class BridgeTypeContractTest {

    @Test
    @DisplayName("the library prefix is derived, not written down twice")
    void libraryPrefixComesFromTheLibraryItself() {
        assertEquals(PandasticLibrary.PACKAGE_PREFIX, BridgeTypeContract.LIBRARY_PACKAGE_PREFIX);
        assertTrue(BridgeTypeContract.isLibraryType(Dto.class), "pandastic-core-util is part of the library");
        assertTrue(BridgeTypeContract.isLibraryType(PandasticService.class), "so is pandastic-core-bootstrap");
        assertFalse(BridgeTypeContract.isLibraryType(String.class));
    }

    @Test
    @DisplayName("interfaces, Dtos and JDK types are accepted")
    void acceptsInterfacesDtosAndJdkTypes() throws Exception {
        assertTrue(BridgeTypeContract.isBridgeSafe(PandasticService.class), "a library interface");
        assertTrue(BridgeTypeContract.isBridgeSafe(BridgeSkinDtoFixture.class), "a Dto");
        assertTrue(BridgeTypeContract.isBridgeSafe(String.class), "a JDK type");
        assertTrue(BridgeTypeContract.isBridgeSafe(returnTypeOf("safeServices")),
                "List<PandasticService>: JDK container of a library interface");
        assertTrue(BridgeTypeContract.isBridgeSafe(returnTypeOf("safeDto")));
        assertTrue(BridgeTypeContract.isBridgeSafe(returnTypeOf("safeJdkTypes")));

        // The real bridged surface has to pass its own gate.
        for (Method method : PandasticServiceManager.class.getMethods()) {
            assertTrue(BridgeTypeContract.isBridgeSafe(method.getGenericReturnType()),
                    method + " returns an unbridgeable type");
            for (Type parameter : method.getGenericParameterTypes()) {
                assertTrue(BridgeTypeContract.isBridgeSafe(parameter),
                        method + " takes an unbridgeable " + parameter);
            }
        }
    }

    @Test
    @DisplayName("a library class that is neither an interface nor a Dto is rejected")
    void rejectsLibraryClassesOnTheBridgedSurface() throws Exception {
        assertFalse(BridgeTypeContract.isBridgeSafe(returnTypeOf("unsafeLibraryClass")),
                "a plain library class cannot be held, proxied or copied");
        assertFalse(BridgeTypeContract.isBridgeSafe(returnTypeOf("unsafeGenericArgument")),
                "an unbridgeable type argument must not be hidden by a JDK raw type");
        assertFalse(BridgeTypeContract.isBridgeSafe(returnTypeOf("unsafeLibraryEnum")),
                "a library enum is a class: a follower cannot hold the leader's constants");
    }

    private static Type returnTypeOf(String methodName) throws NoSuchMethodException {
        return BridgeSurfaceFixture.class.getMethod(methodName).getGenericReturnType();
    }
}
