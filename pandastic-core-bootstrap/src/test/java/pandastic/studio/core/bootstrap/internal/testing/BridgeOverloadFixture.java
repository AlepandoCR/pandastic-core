package pandastic.studio.core.bootstrap.internal.testing;

/**
 * Three overloads sharing a name <em>and</em> an arity, differing only in parameter type.
 * <p>
 * This is the shape that a name-plus-arity resolver cannot tell apart: it returns whichever
 * declaration {@code Class#getMethods()} happens to list first, an order the specification leaves
 * unspecified. A bridge that guesses here invokes a method the caller never asked for, with the
 * caller's arguments, across a trust boundary.
 */
public interface BridgeOverloadFixture {

    String describe(String value);

    String describe(Integer value);

    String describe(CharSequence value);
}
