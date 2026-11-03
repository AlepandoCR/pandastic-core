package pandastic.studio.core.util.internal.minimessage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders the plugin's configured MiniMessage templates.
 *
 * <h2>Runtime values are never parsed as markup</h2>
 * A {@code %placeholder%} used to be substituted into the template as text, and the result parsed.
 * Anything that reached a placeholder was therefore markup: a name, a reason, an error string. The
 * shape that makes that dangerous is not exotic - text is authored by a player, stored, and later
 * rendered to somebody else. A stored {@code <click:run_command:'/op evil'>} then runs as whoever
 * clicks it.
 * <p>
 * Substitution now binds each value to a MiniMessage tag through
 * {@link Placeholder#unparsed(String, String)}, so a value is inserted as literal text and its
 * contents are never interpreted. The template keeps the {@code %key%} syntax the config files are
 * written in; the rewrite to a tag happens here.
 * <p>
 * Getting markup into a slot is deliberately a separate, named decision:
 * {@link #composeTemplate(String, Map)} for a configured fragment going into a configured template,
 * and {@link #markup(String, ComponentLike)} for an already-built component. Both take
 * plugin-authored input only. There is deliberately no "substitute this string and parse it"
 * helper, because that is the original hole with extra steps.
 */
public final class PandasticMiniMessageUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** Adventure's own rule for a tag name; a key that cannot be one is handled without a tag. */
    private static final Pattern TAG_NAME = Pattern.compile("[a-z0-9_-]+");

    private PandasticMiniMessageUtil() {
    }

    /**
     * Parses a template that carries no runtime values. Falls back to plain text if it will not
     * parse, and to an empty component if it is null.
     */
    public static @NotNull Component parseOrPlain(@Nullable String text) {
        if (text == null) {
            return Component.empty();
        }
        try {
            return MINI_MESSAGE.deserialize(text);
        } catch (Throwable parseError) {
            return Component.text(text);
        }
    }

    /**
     * Renders a template, binding every {@code %key%} in the map as literal text.
     *
     * @param template     a plugin-authored template, usually straight from a config file
     * @param placeholders the runtime values; markup inside a value is <em>not</em> interpreted
     * @return the rendered component, plain text if the template will not parse, empty if null
     */
    public static @NotNull Component parseOrPlain(@Nullable String template, @Nullable Map<String, String> placeholders) {
        if (template == null) {
            return Component.empty();
        }
        if (placeholders == null || placeholders.isEmpty()) {
            return parseOrPlain(template);
        }

        return parseOrPlain(template, placeholders, null);
    }

    /**
     * Renders a template, binding every {@code %key%} in the map as an already-built component.
     *
     * <h4>Style contract of an inserted component</h4>
     * The component becomes a child of the surrounding template tree, which yields exactly one
     * behaviour in each direction:
     * <ul>
     *     <li><b>It inherits what it does not declare.</b> Inside {@code <gray><italic>} a
     *     component with no color and no decorations renders gray italic.</li>
     *     <li><b>It overrides only what it does declare, property by property.</b> An aqua
     *     component inside {@code <gray>} renders aqua but still inherits the surrounding
     *     decorations; an italic-only component keeps the surrounding gray and only forces
     *     italic. Declaring a color never touches decorations, and vice versa.</li>
     *     <li><b>Nothing leaks past it.</b> Text after the placeholder continues with the
     *     template's own styling, and later tags ({@code <white>}, resets) behave as if the
     *     component had never been there.</li>
     * </ul>
     * So in {@code <gray>Example %ph% finish example</gray>} with an aqua-italic component, only
     * the inserted text is aqua italic; {@code finish example} stays gray and undecorated.
     *
     * @param template   a plugin-authored template, usually straight from a config file
     * @param components components to splice in; their interactivity (clicks, hovers) survives
     * @return the rendered component, plain text if the template will not parse, empty if null
     */
    public static @NotNull Component parseOrPlainComponents(
            @Nullable String template,
            @Nullable Map<String, ? extends ComponentLike> components
    ) {
        return parseOrPlain(template, null, components);
    }

    /**
     * Renders a template binding both kinds of placeholder at once: strings as literal text,
     * components spliced in under the style contract documented on
     * {@link #parseOrPlainComponents(String, Map)}.
     * <p>
     * A token present in <b>both</b> maps resolves as the component — the richer binding wins —
     * and the string entry for it is ignored.
     *
     * @param template   a plugin-authored template, usually straight from a config file
     * @param values     literal-text placeholders; markup inside a value is <em>not</em> interpreted
     * @param components component placeholders
     * @return the rendered component, plain text if the template will not parse, empty if null
     */
    public static @NotNull Component parseOrPlain(
            @Nullable String template,
            @Nullable Map<String, String> values,
            @Nullable Map<String, ? extends ComponentLike> components
    ) {
        if (template == null) {
            return Component.empty();
        }
        boolean noValues = values == null || values.isEmpty();
        boolean noComponents = components == null || components.isEmpty();
        if (noValues && noComponents) {
            return parseOrPlain(template);
        }

        String rewritten = template;
        List<TagResolver> resolvers = new ArrayList<>();
        Set<String> boundTokens = new HashSet<>();
        int syntheticNames = 0;

        // Components first: on a token declared in both maps, the component binding wins.
        if (!noComponents) {
            for (Map.Entry<String, ? extends ComponentLike> entry : components.entrySet()) {
                String token = entry.getKey();
                ComponentLike component = entry.getValue();
                if (component == null || !rewritten.contains(token)) continue;

                // A key that cannot be a tag name still has to bind — a component has no literal
                // fallback the way a string does — so it gets a synthetic, collision-free name.
                String tagName = tagNameFor(token);
                if (tagName == null) {
                    tagName = "pandastic-ph-" + syntheticNames++;
                }

                rewritten = rewritten.replace(token, '<' + tagName + '>');
                resolvers.add(Placeholder.component(tagName, component));
                boundTokens.add(token);
            }
        }

        if (!noValues) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String token = entry.getKey();
                if (boundTokens.contains(token)) continue;

                String text = entry.getValue() == null ? "" : entry.getValue();
                String tagName = tagNameFor(token);

                if (tagName == null) {
                    // No usable tag name, so there is no resolver to bind. Escaping keeps the value
                    // inert rather than falling back to the substitution this class exists to remove.
                    rewritten = rewritten.replace(token, MINI_MESSAGE.escapeTags(text));
                    continue;
                }

                rewritten = rewritten.replace(token, '<' + tagName + '>');
                resolvers.add(Placeholder.unparsed(tagName, text));
            }
        }

        return render(rewritten, TagResolver.resolver(resolvers));
    }

    /**
     * Renders a template against resolvers built with {@link #value(String, String)} and
     * {@link #markup(String, ComponentLike)}.
     */
    public static @NotNull Component render(@Nullable String template, @NotNull TagResolver... resolvers) {
        if (template == null) {
            return Component.empty();
        }
        try {
            return MINI_MESSAGE.deserialize(template, resolvers);
        } catch (RuntimeException parseError) {
            return Component.text(template);
        }
    }

    /**
     * Binds {@code <name>} to literal text. The right choice for anything that did not come out of
     * a config file: names, reasons, amounts, ids, error text.
     */
    public static @NotNull TagResolver value(@NotNull String name, @Nullable String text) {
        return Placeholder.unparsed(name, text == null ? "" : text);
    }

    /**
     * Binds {@code <name>} to an already-built component. The only sanctioned way to get formatting
     * into a slot: a component is structured data, so nothing inside it is re-parsed.
     */
    public static @NotNull TagResolver markup(@NotNull String name, @NotNull ComponentLike component) {
        return Placeholder.component(name, component);
    }

    /**
     * Substitutes configured fragments into a configured template, before either is parsed, so that
     * a fragment's own markup survives into the result.
     * <p>
     * <strong>Both sides must be plugin-authored.</strong> This is for a config value going into a
     * config template - a lethal-hit badge into an attack row, a clue's reveal line into its row.
     * A runtime value passed here is parsed as markup, which is exactly what
     * {@link #parseOrPlain(String, Map)} exists to prevent.
     *
     * @return the composed template, still unparsed
     */
    public static @NotNull String composeTemplate(@Nullable String template, @NotNull Map<String, String> fragments) {
        if (template == null) {
            return "";
        }
        String composed = template;
        for (Map.Entry<String, String> fragment : fragments.entrySet()) {
            composed = composed.replace(fragment.getKey(), fragment.getValue() == null ? "" : fragment.getValue());
        }
        return composed;
    }

    private static @Nullable String tagNameFor(@NotNull String token) {
        if (token.length() < 3 || token.charAt(0) != '%' || token.charAt(token.length() - 1) != '%') {
            return null;
        }
        String name = token.substring(1, token.length() - 1);

        return TAG_NAME.matcher(name).matches() ? name : null;
    }
}
