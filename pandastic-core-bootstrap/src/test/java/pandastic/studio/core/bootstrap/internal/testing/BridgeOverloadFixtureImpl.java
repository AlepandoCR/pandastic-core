package pandastic.studio.core.bootstrap.internal.testing;

/**
 * Leader-side implementation of {@link BridgeOverloadFixture}. Each overload reports which one ran,
 * so a wrong-overload dispatch is visible in the assertion rather than silent.
 */
public final class BridgeOverloadFixtureImpl implements BridgeOverloadFixture {

    public BridgeOverloadFixtureImpl() {
    }

    @Override
    public String describe(String value) {
        return "String:" + value;
    }

    @Override
    public String describe(Integer value) {
        return "Integer:" + value;
    }

    @Override
    public String describe(CharSequence value) {
        return "CharSequence:" + value;
    }
}
