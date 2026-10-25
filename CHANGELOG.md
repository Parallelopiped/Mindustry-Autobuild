# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`; while `0.x`, any minor bump may
change behaviour or settings).

## [Unreleased]
### Added
- README in English and Simplified Chinese (features, core algorithms, tests, build), with GIFs
  from in-game footage; license: CC BY-NC-SA 4.0.
- Client self-test: `-Dautobuild.record=<dir>` records frames at a fixed time step (footage).
### Changed
- `deploy.sh` finds the Windows mods folder itself (or `MINDUSTRY_WIN_MODS`).

## [1.3.5] - 2026-10-19
### Fixed
- Drill field with water and several outputs: the automatic water port went to the middle of the
  side opposite the first output - often right in front of another output's exit, which then got
  nothing (and the ore near it stayed unmined). It now goes to a side without outputs, or as far
  from them as that side allows.
### Added
- Material efficiency with an output left unused: planned again with max-output prices for belts
  / bridges (drills still at least half on ore); kept when the field gains at least 10% (and
  0.5/s), otherwise the summary says how much that output would add.
- Server test: `w=` / `h=` for non-square regions.

## [1.3.4] - 2026-10-19
### Changed
- Exits of lines to ports away from the region are chosen by the length of the way to the target,
  not by its direction: when rocks make the way out lead through another side, the field sends its
  items to that side directly instead of to the side facing the target and back along the region.

## [1.3.3] - 2026-10-19
### Fixed
- Lines to ports outside the region found no path along long, winding ways (e.g. Maze: field in
  one corridor, output in another). They are now routed over the whole map (A* with a distance
  estimate that accounts for bridges, so routes stay the cheapest); the map grid is only built
  when a port is away from the region (Maze 256x256: 17 ms).
- Server test: `region=x:y` places the field there; `abmap [step]` prints the map.

## [1.3.2] - 2026-10-19
### Fixed
- Drill field with water from outside the region: "Another plan" lost the water. The remote water
  port could land on the edge (or a corner of it) the output lines leave from, where water can't
  enter the field; the first search hid it by falling back to an automatic water port. Water
  ports now avoid the output edges and their corners, and variants keep the player's water ports.
