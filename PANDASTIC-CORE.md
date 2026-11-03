# PANDASTIC-CORE.md — Consumer guide

Instructions for any Claude (or developer) working in **a plugin that consumes pandastic-core**. This is
the usage manual; the library's own internals are documented in `CLAUDE.md` and `docs/SECURITY.md`
of the [pandastic-core repo](https://github.com/AlepandoCR/pandastic-core).

pandastic-core is a shaded library (not a plugin) for Paper 26.3 / Java 25. Every plugin embeds its own
copy; at runtime the first plugin to initialise becomes the **leader** that owns one shared runtime
(thread pool, HttpClient, service registry, caches) and every other plugin gets a transparent
bridge onto it. You write against interfaces and never care which side you are on.

## 1. Wiring the dependency

The package lives in GitHub Packages, whose Maven registry demands authentication even when the
repository behind it is public. Four rules, each load-bearing:

```kotlin
// build.gradle.kts of the consuming plugin
plugins { id("com.gradleup.shadow") version "9.6.1" }

dependencies {
    implementation("pandastic.studio:pandastic-core:1.0.0-SNAPSHOT") { isChanging = true }
    // REQUIRED for @Configurable / @Table / @BridgeApi processing — the processors and their
    // META-INF/services registration ship inside the same jar:
    annotationProcessor("pandastic.studio:pandastic-core:1.0.0-SNAPSHOT")
}

tasks.shadowJar {
    mergeServiceFiles()   // pandastic-core's ServiceLoader seams live in META-INF/services
    // NEVER relocate pandastic.studio.core: all copies must share FQNs or this copy derives a
    // different rendezvous key and isolates itself from every other plugin (by design).
}
```

- Credentials: a PAT with scope `read:packages`, read from the environment (`GITHUB_ACTOR` /
  `GITHUB_USER` and `GITHUB_TOKEN`) next to the repository declaration, with `gpr.user` /
  `gpr.token` in `~/.gradle/gradle.properties` as the fallback. Never in a file inside the
  repository — that is exactly how a token reaches a public remote.
- `isChanging = true` because the version is a SNAPSHOT republished on every push.
- Do not use Paper's `libraries:` for this (it cannot authenticate against the registry).

**The library deliberately brings NO transitive dependencies** — its published metadata declares
zero. You must declare yourself (and every Paper plugin already does):

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")   // needed for paper-api anyway
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")      // the server provides it at runtime
}
```

That is all. The nullability annotations on pandastic-core's API (JetBrains, jspecify) arrive
transitively through paper-api; Floodgate and EssentialsX are internal to the library (never
exposed in its API, probed at runtime) so consumers need neither. Propagating paper-api from the
library was rejected on purpose: it would pin your Paper version to the library's and duplicate a
declaration every plugin must own anyway.

The same applies to the **JDBC driver**: the library never calls `Class.forName` for one and
declares no driver dependency. `pandastic-core-database` relies entirely on JDBC 4 `ServiceLoader` discovery
— `DriverManager` picks up whatever MySQL driver is on the classpath. Paper servers ship
`mysql-connector-j` among their bundled libraries, which covers the normal case; if your target
environment does not, your plugin must provide the driver itself.

## 2. Lifecycle — subscribing and unsubscribing

```java
public final class MyPlugin extends JavaPlugin {

    private PandasticCore pandastic;

    @Override
    public void onEnable() {
        // Elects this plugin leader if it is first, otherwise returns a follower bridge.
        // The boolean enables election logging — keep it on until you have seen two of your
        // plugins report the SAME leader fingerprint (that is the proof they share a runtime).
        this.pandastic = PandasticCore.instance(this, true);
    }

    @Override
    public void onDisable() {
        // Follower: unregisters this plugin's services and detaches.
        // Leader: hands leadership over / tears the runtime down when nobody is left.
        if (this.pandastic != null) this.pandastic.shutdown();
    }
}
```

Rules:

- **Initialise in `onEnable`, not `onLoad`.** Eager failover registers a Bukkit listener, which
  needs an enabled plugin; an `onLoad` init silently degrades to lazy failure detection.
- **Always call `shutdown()` in `onDisable`**, even though failover survives you forgetting it:
  shutdown is what releases your runtime's threads if you were the leader.
- **Failover is automatic.** If the leader plugin is unloaded (plugman), the earliest-joined
  surviving follower promotes itself immediately and logs the whole transition. Your bridge keeps
  working — but **services you registered with the dead leader are gone**: re-register on demand
  or watch for the logged failover line.
- Never cache objects obtained through the bridge across restarts of other plugins; re-fetch via
  the API (`getServiceManager()`, the profile getters) — those re-validate per call.

## 3. Services — using, writing, registering

`pandastic.getServiceManager()` is the shared registry (one per JVM, bridged to you):

```java
PandasticServiceManager services = pandastic.getServiceManager();
services.registerService(myService);                    // visible to EVERY plugin
services.unregisterService(myService);                  // true if something was removed
services.unregisterAll(plugin);                         // all of one plugin's bound services
services.getService("ServiceName");                     // by unique name
services.getServicesByType(MyServiceApi.class);
services.getServiceForPlugin(plugin, MyBoundService.class);
```

Writing a service: implement the right mix of these interfaces (all in
`pandastic.studio.core.service.api`):

| Interface | Adds | Use when |
|---|---|---|
| `PandasticService` | `getServiceName()` (unique id) + `getName()`/`getDescription()` (display Components) | always |
| `SyncPandasticService` / `AsyncPandasticService` | nothing / `getExecutorService()` | declare your threading nature |
| `PluginBoundPandasticService<P>` | `getBoundPlugin()` | one instance per plugin |
| `CacheHolderPandasticService<S, C>` | `getCache()` | the service owns/shares an `PandasticCache` |

**Bridge-safety rules for anything you register** (other plugins hold your object through a
different classloader):

- Expose only **interfaces** in your service's method signatures — never your concrete classes.
- Values crossing by value must be **`Dto` records**: `public record SkinDto(...) implements Dto`
  style. Records reconstruct on the far side with zero serialization; non-record `Dto`s work but
  go through a capped serialization fallback and log a one-time warning.
- JDK/Bukkit/Adventure types, `CompletableFuture`, `Optional`, `List`/`Set`/`Map` of the above are
  all fine and adapted automatically. A `Map` crosses as a **live view** — a follower's `put`
  lands in the leader's map (that is how shared caches work) and `clear()` affects every plugin.
- Service names are unique keys: re-registering an existing name is rejected, pick
  `"MyService_" + plugin.getName()` for plugin-bound ones.

#### Typed service lookup and consumer-defined interfaces (protocol 3)

`getService(String)` can only promise an `PandasticService` view across a classloader boundary.
To get a service back as **your own** interface, use the typed overload:

```java
MarketAnalyticsService svc =
        core.getServiceManager().getService("PandasticMarketAnalytics_PandasticMarket", MarketAnalyticsService.class);
