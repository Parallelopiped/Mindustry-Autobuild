<p align="center"><img src="logo.png" width="640" alt="AutoBuild - Mindustry mod"></p>

# AutoBuild — algorithmic auto-building for Mindustry

**English** | [简体中文](README.zh-CN.md)

Drag a box on the map, say what you want built and where its inputs and outputs are, and AutoBuild
plans the whole facility, shows the plan as a blueprint and lets your unit build it.

- **Drill fields** — the main, stable feature.
- **Defenses** — experimental: works and is tested, but expect rough edges.

Mindustry **v8 (build 160.7+)**, Serpulo. Java mod, client-side (no server plugin needed).
The interface is in English and Simplified Chinese and follows the game's language setting.

> Developed with **Claude Opus 5.5** (Anthropic), at medium reasoning effort, in Claude Code:
> design, algorithms, code, tests and documentation, directed and play-tested by the author.

<p align="center"><img src="docs/media/drill-field.gif" width="560" alt="Drill field: gray search replay, preview, building"></p>

---

## Contents

- [Features](#features)
  - [Drill fields](#drill-fields-main-feature)
  - [Defenses (experimental)](#defenses-experimental)
  - [Interface](#interface)
- [Core algorithms](#core-algorithms)
- [How it is tested](#how-it-is-tested)
- [Install and use](#install-and-use)
- [Building and developing](#building-and-developing)
- [Contributing](#contributing)
- [License](#license)

---

## Features

### Drill fields (main feature)

**Mining**
- Every drill tier available to you; drills too weak for the chosen ore are disabled.
- **Mixed drill sizes**: smaller drills (up to the chosen tier) fill the gaps the main drill can't
  use. Example: airblast, then laser, then pneumatic.
- **Two goals**:
  - *Max output*: mine as much ore as possible.
  - *Material efficiency*: only place drills that are at least half on ore, and favour short
    logistics. It still reaches about 83–91 % of max output.
- **Water boost**: plans water rows or per-drill water connections (conduits, liquid routers,
  bridge conduits). If water wouldn't pay off, it falls back to a dry layout and says so.
- **Power**: power nodes for powered drills, linked into one network.
- Follows the game's rule for which ore a drill actually mines (sand is low priority, ties go to
  the higher item id).

**Logistics**
- Conveyors and item bridges.
  - Bridges hop over walls, belts and drills.
  - Item and liquid bridges can interleave on one line.
- **Line capacity**: no output line is loaded beyond one belt.
  - Ore that doesn't fit is left out instead of backing up.
  - The summary tells you how much more more outputs would carry.
- **Outputs and water inputs**:
  - **On the border**: the belt leaves the region there.
  - **Inside the region**: items are delivered into that tile.
  - **On your existing buildings**: conveyors, routers, bridges, cores, vaults, containers for
    items; tanks, conduits, pumps for water.
  - **Anywhere on the map**: a line is laid to it over free ground, around rocks and through
    winding corridors, with bridges where useful. Its exit from the field is chosen by the real
    path length, not by direction.
- **Several lines into big storage**: a core, vault or container gets as many lines as the load
  needs (up to 4), each one belt's worth, each into a different side.

**Respecting what is already there**
- Never builds over existing structures.
- Never mixes items into your existing belts: blocks that would push items into the new lines are
  respected, and so is the front of a conveyor.
- Keeps other ores free: belts, bridges and nodes avoid tiles with other valuable ores.

<p align="center"><img src="docs/media/laser-multi-output.gif" width="560" alt="Laser drills with fillers, water, power, two outputs"></p>
<p align="center"><sub>Laser drills with pneumatic fillers, water boost and power nodes; two outputs on opposite sides.</sub></p>

### Defenses (experimental)

- **Enemy routes**: the ground route of every spawn is computed the same way the game's pathfinder
  does it, and drawn on the map as soon as you select a region. Flying units are drawn as straight
  lines.
- **The route never moves**: nothing is built on the route or close to it, so the enemies keep
  walking where they walked. The planner re-checks this with every planned block. In tests the
  game's own path matched the prediction tile for tile.
- **One side only**: everything goes on the side of the route with the most room, the walls
  included.
- **Rows facing the route**, like a hand-built line:
  - walls;
  - menders, with power-node slots between them;
  - short-range turrets;
  - a supply lane;
  - longer-range turrets;
  - another supply lane, and so on.
- **Diagonal routes**: the region is split into strips that step along the route.
- **All Serpulo turrets**: item, power, liquid and laser.
  - Several ammo items, each brought in by its own belt.
  - Liquid ammo and coolant; turrets next to a liquid lane get coolant.
  - Walls of your chosen material, 1 or 2 thick.
  - Menders, mend projectors, force projectors, shock mines on the route.
- **Supply lanes**: one row of belts or pipes.
  - Lane tiles next to a turret become routers, at most 3 in a row so items don't wander back and
    forth.
  - Ammo lanes and liquid lanes alternate, so every turret row gets both.
- **Trunks**: from each input to the lane ends. Inputs can be anywhere on the map: a tile, your
  core (an unloader is added), a container, a belt, a tank, a power node.
  - Trunks keep away from the enemies' weapon range.
  - Nothing lands on the wall line.
- **Wave analysis**: the ground / air share of the next 20 waves (by health) and the enemies'
  typical range weight the plan.

<p align="center"><img src="docs/media/defense.gif" width="560" alt="Defense along a diagonal enemy route"></p>
<p align="center"><sub>Defense beside a diagonal route (red): walls, menders, rows of hails and lancers, ammo and water lanes, power.</sub></p>

### Interface

1. Press **F7** or the **AutoBuild** button.
2. Drag a region.
3. In the settings dialog, choose a tab (**Drill field** / **Defense**) and its options.
4. **Click the ports**:
   - edges and inside tiles, or anywhere on the map for lines;
   - your existing buildings are accepted when they fit;
   - right-click lets the planner choose.
5. Planning runs in the background. The search is **replayed as gray ghosts**, then shown as a
   blueprint preview, with red tiles where something is in the way.
6. In the preview:
   - **Another plan**: search again with other preferences; browse plans with ◀ ▶.
   - **Add output**: more output lines.
   - **Ports**, **Edit**, **Build**.
7. The summary shows the expected output and load per line, water and power needs, the build cost
   against your core, and warnings.

---

## Core algorithms

The planners are plain Java without game dependencies (`src/autobuild/plan`). They can be tested
offline. The game side takes a snapshot of the region, runs the planner on a background thread and
turns its placements into build plans.

### Drill fields — `DrillLayoutPlanner`

Several **layout families** each run through the full pipeline (layout → routing → gap filling →
water → power), and the best **final score** wins. The score is ore value minus material, minus
other ore covered, minus overload.

| Family | Method |
|---|---|
| **Belt lanes** | **Dynamic programming** over rows chooses the lane rows and which sides carry drills. Along each lane a **1-D DP** packs drills of mixed sizes. Lanes are split where drills are far apart or a line would exceed capacity. |
| **Bridge lattice** | Rows of item-bridge chains with drills packed around them. **Randomized greedy** construction, improved by **large neighbourhood search** (destroy and repair). |
| **Water rows** | DP over rows where water rows alternate with item lanes, with drills valued at the boosted rate. |
| **Drill-first** | Repeatedly attach the drill with the best *value − connection cost*. A single **multi-start A\*** starts from all candidates' neighbour tiles at once, offset by their value. Drills already touching the network join for free. |

- **Routing**: **A\*** over states *(tile, direction, landed-from-bridge)*.
  - Moves are conveyor steps and bridge jumps over anything.
  - Costs count occupied tiles. A tile under an existing bridge link is cheap for the other kind of
    bridge (shared corridors).
  - The game's rules are encoded:
    - a conveyor never takes items from its front;
    - a bridge doesn't take from its link side;
    - an end bridge doesn't output back toward its feeder.
  - Routes may merge into existing lines only if line **capacity** allows. A route that crosses
    itself is searched again without that tile.
- **Water**: multi-target **liquid A\*** with bridge conduits. It can branch off a conduit, which
  then becomes a liquid router.
- **Gap filling**: smaller drills of any allowed type are added wherever they touch a line with
  room. This runs again once the water network is known.
- **Power**: **greedy set cover** places nodes, then **Dijkstra over node positions** adds relays
  until the network is one group. This is the shared `PowerNetwork`.
- **Ports away from the region**:
  - a **path-cost field** (Dijkstra with bridge jumps) from the target picks the exits on the
    region edge;
  - lines are then routed over the whole map by A\*. Its estimate is scaled by the cheapest cost per
    tile with bridges, so it never overestimates and routes stay optimal.

### Defenses — `DefensePlanner`, `EnemyRoutes`, `GridRouter`

- **Enemy route**: a **flow field** (Dijkstra) from the cores the wave team walks to.
  - Each tile uses the game's ground cost: `1 + 5·health + near-solid + terrain`.
  - A unit steps to the 8-neighbour with the lowest weight, the same rule as the game. That gives
    each spawn's route.
  - Placing a solid block adds its health cost to its tile and +2 to neighbours that weren't near
    something solid. So routes can be recomputed with any plan.
- **Why the route can't move**: no planned solid block touches a route tile or its neighbours.
  - The route's own cost therefore stays the same, and any other path only gets more expensive.
  - So the shortest path stays where it was. The planner still verifies it.
- **Layout**:
  - The region is split by the corridor; the part with the most room is used.
  - A **least-squares fit** of the distance to the route gives the "front" edge.
  - The columns are grouped into **strips** whose wall line varies by at most 3 tiles.
  - Each strip gets rows from a **template**. Several turret orders are built in full and scored:
    short range first, cycles, single types, pairs.
  - A row's turret value is its damage on the route tiles in range with **diminishing returns**,
    `F(D) = K·ln(1 + D/K)`. This spreads fire along the route and balances ground and air.
- **Supply**: `GridRouter` is a generic A\* for item and liquid networks.
  - It handles routers, end bridges and unloaders that push into all their neighbours, and never
    next to another network.
  - Trunks get a cost that grows inside the enemies' range.
  - A tile just beyond each lane end is kept expensive for other trunks, so no lane gets sealed off.

---

## How it is tested

- **Offline tests** (`./test.sh`, no game needed):
  - **Drills**: hundreds of random fields per run. Checked:
    - an item- and liquid-flow model reproduces the game's push rules;
    - every drill reaches an output;
    - no overlaps, no leaks, power connected.
  - **Defenses**: 120 random maps. Checked:
    - nothing on the corridor or the other side, nothing in front of the walls;
    - every turret supplied, no network mixing, power linked;
    - the route unchanged.
- **Dedicated-server tests** (`./servertest.sh`, `./servertest-def.sh`): place a plan instantly in
  the real game, then measure. A few results:

  | Case | Measured | Predicted |
  |---|---|---|
  | Field → vault far away, 3 lines | 12.5 items/s | 12.53 |
  | Field with water from a tank far away → vault far away | 17.65 items/s | 17.78 |
  | Maze map, ~250-tile line through two corridors | 0.37 items/s | 0.36 |
  | Defense: enemy route after building (2 spawns) | 270/270, 266/266 tiles identical | — |
  | Defense on Fork, 6 waves | 0 enemies reached the core (16 without it) | — |

- **Client self-test** (`./selftest.sh <map>[:defense]`): the real UI flow end to end.
  - It covers port picking, the gray replay, the preview, Another plan, and building.
  - It takes screenshots, including in Chinese.

The full history of each feature lives in [DEVLOG.md](DEVLOG.md) (in Chinese): goal, algorithm,
what was tried and why it failed, and verification. Changes per version are in
[CHANGELOG.md](CHANGELOG.md).

---

## Install and use

1. Download `mindustry-autobuildDesktop.jar` from the Releases, or build it (see below).
2. Put it into Mindustry's mods folder. On Windows that is `%AppData%/Mindustry/mods`, or use
   *Mods → Import*.
3. Start a game, press **F7** (or the **AutoBuild** button), drag a region and follow the dialog.

Requirements: Mindustry v8 (build 160.7 or newer), Serpulo content. Erekir is not supported.

---

## Building and developing

You need JDK 17.

```bash
./gradlew jar          # build/libs/mindustry-autobuildDesktop.jar
./test.sh              # offline planner tests (drill + defense)
./selftest.sh Fortress # client self-test (needs a display and a Mindustry.jar in ~/mindustry-test)
./servertest.sh <map> <item> <drill> <outputs> <mode> [options]   # dedicated-server measurement
```

Code layout:

| Path | What |
|---|---|
| `src/autobuild/plan/` | Pure planners: `DrillLayoutPlanner`, `DefensePlanner`, `EnemyRoutes`, `GridRouter`, `PowerNetwork` |
| `src/autobuild/` | Game side: `BuildJob` (shared job framework), `DrillFieldJob`, `DefenseJob`, `RemotePorts`, UI (`AutoBuildMod`), tests (`ServerTest`, `ClientSelfTest`) |
| `assets/bundles/` | English and Chinese texts |
| `test/autobuild/` | Offline tests |

---

## Contributing

Issues, ideas, bug reports with screenshots, and pull requests are all welcome. Good starting points:

- moving the drill planner's routing onto the generic `GridRouter`;
- letting the player balance the load between several outputs;
- defense: naval routes, deliberate "maze" re-routing, point defense (segment);
- more translations (add `assets/bundles/bundle_<locale>.properties`).

Please run `./test.sh` before a pull request, and describe how you checked a change in the game.

---

## License

[CC BY-NC-SA 4.0](LICENSE) (Creative Commons Attribution-NonCommercial-ShareAlike 4.0
International). Copyright © 2026 Maiolao.

- **Attribution**: credit the author and link to this repository and the license.
- **NonCommercial**: no commercial use.
- **ShareAlike**: modified versions must be shared under the same license.

Because it forbids commercial use, this is not an OSI "open source" license. Mindustry itself is ©
Anuken and licensed separately (GPL-3.0); this mod contains no Mindustry code.
