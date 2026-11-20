#!/bin/sh
# Builds gles-scene-test and runs it (under xvfb-run when there is no display). Needs libegl-dev, libgles-dev and Mesa.
#   tools/scene-test/run-gles.sh /path/to/cases [name-filter] [--save]
set -e
cd "$(dirname "$0")"
g++ -O2 -std=c++17 -Wall -Wextra -I. gles-scene-test.cpp -lX11 -lEGL -lGLESv2 -o gles-scene-test
if [ -z "$DISPLAY" ]; then exec xvfb-run -a -s "-screen 0 1920x1080x24" ./gles-scene-test "$@"; else exec ./gles-scene-test "$@"; fi
