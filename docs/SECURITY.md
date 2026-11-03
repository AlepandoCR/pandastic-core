# Pandastic-Core Security Audit

> **Provenance.** This audit was inherited from the upstream `smp-core` project at version
> 1.0.6-SNAPSHOT, the tree pandastic-core was ported from. The code it describes is the same code:
> only the package root (`org.smp.core` → `pandastic.studio.core`), the type prefix (`Smp` →
> `Pandastic`), the module names and the project name changed in the port. Every date, finding,
> acceptance and rejection below is reproduced unaltered, because it is the real history of the
> code shipping in this repository. New findings against pandastic-core belong in new sections.

| | |
|---|---|
| **Date** | 2026-10-10 |
| **Updated** | 2026-10-10, same-day follow-up: the three accepted findings (A-1, A-2, A-3) were fixed; Section 3 records each original acceptance and its fix. The service API moved from `pandastic-core-bootstrap` to the new `pandastic-core-service-api` module after the first pass; paths below reflect that. |
| **Scope** | `pandastic-core-bootstrap` (leader election, JVM anchor, reflection/MethodHandle bridge, DTO transfer, follower registry), `pandastic-core-service-api` (bridged API surface, generated contract), `pandastic-core-bridge-contract` (contract annotations), `pandastic-core-database` (`MySQLManager`, `DaoCrud` — two findings fixed in an earlier pass), `pandastic-core-config` (defaults, tested surface) |
| **Out of scope** | `pandastic-core-dialogs`, `pandastic-core-services` (except `PandasticServiceManagerImpl` unregistration), `pandastic-core-util`, `pandastic-core-annotation-processor` (build-time only) |
| **Verification** | Every fix below was re-verified against the current tree, and is exercised by the project's test suite (107 tests across 22 suites; see [Section 6](#6-testing-status)) |

Pandastic-Core is a Java 25 library shaded **unrelocated** into multiple Paper plugins. Each
plugin classloader holds its own copy of the same fully-qualified class names. The first
plugin to initialise becomes the **leader** and owns the shared runtime (thread pool,
`HttpClient`, service registry, caches); later plugins are **followers** that reach the
leader through a reflection/`MethodHandle` bridge. Rendezvous between copies happens via a
map stored in `System.getProperties()` under
`pandastic.studio.core.anchor.v2` (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticLibrary.java:102`,
`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticJvmAnchor.java:65`).
That design choice is the root of most of what follows.

---

## Severity summary

| # | Severity | Finding | Status |
|---|----------|---------|--------|
| 1 | HIGH | Unvalidated `leader.serviceManager` anchor slot became a reflective invocation target | Fixed |
| 2 | HIGH | Deserialization allowlist admitted any class *named* under `pandastic.studio.core.` | Fixed |
| 3 | MEDIUM | Serialization byte limit measured after the bytes were produced (write-side OOM) | Fixed |
| 4 | MEDIUM | Unbounded recursion/fan-out in record-DTO reconstruction | Fixed |
| 5 | MEDIUM | Follower registry pinned classloaders and never reached zero (leak per `/reload`) | Fixed |
| 6 | MEDIUM | `leader.plugin.ref` structurally incapable of detecting a dead leader | Fixed |
| 7 | MEDIUM | Follower's cached service-manager proxy survived re-election | Fixed |
| 8 | MEDIUM | `ensureLeader` built a new shared runtime on every failed anchor read (thread exhaustion) | Fixed |
| 9 | MEDIUM | JVM-global anchor lock held across a 5-second blocking wait in `releaseLeader` | Fixed |
| 10 | MEDIUM | Unchecked casts on the world-writable followers slot crashed unrelated plugins' startup | Fixed |
| 11 | LOW | Method resolution depended on `getMethods()` enumeration order | Fixed |
| 12 | LOW | Container branches ignored declared container subtypes (`ConcurrentMap`, `Queue`, `SortedSet`) | Fixed |
| DB-1 | HIGH | JDBC URL injection via config-sourced host/port/database | Fixed (earlier) |
| DB-2 | MEDIUM | SQL identifier concatenation throughout `DaoCrud` DDL | Fixed (earlier) |
| A-1 | MEDIUM | `Object`-typed positions passed foreign library objects through unadapted | Fixed (follow-up) |
| A-2 | MEDIUM | `registerService` had no `unregisterService` counterpart (main remaining leak) | Fixed (follow-up) |
| A-3 | LOW | Serialization-fallback allowlist depended on JDK-internal proxy class names | Fixed (follow-up) |

---

## 1. Threat model

Three attackers are considered, in decreasing order of capability:

**(a) A malicious or compromised plugin in the same JVM.** Bukkit plugins are ordinary
code with no sandbox. Such a plugin can write any `System` property (including replacing
the anchor map or any slot in it), define classes in **any** package including
`pandastic.studio.core.*` (plugin classloaders enforce no package ownership), and call the bridge
exactly like a legitimate follower. It cannot be excluded — only made to work harder, fail
loudly, and gain less.

**(b) A trusted-but-buggy plugin passing hostile data.** The plugin's code is honest, but
the DTOs it sends across the bridge are built from player input or remote responses:
oversized graphs, cyclic graphs, strings that end up interpolated into JDBC URLs or SQL
DDL. The bridge and the database layer must treat every *value* as adversarial even when
the *caller* is not.

**(c) Classloader leaks across `/reload`.** Not an attacker but a failure mode with
security consequences: any strong reference from the JVM-global anchor to a plugin object
pins that plugin's entire classloader after a reload, producing unbounded memory growth
(DoS over repeated reloads) and — worse — stale code: a disabled plugin's leader instance
continuing to serve bridged calls from a classloader the server has moved on from.

---

## 2. Findings — fixed

All findings in this section are fixed in the current tree. Each fix was located and read
in the current source while writing this report; line references are current.

### 2.1 HIGH — Unvalidated `leader.serviceManager` slot became a reflective invocation target

**Attack.** The anchor caches the leader's service manager in its own slot
(`leader.serviceManager`, declared at
`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticAnchorKeys.java:64`) so
followers can skip one bridged call. The leader slot was validated; this slot was not. An
attacker writes **only** this slot and leaves the real leader intact — so leader
validation never fires — and every follower's `getServiceManager()` binds to the
attacker's duck-typed object. Method names are resolved on its concrete class and invoked
through `MethodHandle`s: the attacker receives every service lookup and every
`registerService` from every plugin in the JVM, arguments included.

**Fix.** Three layers, all present in the current tree:

- `PandasticLeaderValidator.isTrustedBridgeTarget(candidate, interfaceFqn)`
  (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticLeaderValidator.java:79-86`)
  generalises the leader check to *every* object read out of the anchor: the candidate's
  class must be public, must live under the library package prefix, and must declare an
  interface whose fully-qualified name matches the caller's own copy of the contract type.
  `isTrustedLeader` is now one application of it (`PandasticLeaderValidator.java:63-65`).
