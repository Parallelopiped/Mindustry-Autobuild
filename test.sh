#!/bin/sh
# Offline planner test (no game needed): random maps, checks invariants, prints two ASCII layouts.
set -e
cd "$(dirname "$0")"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
JAVAC=${JAVA_HOME:+"$JAVA_HOME/bin/"}javac
JAVA=${JAVA_HOME:+"$JAVA_HOME/bin/"}java
"$JAVAC" --release 17 -d "$OUT" src/autobuild/plan/*.java test/autobuild/*.java test/autobuild/plan/*.java
"$JAVA" -Xmx1g -cp "$OUT" autobuild.plan.GridRouterTest
"$JAVA" -Xmx1g -cp "$OUT" autobuild.plan.LatticeSearchTest
"$JAVA" -Xmx1g -cp "$OUT" autobuild.DefenseTest
"$JAVA" -Xmx1g -cp "$OUT" autobuild.PlannerTest
