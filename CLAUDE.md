# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

pandastic-core is a **shaded, unrelocated library** for Paper 26.3 plugins (Java 25). It is *not* a
plugin: there is no plugin.yml, nothing to install on the server, and that is a deliberate,
owner-ratified decision (see `docs/SECURITY.md` §5.6 — a standalone "anchor plugin" was prototyped
and rejected). Every consuming plugin embeds the whole jar, so the same fully-qualified class names
exist once **per plugin classloader**. The library's core job is to make those copies cooperate: the
first plugin to initialise is elected **leader** and owns the shared runtime (thread pool,
HttpClient, service registry, caches); later plugins become **followers** that reach the leader
through a reflection/MethodHandle bridge. The rendezvous is a `ConcurrentHashMap` stored in
`System.getProperties()` under `pandastic.studio.core.anchor.v2`.

Entry point for consumers: `PandasticCore.instance(JavaPlugin)`.

## Commands

```bash
./gradlew build                                 # compiles everything, runs all tests (must stay warning-free)
./gradlew :pandastic-core-bootstrap:test        # one module's tests
./gradlew :pandastic-core-bootstrap:test --tests 'pandastic.studio.core.bootstrap.internal.PandasticFailoverTest'   # one suite
./gradlew clean build                           # full verification from scratch
./gradlew publishShadowPublicationToMavenLocal                # local publish for smoke tests
./gradlew publishShadowPublicationToGitHubPackagesRepository   # needs credentials (see below)
```

- The deliverable is the **root** shaded jar: `build/libs/pandastic-core-<version>.jar`. Module
  jars are intermediates. The root `jar` task produces a `-thin` classifier precisely so it never
  shadows the shaded artifact.
- Compiler runs with `-Xlint:deprecation -Xlint:unchecked` and the project treats any warning as a
  defect. No `@SuppressWarnings` without a comment proving the cast safe; the codebase currently
  compiles clean and should stay that way.
- Java toolchain is pinned to 25 (Paper 26.3 is compiled for it). Paper is tracked as
  `26.3.build.+` in `gradle/libs.versions.toml` — note 26.3 is still Paper's BETA channel.
- Publishing credentials are read from the environment (`GITHUB_ACTOR` or `GITHUB_USER`, plus
  `GITHUB_TOKEN`) with the `gpr.user` / `gpr.token` Gradle properties as fallback — put those in
  `~/.gradle/gradle.properties`, never in this repository, which is public. There is deliberately
  no `.env` mechanism. CI (`.github/workflows/publish.yml`) publishes to GitHub Packages on every
  push to `master` using the workflow's automatic token, so nothing has to be configured there;
  publish only runs after `build` (all tests) is green.
- `gradlew` must keep mode `100755` in the git index. It was once committed from Windows as
  `100644` and CI died with exit 126; fix is `git update-index --chmod=+x gradlew` (a partial
  `git commit -- gradlew` silently reverts the mode on Windows — use a normal commit).

## Module graph — and why it is shaped this way

```
pandastic-core-util              Dto/SkinDto, Describable, MiniMessage helper. Depends on nothing.
pandastic-core-bridge-contract   SOURCE-retention annotation DSL (@BridgeApi, @BridgeMethod,
                                 @BridgeAnchorKey, @BridgeContractVersion, policies). Depends on
                                 nothing.
pandastic-core-service-api       The bridged API surface (PandasticCore, PandasticServiceManager,
                                 PandasticService hierarchy, caches, PandasticProfileService)
                                 + PandasticAnchorKeys + the GENERATED contract.
pandastic-core-bootstrap         The machinery: election, anchor, validators, bridge, failover.
                                 Depends on pandastic-core-service-api because its classes
                                 IMPLEMENT that API (PandasticReflectiveApi is a PandasticCore;
                                 PandasticBridgeServiceManager is a PandasticServiceManager).
pandastic-core-services          Leader implementation: PandasticCoreImpl,
                                 PandasticServiceManagerImpl, PandasticRuntime, the three profile
                                 services. Depends on pandastic-core-bootstrap.
pandastic-core-config            @Configurable annotation API + config IO helpers. Applies the
                                 processor.
pandastic-core-database          MySQLManager + DAO annotations + DaoCrud. Depends on
                                 pandastic-core-config.
pandastic-core-dialogs           Annotation-driven Paper dialog factory. Depends on nothing.
pandastic-core-annotation-processor  Three processors. DEPENDS ON NO PROJECT — see below.
```

Shared compile-only deps (paper-api, jetbrains-annotations, jspecify, essentialsx, floodgate) are
declared ONCE in the root `subprojects {}` block. Never add them per module, and never let any of
them (or any third-party class) into the shaded jar — the server provides Paper; the jar currently
contains only `pandastic/studio/**` and that is asserted manually after shading changes.

### Hard invariants (violating any of these breaks the build or the architecture)