- The `PandasticBridgeServiceManager` constructor fails closed: it refuses to construct when the
  target does not pass `isTrustedBridgeTarget` against `PandasticServiceManager`'s FQN
  (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticBridgeServiceManager.java:66-76`).
  No code path can bind a bridge onto an unvalidated object even if a caller forgets the check.
- The anchor read itself validates and falls back: `PandasticReflectiveApi.cachedLeaderServiceManager()`
  applies the same check and, on rejection, logs the attempt and fetches the manager
  through the already-validated leader — the authoritative path
  (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/service/PandasticReflectiveApi.java:134-155`,
  fallback at `:107-111`).

Tested by `PandasticBridgeTargetTrustTest`, which includes a control test demonstrating that
*without* the check the duck-typed fixture is a perfectly valid invocation target.

### 2.2 HIGH — Deserialization allowlist was not deny-by-default

**Attack.** Non-record DTOs cross the classloader boundary through a serialization
round-trip. The previous filter admitted any class whose *name* fell under
`pandastic.studio.core.` — but a package name is not a credential: any plugin can ship a class
named `pandastic.studio.core.anything`, and a Bukkit plugin classloader resolves other plugins'
classes. A library-prefixed non-DTO with a side-effecting `readObject` executed inside the
receiving plugin's loader before the caller saw a single value. This was proven by test:
`BridgeDeserializationGuardTest.theLibraryPrefixIsNotACredential` asserts the gadget's
`readObject` never runs under the fixed filter
(`pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeDeserializationGuardTest.java:45-62`).

**Fix.** `BridgeSerialFilter` is now deny-by-default, and a library-named class must pass
**both** of:

- **shape** — it is a `Dto` or an enum; nothing else belongs in a DTO graph
  (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeSerialFilter.java:224-232`);
- **origin** — it was *defined by* the receiving plugin's own classloader or one of its
  ancestors (`BridgeSerialFilter.java:233-240`, implemented at `:289-296`). Origin is the
  one property a name cannot forge: code the receiver shipped is code it chose.

Everything outside the library prefix is held to a hand-picked immutable-JDK-value
allowlist (`BridgeSerialFilter.java:99-171`); dynamic proxies are refused outright by the
resolving stream (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeDtoTransfer.java:429-432`),
which also resolves every class exclusively in the receiver's loader rather than the
stack-dependent default (`BridgeDtoTransfer.java:415-426`). The second guard test,
`aForeignPluginsDtoIsNotTheReceiversOwnCode`, covers the stronger attacker who satisfies
the shape check and is rejected on origin alone.

### 2.3 MEDIUM — Serialization byte limit measured after the bytes were produced

**Failure.** The write side serialized into an unbounded `ByteArrayOutputStream` and
compared the finished array against the limit. `writeObject` is the sending side's own
code — on a server, another plugin's code — so a hostile or buggy DTO could allocate
arbitrarily many megabytes (twice: the growing buffer and its copy) before the check ran.
`ObjectOutputStream` exposes no filter, so the byte count is the only thing boundable on
write — and it was being bounded too late, turning a size limit into an OOM vector.

