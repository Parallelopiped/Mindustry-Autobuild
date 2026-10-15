# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) (`MAJOR.MINOR.PATCH`; while `0.x`, any minor bump may
change behaviour or settings).

## [Unreleased]

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
[0.3.0]: #
[0.2.0]: #
[0.1.0]: #
