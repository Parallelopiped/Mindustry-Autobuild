#!/bin/bash
# End-to-end check on a dedicated server (no GUI): host a map, place a planned drill field
# instantly, then measure items/s arriving at the port.
#   ./servertest.sh <map> <item> <drill> <dir> <mode> [size] [belt]
cd ~/mindustry-test
map=$1; shift
{
  sleep 4; echo "host $map sandbox"
  sleep 5; echo "abtest $*"
  sleep 15; echo "abcount"   # warm-up: drills spin up, belts fill
  sleep ${MEASURE:-30}; echo "abcount"   # steady state
  sleep ${MEASURE:-30}; echo "abcount"
  sleep 1; echo "exit"
} | timeout $((60 + 2 * ${MEASURE:-30})) ~/jdk17/bin/java -jar server.jar 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '\[ab|autobuild|\[E\]|Exception|at mindustry|at autobuild' | sed 's/^\[[^]]*\] //'