**Fix.** `BoundedBuffer` in `BridgeDtoTransfer` throws `BufferLimitExceeded` **during**
the write, the moment the running count would exceed `BridgeSerialFilter.MAX_BYTES`
(64 KiB) (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeDtoTransfer.java:371-393`,
wired into serialization at `:313-332`). Tested by
`BridgeDeserializationGuardTest.theByteLimitIsEnforcedWhileWriting`, which counts bytes
the hostile `writeObject` managed to emit before being stopped.

### 2.4 MEDIUM — Unbounded recursion/fan-out in record-DTO reconstruction

**Failure.** The record path rebuilds a DTO by reading foreign accessors and invoking the
local canonical constructor, recursing through nested DTOs. A record cannot contain itself
at construction time, but the `List` handed to its constructor can be mutated afterwards
to close a cycle — `StackOverflowError`. Separately, a graph that merely shares one child
at every level (each level holding the level below twice) is linear to build and
exponential to copy.

**Fix.** A per-thread budget in `BridgeDtoTransfer` enforces both a nesting depth of 16
(`MAX_TRANSFER_DEPTH`, mirrored from the deserialization filter so both paths refuse the
same graphs, `BridgeDtoTransfer.java:71`) and a total object budget of 10,000 per
top-level transfer (`MAX_TRANSFERRED_OBJECTS`, `:81`; thread-local budget `:91`; checks
`:116-128`). The thread-local is removed at depth zero so pool threads retain nothing.
Tested by `cyclicDtoGraphsAreBounded` and `sharedChildDtoGraphsAreBounded`.

### 2.5 MEDIUM — Follower registry pinned classloaders and could never reach zero

**Failure.** The previous `PandasticApiRegistry` shape was `Map<Plugin, WeakReference<Object>>`:
strong `Plugin` keys in a JVM-global map, with a pruning condition ("the bridge was
collected") unreachable by construction — the weakly-held bridge was always reachable from
the strongly-held key. Consequences: one pinned classloader per `/reload`, **and**
`followerCount()` never hit 0, so the leader never tore down its runtime. Every reload
leaked a thread pool and an `HttpClient`.

**Fix.** The registry is now keyed by plugin **name** (a `String`, which pins nothing) and
holds `WeakReference<Plugin>`
(`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticApiRegistry.java:96-99`);
collected entries are pruned on every read (`:122-143`). Because the slot's value shape
changed incompatibly, `ANCHOR_LAYOUT_VERSION` was bumped to 2, which changes the anchor
property key itself so a version-1 copy can never misread a version-2 map
(`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticLibrary.java:97`,
key derivation at `:102`). Tested by `PandasticApiRegistryTest.theRegistryDoesNotPinAFollowersPlugin`.

### 2.6 MEDIUM — `leader.plugin.ref` was write-only and structurally incapable of working

**Failure.** The anchor recorded a `WeakReference` to the leader's owning plugin, intended
to detect a dead leader — but nothing ever read it, and it could not have worked anyway:
the leader object sits in the same anchor under a **strong** reference and holds its
owning plugin, so the weak reference was kept alive by the very slot it was meant to
report on. A disabled leader kept serving bridged calls from a dead classloader.

**Fix.** `PandasticBootstrap.readTrustedLeader` now asks the recorded plugin directly whether it
is still enabled — `plugin.isEnabled()` is independent of garbage collection and true
immediately (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticBootstrap.java:340-356`,
invoked from the trust gate at `:315-322`). A leader owned by a disabled plugin is cleared
and re-elected, with a log line explaining what was found. Exceptions from the liveness
probe (the slot is world-writable, so it may not hold a real `Plugin`) are treated as
stale, which fails toward a known-good re-election (`:349-355`).

### 2.7 MEDIUM — Follower's cached service-manager proxy survived re-election

**Failure.** A follower cached its `PandasticBridgeServiceManager` once. After the leader's
plugin was disabled and a new leader elected elsewhere, the cached proxy still pointed
into the old leader's registry — which had been cleared. Registrations went to a registry
nobody would ever read, and lookups reported no services. Silently: the worst possible
failure for a registry.

**Fix.** The cached proxy is tagged with the leader it was built from and compared by
identity on every `getServiceManager()` call; a leader swap transparently rebuilds the
proxy (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/service/PandasticReflectiveApi.java:52-63`,
check at `:98-117`). The same pattern covers resolved `MethodHandle`s: each cached call is
tagged with the leader class it was resolved against (`PandasticReflectiveApi.java:246-257`,
check at `:212`).

### 2.8 MEDIUM — `ensureLeader` built a new shared runtime on every failed anchor read

**Attack.** `ensureLeader` runs on every bridged call and elects whenever the anchor does
not hold a usable leader. It previously constructed a fresh leader — a new cached thread
pool, a new `HttpClient` with its own selector thread — on every election. The anchor is
world-writable, so an attacker keeping the leader slot poisoned converts every bridged
call into runtime construction: a few hundred calls is thread exhaustion, and none of the
orphaned runtimes is ever shut down (only the currently published leader is released).

**Fix.** The per-copy leader is memoised in `PandasticBootstrap.localLeader`, written and read
only under the anchor lock; re-election in the same classloader reclaims the existing
instance instead of constructing a second runtime
(`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticBootstrap.java:270-277`,
field at `:92`, cleared on release at `:420`).

### 2.9 MEDIUM — Anchor lock held across a 5-second blocking wait in `releaseLeader`

**Failure.** Tearing down the shared runtime waits a bounded time (`awaitTermination`,
5 s) for in-flight tasks. That wait ran while holding `PandasticJvmAnchor.lock()` — the single
JVM-global monitor every bridged call acquires. Any pool task that needed the bridge
blocked on the very lock the shutter held, so the wait was *guaranteed* to run to its
full timeout, stalling every plugin's bridged calls — on the main thread during
`/reload`, which is watchdog-kill territory.

**Fix.** `releaseLeader` now takes the decision under the lock (unpublish, count
followers), runs the teardown **outside** it, and re-acquires the lock to re-check that
nothing was published in the interim before the final anchor reset
(`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticBootstrap.java:407-441`;
the rationale is documented at `:394-402`). The reset preserves the lock object itself so
a thread already blocked on it keeps seeing the same monitor
(`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticJvmAnchor.java:116-123`).

### 2.10 MEDIUM — Unchecked casts on the world-writable followers slot

**Failure.** The followers slot was read with unchecked casts on every plugin's bootstrap
path. A hostile or merely colliding value threw `ClassCastException` into *unrelated*
plugins' startup — an attacker could break every Pandastic-Core plugin on the server by writing
one property.

**Fix.** Reads are shape-checked: a non-`ConcurrentMap` slot is logged and ignored
(`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticApiRegistry.java:77-88`),
and each entry's value is pattern-matched rather than cast, with poisoned entries pruned
in place (`PandasticApiRegistry.java:122-143`). A hostile slot degrades to "no followers". The
anchor map itself gets the same treatment, including defensive migration of a
non-concurrent map (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticJvmAnchor.java:70-97`).
Tested by `PandasticApiRegistryTest.aPoisonedFollowerSlotIsNotBelieved`.

### 2.11 LOW — Method resolution depended on `getMethods()` enumeration order

