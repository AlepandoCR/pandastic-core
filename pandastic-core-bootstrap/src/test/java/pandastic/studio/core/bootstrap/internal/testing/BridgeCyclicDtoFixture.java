package pandastic.studio.core.bootstrap.internal.testing;

import pandastic.studio.core.util.api.data.Dto;

import java.util.List;

/**
 * A record {@link Dto} that can be made to contain itself.
 * <p>
 * A record is immutable once built, but the <em>list</em> handed to its constructor is not: build
 * the list first, construct the record with it, then add the record to the list. The result is a
 * perfectly ordinary {@code Dto} whose object graph is a cycle — reachable from any plugin that
 * assembles a DTO from untrusted input without thinking about it, and from any plugin that wants
 * to hang the receiving side.
 * <p>
 * The same shape with a <em>shared</em> child instead of a cycle is the cheaper attack: a graph
 * that is small to build but exponential to copy if the copier has no budget.
 *
 * @param children nested DTOs, possibly including this one
 */
public record BridgeCyclicDtoFixture(List<BridgeCyclicDtoFixture> children) implements Dto {
}