- When the water network is skipped, the summary says so again.
- Lines to ports outside the region: "no free path" in complex terrain although there is one. The
  grid for these lines now reaches 24 tiles beyond the region and the ports (room to go around
  rocks), edge outputs are only put where the target can be reached from, and the water line is
  laid first (output lines can't block it).
- Server test: `another=N` plans N more variants like "Another plan".

## [1.3.1] - 2026-10-19
### Fixed
- Drill field with water: empty drill spots right next to planned belts / water lines. Gap
  filling ran while every drill was still assumed watered (lines looked full); it now runs again
  once the water network is built.
- Drill field: output / water ports could only be picked outside the region (since 1.2.0); the
  border and inside tiles work again, and far-away picks too.

## [1.3.0] - 2026-10-19
### Added
- **Simplified Chinese**: every text of the mod (buttons, dialogs, prompts, summaries, warnings,
  planning stages, planner failures) comes from `assets/bundles/bundle.properties` /
  `bundle_zh_CN.properties` and follows the language set in the game. Block, item and liquid names
  use the game's own translations.

## [1.2.1] - 2026-10-19
### Changed
- **Material efficiency mode mines more**: any drill (fillers included) is worth placing when at
  least half of it is on ore; the threshold scales with the drill's own speed, so smaller, slower
  fillers fill the gaps again (before, they practically never paid off). Over 120 random
  multi-patch fields: output 83 -> 91% (2x2), 85 -> 88% (3x3), 77 -> 83% (4x4) of max output,
  still with 20-25% less drill area per item than max output.

## [1.2.0] - 2026-10-19
### Added
- **Drill field ports anywhere on the map**: item outputs and water inputs can be clicked away from
  the region - a tile, or your core / vault / container / belt / router / bridge (outputs), tank /
  conduit / pump (water). A line (belts / pipes, bridges) is planned from the field to it over free
  ground; on / near the region the click works as before.
- **Several lines into big storage**: an output into a core / vault / container gets up to 4 lines
  (as many as it has free sides), each one belt's worth, from the side(s) of the field facing it;
  the field is spread over them and as many are kept as the load needs. Storage right next to the
  region gets one line per tile facing it. The marker shows the load and the line count.
- Server test: `#BLOCK@dx:dy` ports are placed on the nearest open spot.

## [1.1.0] - 2026-10-18
### Changed
- **Defense layout rebuilt as rows facing the route** (like a hand-built defense line): walls,
  a support row (menders, power-node slots), then turret rows with supply lanes between them -
  short-range turrets in front, long-range behind; several row orders are tried and the best kept.
  Where the route runs diagonally the region is split into strips that step along it.
- **Supply lanes**: one row of belts / pipes; lane tiles next to turrets become routers (at most
  3 in a row, so items don't wander back and forth), ammo and liquid lanes alternate so every
  turret row gets both - water now reaches every turret beside a liquid lane.
- **Only one side of the route** is used (walls too); nothing of the plan, nodes and bridges
  included, stands on the wall line or in front of it.
- **Input ports anywhere on the map** (outside the region): trunks run from there to the lane
  ends, kept away from the route (cost grows within enemy range) and short (bridges); a router
  hub at each port with one side left free for your belt / pipe.
- The region is filled completely: regions over 900 tiles are refused (select a strip along the
  route). The "turrets at most" slider is gone.

## [1.0.0] - 2026-10-18
### Added
- **Defense** (second facility, a tab in the settings dialog): select a region on the enemy route,
  pick turrets (every Serpulo turret: item, power, liquid, laser), ammo items, a liquid (liquid ammo
  / coolant), wall material and thickness, menders, mend projectors, force projectors, shock mines,
  belts / pipes, route clearance and a turret budget. Ports: one input per ammo item, the liquid,
  power - an edge, an inside tile, or your core / container (an unloader is added), conveyor,
  router, tank, conduit, power node, battery or generator; empty = chosen behind the defense.
- The enemy ground route is computed like the game's flow field (same costs, same step rule) and
  drawn as soon as the region is selected; flying units' lines too. The planner keeps the route
  and its neighbours free, so the route stays where it is, and checks it again with the plan.
- Turrets are placed for damage on the route with diminishing returns, enemy fire risk, price and
  supply cost; ammo / coolant networks of belts, routers, bridges and pipes branch to every turret;
  power nodes join the power ports (extra ports marked when the route splits the grid); walls go
  between the route and what they protect; menders / force projectors cover exposed buildings.
- Wave analysis in the dialog: ground / air share of the next 20 waves and enemy range.
- Server test commands `abroute`, `abdef`, `abdefcheck`, `abwave`, `abwavecount`
  (`servertest-def.sh`); offline `DefenseTest`; `selftest.sh <map>:defense`.

### Changed
- Shared framework for both facilities (BuildJob / PortKind / PlanResult); power-node placement
  shared by both (PowerNetwork). Drill fields behave exactly as before.
- Port labels of different kinds are stacked instead of overlapping.

## [0.10.0] - 2026-10-17
### Added
- **Keeps other ores free**: belts, bridges, water and power nodes avoid tiles with other valuable
  ores (not the target, not low-priority sand), and drills are valued lower when they cover other
  ore. Over 120 mixed-ore fields: other ore covered 4.4% -> 2.9% (dry) and 9.1% -> 4.7% (water),
  same output.
- **Ports on existing buildings**: click one of your belts, routers, bridges, junctions, cores or
  containers as an item output (items are delivered into it; a drill touching it delivers directly),
  or a liquid router / tank / conduit / pump as a water input (used as a source, not rebuilt).
  Incompatible blocks are refused with a reason (e.g. "Mender can't take items").
- Server test: `#BLOCK@dx:dy` ports build that block first and use it as an existing port.

### Fixed
- Next to an existing router-like output, only a belt pointing into it is placed (its own items
  can't get into our lines); a conveyor's front and a bridge's link side are never used to deliver.

## [0.9.1] - 2026-10-17
### Fixed
- Plans were drawn over existing structures (red overlaps in the preview): `Build.validPlace` also
  accepts tiles whose block may be *replaced* (a belt over a belt, a better drill over a drill...).
  Only empty tiles are planned on now, in the snapshot and in the preview check.
- No item mixing with existing networks: our belts/bridges are not placed where an existing drill,
  router, bridge (or a conveyor's front) would push items into them, our drills don't touch existing
  logistics blocks (storage is fine), and our end bridges don't dump into existing ones.
- Server test option `existing=N` (pre-placed drills and belts; reports overlaps).

## [0.9.0] - 2026-10-17
### Added
- **Output line capacity**: every output carries at most one belt's worth (the chosen belt's speed).
  Lanes and bridge chains that would exceed it are split; anything that doesn't fit any output is
  left out instead of overloading a line, and the summary shows how much more the field could
  supply ("Add output"). Per-output loads are shown in the summary and on the markers.
- **Several outputs and water inputs**: click as many as you like (Done / right-click to continue,
  Undo, Auto). **Ports inside the region**: a click inside the selection makes that tile an output
  (items are delivered into it - put your conveyor or core there) or a water input (a liquid router
  you feed); a click on / near the border snaps to the edge as before.
- **Add output** button in the preview: keeps the current ports (also auto-chosen ones) and adds more.
- Server test: `abtest <item> <drill> <outputs> <mode>` with outputs like `E:0.3,W:0.7,@12:8`,
  `water=auto|<ports>`, a vault per output (per-output counts), `abtile x y [r]` for debugging.

### Fixed
- Drill-first with water planned line capacity for unboosted drills (water added later
  multiplied their output); it now plans for boosted drills and recomputes loads from the water
  network actually built.

## [0.8.1] - 2026-10-17
### Fixed
- Material efficiency on large regions mined only a few patches (or produced plans with negative
  scores): logistics were priced like drills, so remote patches never paid for their connection.
  Logistics prices in efficiency mode are now 1/5 (belt 0.05, bridge 0.4, water 0.06, node 0.2) and
  drill-first uses the same prices. Big sparse regions: output x2.5, no negative scores;
  ordinary fields: output +3.2%, ore per drill unchanged.

## [0.8.0] - 2026-10-17
### Added
- **Drill-first layout family**: repeatedly picks the drill (any type) with the best
  value minus connection cost and builds it together with its connection, found by one
  multi-start search from all candidates' neighbour tiles at once; drills already touching the
  network are attached for free. With water, each drill is then connected to the water network on
  its own (liquid A* to a router next to it).
- **Gap filling** for every layout: after routing, drills of any type are added on free tiles that
  already touch a belt or linked bridge.
- **Shared corridors**: tiles under an existing bridge link are cheap for the other kind of bridge,
  so item and liquid bridge chains interleave on one line (`=R  =R  =R`).

### Changed
- Every layout family (belt lanes, bridge lattice, water rows, drill-first) now runs through the full
  pipeline (routing, filling, water, power) and the best final score wins.
- Over 480 random fields vs 0.7.0: output +0.5–2.2% without water and +1.4–7.1% with water;
  logistics tiles per output -2% to -16%.

### Fixed
- A* states carry direction / landed-bridge, so a single route could cross itself (two blocks on
  one tile); such routes are now rejected and searched again.

## [0.7.0] - 2026-10-17
### Added
- **Mixed drill tiers** ("Fill gaps with smaller drills", on by default): for every smaller size,
  the fastest drill with a tier no higher than the chosen one is used where the chosen drill doesn't
  fit (e.g. airblast + laser + pneumatic). Drills are valued by their own speed; power and water are
  planned per type. +1.0% (3x3) / +2.2% (4x4) output on average over 120 random fields.
- Routes can merge into routed item bridges: from the side (not the link side) or by linking a new
  bridge straight into an existing one.
- Water connections can branch off the middle of a conduit (it becomes a liquid router).
- Server test option `nomix`; `MEASURE=<seconds>` for longer measurement windows.

### Changed
- **Fewer logistics tiles**: routing costs count occupied tiles, so a bridge jump of any length costs
  about one tile plus a material premium; end bridges pay for the buffer tiles they reserve.
  Lanes and water rows are split where drills are far apart and each part is connected separately
  (bridges across the gap). Screenshot case (two far ore clusters + water): 8.11 -> 3.86 logistics
  tiles per drill.

## [0.6.0] - 2026-10-16
### Added
- **Click to place ports**: after the settings dialog, click any tile in the region for the item
  output, then the water input; the click snaps to the nearest edge, with a marker and label under
  the cursor. Right-click / "Auto" lets the planner choose: it tries every side and keeps the best.
- **Another plan**: searches again with different preferences (variant noise on drill values,
  routing order, turn penalty, lattice seed); duplicates are skipped. Browse plans with ◀ ▶; the
  best-scoring one is marked. The first plan is already the best of three variants.
- **Search replay**: keyframes recorded during the background search are replayed as gray ghosts
  (at most ~2.5 s, skippable; "Replay the search" option in the dialog).
- Preview panel shows summary, ports, plan number and score; port labels drawn in the world.
- `deploy.sh`: installs the jar into the WSL test profiles and the Windows game's mods folder.

### Fixed
- The final preview drew nothing: `Block.drawPlan` scales by `BuildPlan.animScale`, which is 0 for
  plans outside the player's own queue. (The plan only became visible once it was built.)

### Changed
- Output side / water side buttons and position sliders were replaced by clicking.
- The region is snapshotted once; every candidate side reuses the snapshot.
- Server test: `dir` and `water=` accept `auto`.

## [0.5.1] - 2026-10-16
### Fixed
- Water rows stopped at walls longer than a bridge conduit's range: drills beyond the wall got no
  water, and a dead-end conduit tail was left at the wall. Each water row is now split into
  contiguous runs, and every run is connected on its own by a multi-target liquid A* that can go
  around the wall; conduits only extend to the outermost router.
- Liquid A* heuristic was inadmissible (corridor tiles cost 0.5), which could pick bridge-conduit
  chains over plain conduits.

## [0.5.0] - 2026-10-16
### Added
- **Water boost**: "Water boost" option with water port side (any side but the output),
  position, and conduit type (conduit / pulse conduit).
  - Layout reserves water rows next to drill bands (water rows and item lanes alternate), valued
    at the boosted rate (x2.56 for mechanical/pneumatic/laser/airblast drills).
  - Water network: port liquid router, A* legs (conduits + bridge-conduit jumps) along a reserved
    corridor to a router at each water row, routers in each row next to its drills.
  - Falls back to the best dry layout if the water network wouldn't pay off.
  - Summary: watered drills and water demand at the port; blue marker at the water port.
- Server test options as `key=value` (`size=`, `belt=`, `nobridge`, `water=SIDE[:FRAC]`, `conduit=`),
  liquid source placed at the water port; `selftest.sh map:water`.

### Fixed
- Liquid model: an unlinked bridge conduit does not output back toward its incoming bridge.

## [0.4.0] - 2026-10-16
### Added
- Item bridges ("Use item bridges" option, on by default):
  - **Bridge lattice layout**: rows of bridge chains with drills packed around them; frees most
    of the logistics row for drills. Chosen automatically when it beats belt lanes
    (2x2 drills on the test field: 86% of ore covered vs 83%).
  - **Bridge jumps in routing**: routes can hop over belts, drills and walls.
- Planning runs on a background thread; while it runs the current state is drawn as gray
  ghosts (keyframes at most once per frame), with a "Planning: <stage>" bar and Cancel.
- Bridge links are drawn in the preview.
- `selftest.sh`: automated client check (threaded planning, preview, commit, unit builds all).

### Changed
- Summary shows which layout was chosen.

## [0.3.1] - 2026-10-16
### Fixed
- Which ore a drill mines now follows the game's exact rule (`Drill.countOre`): non-low-priority
  items first (sand is low priority), then count, then higher item id. Previously a drill over
  3 sand + 1 copper was treated as mining sand, and ties were rejected.
- Mineability uses `Drill.canMine`/`getDrop` (respects blocked items and static walls).

## [0.3.0] - 2026-10-15
### Added
- All drills available in the current environment can be selected (laser drill, airblast drill, …);
  drills too weak for the chosen ore are disabled, and picking an ore auto-selects a capable drill.
- Automatic power-node placement for powered drills: greedy coverage of all drills, then relay
  nodes so every node is in one linked group. Connect any node to your power grid.
- "Optimize for" option: **Max output** (cover as much ore as possible) or
  **Material efficiency** (only drills sitting mostly on ore, fewer belts).
- Belt choice (conveyor / titanium conveyor) with capacity warning.
- Summary after planning: estimated items/s, power use, dropped lanes/drills, liquid warning.
- Server commands `abtest` / `abcount` and `servertest.sh` for headless end-to-end checks.

### Changed
- Lane rows and sides are now chosen by a DP over rows instead of a fixed period, which covers
  irregular ore fields much better (about 83–87% of ore tiles in output mode on the test field).
- Mod author set to Maiolao; display name "AutoBuild".

## [0.2.0] - 2026-10-15
### Added
- Drill field planner: belt lanes with drills on both sides (1D DP per lane), A* routing of each
  lane to a user-chosen output port, merging into already-routed belts.
- Config dialog (ore, drill, output side, port position), ghost preview, Build/Edit/Cancel.
- Offline planner test (`test.sh`).

## [0.1.0] - 2026-10-15
### Added
- Environment check: region selection (F7 / button), terrain snapshot, conveyor row placement.

[Unreleased]: #
[1.3.5]: #
[1.3.4]: #
[1.3.3]: #
[1.3.2]: #
[1.3.1]: #
[1.3.0]: #
[1.2.1]: #
[1.2.0]: #
[1.1.0]: #
[1.0.0]: #
[0.10.0]: #
[0.9.1]: #
[0.9.0]: #
[0.8.1]: #
[0.8.0]: #
[0.7.0]: #
[0.6.0]: #
[0.5.1]: #
[0.5.0]: #
[0.4.0]: #
[0.3.1]: #
[0.3.0]: #
[0.2.0]: #
[0.1.0]: #