```

Same classloader → the real instance. Across a boundary → a proxy implementing your copy of
the requested interface. A name hit whose service does not implement the requested type (in
the leader's view) throws `ClassCastException` naming both types — never `null`, which would
read as "not registered".

For a consumer-defined interface (anything outside `pandastic.studio.core`) the bridge must be told the
package is trustworthy, **before the service is registered**:

```java
@Override
public void onEnable() {
    PandasticCore core = PandasticCore.instance(this);
    core.registerBridgePackage(this, "pandastic.studio.market.api");     // 1. trust the package
    core.getServiceManager().registerService(analyticsService); // 2. then register
}
```

Order matters: a service registered by a follower is stored leader-side as a proxy whose
interface set is fixed the moment it crosses, so a prefix registered afterwards cannot
retrofit it. The trust makes a package's **interfaces** proxyable — concrete classes still
never cross, and the `Dto` rules are unchanged. Prefixes must be real packages of at least two
segments and may not overlap `pandastic.studio.core` or platform namespaces (`java*`, `jdk`, `sun`,
`com.sun`, `org.bukkit`, `io.papermc`, `net.kyori`); violations throw
`IllegalArgumentException`. Registrations die with the owning plugin (service sweep, plugin
disable, failover) and each one is logged at INFO — operator telemetry for a widened trust
boundary.

This also makes **consumer-declared caches** work across plugins: an interface like
`interface MarketCache extends PandasticCache<PandasticService> { Map<String, String> marketData(); }`
registered as a service comes back castable to `MarketCache` via the typed lookup, and its
`Map` accessors cross as the same live map (parent-visible element types pass by reference;
library-typed elements get the usual live translating view).

**Mixed-copy pruning (don't be alarmed by the WARN).** On a real server the leader's
classloader usually *can* resolve your interface class through Paper's cross-plugin lookup —
but your interface's method signatures reference **your plugin's** shaded `pandastic.studio.core` copy,
which the leader's loader resolves to *its own* copy. The JDK refuses to build a proxy mixing
the two, so the bridge **prunes** such interfaces from the leader-side holding proxy and logs a
one-time `Skipping proxy interface …` warning instead of failing your `registerService` (which
is what used to happen). Nothing is lost: the registry detects that the original object still
implements the requested name and the typed lookup re-views it through the token you pass, in
your own classloader, where everything links. Only the *untyped* `getService(String)` view held
by **other** plugins omits your interface in this topology — they should use the typed overload.

## 4. Profile services (names, UUIDs, skins — Java + Bedrock)

Three ready-made services, all sharing **one** cache across all plugins:

```java
PandasticProfileService<MyPlugin> profiles = pandastic.getUniversalProfileService(this);
// also: getJavaProfileService(this) — playerdb.co lookups only
//       getBedrockProfileService(this) — Floodgate/Geyser Global API only

profiles.getName(uuid)             // CompletableFuture<Optional<String>>
profiles.getUniqueIdentifier(name) // CompletableFuture<Optional<UUID>>
profiles.getSkin(uuid)             // CompletableFuture<Optional<SkinDto>>

// Inline player-head chat component, built from the cached signed skin — renders instantly
// for every viewer, no client-side lookup, works for offline and Bedrock players alike:
profiles.getPlayerHeadComponent(uuid)   // CompletableFuture<Optional<Component>>
        .thenAccept(head -> head.ifPresent(h ->
                viewer.sendMessage(h.append(Component.text(" killed the dragon!")))));
```

Related, in `pandastic.studio.core.util.api.component.PandasticComponentUtil` (pure builders, no service needed):
`playerHead(SkinDto)` / `playerHead(id, name, skin, hat)` build a head straight from a skin you
already hold; `playerHeadByLookup(uuid)` defers rendering to each viewing client (fallback only);
`sprite(atlas, sprite)` renders an atlas sprite inline — **the atlas is always explicit**, the
default-atlas overload is deliberately not exposed (an implicit atlas is how an icon silently
breaks when a sprite moves).

- All three write the same cache; every call peeks it before any HTTP request.
- The **universal** service resolves misses Bedrock-first, then Java; a Bedrock-prefixed name
  (Floodgate prefix, fallback `.`) never reaches the Java path.
- Bedrock ids are Floodgate-format UUIDs (`msb == 0`); Floodgate is optional at runtime.
- Misses resolve to `Optional.empty()`, never exceptionally. Futures complete on the shared
  async pool — hop to the main thread before touching Bukkit API.

## 5. Economy service (Vault)

One **shared, server-wide** service over Vault's registered economy provider — not per-plugin,
because money is global state and funnelling every plugin through one instance is what makes
`charge` and `transfer` atomic across plugins (no two plugins can both pass the balance check and
overdraw). No Vault type appears in the API, so **consumers need no Vault dependency**; the server
needs Vault + an economy plugin (EssentialsX, CMI, ...) at runtime.

```java
PandasticEconomyService economy = pandastic.getEconomyService();

