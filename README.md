# rift-border

[![build](https://github.com/natesoftware/rift-border/actions/workflows/build.yml/badge.svg)](https://github.com/natesoftware/rift-border/actions/workflows/build.yml)

A volumetric circular border for Paper 1.21.11 servers. Renders a shrinkable
cylinder, animates shape transitions, and damages players who stray outside the
radius or beyond an optional ceiling / floor.

Two render modes ship in the box. **Particles** need nothing but the jar and
work on any client. **Shader** draws a solid cylinder wall via grid-mounted
`ItemDisplay` entities and a custom item-model, and needs a resource pack
(see [Rendering](#rendering)).

## Features

- Smooth shrink and re-centre animations driven by a single `moveTo` call
- Optional volumetric ceiling and floor (not just a 2D ring)
- Optional multi-phase controller with pause / resume / sync-to-timer
- Pluggable choice of where each phase shrinks to
- Per-player damage with grace period, warning title, and re-entry sound
- Per-player render mode, so players can pick particles or the shader wall
- Particle-ring preview of the next phase's target
- One integration point (`BorderCallbacks`) for branding, messages, and sound

## Requirements

- Paper (or compatible fork) 1.21.11+
- Java 21
- A resource pack, **only** if you want the shader wall

## Installation

Coordinate `com.natesoftware:rift-border`, package `com.natesoftware.riftborder`.
Not on Maven Central. Three options:

### Composite build (recommended while iterating)

Clone alongside your plugin:

```
your-workspace/
├── rift-border/
└── your-plugin/
```

`settings.gradle.kts`:
```kotlin
includeBuild("../rift-border")
```

`build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.natesoftware:rift-border:1.3.0")
}
```

Source changes in `rift-border/` flow through to your plugin's next build with
no publish step.

### `mavenLocal()`

```bash
git clone git@github.com:natesoftware/rift-border.git
cd rift-border
./gradlew publishToMavenLocal
```

`build.gradle.kts`:
```kotlin
repositories {
    mavenLocal()
}
dependencies {
    implementation("com.natesoftware:rift-border:1.3.0")
}
```

### JitPack

```kotlin
repositories {
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.natesoftware:rift-border:v1.3.0")
}
```

## Bundling

**This is a library, not a plugin.** Paper will not load it from the server
classpath, so it has to be bundled into your plugin jar or you get a
`NoClassDefFoundError` the first time a border spawns. Apply the Shadow plugin:

```kotlin
plugins {
    id("com.gradleup.shadow") version "9.0.0"
}

tasks.jar { enabled = false }
tasks.shadowJar { archiveClassifier.set("") }   // drop the -all suffix
tasks.build { dependsOn(tasks.shadowJar) }
```

Keep the dependency as `implementation` and Shadow bundles it. Relocation is
optional: rift-border has no transitive dependencies (`paper-api` is
`compileOnly`, so the published POM is empty) and its only static state is a
set of live border ids that is private to each plugin's bundled copy, so two
plugins bundling it do not collide. Relocate it anyway if you want to pin a
version independently of whatever else is on the server.

Shadow's version has to match your Gradle. `9.0.0` is verified on Gradle 9.0.0.
Newer Shadow releases (9.6.x) fail at configuration time on Gradle 9.0.x with a
missing `AdhocComponentWithVariants` method; if you want a newer Shadow, run a
newer Gradle to go with it.

## Usage

```java
import com.natesoftware.riftborder.BorderCallbacks;
import com.natesoftware.riftborder.GameBorder;

GameBorder border = new GameBorder(plugin, world, centerX, centerY, centerZ)
    .withCallbacks(new BorderCallbacks() {})        // required, even if empty
    .withParticipants(() -> alivePlayerUuids);      // Supplier<Set<UUID>>
border.spawn(200);
```

`withCallbacks` is mandatory; `spawn()` throws without it. `withParticipants`
is optional, but without it **every survival or adventure player in the world**
is subject to the border.

From here there are two ways to drive it. Pick one.

### Manual

You decide where and when:

```java
border.moveTo(50, 50, 100, 20 * 30);        // shrink to (50,50) r=100 over 30s
border.onShrinkComplete(() -> border.moveTo(80, 10, 60, 20 * 20));
border.setDamagePerSecond(3.0);
```

`moveTo` has overloads that also take a ceiling Y and a floor Y. `setPosition`
snaps with no animation. `pauseShrinking` / `resumeShrinking` freeze and
continue an in-flight shrink.

### Phase controller

Or hand over a schedule and let the library drive:

```java
import com.natesoftware.riftborder.BorderPhase;
import com.natesoftware.riftborder.BorderPhaseController;
import com.natesoftware.riftborder.NextBorderIndicator;

List<BorderPhase> phases = List.of(
    new BorderPhase(60, 30, 100, 2.0),  // wait 60s, shrink 30s to r=100, 2 dmg/s
    new BorderPhase(30, 30, 50,  3.0),
    new BorderPhase(15, 15, 0,   5.0));

BorderPhaseController controller =
    new BorderPhaseController(plugin, border, phases, mapCenterX, mapCenterZ, 200);
controller.start(gameDurationSeconds);

// Optional: pulse a faint ring where the next phase will land
NextBorderIndicator indicator = new NextBorderIndicator(controller);
indicator.start();
```

`gameDurationSeconds` is the clock every later `setRemainingTime` reading is
measured against. Under natural progression it only needs to cover the
schedule (the sum of every phase's wait and shrink); pass your own round
length if you sync to a game timer.

`BorderPhase` has longer constructors that add a ceiling Y and a floor Y for
that phase. Do not also call `moveTo` yourself while a controller is running;
the controller owns the shape.

If your game has its own clock, keep the controller aligned with it:

```java
controller.pause();
controller.resume();
controller.setRemainingTime(secondsLeft);      // re-sync after a host adds or removes time
controller.setOnAllPhasesComplete(() -> ...);  // the last phase has landed
int hud = controller.getSubPhaseRemaining();   // seconds left in the current wait or shrink
```

### Shrink targets

Where each phase shrinks *to* is a `ShrinkTargetSelector`. Two ship in the
box: `RANDOM_INSIDE` (the default, a uniformly random point inside the current
zone) and `FIXED_CENTER` (always toward the map centre). Supply your own to
express anything else. It returns a `BorderPoint`; the controller clamps
whatever you return so the new circle always fits inside the previous one,
pulling an out-of-range point back along its ray.

```java
controller
    .withTargetSelector(ctx -> new BorderPoint(peakX, peakZ))   // e.g. highest point
    .withRandom(new Random(matchSeed));                         // reproducible zones, optional
```

Targets are resolved as each phase's wait begins, so a selector that reads
player positions sees them as they are then, and the indicator can preview the
result during the wait.

### Teardown

Always tear down when the match ends and on plugin disable:

```java
border.remove();      // also stops any controller and indicator attached to it
```

In shader mode the border force-loads the chunks holding its wall anchors while
it is active. Those flags are saved with the world, so skipping `remove()`
leaves the chunks pinned loaded across restarts. Particle mode force-loads
nothing.

### Callbacks

Every member of `BorderCallbacks` has a default, so implement only what you
want to change:

```java
public class MyCallbacks implements BorderCallbacks {
    @Override
    public Component warningTitle() {
        return Component.text("Get back inside!", NamedTextColor.RED);
    }
}
```

What players get out of the box:

| | Default |
| --- | --- |
| Damage outside the border | 2.0 / s, after a 1 s grace, with the vanilla hurt flash and sound |
| Warning title | none (`warningTitle()` returns null). When set, it owns the player's title slot while they are outside |
| Sound on crossing out | `minecraft:block.anvil.land` |
| Sound while still outside | `minecraft:entity.wither.spawn`, every 5 s |
| Particle wall colour | white |
| Creative / spectator | ignored |

Border damage writes health directly. Non-lethal ticks fire no
`EntityDamageEvent`, so armour, Resistance and other plugins' damage listeners
do not see them; only the killing tick goes through `Player.damage` with the
`OUTSIDE_BORDER` damage type. If you need event-visible damage, set the rate
to 0 (the warnings and sounds keep working) and deal it yourself from
`onWarningShown` / `onWarningCleared`, which tell you exactly who is outside
and when they return.

## Rendering

Each player is resolved to a `BorderRenderMode` on every visibility pass:

| Mode | Needs a pack | What it looks like |
| --- | --- | --- |
| `PARTICLE` | no | A dust wall on the arc nearest the player, density scaled to the current radius |
| `SHADER` | yes | A solid cylinder wall, drawn by the pack's item-model |

**With no pack, you get particles and nothing else to configure.** Leave
`wallItemModel()` alone: it defaults to `null`, which puts every player on
`PARTICLE`, skips the display grid entirely, and logs one line saying so.

To offer the shader wall, return a key from `wallItemModel()` and register a
model under it in your pack. Every player then defaults to `SHADER`; supply a
resolver to let them choose:

```java
border.withRenderModeResolver(uuid -> preferences.renderMode(uuid));
```

Shader mode mounts its wall on an anchor grid: one invisible display every 80
blocks, out to 500 blocks from the centre. A radius past that cap renders no
wall on its far side and logs a warning at spawn. Both numbers are tunable:

```java
border.withGrid(80, 1200)   // spacing, max extent
      .withAnchorY(200);    // where the anchors sit; defaults to just under the build limit
```

### Pack contract

The wall is a `Material.PAPER` `ItemStack` whose item-model is the key from
`wallItemModel()`, carried by `ItemDisplay` entities on an 80-block grid. The
model is expanded into a cylinder by a core-shader override, and the border's
geometry reaches the shader through the display's scale: **X and Y carry the
live radius, Z carries the pattern-anchor radius** (the transition target while
shrinking, so the pattern does not slide mid-shrink).

The pack that implements this contract is not published. Particle mode is the
supported path for third parties today; a future plugin release will bundle
and serve the pack itself. Until then, message `Nateiwnl` on Discord if you
want it, or write your own against the contract above.

## Example

[`examples/ExamplePlugin.java`](examples/ExamplePlugin.java) is a complete
plugin assembled from the snippets above: it spawns a border around the first
world on enable, drives it through three phases, previews each target, and
tears everything down on disable. Drop it into a project set up per
[Bundling](#bundling) with the `plugin.yml` beside it.

## Development

```bash
./gradlew build      # compiles, runs the tests, builds the jar, sources jar and javadoc jar
./gradlew test       # the suite alone - the schedule, animator, damage tracker and selectors run against a fake scheduler, no server needed
./gradlew javadoc    # API docs into build/docs/javadoc
```

The public types carry Javadoc, also hosted per release at
`https://javadoc.jitpack.io/com/github/natesoftware/rift-border/<tag>/javadoc/`;
the internals carry `//` notes. CI runs `build` on every push and pull request.

## Licence

MIT. See [LICENSE](LICENSE).
