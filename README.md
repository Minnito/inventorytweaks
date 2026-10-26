# Developer Log: Inventory Tweaks

| | |
|---|---|
| **Addon** | Inventory Tweaks (ID `inventorytweaks`) |
| **Version** | 1.2.0 |
| **Developer** | Minnito / Claude |
| **Platform** | Client addon for the Swift Client, API 7.1.03, Minecraft 26.3 |
| **Language / build** | Java 25 (`--release 25`), Gradle 9.4.1 (wrapper) |
| **Status** | 2026-10-26 |
| **Build** | `build/libs/inventorytweaks.jar`, 32,343 bytes, SHA-256 `430139d7c03345fc8b8759ba390076e5a8e948f26f3f314bdd466e3e946891ce` |
| **Self-test** | `gradlew runTest`: 39 of 39 checks passed (2026-10-26, 12:09, client 7.1.03) |
| **Download** | v1.2.0 not released yet. [Release v1.1.0](https://github.com/Minnito/inventorytweaks/releases/tag/v1.1.0) is built for API 6.2.1 and no longer loads on clients from 7.1.03 on |
| **Patch check** | `gradlew check`: 3 of 3 patches OK against Minecraft 26.3 (2026-10-26); in the game 3/3 applied |

---

## 1. Goal

Inventory helpers that run directly in the launcher's client, without Fabric or Forge: sorting on a middle click, moving items quickly by dragging, locking hotbar slots, and a warning before a tool breaks.

## 2. Features

### Sorting (middle mouse button)
- Press the mouse wheel in an open inventory: equal items are combined into full stacks, then sorted, empty slots go to the end
- Sort by type (item ID, then amount), name (display name, then amount) or rarity (rarest first, then name, then amount)
- Chest, barrel, shulker box, dispenser, dropper and hopper: only the container is sorted
- Own inventory, crafting table, furnace and others: the 27 slots above the hotbar are sorted. Hotbar, armor, offhand and input/output slots are never touched
- An item on the cursor is put into a free slot first; if there is none, nothing is sorted and a notice explains why
- Creative inventory: the target layout is written directly, the way the creative inventory does it itself. A middle click on an item keeps vanilla's "pick item"
- Servers can turn sorting off with the client feature `inventory` (`disabled-features` in the server plugin)

### Quick move (Shift + left mouse button held)
- Hold Shift and the left mouse button and drag over slots: every item goes to the other side at once, each slot only once per drag
- Uses the screen's own shift-click, so every menu follows its own rule: inventory ↔ hotbar, inventory ↔ container, all creative tabs

### Hotbar lock
- Lock any of the hotbar slots 1–9 in the settings
- Locked items cannot be dropped (Q in the game, Q and Ctrl+Q in menus), moved (click, shift-click, drag, number keys, F to swap with the offhand) or deleted in creative (shift-click in item tabs; shift-click on the trash slot is blocked while a slot is locked)
- Blocked before the local click, so client and server stay in sync
- Holding a locked item turns the hotbar selection frame red (vanilla's frame, tinted)

### Durability warning
- Below a threshold (default 10 %) the tool in the main hand shows "⚠ Low durability: 9.5 % ⚠" above the hotbar
- Plays the iron golem repair sound once; it can come again only after the tool was above the threshold. Volume adjustable

### Other
- German and English; other languages fall back to English
- Settings split into three short sub-pages (Sorting, Hotbar lock, Durability warning), because the client's settings list jumps to the top after every change
- Permission: `input` (mouse and key events)

## 3. Architecture

4 classes in package `inventar`, 804 lines in total.

| Class | Lines | Purpose |
|---|---:|---|
| `Inventar` | 600 | Entry point (`client.api.Module`): keys and mouse, what to sort, quick move, hotbar lock state and red frame, durability warning, settings, self-test |
| `Sortierer` | 128 | Target layout (merge + sort, testable without a menu), sorting via real inventory clicks, creative path |
| `Hooks` | 57 | Patch hooks for the hotbar lock |
| `T` | 19 | Translations from `assets/inventorytweaks/lang/` |

**Sorting:** clear the cursor → fill partial stacks (pick up stack b, put it on a, put the rest back) → swap into the target order. After merging there is at most one partial stack per item, so pick up – place – put back is a clean swap even for equal items (starting from the fuller stack).

### Patches (`module.json`, active after a restart)

| Target | Mode | Hook | Purpose |
|---|---|---|---|
| `MultiPlayerGameMode.handleContainerInput` | replace | `klick` | All menu clicks: pick up, shift-click, number keys, Q in menus |
| `MultiPlayerGameMode.dropItem` | replace | `wegwerfen` | Q in the game |
| `CreativeModeInventoryScreen.slotClicked` | replace | `kreativKlick` | The creative inventory clicks past `handleContainerInput` |

The hovered slot (`AbstractContainerScreen.hoveredSlot`), the screen's `slotClicked` and the target of creative slot wrappers are private or protected and are read via reflection once. If one is missing, only that feature switches off.

### Storage locations

| What | Where |
|---|---|
| Settings and locked slots | `client-modules/inventorytweaks.properties` (client API `AddonConfig`) |
| Key binding | `client-modules/keys.properties` (client) |

## 4. History

Steps 1–7 happened on 2026-10-25, step 8 on 2026-10-26.

1. **First version.** Sorting on a key, merging stacks, three sort orders, durability warning with sound, no patches.
2. **Middle mouse button** instead of a key; **quick move** with Shift + left mouse button.
3. **Inventory ↔ hotbar** in every screen by using the screen's own shift-click; fix for the creative inventory, where all slot wrappers share index 0.
4. **Hotbar lock** with three patches, quick move enabled in all creative tabs.
5. **Red selection frame** for a locked item in hand (replaced a lock icon).
6. **Settings on short sub-pages.**
7. **Renamed to Inventory Tweaks (version 1.1.0).** New ID `inventorytweaks`: jar, config file, key binding and translations use the new name. Settings saved under the old ID `inventar` are not taken over.
8. **API 7.1.03 (version 1.2.0).** Clients from 7.1.03 on no longer load addons built for older APIs. `module.json` now has `"api": "7.1.03"` and `"minecraft": ["26.3"]`, and `build.gradle` follows the 7.1.03 template. No code changes were needed: every client API call still exists. `gradlew check` passed 3 of 3 patches, `runTest` 39 of 39 checks.

## 5. Tests

`gradlew runTest` starts a local vanilla server (port 25598, offline mode) and the game with the addon. The addon checks itself via `client.api.Testing`. Every step waits for the real state instead of a fixed time, and every result is checked again one second later, so only what the server confirmed counts. Result from 2026-10-26 (check names are output by the addon in German):

```
PASS Typ: zusammengefasst, nach Sorte, Leeres hinten
PASS Seltenheit: Seltenes zuerst
PASS Haltbarkeit: Stein und neue Spitzhacke = 100 %
PASS Übersetzungen geladen (en_US)
PASS Sortieren liegt auf der mittleren Maustaste
PASS Einstellungen: drei kurze Unterseiten
PASS Überleben: Inventar per Mittelklick sortiert – vom Server bestätigt
PASS Ziehen: Inventar → Hotbar – vom Server bestätigt
PASS Ziehen: Hotbar → Inventar – vom Server bestätigt
PASS Kreativ: Inventar sortiert und vom Server übernommen – vom Server bestätigt
PASS Kreativ: Ziehen Inventar → Hotbar – vom Server bestätigt
PASS Haltbarkeitswarnung
PASS Rest-Haltbarkeit berechnet (0.7046765 %)
PASS Warnung endet ohne Werkzeug
PASS Sperre: Fackel bleibt (Ziehen, Shift-Klick, Q, Ziffertaste, Aufnehmen, Q im Spiel) – vom Server bestätigt
PASS Roter Auswahlrahmen für gesperrten Gegenstand in der Hand
PASS Sperre: Fackel bleibt im Kreativ-Inventar – vom Server bestätigt
PASS Kiste: nur die Kiste sortiert, Inventar bleibt – vom Server bestätigt
PASS Ziehen: Gegenstände wandern in die Kiste – vom Server bestätigt
… 39 checks in total (preparation steps and first sightings omitted here)
```

The chest test runs last: after closing a chest in the test, the test client no longer reliably received inventory updates. In normal play this did not occur.

The GitHub Action (`.github/workflows/build.yml`) only builds the jar on every push. `runTest` needs OpenGL and therefore runs locally.

## 6. Known limitations

- **All sorting clicks in one tick.** Very strict anti-cheat plugins could flag this; spreading the clicks over several ticks would fix it (`Sortierer`).
- **Locked items still drop on death.** That is decided by the server.
- **Double click to collect** with an item on the cursor can still take matching items out of a locked slot.
- **The red frame shows only in the game**, not while a menu is open, and only when the locked slot holds an item.
- **A saved key binding wins** over the default. Anyone who bound the sort key earlier keeps that binding.
- **Patches need a restart.** `.addon reload inventorytweaks` is not enough.

## 7. Building and running

Requirement: JDK 25 or newer. The first build downloads Minecraft and its libraries once into `~/.gradle/caches/client-mc`.

```bash
./gradlew build        # → build/libs/inventorytweaks.jar, also checks the patches
./gradlew jar          # jar only, without the patch check
./gradlew runClient    # game with the addon, offline as "Dev", folder run/
./gradlew runTest      # test server + game, self-test
./gradlew install      # copy jar into the game; patches need a restart
```

Start `runClient` only after the previous game has closed: a running game keeps its jar, and the new one would only be placed next to it as `inventorytweaks.jar.update`.

## 8. Controls

| Input | Action |
|---|---|
| Middle mouse button (in an inventory) | Sort |
| Shift + left mouse button held, drag | Quick move |
| Settings → Hotbar lock | Lock hotbar slots |