// Gate once at startup instead of catching per call:
economy.isEconomyAvailable().thenAccept(ok -> { if (!ok) getSLF4JLogger().warn("No economy!"); });

economy.getBalance(playerId);          // CompletableFuture<Double>, always fresh — never cached
economy.has(playerId, 500.0);          // CompletableFuture<Boolean>
economy.format(1234.5);                // provider-formatted, e.g. "$1,234.50"

// THE way to take money for a purchase — balance check + withdrawal as ONE atomic step:
economy.charge(playerId, price).thenAccept(result -> {
    if (result.success()) {
        give(playerId, item);          // safe: futures complete ON THE MAIN THREAD (see below)
    } else {
        // result.newBalance() is the untouched balance; result.error() says why
        tell(playerId, "You need " + (price - result.newBalance()) + " more.");
    }
});

economy.deposit(playerId, reward);                 // give money
economy.withdraw(playerId, fee);                   // take without the pre-check
economy.transfer(fromId, toId, amount);            // atomic, refunds the payer if the deposit leg fails
```

The rules that differ from the rest of the library:

- **Futures complete on the server MAIN thread**, not the async pool: Vault providers do not
  promise thread safety, so every operation runs as one main-thread task (inline when called from
  the main thread — no tick delay). Continuations may touch Bukkit API directly; move heavy work
  off-thread yourself.
- **Refusals are data, environment faults are exceptions.** Insufficient funds / provider
  rejection → future completes normally with `EconomyTransactionDto.success() == false` and an
  `error()` message. No Vault provider installed → `IllegalStateException`; invalid amount
  (zero, negative, NaN) → `IllegalArgumentException`.
- **Never check-then-take with `has` + `withdraw`** — another plugin can move money in between.
  That race is exactly what `charge` exists to close.
- **No balance cache, on purpose.** Balances are mutated by commands and other plugins with no
  invalidation signal; reads always hit the provider.

## 6. Dialogs with annotations (`pandastic-core-dialogs`)

Write a controller class; the factory reflects it once (plan cached per class) and builds a Paper
dialog from it on every call — so config-driven values and conditional elements re-resolve on
every open. Dialogs are immutable once built: "updating" one means building a new controller
instance and showing it again (see pagination in §6.5).

### 6.1 A full dialog: bodies, every input kind, and a handler that reads them

A bounty-posting dialog — static text body, a text input, a number slider, an option group, a
checkbox, and confirm/cancel:

```java
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import pandastic.studio.core.dialog.api.Dialog;
import pandastic.studio.core.dialog.api.DialogBody;
import pandastic.studio.core.dialog.api.DialogButton;
import pandastic.studio.core.dialog.api.DialogButtonHandler;
import pandastic.studio.core.dialog.api.DialogInput;
import pandastic.studio.core.dialog.api.factory.DialogContext;
import pandastic.studio.core.dialog.api.factory.DialogFactory;

import java.util.List;

@Dialog(canBeClosedWithEscape = true)
public final class PostBountyDialog {

    private final MyPlugin plugin;
    private final Player target;

    public PostBountyDialog(MyPlugin plugin, Player target) {
        this.plugin = plugin;
        this.target = target;
    }

    // ---- body: static explanatory text (order sorts within each kind) ----
    @DialogBody(id = "explainer", order = 0)
    io.papermc.paper.registry.data.dialog.body.DialogBody explainer() {
        return io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(
                Component.text("Post a bounty on " + target.getName() + "."), 250);
    }

    // ---- TEXT input: free text, optionally multiline ----
    @DialogInput(id = "note", order = 0)
    io.papermc.paper.registry.data.dialog.input.DialogInput note() {
        return io.papermc.paper.registry.data.dialog.input.DialogInput.text(
                "note",                                        // key — what the handler reads
                250,                                           // width
                Component.text("Reason (shown to hunters)"),   // label
                true,                                          // label visible
                "",                                            // initial text
                128,                                           // max length
                TextDialogInput.MultilineOptions.create(3, 60) // maxLines, maxHeight — or null for single-line
        );
    }

    // ---- NUMBER RANGE input: a slider; builder form shown ----
    @DialogInput(id = "amount", order = 1)
    io.papermc.paper.registry.data.dialog.input.DialogInput amount() {
        return io.papermc.paper.registry.data.dialog.input.DialogInput
                .numberRange("amount", Component.text("Bounty"), 100.0f, 50_000.0f)
                .initial(1_000.0f)        // where the slider starts
                .step(100.0f)             // snap increments
                .labelFormat("%s: %s$")   // label, then current value
                .width(250)
                .build();
    }

    // ---- SINGLE OPTION input: radio group; exactly one entry pre-selected ----
    @DialogInput(id = "visibility", order = 2)
    io.papermc.paper.registry.data.dialog.input.DialogInput visibility() {
        return io.papermc.paper.registry.data.dialog.input.DialogInput.singleOption(
                "visibility",
                250,
                List.of(
                        SingleOptionDialogInput.OptionEntry.create("public", Component.text("Public"), true),
                        SingleOptionDialogInput.OptionEntry.create("hunters", Component.text("Hunters only"), false)
                ),
                Component.text("Visibility"),
                true
        );
    }

    // ---- BOOLEAN input: a checkbox ----
    @DialogInput(id = "anonymous", order = 3)
    io.papermc.paper.registry.data.dialog.input.DialogInput anonymous() {
        return io.papermc.paper.registry.data.dialog.input.DialogInput
                .bool("anonymous", Component.text("Post anonymously"))
                .initial(false)
                .build();
    }

