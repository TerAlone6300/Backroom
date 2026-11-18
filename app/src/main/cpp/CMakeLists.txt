cmake_minimum_required(VERSION 3.10.2)
project(backrooms_vk LANGUAGES CXX)

# libvulkan is dlopen'ed at runtime (see vk_core.h), so there is nothing Vulkan to link: minSdk stays 21.
add_library(backrooms_vk SHARED vk_core.cpp vk_jni.cpp)
target_compile_features(backrooms_vk PRIVATE cxx_std_17)
target_compile_options(backrooms_vk PRIVATE -Wall -Wextra -Wno-missing-field-initializers -fvisibility=hidden)
target_link_libraries(backrooms_vk android log dl)
