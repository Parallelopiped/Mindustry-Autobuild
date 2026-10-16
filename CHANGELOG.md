# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`; while `0.x`, any minor bump may
change behaviour or settings).

## [Unreleased]

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
[0.6.0]: #
[0.5.1]: #
[0.5.0]: #
[0.4.0]: #
[0.3.1]: #
[0.3.0]: #
[0.2.0]: #
[0.1.0]: #