    // ---- buttons: a Builder gets its callback attached by the handler below ----
    @DialogButton(id = "post", order = 0)
    ActionButton.Builder post() {
        return ActionButton.builder(Component.text("Post bounty")).width(150);
    }

    @DialogButton(id = "cancel", order = 1)
    ActionButton.Builder cancel() {
        return ActionButton.builder(Component.text("Cancel")).width(150);
        // No handler for "cancel": a button with no action just closes the dialog.
    }

    // ---- the handler: context + response view ----
    @DialogButtonHandler(buttonId = "post", uses = 1)
    void onPost(DialogContext ctx) {
        if (!(ctx.getAudience() instanceof Player poster)) return;

        var response = ctx.dialogResponseView();

        // Every getter is NULLABLE and keyed by the input's key. Default explicitly —
        // a null must never silently become "0" or "the wrong branch".
        String note      = response.getText("note");          // String, or null
        Float amount     = response.getFloat("amount");       // Float, or null
        String visible   = response.getText("visibility");    // the selected OptionEntry id
        Boolean anon     = response.getBoolean("anonymous");  // Boolean, or null

        if (amount == null) return;
        boolean anonymous = Boolean.TRUE.equals(anon);
        boolean publicPost = !"hunters".equals(visible);      // default matches the pre-selected entry

        // Player-typed text is DATA, never markup: pass it through placeholders / plain
        // components downstream. Parsing it as MiniMessage lets a player smuggle
        // <click:run_command:...> into every screen that later renders the note.
        plugin.getBountyBoard().post(poster, target, Math.round(amount), note, publicPost, anonymous);
    }
}

// Showing it:
player.showDialog(DialogFactory.create(new PostBountyDialog(plugin, target)));
```

Reading responses, summarized: `ctx.getAudience()` is who clicked (pattern-match to `Player` for
game actions); `ctx.dialogResponseView()` exposes `getText(key)`, `getFloat(key)`,
`getBoolean(key)` — all nullable, keyed by the **input key** you declared, where a
single-option's "value" is the selected entry's **id** via `getText`.

### 6.2 Conditional elements — `return null`

Any provider may return `null` and that element is skipped **for that build only**. Three
idiomatic uses:

```java
// Permission-gated section:
@DialogBody(id = "staff-note", order = 5)
io.papermc.paper.registry.data.dialog.body.DialogBody staffNote() {
    if (!viewer.hasPermission("myplugin.staff")) return null;
    return io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(staffOnlyText());
}

// State-gated input (editing an immutable, already-published entry):
@DialogInput(id = "rename", order = 1)
io.papermc.paper.registry.data.dialog.input.DialogInput rename() {
    if (entry.isPublished()) return null;
    return io.papermc.paper.registry.data.dialog.input.DialogInput.text("rename", Component.text("Name")).build();
}

// Edge-of-list navigation (see §6.5): no "previous" button on the first page. A dialog covers
// the chat box, so a refusal message the player cannot see reads as a dead button — omit the
// button instead.
@DialogButton(id = "previous", order = 8)
ActionButton.Builder previous() {
    return page > 0 ? ActionButton.builder(Component.text("◀ Previous")) : null;
}
```

### 6.3 Dynamic button lists — inline actions, not handlers

`@DialogButton` may return `List<ActionButton>` for data-driven rows (one button per warp, per
claim, per report). **Handlers cannot attach to a list** — each row carries its own inline
callback via `DialogAction.customClick`, which receives the same `(response, audience)` pair:

```java
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import net.kyori.adventure.text.event.ClickCallback;

@DialogButton(id = "warp-rows", order = 0)
List<ActionButton> warpRows() {
    List<WarpDto> warps = plugin.getWarps().forPlayer(viewer);
    if (warps.isEmpty()) {
        return List.of(ActionButton.builder(Component.text("You have no warps")).build());
    }
    return warps.stream().map(warp ->
            ActionButton.builder(Component.text(warp.name()))
                    .width(200)
                    .action(DialogAction.customClick((response, audience) -> {
                        if (audience instanceof Player player) {
                            player.teleportAsync(plugin.getWarps().locate(warp));
                        }
                    }, ClickCallback.Options.builder().build()))
                    .build()
    ).toList();
}
```

Use the inline form **only** where the list forces it; for fixed buttons prefer
`@DialogButtonHandler` — it keeps wiring declarative, and `uses` is handled for you.

### 6.4 Item bodies

Bodies are not limited to text — `DialogBody.item(ItemStack)` renders an item (tooltip included),
with a builder for a caption and sizing:

```java
@DialogBody(id = "preview", order = 1)
io.papermc.paper.registry.data.dialog.body.DialogBody preview() {
    return io.papermc.paper.registry.data.dialog.body.DialogBody.item(kit.previewStack())
            .description(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(
                    Component.text("Contents of the " + kit.name() + " kit")))
            .build();
}
```

### 6.5 Pagination / navigation — rebuild and re-show

Dialogs are immutable; a page flip is a **new controller at the new page**. Keep page state in
constructor fields, derive a sibling with `atPage`, and re-show from the handler:

```java
@Dialog
public final class AuctionBrowserDialog {

    private final MyPlugin plugin;
    private final List<AuctionDto> auctions;   // snapshot taken once, pages stay consistent
    private final int page;
    private static final int PER_PAGE = 6;

    private AuctionBrowserDialog atPage(int page) {
        return new AuctionBrowserDialog(plugin, auctions, page);
    }

    private int totalPages() {
        return Math.max(1, (auctions.size() + PER_PAGE - 1) / PER_PAGE);
    }

    @DialogButton(id = "next", order = 9)
    ActionButton.Builder next() {                       // conditional: absent on the last page
        return page < totalPages() - 1 ? ActionButton.builder(Component.text("Next ▶")) : null;
    }

