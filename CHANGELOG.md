# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`; while `0.x`, any minor bump may
change behaviour or settings).

## [Unreleased]

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