**Failure.** `getMethods()` reports a method once per declaring type, in an order the
specification leaves unspecified. Taking the first match did not change which code ran
(the handle is virtual) but did change which `Method`'s **generic** signature drove
argument adaptation: an interface declaration may erase a type variable where the
implementation names a concrete library type, and an element type that mentions no library
type is handed over unadapted — a cross-classloader value leaking through unconverted.

**Fix.** `findBySignature` collects all matches and picks the most-derived declaration
deterministically: most-derived wins, a class declaration beats an interface one, and
unrelated interfaces tie-break by name; compiler-generated bridge methods are used only
when no real declaration matches
(`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticBootstrapReflection.java:122-148`,
tie-break at `:157-171`). Tested by
`PandasticBootstrapReflectionTest.theMostSpecificDeclarationWins`.

### 2.12 LOW — Container branches ignored the declared container subtype

**Failure.** The container branches of the value adapter tested only that the declared
type was *some* `Map`/`Collection`/`CompletableFuture`, then produced a container of the
bridge's choosing (unmodifiable `List`, `LinkedHashSet`, translating map view, dependent
future). A signature declaring `ConcurrentMap`, `Queue`, or `SortedSet` received something
that is not one, and the `ClassCastException` surfaced later in the consumer's code with
nothing pointing at the bridge.