    @DialogButtonHandler(buttonId = "next")
    void onNext(DialogContext ctx) {
        if (ctx.getAudience() instanceof Player player) {
            player.showDialog(DialogFactory.create(atPage(page + 1)));
        }
    }
    // ... "previous" mirrors it with page - 1 and the page > 0 guard ...
}
```

The same rebuild-and-show pattern covers "back" navigation (hold a reference to the parent
controller and re-`create` it) and refresh-after-action.

### 6.6 The contract, precisely

- `@DialogBody(id, order)` methods return a Paper `DialogBody` **or** `List<DialogBody>`.
  `@DialogInput(id, order)` likewise with Paper's `DialogInput`. `@DialogButton(id, order)`
  returns `ActionButton.Builder`, `ActionButton`, or `List<ActionButton>` — but **handlers only
  attach to a `Builder`** (a prebuilt button or a list already carries its own callbacks; a
  handler on those is ignored with a warning).
- **Conditional elements: return `null`** from any provider method — skipped for that build.
- `@DialogButtonHandler(buttonId, uses)`: `void` method with exactly one `DialogContext`
  parameter; `uses` is how many clicks the callback accepts (default 1 — a "confirm" should
  stay 1; a toggle row wants more).
- Response getters (`getText`/`getFloat`/`getBoolean`) are nullable; a single-option input
  reports the selected entry **id** through `getText(key)`.
- Ids must be unique per kind; `order` sorts elements, and collisions across a class hierarchy
  resolve most-derived-first (the subclass keeps its slot, others shift).
- Inherited/interface-default provider methods are honoured; annotate the declaration you want.
- `@DialogConfigProvider` (optional): a field implementing
  `pandastic.studio.core.dialog.api.factory.DialogConfig` (`Component title()`), re-read on every
  `create` — back it with a config for live-reloadable titles. Without it, the title falls back
  to the class simple name.
- Handlers run on the server thread that dispatched the click; player-typed text from inputs is
  data, never markup — do not feed it to a MiniMessage parser.

## 7. Configs with annotations (`pandastic-core-config`)

Declare ONE annotated type; the processor generates the whole YAML-backed implementation
(requires the `annotationProcessor` dependency from §1).

**Preferred shape — an interface whose `default` methods are both schema and defaults:**

```java
import pandastic.studio.core.service.api.config.*;

@Configurable(name = "ArenaConfig", format = ConfigFormat.YAML)   // optional targetPackage = ""
public interface ArenaSettings {

    @ConfigValue(fieldName = "max-players", comment = "Hard cap per arena.")
    default int getMaxPlayers() { return 16; }

    @ConfigValue(fieldName = "motd", comment = "Shown on join. MiniMessage allowed.")
    default @NotNull String getMotd() { return "<green>Welcome"; }

    @ConfigValue(fieldName = "spawn-offsets", comment = "Relative Y offsets per ring.")
    default double[] getSpawnOffsets() { return new double[] {0.0, 1.5}; }
}
```

Generates `ArenaConfig implements ArenaSettings, GeneratedConfig` in the same package (or
`targetPackage`), with: a no-arg constructor **pre-seeded with your defaults**, a static
`create(File)` factory, a `DEFAULT_FILENAME` constant, and `load/save/loadOrCreate` plus
`...Async` variants. A class with `@ConfigValue` **fields** works too (defaults seeded from a
template instance if it has a no-arg constructor).

Supported types: all primitives and boxes (`float`/`short`/`byte`/`char` included), `String`,
`List<AnyBox|String|Character>`, primitive and boxed arrays, and any Bukkit
`ConfigurationSerializable`. Anything else is a **compile error** — the processor never generates
silently-broken code.

**Loading — one config:**

```java
ArenaConfig config = ArenaConfig.create(new File(getDataFolder(), ArenaConfig.DEFAULT_FILENAME));
config.loadOrCreate();   // reads the file, or writes a fully-commented default one
```

**Loading — many configs, batch (`pandastic.studio.core.service.api.config.io`):**

```java
new ConfigLoader(this)
    .load(ConfigFolder.root(), "arena.yml", ArenaConfig.class, cfg -> this.arena = cfg)
    .load(ConfigFolder.of("games"), "duels.yml", DuelsConfig.class, cfg -> this.duels = cfg, "Duels")
    .loadAndTransform(ConfigFolder.root(), "db.yml", DatabaseConfig.class,
            cfg -> new MySQLManager(this, cfg), mgr -> this.db = mgr)
    .loadAll()                                           // CompletableFuture<Void>
    .thenRun(() -> getSLF4JLogger().info("configs ready"));
```

⚠ **Callbacks run on the async pool, never the main thread.** No Bukkit API inside them; hand
values back through the scheduler. One malformed file logs its error and is skipped — it never
sinks the batch. `ConfigInspector` (same package) gives you per-file syntax checks, missing-key
and unknown-key reports for diagnostics commands.

## 8. Database with annotations (`pandastic-core-database`)

**Connection** — config comes from the annotation system, never hand-parsed:

```java
DatabaseConfig dbConfig = DatabaseConfig.create(new File(getDataFolder(), DatabaseConfig.DEFAULT_FILENAME));
dbConfig.loadOrCreate();                                  // host/port/database/user/password/useSSL
MySQLManager mysql = new MySQLManager(this, dbConfig);    // takes any DatabaseSettings
mysql.ensureDatabaseExists();
mysql.connect();
```

Host, port and database names are validated against injection at construction; credentials never
appear in the JDBC URL.

**Entities — annotate a DAO interface, get the implementation and repository generated:**

```java
import pandastic.studio.core.database.api.annotations.*;

@Table("players")                                        // the SQL table name
public interface PlayerDao {

