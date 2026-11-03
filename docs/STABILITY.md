# Local stability/performance patch

Based on upstream `fc19beb` (AutoBuild 1.3.6), targeting Mindustry **160.7**.
This derivative retains the upstream attribution and CC BY-NC-SA 4.0 license.
The mod version is `1.3.6-local.1`, to distinguish this local build from an upstream release.

## Confirmed crash

The supplied Windows/Steam 160.7 report ends at
`RemotePorts.edgePortsFacing(RemotePorts.java:334)` with
`ArrayIndexOutOfBoundsException: Index 3 out of bounds for length 3`.

When a remote water source cannot reach a usable edge, the fallback filters out
output-facing sides. It then indexes that filtered array using a fixed four-iteration
loop. Iterate over the actual array length. The regression reproduces the same
exception and line on the unmodified upstream revision; the patch passes 1,152
combinations of exclusion masks, requested line counts, accessibility and region shapes.
The existing fallback for all four sides excluded is preserved.

## Worker lifecycle and memory

- Catch runtime exceptions from main-thread snapshot preparation and report them in
  the existing error UI, instead of letting the exception escape the input handler.
- Invalidate callbacks on reset, world load and cancellation. Stale progress is
  rejected while holding the same lock used to clear replay frames.
- A single daemon executor runs planning jobs. Cancelling interrupts the task and
  purges cancelled queued work. Routing, flow-field and lattice loops check cancellation.
- Each request copies the settings used by its worker. Mutable drill port lists and
  defense turret selections are separate; port-kind identities remain shared for UI
  lookups. The main thread still prepares world data. This is not a general deep copy
  of all Mindustry world objects.
- Duplicate detection reads captured signatures; only main-thread completion advances
  the next variant counter. Workers do not iterate the live preview list.
- Retain at most 120 replay frames and 50,000 recorded BuildPlan entries. Non-forced
  progress snapshots are throttled to 50 ms. Replay data is released when skipped,
  completed, cancelled or reset; the latest preview is kept while searching.
- Keep the most recent 12 completed alternatives to bound retained planner graphs.

## Search changes

`GridRouter` now tracks a source by its full `(tile, entry direction)` state. Previously,
two starts on one tile could overwrite each other and give reconstruction the wrong
source block or branch origin.

The search heap stores primitive state IDs and priorities, reuses its storage and the
distance/predecessor arrays, and retains PriorityQueue's priority tie handling.
Routing exclusions use a boolean mask instead of boxing each explored tile ID.
Shortest-path costs are unchanged in the randomized A*/Dijkstra comparison and the
fixed benchmark's distance-field checksum is unchanged. Different source-state bug
cases can intentionally select different routes.

The drill lattice's local DFS was exponential and did not check cancellation inside
recursion. Each window now allows 20,000 visited nodes, each lattice improvement
500,000 nodes, with a maximum of 256 local candidates for recursion. The removed
selection is the incumbent: budget exhaustion retains a valid solution whose local
drill-value sum is no lower. The full pipeline still compares layout families.
This makes local optimization approximate; it does **not** guarantee an identical
layout or globally optimal production on every map.

## Reproducing validation

Use JDK 17 or newer (all mod classes target Java 17):

```text
./gradlew check jar
./gradlew regressionTest
```

On Windows use `gradlew.bat`. If the release dependency cannot be downloaded by Java,
download the official v160.7 `dependencies.jar`, then use:

```text
./gradlew check jar -PmindustryJar=/absolute/path/to/dependencies.jar
```

In PowerShell quote the entire `'-PmindustryJar=...'` argument. No certificate
verification is disabled. The override is optional; the default remains the upstream
version-pinned dependency. The JAR does not bundle Mindustry dependencies.

`check` runs the existing PlannerTest/DefenseTest main-method suites and the four new
regression suites. `test.sh` runs the pure Java suites without game dependencies and
uses JAVA_HOME or the JDK on PATH instead of a developer-specific home directory.

The standalone `autobuild.RouterBenchmark` uses a deterministic 160x160 map, bridge
range 4, five warmups and nine measured searches; it prints median time, per-thread
allocated bytes and a checksum. Run it against each revision with the same JVM and
heap. This is a routing microbenchmark, not a game FPS measurement.

## Remaining validation limits

Automated controller tests do not render the Steam client's UI. A long session with
the original save/map is still useful for checking player-specific conditions.
Main-thread world snapshotting and remote-port flood preparation remain synchronous;
very large maps can still pause briefly at preparation. No Android DEX build is
included. The delivered desktop JAR is intended for Steam 160.7.

## Local results (2026-11-03)

- JDK 21, `--release 17`; `regressionTest jar` succeeded. The normal `check`
  task graph was inspected; the long planner main suites were also run directly.
- Upstream and modified PlannerTest: `ALL OK` (400 random seeds plus ore rules,
  water, capacity, existing-port, mixed-drill and efficiency cases).
- Upstream and modified DefenseTest: 120/120 planned and valid, `DEFENSE OK`.
- Regression suites: 1,152 remote-port combinations; 160 A*/Dijkstra comparisons;
  heap ordering, same-tile start identity, cancellation, stale controller callbacks,
  replay bounds, snapshot/worker failures, dense mixed-tier lattice search.
- Fixed routing benchmark: median **84.854 -> 49.483 ms**, allocation
  **33.433 -> 0.198 MiB** per search, checksum **18597549.6000** in both revisions.
  These are local microbenchmark results, not a general FPS or whole-planner claim.
- Efficiency/max-output ratios from the existing 120-map tests, by main drill size:
  2: **90.6% -> 90.7%**; 3: **88.3% -> 88.1%**; 4: **82.5% -> 82.5%**.
  Bounded local search can change layouts and does not preserve exact per-map scores.
- Isolated official 160.7 headless server, Fortress: dry mechanical-drill field
  delivered **4.84 items/s** vs **4.98** predicted after warmup; water-boosted
  pneumatic-drill field delivered **9.90 items/s** vs **9.89** predicted.
- Steam rendering, the user's exact save, and long-duration gameplay were not tested.
