#!/bin/bash
# End-to-end check of the defense planner on a dedicated server: host a map (sandbox), plan a
# defense on the enemy route and place it, check ammo / power / coolant and the route against the
# game's own flow field, then spawn waves and count kills and losses.
#   ./servertest-def.sh <map> <turrets> <ammo|-> [options]     (WAVES=n, FIGHT=seconds)
cd ~/mindustry-test
map=$1; shift
{
  sleep 4; echo "host $map sandbox"
  sleep 6; echo "abroute"
  sleep 1; echo "abdef $*"
  sleep 25; echo "abdefcheck"
  sleep 1; echo "abwave ${WAVES:-3}"
  sleep ${FIGHT:-45}; echo "abwavecount"
  sleep 1; echo "exit"
} | timeout $((80 + ${FIGHT:-45})) ~/jdk17/bin/java -jar server.jar 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '\[ab|\[route|autobuild|\[E\]|Exception|at mindustry|at autobuild' | sed 's/^\[[^]]*\] //'