    @PrimaryKey                                          // (autoIncrement = true for numeric ids)
    @Column(name = "uuid", type = SqlType.VARCHAR, length = 36, nullable = false)
    UUID getUuid();

    @Column(name = "name", type = SqlType.VARCHAR, length = 16, unique = true)
    String getName();

    @Column(name = "balance")                            // type inferred: double -> DOUBLE
    double getBalance();

    @Index(name = "idx_guild")                           // @Index / @Indexes for secondary indexes
    @ForeignKey(referencesDao = GuildDao.class, referencesColumn = "id",
                onDelete = OnDelete.CASCADE)
    @Column(name = "guild_id")
    long getGuildId();

    @Index(name = "idx_last_seen")                       // earns its keep: findWhereAsync ranges
    @Column(name = "last_seen", nullable = false)        // type inferred: Instant -> DATETIME
    Instant getLastSeen();
}
```

Generates `PlayerDaoImpl` (immutable, with a `Builder`) and
`PlayerDaoRepository extends DaoCrud<PlayerDao, UUID>`:

```java
PlayerDaoRepository players = new PlayerDaoRepository(mysql);
players.createOrMigrateAsync()                            // CREATE TABLE IF NOT EXISTS + additive ALTERs
    .thenCompose(v -> players.saveAsync(PlayerDaoImpl.builder()...build()))
    .thenCompose(k -> players.loadAsync(uuid))            // CompletableFuture<Optional<PlayerDao>>
    ...
// Full CRUD: insertAsync, updateAsync, saveAsync (upsert), deleteAsync, deleteByKeyAsync,
// existsAsync, loadAllAsync, countAsync, deleteAllAsync,
// findByColumnAsync / findOneByColumnAsync (column name checked against the declared columns),
// findWhereAsync / countWhereAsync / deleteWhereAsync / aggregateAsync (DaoQuery, below),
// migrateAsync / createOrMigrateAsync (additive schema migration, below).
```

**Targeted queries — `DaoQuery`.** Do not `loadAllAsync()` and filter in Java: every repository
inherits a filtered query surface driven by `pandastic.studio.core.database.api.DaoQuery` (nothing is
generated for it — it works on day one for every existing DAO):

```java
import pandastic.studio.core.database.api.Aggregate;
import pandastic.studio.core.database.api.DaoQuery;

DaoQuery topEarners = DaoQuery.builder()
    .where("guild_id", DaoQuery.Operator.EQUALS, guildId)          // AND-joined, in order
    .where("balance", DaoQuery.Operator.GREATER_OR_EQUAL, 100.0)
    .where("name", DaoQuery.Operator.IS_NOT_NULL)
    .orderBy("balance", DaoQuery.Direction.DESC)                   // repeatable
    .limit(10).offset(20)                                          // page 3 of 10
    .build();

players.findWhereAsync(topEarners);                       // CompletableFuture<List<PlayerDao>>
players.countWhereAsync(topEarners);                      // CompletableFuture<Long> — ignores
                                                          // orderBy/limit/offset, so the SAME query
                                                          // yields the page AND the total
players.deleteWhereAsync(DaoQuery.builder()               // CompletableFuture<Integer> affected rows
    .where("last_seen", DaoQuery.Operator.LESS_THAN, cutoff)
    .orderBy("last_seen", DaoQuery.Direction.ASC)
    .limit(1000)                                          // capped pruning: delete the 1000 oldest
    .build());
players.aggregateAsync(Aggregate.SUM, "balance",          // CompletableFuture<Optional<Double>>
    DaoQuery.builder().build());                          // empty query = whole table
