#!/bin/sh
# Builds vk-scene-test and runs it (under xvfb-run when there is no display). The screen must be as large as the frame.
#   tools/scene-test/run.sh /path/to/cases [name-filter] [--save]
# Cases: kotlinc core/*.kt core/levels/*.kt tools/scene-test/Cases.kt tools/scene-test/SceneDump.kt -include-runtime -d dump.jar && java -jar dump.jar /path/to/cases
set -e
cd "$(dirname "$0")"
CPP=../../app/src/main/cpp
g++ -O2 -std=c++17 -Wall -Wextra -Wno-missing-field-initializers -I"$CPP" vk-scene-test.cpp "$CPP/vk_core.cpp" -lX11 -ldl -o vk-scene-test
if [ -z "$DISPLAY" ]; then exec xvfb-run -a -s "-screen 0 1920x1080x24" ./vk-scene-test "$@"; else exec ./vk-scene-test "$@"; fi
