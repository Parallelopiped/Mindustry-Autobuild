#!/bin/sh
# Build, then install the mod jar into the WSL test profile and the Windows game's mods folder
# (overwrites the previous version there).
set -e
cd "$(dirname "$0")"
JAVA_HOME=~/jdk17 ./gradlew jar --no-daemon -q
JAR=build/libs/mindustry-autobuildDesktop.jar
cp "$JAR" ~/mindustry-test/data/mods/autobuild.jar
cp "$JAR" ~/mindustry-test/config/mods/autobuild.jar
WIN=/mnt/c/Users/25753/AppData/Roaming/Mindustry/mods
if [ -d "$WIN" ]; then cp "$JAR" "$WIN/mindustry-autobuildDesktop.jar" && echo "deployed to $WIN"; else echo "Windows mods folder not found: $WIN"; fi