```

The rules, all enforced at the API so you cannot get them wrong silently:

- **No SQL ever enters a `DaoQuery`.** It accepts `(column, operator, values...)` tuples only;
  operators are a closed enum (`EQUALS, NOT_EQUALS, LESS_THAN, LESS_OR_EQUAL, GREATER_THAN,
  GREATER_OR_EQUAL, BETWEEN, IN, IS_NULL, IS_NOT_NULL, LIKE`). Column names must be columns the
  DAO *declares* — the repository validates them at execution and fails the future with
  `Unknown column: ...` otherwise. Every value is a bound statement parameter.
- **AND-only, by design.** `OR` was deliberately left out (a flat AND chain covers the suite's
  needs; a predicate tree can come later) — do not read its absence as an oversight.
- `LIKE`: the pattern is data — `%` and `_` are **not** escaped by the library. Escape them
  yourself if the pattern embeds user input.
- `IN` with an empty collection fails fast with `IllegalArgumentException` (SQL `IN ()` is
  invalid; "matches nothing" is almost never intended). Null values are rejected everywhere —
  SQL `= NULL` never matches; use `IS_NULL` / `IS_NOT_NULL`.
- `offset()` without `limit()` fails at `build()` — MySQL requires `LIMIT` with `OFFSET`.
- `deleteWhereAsync` **requires a predicate** (a predicate-free delete fails, pointing you at
  `deleteAllAsync()`), supports `ORDER BY` + `LIMIT` (capped pruning) and rejects `OFFSET`,
  which MySQL DELETE does not support.
- `aggregateAsync` (`SUM/AVG/MIN/MAX`): SQL defines aggregates over zero rows as `NULL` —
  including `SUM` — and that surfaces as `Optional.empty()`, never a silent `0.0`. The result is
  read as `double` (aggregate in Java over `findWhereAsync` when `DECIMAL` exactness matters).
  `GROUP BY` is deliberately out — group in Java until a real need appears.

**Additive schema migration.** `migrateAsync()` diffs the declared columns against
`INFORMATION_SCHEMA.COLUMNS` (schema and table name as bound parameters) and executes one
`ALTER TABLE ... ADD COLUMN ...` per declared-but-missing column, built from the exact same DDL
fragment `CREATE TABLE` uses, returning the executed statements. `createOrMigrateAsync()` is the
one-call entry point for `onEnable` (create if missing, then migrate). Limits, by design: a
declared column that exists with a *different* type is logged as a WARNING and left untouched;
drops, type changes, nullability changes and reordering are out of scope — reconcile those
manually. `migrateAsync()` alone fails if the table does not exist yet.

- `@Column` on no-arg getters only; `type` defaults are inferred from the Java type
  (`UUID → VARCHAR(36)`, `String → VARCHAR(255)` unless `length` given, dates → `DATETIME`,
  `LocalTime → TIME`, `short → SMALLINT`, `byte → TINYINT`, `char → CHAR(1)`...).
- `length` applies **only** to `VARCHAR`/`CHAR`; it is ignored for `TEXT`/`BLOB` and every other
  type (use `sqlType` for parameterised types like `DECIMAL(10,2)`).
- `@Index` or `unique = true` on a `TEXT`/`BLOB` column generates DDL without a key prefix
  length, which MySQL rejects with **error 1170 at CREATE TABLE**. Index a bounded `VARCHAR(n)`
  instead.
- `BigDecimal` is the money type: it binds natively (`setBigDecimal`/`getBigDecimal`), so
  exactness survives the round trip — but only if the column is exact too. The inferred DDL for
  `BigDecimal` is `TEXT`; declare `sqlType = "DECIMAL(p,s)"` explicitly. A `DOUBLE` column
  round-trips through binary floating point and keeps double's rounding, whatever the Java type.
- Enum columns store `Enum.name()` and read back via `Enum.valueOf`, which **throws on unknown
  constants**: one renamed constant fails the whole `loadAllAsync`. For rename-prone enums,
  persist a stable string key in a `String` column instead.
- `sqlType`/`defaultExpr` are verbatim SQL by design — developer-authored, so never feed them
  runtime input.
- Every table/column/index identifier is validated at repository construction
  (`[A-Za-z0-9_$]{1,64}`); an entity with a bad name refuses to construct instead of running.
- All operations are async (`CompletableFuture`) and run on the manager's **own dedicated
  database thread** (`pandastic-db-<database>`), never the Bukkit scheduler. That makes them
  **startup-safe**: schema setup in `onEnable` may block briefly
  (`repo.createOrMigrateAsync().get(10, SECONDS)`) and will actually complete — the Bukkit
  async scheduler does not dispatch until the first server tick, which is after every plugin has
  enabled, so a scheduler-backed future joined in `onEnable` can only ever time out. Completions
  fire on the database thread: hop to the main thread before touching Bukkit API.
- **That one thread is the whole concurrency budget for its manager.** Every operation on every
  repository built on the same `MySQLManager` serialises through its single thread and single
  connection — and in a suite where one plugin owns the data layer and exposes it to the others
  as services, that means one plugin's bulk load delays every other plugin's reads on the same
  database. Keep statements small (that is what `DaoQuery` is for), and **never leave CPU work
  on a DB-future continuation**: `thenApply` runs on the database thread and stalls every queued
  statement behind it — hop off (`thenApplyAsync`, or the main thread) for anything beyond
  trivial plumbing.
- **Entity proxies are identity-equals**: two loads of the same row are *never* `equals()`, and
  `hashCode()` is identity-based. Do not use DAO entities as `HashSet` members or map keys —
  key collections by the primary key value instead.
- A timestamp column deserves an `@Index(name = ...)` now: `findWhereAsync` can serve a
  `BETWEEN`/`LESS_THAN` range over it. (Before `DaoQuery`, the only generated finder was exact
  equality, so an index there was dead weight.)
- Call `mysql.shutdown()` from `onDisable` once your repositories are done; the thread is a
  daemon, so forgetting it leaks one idle thread but never blocks server shutdown. If your
  plugin supports hot reloads (plugman), prefer `mysql.awaitShutdown(Duration.ofSeconds(5))`:
  queued tasks close over your plugin's classes, so a queue that outlives the plugin pins its
  classloader until it drains — `awaitShutdown` waits for the drain and tells you whether it
  happened.

## 9. Your own DTOs across plugins

Any value that must travel between plugins (through a service call, a shared cache, a map):

```java
import pandastic.studio.core.util.api.data.Dto;

public record WarpDto(String name, UUID world, double x, double y, double z) implements Dto {}
```

- **Must be a `public record` implementing `Dto`** — that gets the zero-serialization
  reconstruction path, works in `Optional`/`List`/`Map`/`CompletableFuture`, and may nest other
  `Dto` records and library enums.
- Keep components to JDK/Bukkit value types, other `Dto`s, or library interfaces. Keep it
  immutable — it is copied across the boundary, not shared.
- `SkinDto` (`pandastic.studio.core.util.api.data.SkinDto`) is the built-in example.

## 10. Gotchas checklist

- Init in `onEnable`; `shutdown()` in `onDisable`; both idempotent.
- Everything async completes on the shared pool — schedule back to main before Bukkit calls.
- Shared caches and bridged `Map`s are live and JVM-wide: your `clear()` clears it for everyone.
- After a leader failover (watch for the `[PandasticCore]` log lines), re-register your services.
- Any `[PandasticCore]` warning mentioning "Rejected", "world-writable" or "re-electing" is the
  library's intrusion/foul-play telemetry — read it, do not suppress it.
- SNAPSHOT dependency: `isChanging = true`, or you build against a day-old library.
- Never relocate `pandastic.studio.core`; always `mergeServiceFiles()` in your shadowJar.
- Library async never depends on the Bukkit scheduler, so saving/loading works in `onEnable` and
  `onDisable`: database futures complete on the manager's own thread, configs on the JDK pool,
  profiles on the shared pool, and economy runs inline when called from the main thread (which
  `onEnable`/`onDisable` are). Blocking briefly on them during enable/disable is safe.

## 11. Bootstrap prompt — paste this into every consumer plugin's initial prompt

The block below is self-contained and written to be pasted verbatim at the start of any prompt
for an agent working on a plugin that consumes pandastic-core, together with this file as an
attachment. It encodes the non-negotiable rules: the library's APIs are the ONLY way to do
dialogs, configs, database, economy and profiles in a consumer plugin.

```text
You are working on a Paper plugin that embeds the pandastic-core library. The file PANDASTIC-CORE.md is
attached (or present in the repo): it is the library's consumer manual and the rules below are
NON-NEGOTIABLE. Deviating from them creates parallel, broken implementations of things the
library already does better.

