cmake_minimum_required(VERSION 3.22.1)
project("rade_decode" C CXX)

# ─── Compiler flags ──────────────────────────────────────────
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -ffast-math")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -ffast-math")

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR})

# ─── Oboe (low-latency audio I/O) ───────────────────────────
include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG        1.9.0
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(oboe)

# ─── Build opus as a static library (provides FARGAN, nnet, FFT, etc.) ───
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_FLOAT_APPROX ON CACHE BOOL "" FORCE)
set(OPUS_HARDENING ON CACHE BOOL "" FORCE)
set(OPUS_ENABLE_FLOAT_API ON CACHE BOOL "" FORCE)
set(OPUS_DISABLE_INTRINSICS OFF CACHE BOOL "" FORCE)
# DNN features needed for FARGAN (DEEP_PLC includes nnet + fargan)
# DRED/OSCE disabled — they require auto-generated weight data we don't have
set(OPUS_DEEP_PLC ON CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)

add_subdirectory(opus)

# ─── RADE modem library ─────────────────────────────────────
# rade_all.c is a unity build that #includes all RADE .c files.
# With USE_LIBOPUS, rade_nnet.c is empty (nnet comes from libopus).
add_library(rade STATIC
    radae/rade_all.c
    radae/rade_nnet.c
)

target_compile_definitions(rade PRIVATE
    IS_BUILDING_RADE_API=1
    RADE_PYTHON_FREE=1
    HAVE_CONFIG_H=1
    USE_LIBOPUS=1
)

target_include_directories(rade PRIVATE
    ${CPP_DIR}/radae
    ${CPP_DIR}/opus/dnn
    ${CPP_DIR}/opus/celt
    ${CPP_DIR}/opus/celt/arm
    ${CPP_DIR}/opus/include
    ${CPP_DIR}/opus
    ${CPP_DIR}/eoo
)

# Link rade against opus for nnet functions
target_link_libraries(rade PRIVATE opus)

# ─── EOO callsign codec ─────────────────────────────────────
add_library(eoo STATIC
    eoo/EooCallsignCodec.cpp
    eoo/eoo_callsign_codec_c.cpp
)

target_compile_features(eoo PRIVATE cxx_std_11)

target_include_directories(eoo PRIVATE
    ${CPP_DIR}/eoo
)

# ─── JNI bridge + Audio Engine ───────────────────────────────
add_library(rade_jni SHARED
    rade_jni.cpp
    audio_engine.cpp
    audio_ring_buffer.cpp
    usb_serial_helper.c
)

target_compile_features(rade_jni PRIVATE cxx_std_17)

target_include_directories(rade_jni PRIVATE
    ${CPP_DIR}
    ${CPP_DIR}/radae
    ${CPP_DIR}/opus/dnn
    ${CPP_DIR}/opus/celt
    ${CPP_DIR}/opus/celt/arm
    ${CPP_DIR}/opus/include
    ${CPP_DIR}/opus
    ${CPP_DIR}/eoo
)

target_compile_definitions(rade_jni PRIVATE
    HAVE_CONFIG_H=1
    USE_LIBOPUS=1
)

target_link_libraries(rade_jni
    rade
    opus
    eoo
    oboe
    log
    m
    android
)

# ─── fdsan disable library (LD_PRELOAD for rigctld) ─────────
add_library(fdsan_disable SHARED
    fdsan_disable.c
)
target_link_libraries(fdsan_disable
    dl
    log
)
