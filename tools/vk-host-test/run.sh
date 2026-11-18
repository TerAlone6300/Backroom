#!/bin/sh
# Builds and runs the Vulkan host test. Uses xvfb-run when there is no display (CI, SSH, Termux proot).
set -e
cd "$(dirname "$0")"
CPP=../../app/src/main/cpp
g++ -std=c++17 -Wall -Wextra -Wno-missing-field-initializers -I"$CPP" main.cpp "$CPP/vk_core.cpp" -lX11 -ldl -o vk-host-test
if [ -z "$DISPLAY" ]; then exec xvfb-run -a -s "-screen 0 1280x720x24" ./vk-host-test "$@"; else exec ./vk-host-test "$@"; fi
