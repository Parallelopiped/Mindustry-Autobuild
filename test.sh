#!/bin/sh
# Offline planner test (no game needed): random maps, checks invariants, prints two ASCII layouts.
set -e
OUT=$(mktemp -d)
~/jdk17/bin/javac -d "$OUT" src/autobuild/plan/*.java test/autobuild/*.java
~/jdk17/bin/java -cp "$OUT" autobuild.DefenseTest
~/jdk17/bin/java -cp "$OUT" autobuild.PlannerTest