**Fix.** Every container branch now routes through `requireHoldable`, which checks the
produced container against the declared type and fails loudly with an explanation naming
the allowed container types (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeValueAdapter.java:174-183`,
applied at `:95-131`).

### 2.13 Earlier fixes in `pandastic-core-database`

**DB-1 (HIGH) — JDBC URL injection via config-sourced host/port/database.** Host, port and
database name come from plugin configuration and were interpolated directly into the JDBC
URL. A database name such as `db?allowLoadLocalInfile=true&` appends arbitrary driver
properties — `allowLoadLocalInfile` is a known MySQL Connector/J client-side file-read
vector (`LOAD DATA LOCAL INFILE` lets a malicious or spoofed server read files off the
client). Fixed in `MySQLManager` with strict validation at construction: hosts/bracketed
IPv6 literals against `VALID_HOST`
(`pandastic-core-database/src/main/java/pandastic/studio/core/database/internal/MySQLManager.java:73`, which
rejects `?`, `&`, `/`, `=` outright), the database name against the unquoted-identifier
pattern `VALID_IDENTIFIER` (`MySQLManager.java:80`), ports range-checked
(`MySQLManager.java:173-179`), all enforced in the private constructor
(`MySQLManager.java:135-146`, `requireUrlSafe` at `:158-164`).

**DB-2 (MEDIUM) — SQL identifier concatenation throughout `DaoCrud` DDL.** Table, column,
foreign-key and index names are interpolated into generated DDL because identifiers cannot
be parameterised in JDBC. Fixed by validating **every** identifier once, at repository
construction, against the same unquoted-identifier pattern
(`pandastic-core-database/src/main/java/pandastic/studio/core/database/internal/DaoCrud.java:63`, constructor
validation at `:279-285`, per-column walk at `:301-312`, `requireValidIdentifier` at
`:324-330`). Severity is tempered by the fact that identifiers originate in `@Table`/
`@Column` annotations (developer code) rather than config, making this defence-in-depth
against attacker-influenced entity definitions.

---

## 3. Findings — accepted in the first pass, fixed in the follow-up

The first pass documented these three as deliberate residuals. All three were fixed the
same day; each entry below keeps the original finding and records the fix.

### 3.1 `Object`-typed positions passed foreign library objects through unadapted — FIXED

**Original finding.** `BridgeTypeContract.canHold(Object.class, x)` is trivially true for
any non-null value, so the adapter's holdability fast path passed an `Object`-declared
value through with no adaptation. Not a deserialization risk — a reference crossing, no
`readObject` runs — but a foreign library-typed object arriving as `Object` failed on the
receiver's first downcast with a confusing cross-classloader `ClassCastException`, and the
position quietly defeated the rule that library objects cross only as proxies or by value.

**Fix.** `BridgeValueAdapter.adapt` gained a dedicated branch, sitting immediately before
the `canHold` fast path, for positions whose *erased* declared type is exactly
`java.lang.Object` (this also covers unbounded type variables) holding a library-named
value that is not holdable as the receiver's own type
(`BridgeTypeContract.isHoldableAsOwnType`). Such values are adapted by their concrete
shape, mirroring the declared-type ladder: `Dto` → value transfer through
`BridgeDtoTransfer`; library-interface-bearing → the standard cross-proxy; anything else —
including foreign library *enum constants*, which previously slipped through — → the
adapter's standard contract-violation `IllegalStateException` at the bridge. The receiver
classloader, which an `Object` position cannot name, is pinned by a `RECEIVER_CONTEXT`
`ThreadLocal` around the argument-adaptation loop and the DTO component-conversion path.
Parent-visible values and same-loader library objects pass through exactly as before
(regression-tested). Documented residual: containers whose declared *element* type is
`Object` (`Map<K, Object>`, raw collections) still cross live with unadapted elements,
because adapting them would break identity/liveness for parent-visible payloads; this is
stated in both classes' Javadoc.

**Tests.** `BridgeObjectPositionAdaptationTest` (7 cross-classloader tests): foreign Dto in
an `Object` record component arrives as the receiver's type; foreign interface-bearing
object arrives as a working proxy; foreign plain class rejected at the bridge; `java.io.File`
and same-loader library objects still pass by reference; unbounded-type-variable erasure.

### 3.2 `registerService` had no `unregisterService` counterpart — FIXED

**Original finding.** `PandasticServiceManager`
(`pandastic-core-service-api/src/main/java/pandastic/studio/core/service/api/PandasticServiceManager.java`) exposed
`registerService` only. A follower registering a service placed a strong reference to its
own object — and therefore its classloader — into the leader's registry until the leader
died: the main remaining leak once the follower registry (finding 2.5) became weak.

**Fix.** Two bridged methods were added, each `@BridgeMethod(stability = DERIVED_ID,
sinceProtocol = 2)` with generated ids
`PandasticServiceManager.unregisterService__PandasticService` / `unregisterAll__Plugin`:

- `boolean unregisterService(@NotNull PandasticService service)` — identity match first, then
  service-name equality. Name fallback is deliberate: every bridge crossing mints a fresh
  proxy, so cross-loader reference equality never holds, while `getServiceName()` delegates
  through the proxy and the registry already enforces the name as unique key.
- `int unregisterAll(@NotNull Plugin plugin)` — matches `getBoundPlugin()` by plugin *name*;
  a reloaded plugin is a new instance and classloader, so instance equality would never
  match precisely when unregistration matters most.

`PandasticReflectiveApi.shutdown()` now calls `unregisterAll(caller)` best-effort through the
already-built bridge before detaching — deliberately not via `getServiceManager()`, which
could elect a fresh leader inside the shutting-down plugin's classloader. The leader's own
`shutdown()` continues to clear the whole registry; the interplay is documented at both
sites. Rejecting registrations from disabled plugins was evaluated and left out:
`isEnabled() == false` cannot distinguish legitimate `onLoad`-time registration from the
leak case, and the shutdown wiring removes the leak regardless.

**Tests.** `PandasticServiceManagerImplTest` (6, including proxy-identity and reloaded-plugin
matching) and the cross-loader `PandasticBridgeServiceManagerUnregisterTest` (2: the leader holds
a proxy, unregistering with the original object works, `boolean`/`int` cross back).

### 3.3 Serialization-fallback allowlist depended on JDK-internal proxy class names — FIXED

**Original finding.** The JDK allowlist in `BridgeSerialFilter` named internal
serialization proxies (`java.time.Ser`, `java.util.CollSer` /
`ImmutableCollections$CollSer`, `EnumSet$SerializationProxy`). Those names are not
specified API; a JDK rename breaks non-record DTOs as a silent availability failure.

**Fix — self-calibrating allowlist.** A static initializer serializes ten probe graphs
(`List.of`/`Set.of`/`Map.of` in both capacity variants, `Instant`, regular and jumbo
`EnumSet`, an unmodifiable wrapper) and deserializes each through a recording
`ObjectInputFilter`, collecting the exact class names the *running* JDK asks about. The
production filter honours a discovered name **only when the resolved class is defined by
the bootstrap or platform loader** — the name is never the credential, the defining loader
is — and falls back to the old hardcoded set if probing ever fails (fail-closed: a stale
fallback rejects, never admits). Probing immediately paid for itself: **the old hardcoded
list had a latent bug** — `java.util.RegularEnumSet`/`JumboEnumSet` were never listed, so
any non-record `Dto` carrying an `EnumSet` was being rejected on JDK 25 despite
`EnumSet$SerializationProxy` being allowlisted. The discovered set on JDK 25.0.2 has 20
entries and admits all probe shapes.

**Fix — steering toward records.** `BridgeDtoTransfer` now logs a one-time-per-type
warning when a non-record `Dto` takes the serialization fallback, naming the class to
convert; `Dto`'s Javadoc states records are the first-class citizens and serialization is
a capped compatibility path. Making records mandatory (deleting the fallback outright)
remains the end state to consider once no non-record `Dto`s exist.

**Tests.** `BridgeSerialFilterDiscoveryTest` (3: discovery non-empty and platform-defined,
nine payload round-trips across two loaders, same-name class from a non-platform loader
rejected with a platform-defined positive control) and `BridgeDtoFallbackWarningTest` (1:
warning fires exactly once across two transfers). `BridgeDeserializationGuardTest` and
`BridgeDtoTransferTest` pass byte-for-byte unchanged, pinning deny-by-default and the
shape/origin rules.

---

## 4. Residual risks (architecture-level)

These are properties of the current rendezvous design that no amount of validation inside
the library can remove.

- **The `System.getProperties()` rendezvous is world-writable and unfixable from inside.**
  Any plugin can replace the whole anchor map, swap the `lock` object (two monitors means
  two mutual exclusions means two leaders), call `System.setProperties()` and wipe
  everything, or keep the leader slot poisoned to force endless re-election. Every current
  defence validates what comes **out** of the anchor; nothing can lock what goes **in**.
  This asymmetry is acknowledged in the code itself
  (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticJvmAnchor.java:21-38`).
