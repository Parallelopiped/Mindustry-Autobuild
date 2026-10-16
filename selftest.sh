#!/bin/sh
# Automated client check (needs a display): sandbox game, threaded planning with gray preview,
# commit, unit builds everything, PASS/FAIL in the log. Usage: ./selftest.sh [map]
set -e
cd "$(dirname "$0")"
JAVA_HOME=~/jdk17 ./gradlew jar --no-daemon -q
cp build/libs/mindustry-autobuildDesktop.jar ~/mindustry-test/data/mods/autobuild.jar
cd ~/mindustry-test
MINDUSTRY_DATA_DIR=$PWD/data timeout 200 ~/jdk17/bin/java -Dautobuild.selftest="${1:-Fortress}" -jar Mindustry.jar -gl 3.3 2>&1 \
  | sed 's/\x1b\[[0-9;]*m//g' | grep -E 'selftest|\[autobuild\]|\[E\]|Exception|at autobuild'
