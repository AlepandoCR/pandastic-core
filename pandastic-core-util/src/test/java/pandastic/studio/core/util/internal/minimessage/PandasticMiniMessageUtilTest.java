package pandastic.studio.core.util.internal.minimessage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the style contract of component placeholders: an inserted component inherits everything it
 * does not declare, overrides exactly the properties it does declare — one at a time — and leaks
 * nothing into the template text that follows it.
 */
class PandasticMiniMessageUtilTest {

    // ------------------------------------------------------------------
    // Effective-style harness: walk the tree computing inherited styles per text run,
    // property by property, with no reliance on Style.merge semantics.
    // ------------------------------------------------------------------

    private record Run(String text, @Nullable TextColor color, Map<TextDecoration, Boolean> decorations) {
        boolean has(TextDecoration decoration) {
            return decorations.getOrDefault(decoration, false);
        }
    }

    private static List<Run> runs(Component root) {
        List<Run> out = new ArrayList<>();
        walk(root, null, new EnumMap<>(TextDecoration.class), out);
        return out;
    }

    private static void walk(Component node,
                             @Nullable TextColor inheritedColor,
                             Map<TextDecoration, Boolean> inheritedDecorations,
                             List<Run> out) {
        TextColor color = node.color() != null ? node.color() : inheritedColor;
        Map<TextDecoration, Boolean> decorations = new EnumMap<>(inheritedDecorations);
        for (TextDecoration decoration : TextDecoration.values()) {
            switch (node.decoration(decoration)) {
                case TRUE -> decorations.put(decoration, true);
                case FALSE -> decorations.put(decoration, false);
                case NOT_SET -> { /* inherit */ }
            }
        }

        if (node instanceof TextComponent text && !text.content().isEmpty()) {
            out.add(new Run(text.content(), color, decorations));
        }
        for (Component child : node.children()) {
            walk(child, color, decorations, out);
        }
    }

    private static Run runContaining(List<Run> runs, String needle) {
        return runs.stream().filter(run -> run.text().contains(needle)).findFirst()
                .orElseThrow(() -> new AssertionError("no run containing '" + needle + "' in " + runs));
    }

    // ------------------------------------------------------------------
    // The user-visible contract, with the exact example from the design conversation.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a colored component overrides only the color and leaks nothing forward")
    void colorOverridesOnlyColor() {
        Component placeholder = Component.text("X", NamedTextColor.AQUA);

        List<Run> runs = runs(PandasticMiniMessageUtil.parseOrPlainComponents(
                "<gray>Example %ph% finish example</gray>", Map.of("%ph%", placeholder)));

        Run inserted = runContaining(runs, "X");
        assertEquals(NamedTextColor.AQUA, inserted.color(), "declared color wins inside the component");
        assertFalse(inserted.has(TextDecoration.ITALIC), "no decoration was declared or inherited");

        Run before = runContaining(runs, "Example");
        Run after = runContaining(runs, "finish example");
        assertEquals(NamedTextColor.GRAY, before.color());
        assertEquals(NamedTextColor.GRAY, after.color(), "the component's color must not leak forward");
        assertFalse(after.has(TextDecoration.ITALIC));
    }

    @Test
    @DisplayName("a decorated component overrides only that decoration and inherits the color")
    void decorationOverridesOnlyDecoration() {
        Component placeholder = Component.text("X").decorate(TextDecoration.ITALIC);

        List<Run> runs = runs(PandasticMiniMessageUtil.parseOrPlainComponents(
                "<gray>Example %ph% finish example</gray>", Map.of("%ph%", placeholder)));

        Run inserted = runContaining(runs, "X");
        assertTrue(inserted.has(TextDecoration.ITALIC), "declared decoration wins");
        assertEquals(NamedTextColor.GRAY, inserted.color(), "undeclared color inherits from the template");

        Run after = runContaining(runs, "finish example");
        assertFalse(after.has(TextDecoration.ITALIC), "the decoration must not leak forward");
        assertEquals(NamedTextColor.GRAY, after.color());
    }

    @Test
    @DisplayName("an undeclared component inherits everything the template had")
    void plainComponentInheritsSurroundings() {
        List<Run> runs = runs(PandasticMiniMessageUtil.parseOrPlainComponents(
                "<gray><italic>a %ph% b</italic></gray>", Map.of("%ph%", Component.text("X"))));

        Run inserted = runContaining(runs, "X");
        assertEquals(NamedTextColor.GRAY, inserted.color());
        assertTrue(inserted.has(TextDecoration.ITALIC));
    }

    @Test
    @DisplayName("declaring a color keeps inheriting the surrounding decorations")
    void colorDoesNotDisturbInheritedDecorations() {
        Component placeholder = Component.text("X", NamedTextColor.AQUA);

        List<Run> runs = runs(PandasticMiniMessageUtil.parseOrPlainComponents(
                "<gray><bold>a %ph% b</bold></gray>", Map.of("%ph%", placeholder)));

        Run inserted = runContaining(runs, "X");
        assertEquals(NamedTextColor.AQUA, inserted.color());
        assertTrue(inserted.has(TextDecoration.BOLD), "only the color was declared; bold still inherits");

        Run after = runContaining(runs, "b");
        assertEquals(NamedTextColor.GRAY, after.color());
        assertTrue(after.has(TextDecoration.BOLD));
    }

    @Test
    @DisplayName("template tags after the placeholder keep working")
    void laterTagsAreRespected() {
        Component placeholder = Component.text("X", NamedTextColor.AQUA, TextDecoration.ITALIC);

        List<Run> runs = runs(PandasticMiniMessageUtil.parseOrPlainComponents(
                "<gray>a %ph% <white>b</white></gray>", Map.of("%ph%", placeholder)));

        Run later = runContaining(runs, "b");
        assertEquals(NamedTextColor.WHITE, later.color(), "a later <white> must win as always");
        assertFalse(later.has(TextDecoration.ITALIC));
    }

    @Test
    @DisplayName("the combined overload binds strings as literals and components as components")
    void combinedOverload() {
        List<Run> runs = runs(PandasticMiniMessageUtil.parseOrPlain(
                "<gray>%who% says %msg%</gray>",
                Map.of("%msg%", "<red>owned</red>"),
                Map.of("%who%", Component.text("Steve", NamedTextColor.AQUA))));

        assertEquals(NamedTextColor.AQUA, runContaining(runs, "Steve").color());
        Run message = runContaining(runs, "<red>owned</red>");
        assertEquals(NamedTextColor.GRAY, message.color(),
                "string placeholders stay literal text: their markup is never parsed");
    }

    @Test
    @DisplayName("a token present in both maps resolves as the component")
    void componentWinsOverString() {
        List<Run> runs = runs(PandasticMiniMessageUtil.parseOrPlain(
                "<gray>%ph%</gray>",
                Map.of("%ph%", "plain-loser"),
                Map.of("%ph%", Component.text("rich-winner", NamedTextColor.AQUA))));

        assertEquals(NamedTextColor.AQUA, runContaining(runs, "rich-winner").color());
        assertTrue(runs.stream().noneMatch(run -> run.text().contains("plain-loser")));
    }

    @Test
    @DisplayName("a key that cannot be a tag name still binds its component")
    void unsafeKeyStillBinds() {
        List<Run> runs = runs(PandasticMiniMessageUtil.parseOrPlainComponents(
                "<gray>a %Bad Key!% b</gray>",
                Map.of("%Bad Key!%", Component.text("X", NamedTextColor.AQUA))));

        assertEquals(NamedTextColor.AQUA, runContaining(runs, "X").color());
    }
}
