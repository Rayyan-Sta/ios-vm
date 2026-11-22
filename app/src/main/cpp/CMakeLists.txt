cmake_minimum_required(VERSION 3.22.1)
project(iosnative LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(iosnative SHARED
    jni/jni_bridge.cpp
    cpu/cpu_features.cpp
    gfx/guest_ram_map.cpp
    gfx/gl_renderer.cpp
    qmp/qmp_client.cpp)

target_include_directories(iosnative PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(iosnative PRIVATE
    -O2 -fno-emulated-tls -fvisibility=hidden -ffunction-sections -fdata-sections
    -Wall -Wextra -Wno-unused-parameter)
target_link_options(iosnative PRIVATE
    -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,separate-code)
target_link_libraries(iosnative PRIVATE android log EGL GLESv3)
