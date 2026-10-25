#!/bin/sh
# Build, then install the mod jar into the WSL test profile and the Windows game's mods folder
# (overwrites the previous version there).
set -e
cd "$(dirname "$0")"
JAVA_HOME=~/jdk17 ./gradlew jar --no-daemon -q
JAR=build/libs/mindustry-autobuildDesktop.jar
cp "$JAR" ~/mindustry-test/data/mods/autobuild.jar
cp "$JAR" ~/mindustry-test/config/mods/autobuild.jar
# the Windows game's mods folder (WSL path); set MINDUSTRY_WIN_MODS to yours
WIN=${MINDUSTRY_WIN_MODS:-$(ls -d /mnt/c/Users/*/AppData/Roaming/Mindustry/mods 2>/dev/null | head -1)}
if [ -d "$WIN" ]; then cp "$JAR" "$WIN/mindustry-autobuildDesktop.jar" && echo "deployed to $WIN"; else echo "Windows mods folder not found: $WIN"; fi
