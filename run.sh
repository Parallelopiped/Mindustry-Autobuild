#!/bin/sh
# Build the mod, install it into the isolated test profile, launch the client.
# -gl 3.3: WSLg's GLX rejects the default OpenGL 4.6 context request and the fallback crashes (xcb).
set -e
cd "$(dirname "$0")"
JAVA_HOME=~/jdk17 ./gradlew jar --no-daemon -q
cp build/libs/mindustry-autobuildDesktop.jar ~/mindustry-test/data/mods/autobuild.jar
cd ~/mindustry-test
MINDUSTRY_DATA_DIR=$PWD/data exec ~/jdk17/bin/java -jar Mindustry.jar -gl 3.3 "$@"