- **Name-based trust is the ceiling.** Two unrelocated copies share exactly one identity:
  the fully-qualified name. An object genuinely implementing an interface *named*
  `pandastic.studio.core.service.api.PandasticCore` is indistinguishable from a real leader
  (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticLeaderValidator.java:38-45`
  states this plainly). The validator stops the cheap attack — duck typing — and makes the
  expensive one loud; it cannot make it impossible. Installed plugins are part of the
  trust base.
- **`registerBridgePackage` widens proxyability — and only proxyability — by explicit opt-in.**
  A plugin may register one of its own package prefixes (`PandasticCore.registerBridgePackage`),
  after which the bridge treats *interfaces* under that prefix like library interfaces when
  building cross-classloader proxies (`BridgeTypeContract.isProxyableInterfaceType`). Nothing
  else widens: concrete classes stay uncrossable, the `Dto` value rules and `isBridgeSafe` are
  untouched, and the trust is name-prefix-based — consistent with the ceiling above, since a
  prefix proves shape, not origin, and the installed plugin list remains the real trust base.
  Registration is validated (a dot-separated package name of at least two segments that may
  not equal, contain or be contained by `pandastic.studio.core`, `java`, `javax`, `jdk`, `sun`,
  `com.sun`, `org.bukkit`, `io.papermc` or `net.kyori` — a wildcard-ish prefix would defeat
  the type contract entirely), stored in an additive, shape-checked anchor slot that degrades
  to "no trusted prefixes" when poisoned, and dropped when the owning plugin's services are
  unregistered or its plugin dies. Every registration is logged at INFO naming the owning
  plugin and the prefix; that line is operator telemetry for a deliberately widened boundary
  and must not be silenced.
- **One JVM-global monitor serialises all cross-plugin bridged calls**
  (`PandasticJvmAnchor.lock()`). It is a throughput ceiling and a priority-inversion surface —
  and because `Properties` methods are `synchronized`, the initial `anchorMap()` lookup
  additionally contends with every `System.getProperty()` call in the JVM.
- **Proxies pin the leader classloader by design**, and live shared maps handed across the
  bridge (`BridgeAdaptingMap` views, pass-through maps) are a cross-plugin integrity
  surface: any plugin holding the view can `clear()` the leader's live state.
- **Unrelocated shading makes version skew a silent split.** Two plugins shading different
  library versions elect separate leaders and simply stop sharing, with no error. The
  `ANCHOR_LAYOUT_VERSION` and bridge protocol version bumps
  (`pandastic-core-bootstrap/src/main/java/pandastic/studio/core/bootstrap/internal/PandasticLibrary.java:97`,
  `PandasticBootstrap.java:34`) are load-bearing discipline: forgetting to bump on an
  incompatible change converts "silent split" into "silent corruption".

---

## 5. Fixing the rendezvous

This is the section that matters most for the project's future. Everything in Section 2
hardens the *reading* of a fundamentally unownable location. The options below change the
location.

### 5.1 Option (a): Bukkit `ServicesManager` as the rendezvous

Register the leader with Bukkit's `ServicesManager` instead of (or in addition to) the
system property; followers look the leader up as a Bukkit service.

**Pros.**
- Plugin-scoped lifecycle: Bukkit automatically unregisters a plugin's services when it is
  disabled. Findings 2.5 and 2.6 (stale leaders, pinned classloaders) largely dissolve —
  the server, not the library, answers "is the owner alive".
- The registration is visible to operators in standard tooling (`/services` style
  debugging, timings attribution), unlike an opaque system property.
- The registering `Plugin` is a first-class part of the record; no separate
  `leader.plugin.ref` slot to keep honest.

**Cons and the contract-type problem.**
- Still spoofable: any plugin can register first, or register at `ServicePriority.Highest`
  and shadow the real leader. The trust ceiling does not move; `PandasticLeaderValidator` stays.
- The real obstacle is that `RegisteredServiceProvider` is keyed by a `Class<T>` token,
  and the server classpath does not contain `pandastic.studio.core.*`. Each shaded copy's
  `PandasticCore.class` is a *different* class object, so plugin B cannot look up what plugin A
  registered under A's token. Workarounds:
  - **Marker-only registration:** register under a parent-visible type that all copies
    share (in the degenerate case, `Object.class` filtered by provider-class name). Works,
    but reintroduces name-based matching — the validator is doing the same job in a
    different store.
  - **Key by name:** iterate `getKnownServices()` and match the service class's FQN
    against `PandasticCore`'s name. Same trust properties as today, better lifecycle.
- Either way the anchor's non-leader slots (protocol version, follower registry) still
  need somewhere to live, so this option shrinks the property-based surface rather than
  eliminating it.

**Verdict.** A meaningful lifecycle upgrade and a modest integrity upgrade, achievable
without changing the deployment model. It does not solve class identity.

### 5.2 Option (b): a tiny standalone "anchor plugin" — the architecturally correct fix

Ship the shared state's owner as its own small plugin (or a relocated, versioned core
plugin). It contains the bridge contract types (`PandasticCore`, `PandasticServiceManager`, `Dto`, the
anchor/registry) in **one** classloader. Every consuming plugin declares a plugin
dependency on it (`depend:` / `paper-plugin.yml` dependencies), which makes the anchor
plugin's classes *parent-visible* to all consumers through Bukkit's plugin classloading.

**Why this is the only structurally different option.** It is the only design that gives a
single `Class` identity for the contract. With one loader owning `PandasticCore.class`:

- `instanceof` works. The entire name-based trust machinery — `isTrustedBridgeTarget`,
  `implementsByName`, recorded-class tampering checks — becomes a real type check.
- The reflection/`MethodHandle` bridge disappears for the contract surface: consumers call
  the interface directly. No method resolution (2.11), no value adaptation at the contract
  boundary, no cross-loader proxies for contract types.
- DTO transfer disappears for contract types: both sides hold the same `Class`, so values
  pass by reference. Findings 2.2, 2.3, 2.4 become moot *for the contract surface*.
- The anchor property disappears entirely; the anchor plugin's static state (or its Bukkit
  service registration) is the rendezvous, and it is writable only by code inside that
  plugin.

**Migration path.**
1. Extract `pandastic-core-bridge-contract` plus the stable API interfaces (`PandasticCore`,
   `PandasticServiceManager`, `PandasticService`, `Dto`) into the anchor plugin's jar. This is close
   to the existing module boundary — `pandastic-core-bridge-contract` already exists as a separate
   module for exactly this kind of split.
2. The anchor plugin owns the shared runtime (pool, `HttpClient`, registry) and registers
   its `PandasticCore` with Bukkit's `ServicesManager` — here option (a)'s weakness is gone,
   because the `Class` token is shared and the lookup is typed.
3. Shaded copies shrink to thin clients: resolve the service, use it. The shaded portion
   becomes convenience/implementation code only, and *must not* contain contract types
   (enforceable with a build-time check that the shaded jar contains no
   `pandastic.studio.core.service.api.*` classes).
4. Compatibility bridge for one release: the thin client publishes/reads the old anchor
   property alongside the new mechanism, so mixed deployments degrade to today's
   behaviour instead of splitting.
5. Version skew becomes explicit and manageable: the anchor plugin versions its API like
   any plugin dependency, instead of the current silent protocol split.

**Costs.** A new deployment artifact server owners must install (the strongest objection
in practice — "drop one jar in" becomes "drop two"); a hard dependency edge affecting
plugin load order; the anchor plugin itself must be versioned carefully since all
consumers share one copy. These are real but ordinary plugin-ecosystem costs (this is
exactly how Vault, PlaceholderAPI and ProtocolLib work), as opposed to the current
design's extraordinary ones.

### 5.3 Option (c): capability token / first-writer-wins sealing

Keep `System.getProperties()` but make the stored value harder to forge or replace:
publish the anchor with `putIfAbsent` so the first writer wins; make the anchor value an
opaque object whose *identity* is the credential (followers compare `==` against what they
first saw); add an HMAC over the map contents keyed by a per-boot random key held only by
the leader, verified on every read.

**What it closes.** Casual overwrites get detected: a swapped map or slot no longer
verifies, and the library can fail loudly instead of bridging onto the replacement.
Identity-pinning the first-seen anchor defeats later whole-map replacement *for processes
that already joined*.

**What it cannot close — stated honestly.** Anything readable and writable by every
plugin cannot be made unforgeable. The attacker runs in the same JVM with the same
privileges: they can read the anchor before anyone else (plugin load order is not a
security boundary), take the real sealed object and wrap it, present the original
identity while interposing on calls, or simply install themselves *first* and be the
legitimate first writer. The HMAC key must be reachable by verifiers; if verifiers are
shaded copies, the key is reachable by the attacker's shaded copy too. First-writer-wins
also converts the attack from "overwrite anytime" to "win the race once" — a reduction,
not a removal. This option raises the bar and sharpens detection; it changes no trust
boundary. Worth doing only as cheap hardening layered on (a), never as the destination.

### 5.4 Option (d): ClassLoader-graph rendezvous

Walk up from each plugin's classloader to a common parent (Paper's plugin loaders
ultimately share ancestors) and attach the shared state there — e.g. a map keyed into a
field on the shared parent via reflection, or a class injected into the parent.

**Why it was not chosen.**
- It is deep reflection into server internals. Paper 1.21+ has both legacy
  `PluginClassLoader` and `PaperPluginClassLoader` with different hierarchies, and paper
  plugins can use isolated loaders whose common ancestor is high enough (system/boot
  loader) that the "scope" degenerates back to JVM-global — exactly what the property
  already is, minus its stability.
- Modern JDKs require `--add-opens` for the field-injection variants; a library cannot
  demand JVM flags of every server it is shaded into.
- It is still same-privilege-writable: any plugin can do the same walk and the same write.
  The integrity properties are identical to the system property; only the obscurity
  differs, and the fragility is strictly worse (every Paper loader refactor is a breaking
  change).

The one honest advantage — an attribute on a *shared plugin-loader parent* would scope the
rendezvous to "plugins" rather than "the whole JVM" — is obtained cleanly and supportedly
by option (b)'s dependency edge.

### 5.5 Complementary mitigations, regardless of mechanism

- **Per-boot handshake nonce.** Have the leader generate a random per-boot token at
  election, store it beside the leader, and require followers to echo it on first contact.
  Like (c), this is detection rather than prevention, but it is nearly free and makes
  "somebody re-pointed the anchor mid-boot" observable.
- **Fail-loud telemetry — already present; operators should watch for it.** Every
  validation rejection logs a distinctive warning: rejected leader
  (`PandasticBootstrap.java:298-301`), rejected cached service manager
  (`PandasticReflectiveApi.java:149-153`), rejected deserialization
  (`BridgeSerialFilter.java:226-247`), poisoned follower slot (`PandasticApiRegistry.java:83-85`),
  non-map anchor (`PandasticJvmAnchor.java:78-91`). These lines are the intrusion-detection
  surface of this library; operators should treat any `[PandasticCore]` warning mentioning
  "world-writable", "re-electing" or "Rejected" as a signal to audit the installed plugin
  list, not as noise.
- **Document the trust base.** Server owners must understand that installed plugins are
  trusted code: nothing in this library (or any library in one JVM) protects one plugin
  from another that is actively malicious. The README/operator docs should say so
  explicitly and point at the log lines above.

### 5.6 Recommendation and decision

The original recommendation ranked option (b) as the destination and option (a) as the
interim. **The project owner rejected both (2026-10-10), deliberately:** this library's
entire value proposition is "shade one jar, no server-side dependency", and both (a) and
(b) reintroduce a deployment dependency — (b) literally ("drop two jars"), (a) through the
server-managed lifecycle it leans on. A world where an anchor plugin is acceptable is a
world where the whole library might as well be a core plugin, with no classloader bridge
at all. The decision is to stay on the shaded, plugin-less, `System.getProperties()`
rendezvous and make it as rigid as that shape allows.

`PandasticLeaderValidator` and the bridge-target checks therefore remain the **primary**
integrity mechanism, not defence-in-depth, and the trust base remains the installed
plugin list. That trade-off is accepted and documented rather than regretted.

### 5.7 Implemented hardening (2026-10-10)

What was actually built on top of the decision — detection and lifecycle, the two things
options (c) and (a) could offer without changing the deployment model:

- **Anchor-swap detection** (option (c)-lite). Each copy pins the first anchor map it
  observes (`PandasticJvmAnchor.reportIfSwapped`); a later read finding a different object logs
  a one-time warning naming the only two causes — `System.setProperties` or a wholesale
  overwrite — and follows the current map so every copy stays on the shared rendezvous.
  Detection, not prevention, stated as such.
- **Eager leader failover on plugin unload** (`PandasticLifecycleGuard` +
  `PandasticBootstrap.onParticipantDisabled`). Every participant registers a
  `PluginDisableEvent` listener at init. When any plugin dies:
  - If it owned the leadership: survivors clear the dead leader's slots immediately, and
    the **designated successor** — the earliest-joined live follower, by a join timestamp
    recorded in the new additive `followers.seq` anchor slot, name as tie-break —
    promotes itself on the spot. Remaining followers re-attach lazily on their next call
    (their proxies are leader-tagged and re-validate per call). The transition is logged
    at every step, including the explicit note that services registered with the dead
    leader must be re-registered.
  - If it was a follower that never called `shutdown()`: the leader copy prunes its
    registration and unregisters its services, closing the plugman-unload variant of the
    §3.2 leak.
  - The handler is order-independent across listeners (promotion is decided against the
    anchor, not against who cleared it) and idempotent once a healthy leader exists.
  - The lazy `readTrustedLeader` liveness check remains as the backstop wherever the
    listener cannot register (no server in tests, init while disabled).
- **Own-runtime release after failover.** `releaseLeader` previously returned early when
  the caller was no longer the elected leader — so a leader that had been failed over and
  then shut down normally leaked its thread pool and `HttpClient` forever, since no other
  copy can release them. It now tears down the caller's own runtime in that case, outside
  the anchor lock, leaving the anchor to the current leader.

Covered by `PandasticFailoverTest` (7 tests): earliest-follower promotion, non-designated
copies not promoting, listener-order independence, unrelated disables as no-ops,
no-follower clears, dead-follower pruning with service unregistration, and the
designation rules themselves.

---

## 6. Testing status

107 tests across 22 suites. The security-relevant suites (first pass plus follow-up):

| Suite | Tests | Covers |
|-------|-------|--------|
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeDtoTransferTest.java` | 7 | Cross-classloader DTO transfer, both paths: record reconstruction and the serialization fallback; nested DTO/enum recursion; filter accept and reject; non-DTO rejection |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeDeserializationGuardTest.java` | 5 | Adversarial path: library prefix is not a credential (gadget `readObject` never runs), foreign-origin DTO rejected on origin, write-side byte limit, cyclic-graph depth bound, shared-child object budget |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeTypeContractTest.java` | 3 | Bridged-surface rules: interfaces/DTOs/JDK types admitted, library classes rejected |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/handler/BridgeOverloadResolutionTest.java` | 2 | Overload resolution by full parameter signature, never arity; unmatched signatures refused |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/PandasticLeaderTrustTest.java` | 5 | Leader trust: duck typing insufficient, hostile leader rejected and slot cleared, genuine foreign leader accepted, recorded-class mismatch rejected |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/PandasticBridgeTargetTrustTest.java` | 4 | Finding 2.1: service-manager slot trust, including the control test showing the attack works without the check |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/PandasticApiRegistryTest.java` | 3 | Registry hygiene: no classloader pinning, detach, poisoned slot ignored |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/PandasticBootstrapReflectionTest.java` | 7 | Public-only access: no escalation (including `setAccessible` not helping), package-private reached only via public interface, most-specific declaration wins |
| `pandastic-core-config/src/test/java/pandastic/studio/core/service/api/config/defaults/DatabaseConfigDefaultsTest.java` | 5 | Config defaults agree with declarations and documentation; no shared state between instances |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeObjectPositionAdaptationTest.java` | 7 | Finding 3.1: foreign library objects in `Object`-erased positions are adapted by concrete shape or rejected; parent-visible and same-loader pass-through pinned |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/PandasticBridgeServiceManagerUnregisterTest.java` | 2 | Finding 3.2: cross-loader unregistration; the leader stores a proxy, removal by original object works |
| `pandastic-core-services/src/test/java/pandastic/studio/core/service/internal/PandasticServiceManagerImplTest.java` | 6 | Finding 3.2: registry removal semantics, proxy-identity and reloaded-plugin matching, name-index consistency |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeSerialFilterDiscoveryTest.java` | 3 | Finding 3.3: probe discovery, platform-loader enforcement, round-trips for both collection capacity variants and both `EnumSet` shapes |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeDtoFallbackWarningTest.java` | 1 | Finding 3.3: one-time-per-type fallback warning |
| `pandastic-core-bootstrap/src/test/java/pandastic/studio/core/bootstrap/internal/bridge/BridgeProfileCacheCrossingTest.java` | 5 | Live shared-cache crossing: `Optional<SkinDto>` adaptation, translating skin map in both directions, same-instance String/UUID maps |

The remaining suites (profile services, cache, config loader/factory/folder/inspector)
cover functional behaviour rather than the security surface.

**Named gaps:**

- **No end-to-end `PandasticBootstrap.init` / `releaseLeader` test.** The election, protocol
  mismatch, follower attach, leader release and anchor-reset flows are tested only at the
  level of `readTrustedLeader` and the individual slots; the full lifecycle needs a real
  or fake `JavaPlugin` (the current tests deliberately avoid a Bukkit server). This is the
  largest untested surface, and it contains the lock-ordering fix (2.9), which currently
  has no regression test.
- **No build-time bridge-surface walk.** Nothing verifies that every method of every
  `@BridgeApi` type (`pandastic-core-bridge-contract/src/main/java/pandastic/studio/core/bootstrap/api/contract/BridgeApi.java`;
  applied at `pandastic-core-service-api/src/main/java/pandastic/studio/core/service/api/PandasticCore.java` and
  `PandasticServiceManager.java:26`) satisfies `BridgeTypeContract.isBridgeSafe` for its full
  generic signature. A new API method with an unbridgeable type currently fails at first
  call rather than at build; the annotation processor is the natural place for this check.
