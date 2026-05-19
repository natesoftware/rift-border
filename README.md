# rift-border

A volumetric circular border for Paper 1.21.8 servers. Renders a shrinkable
cylinder via grid-mounted `ItemDisplay` entities and a custom item-model
shader, animates shape transitions, and damages players who stray outside the
radius or beyond an optional ceiling / floor.

## Features

- Smooth shrink and re-centre animations driven by a single `moveTo` call
- Optional volumetric ceiling and floor (not just a 2D ring)
- Multi-phase orchestrator with pause / resume / sync-to-timer
- Per-player damage with grace period, warning title, and re-entry sound
- Particle-ring preview of the next phase's target
- One integration point (`BorderCallbacks`) for branding, messages, and sound

## Requirements

- Paper (or compatible fork) 1.21.8+
- Java 21
- A resource pack registering the wall item-model (see [Resource pack contract](#resource-pack-contract))

## Installation

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
    implementation("com.natesoftware:rift-border:1.0.0")
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
    implementation("com.natesoftware:rift-border:1.0.0")
}
```

### JitPack

```kotlin
repositories {
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.natesoftware:rift-border:main-SNAPSHOT")
}
```

## Usage

```java
GameBorder border = new GameBorder(plugin, world, centerX, centerY, centerZ)
    .withCallbacks(new MyCallbacks())
    .withParticipants(() -> alivePlayerUuids);
border.spawn(200);

// Drive it manually...
border.moveTo(50, 50, 100, 20 * 30);  // shrink to (50,50)/r=100 over 30s

// ...or hand it to the phase controller
List<Phase> phases = List.of(
    new Phase(60, 30, 100, 2.0),  // wait 60s, shrink 30s to r=100, 2 dmg/s
    new Phase(30, 30, 50,  3.0),
    new Phase(15, 15, 0,   5.0));

new BorderPhaseController(plugin, border, phases, 0, 0, 200)
    .start(gameDurationSeconds);
```

Implement `BorderCallbacks` to supply your branding:

```java
public class MyCallbacks implements BorderCallbacks {
    @Override
    public Component warningTitle() {
        return Component.text("Get back inside!", NamedTextColor.RED);
    }

    @Override
    public NamespacedKey wallItemModel() {
        return new NamespacedKey("myplugin", "border_wall");
    }

    // The rest of the interface has sensible defaults.
}
```

## Resource pack contract

The wall renders as `ItemDisplay` entities holding a paper `ItemStack` whose
item-model is set via `BorderCallbacks.wallItemModel()`. **Your resource pack
must register a model under that key that expands into a cylinder shader.**
Without it, the wall is invisible (or renders as a flat paper sheet, depending
on your model fallback).

This library does not ship a default pack. If you'd like one, message
`Nateiwnl` on Discord.