1. **The annotation processor has zero project dependencies.** Modules that declare annotated types
   apply it via `annotationProcessor(project(":pandastic-core-annotation-processor"))`; a compile
   dependency in the other direction is a Gradle task cycle. Consequently the processors read
   annotations through the `javax.lang.model` mirror API and keep each annotation FQN as a single
   documented string constant — that duplication-free literal IS the de-hardcoding policy here.
2. **The whole annotated bridge surface lives in one module (`pandastic-core-service-api`).** The
   contract annotations are SOURCE-retention and `BridgeContractProcessor` emits ONE file pair
   (`pandastic.studio.core.bootstrap.api.GeneratedBridgeContract` / `GeneratedBridgeIds`) at a fixed
   FQN. Splitting `@BridgeApi` types, `PandasticAnchorKeys` or `@BridgeContractVersion` across
   modules would generate duplicate classes (shaded-jar collision) with half the content each.
3. **Dependency inversion at the two seams is done with `ServiceLoader`, never class names:**
   `PandasticCoreProvider` (bootstrap discovers the leader impl in pandastic-core-services) and
   `PandasticCoreEntrypoint` (`PandasticCore.instance` discovers the bootstrap). Both load from
   their own classloader so each shaded copy finds the provider packaged next to it.
   `mergeServiceFiles()` in the root `shadowJar` keeps those `META-INF/services` entries alive — do
   not remove it.
4. **Version bumps are load-bearing.** `PandasticLibrary.ANCHOR_LAYOUT_VERSION` (part of the anchor
   property key) must be bumped when any anchor slot's shape changes incompatibly;
   `@BridgeContractVersion` / protocol when the invocation contract changes. Forgetting converts
   "two library versions silently split" into "two versions silently corrupt each other".
   Additive anchor slots (new keys) do NOT require a bump.
5. **Publishing uses the `shadow` component, not `components["java"]`.** The java component's POM
   would declare the nine subprojects as dependencies while the jar already embeds their classes —
   consumers would resolve everything twice. The generated POM must have zero dependencies.
6. **No library async work may depend on the Bukkit scheduler.** Scheduler tasks are only
   dispatched once the server ticks, and plugins enable BEFORE the first tick — so a
   scheduler-backed future joined in `onEnable` can only time out (this deadlocked a real
   consumer; see `MySQLManagerAsyncTest`). Database work runs on a dedicated per-manager thread,
   configs on the JDK common pool, profiles on the runtime pool. The ONE sanctioned exception is
   the economy's cross-thread hop (Vault requires the main thread): its inline fast path keeps
   `onEnable`/`onDisable` scheduler-free, and a scheduler refusal fails the future loudly
   (`PandasticEconomyServiceStartupTest` pins both properties).

## The bridge — what may cross a classloader boundary

All marshalling lives in `pandastic-core-bootstrap/.../bridge/`. The rules (enforced by
`BridgeTypeContract.isBridgeSafe` and `BridgeValueAdapter.adapt`):

- **Parent-visible types** (JDK, Bukkit/Paper, Adventure) cross by reference, untouched.
- **Library interfaces** cross as JDK proxies built in the receiver's loader
  (`GenericPandasticBootstrapHandler`), resolving methods by FULL erased signature (never arity)
  and only ever through `publicLookup()` — there is deliberately no `setAccessible` anywhere in the
  bridge, and `PandasticBootstrapReflection.unreflectPublic` is the single gate. A candidate
  interface is added only if it **links** from the receiver's loader (`linksCleanlyFrom`: the
  interface and every method-referenced type must resolve to the identical `Class` there). This
  prunes the mixed-copy shape — Paper's cross-plugin lookup resolves a consumer's interface whose
  signatures reference *that consumer's* library copy — which used to escape as the JDK
  ProxyBuilder's `IllegalArgumentException` and kill `registerService` (production incident,
  upstream 1.0.5). Pruned interfaces log a one-time WARN;
  `PandasticServiceManagerImpl.getService(String, Class)` compensates by unwrapping to the original
  and returning the stored registration for caller-side re-viewing (`PandasticBridgeMixedCopyTest`
  pins all of it; the doubled-API harness alone cannot see this).
- **`Dto` implementations** (`pandastic.studio.core.util.api.data.Dto`) cross **by value**. Records
  take the reconstruction path (read foreign accessors, invoke local canonical constructor — no
  serialization); non-records fall back to Java serialization behind `BridgeSerialFilter`, a
  deny-by-default `ObjectInputFilter` whose JDK-proxy allowlist is **self-calibrated at class
  init** by probing the running JDK, and which only honours discovered names defined by the
  platform/boot loader. Prefer records for every new Dto; non-records log a one-time warning.
- **Containers** (`CompletableFuture`, `Optional`, `List`/`Set`, `Map`) are adapted element-wise
  when their declared element type mentions a library type; `Map` becomes a live translating view
  (`BridgeAdaptingMap`) so shared caches stay genuinely shared. `Optional` has its own branch —
  `canHold(Optional.class, x)` is vacuously true, which once smuggled foreign elements through.
- **Erased-`Object` positions** adapt by the value's concrete shape (Dto → value, interface →
  proxy, else contract-violation `IllegalStateException`). Known residual: containers whose
  declared *element* type is `Object` still cross live and unadapted.