== STEP 0 — READ PANDASTIC-CORE.md COMPLETELY. NO SKIMMING. ==

Read it top to bottom, every section, INCLUDING every code block. Do not summarize it to
yourself, do not sample it, do not stop at the table of contents. Walk it in this exact order
and do not proceed to code until you have read all of it:

  §1  Wiring the dependency        — shadow, no relocation, mergeServiceFiles, annotationProcessor,
                                     registry credentials; the library brings NO transitive deps.
  §2  Lifecycle                    — PandasticCore.instance in onEnable, shutdown() in onDisable,
                                     automatic leader failover and what it means for you.
  §3  Services                     — the shared registry, the service interface mix-ins, and the
                                     bridge-safety rules for anything you register.
  §4  Profile services             — names/UUIDs/skins/player heads for Java AND Bedrock, all
                                     behind one shared cache.
  §5  Economy service              — atomic charge/transfer, main-thread-completing futures,
                                     refusals-as-data.
  §6  Dialogs (ALL subsections,    — the annotation contract, every input kind, response views,
      6.1 through 6.6)               conditional elements, dynamic lists, pagination.
  §7  Configs                      — @Configurable declarations, generated configs,
                                     ConfigFactory/ConfigLoader/ConfigInspector.
  §8  Database                     — @Table DAOs, generated repositories, MySQLManager.
  §9  DTOs                         — public records implementing Dto, and why.
  §10 Gotchas checklist            — read it twice.

If any API detail is still unclear after reading, DO NOT GUESS and do not improvise around it:
open the library itself and read the real signatures and Javadoc (the sources ship with the
dependency; the Javadoc in this library explains the why, not just the what). Investigating is
cheap; a parallel reimplementation is not.

== HARD RULES — the library's API is the ONLY path for these ==

1. DIALOGS: every dialog is an annotated controller (@Dialog + @DialogBody/@DialogInput/
   @DialogButton/@DialogButtonHandler) built through pandastic-core's DialogFactory.create(...).
   NEVER assemble dialogs through Paper's raw dialog registry/builder APIs directly. Paper's
   element types (DialogBody.plainMessage, DialogInput.text/numberRange/singleOption/bool,
   ActionButton.builder) are what your provider METHODS return — the assembly, callbacks,
   conditionals and lifecycle belong to the pandastic-core factory. Conditional element = return null.

2. CONFIGS: every configuration is a @Configurable declaration (prefer an interface with
   @ConfigValue default methods — the defaults live there) and is consumed through the GENERATED
   class. Load with ConfigFactory (one file) or ConfigLoader (batches, chainable); diagnose with
   ConfigInspector. NEVER hand-parse YAML, never roll your own getConfig()/config.yml handling,
   never write a config class by hand that the processor could generate.

3. DATABASE: every table is a @Table-annotated DAO interface; persistence goes through the
   GENERATED <Dao>Repository (extends DaoCrud) against a MySQLManager constructed from the
   generated DatabaseConfig. NEVER open your own JDBC connections, never write raw SQL strings,
   never add a connection/ORM library — the repository's async CRUD + findByColumn cover it, and
   its identifier validation is the SQL-injection defence you would otherwise lose.

4. ECONOMY: all money operations go through pandastic.getEconomyService(). NEVER call Vault directly,
   never add a Vault dependency, and NEVER do has() followed by withdraw() — that is a race
   between plugins; charge() exists precisely to do check+take atomically. transfer() refunds the
   payer if the deposit leg fails. Refusals come back as EconomyTransactionDto with success()
   false — handle them as data, not exceptions. These futures complete ON THE MAIN THREAD.

5. PROFILES & PLAYER HEADS: names, UUIDs, skins and inline player-head components come from the
   profile services (getUniversalProfileService unless you have an edition-specific reason) and
   PandasticComponentUtil. NEVER call Mojang/playerdb/Geyser APIs yourself and never build your own
   profile cache — the shared cache already serves every plugin. One-UUID-in player head:
   profiles.getPlayerHeadComponent(uuid). Sprites: PandasticComponentUtil.sprite(atlas, sprite) — the
   atlas is always explicit.

6. CROSS-PLUGIN STATE: anything shared with other plugins is a service registered in
   pandastic.getServiceManager(), exposing interfaces only; values that cross are public records
   implementing Dto. Never share concrete classes, never serialize by hand.

7. LIFECYCLE & THREADING: PandasticCore.instance(this) in onEnable (never onLoad), pandastic.shutdown() in
   onDisable, always. Library futures complete on the shared async pool (EXCEPT economy: main
   thread) — hop to the main thread before touching Bukkit API from async completions.

8. BEFORE WRITING ANY SYSTEM, CHECK IT DOES NOT ALREADY EXIST. If pandastic-core already provides the
   capability (or 80% of it), use it or extend it through its intended seams. If you believe
   something genuinely is missing from the library, SAY SO explicitly in your report instead of
   silently hand-rolling a parallel version inside the plugin.

Acknowledge these rules by following them, not by repeating them. When your work touches
dialogs, configs, database, economy or profiles, re-open the corresponding PANDASTIC-CORE.md section
FIRST and write code that matches its examples and contracts exactly.
```