- Everything read from the anchor is **untrusted input**:
  `PandasticLeaderValidator.isTrustedBridgeTarget` gates every object before a MethodHandle is
  bound to it. Name-based trust is the ceiling — the anchor is world-writable and the installed
  plugin list is the real trust base. Read `docs/SECURITY.md` before touching anything in `bridge/`
  or the validators; it documents 17 fixed findings, the accepted residuals, and the rejected
  alternatives.

## Lifecycle: election, failover, shutdown

- `PandasticBootstrap.init` elects or attaches under one JVM-global lock
  (`PandasticJvmAnchor.lock()`). Never hold that lock across blocking work — a previous bug held it
  through a 5s `awaitTermination` and stalled every plugin's bridged calls.
- Each copy memoises its own leader (`localLeader`) so a poisoned anchor cannot amplify into
  runtime-per-call creation. Tests reset it via `PandasticBootstrap.resetLocalLeaderForTesting()`.
- **Failover is eager**: every participant registers `PandasticLifecycleGuard` (a
  `PluginDisableEvent` listener). When the leader's plugin is unloaded (plugman), survivors clear
  its slots and the **earliest-joined live follower** (join timestamps in the `followers.seq`
  anchor slot, name tie-break) self-promotes immediately; other followers re-attach lazily because
  their proxies are leader-tagged and re-validate per call. Follower death prunes its registrations
  and services on the leader. The lazy `readTrustedLeader` liveness check remains the backstop. The
  whole transition logs via `PandasticCoreLog` — those log lines are the operator-facing intrusion/
  failover telemetry and must not be silenced.
- `releaseLeader` tears down the caller's own runtime even when it is no longer the elected
  leader (post-failover shutdown) — otherwise its thread pool leaks forever.

## Code generation (three processors, all in pandastic-core-annotation-processor)

- `BridgeContractProcessor` → `GeneratedBridgeContract`/`GeneratedBridgeIds` from the annotated
  surface in pandastic-core-service-api. When debugging contract-id mismatches, read the generated
  files in `pandastic-core-service-api/build/generated/sources/annotationProcessor/`.
- `ConfigContractProcessor` → one `GeneratedConfig` implementation per `@Configurable` type.
  Supports classes (fields) AND interfaces (methods). Defaults are seeded into the generated
  no-arg constructor: from a template instance for classes, via `Interface.super.method()` for
  interface `default` methods. The full supported type matrix (all primitives/boxes, boxed lists,
  primitive arrays, `ConfigurationSerializable`) is documented on the processor class; anything
  else must be a compile ERROR, never silently-broken generated code.
- `TableProcessor` → `<Dao>Impl` + `<Dao>Repository extends DaoCrud` from `@Table` interfaces.
  Generated repositories feed table/column names through `DaoCrud`'s identifier validation —
  that validation and `MySQLManager`'s host/port/database checks are the SQL/JDBC injection
  defence; do not bypass them with raw SQL fragments.

The pattern for config consumers: declare a `@Configurable` interface with `@ConfigValue` default
methods (see `DatabaseSettings` → generated `DatabaseConfig implements DatabaseSettings`), load via
`ConfigFactory`/`ConfigLoader` in `pandastic.studio.core.service.api.config.io` (callbacks run on
the async pool — never touch Bukkit API inside them).

## Testing

~218 executed tests across 34 suites (`pandastic-core-bootstrap` 100, `pandastic-core-database` 44,
`pandastic-core-services` 33, `pandastic-core-config` 29, `pandastic-core-util` 12 — 195 declared
test methods, the rest are `@ParameterizedTest` expansions). The crown jewel is the
**cross-classloader harness**: `IsolatedLibraryClassLoader` (bootstrap test sources) is child-first
for `pandastic.studio.core.*`, re-defining the same classes in two loaders so the same FQN yields
two distinct `Class` objects — exactly a Paper server's shape. Any change to the bridge, Dto
transfer, serial filter, or trust validators needs a test through that harness, not just a
same-loader unit test. Election/teardown state machine logic is tested via package-private seams
on a private anchor map (`PandasticFailoverTest`); there is still **no end-to-end
`init`/`releaseLeader` test with a real server** — that and a build-time walk of every `@BridgeApi`
signature through `isBridgeSafe` are the two known test gaps.

## Conventions

- Everything in English: code, Javadoc, log messages, commit messages.
- Explicit imports only; the lone sanctioned exception is a fully-qualified name when two simple
  names collide (e.g. the dialog API's `Dialog` vs Paper's `Dialog` in `pandastic-core-dialogs`).
- Every public and package-private type carries real Javadoc — this codebase's Javadoc explains
  *why* (threat models, lock ordering, seeding rules), not just *what*. Match that bar.
- Internal vs API packages: `pandastic.studio.core.<area>.api` is consumer-facing;
  `...internal...` is not. Never leak internal types through API signatures.
- Null-safety annotations: JetBrains `@NotNull`/`@Nullable` on the API surface (declared once in
  the root build as compileOnly).
