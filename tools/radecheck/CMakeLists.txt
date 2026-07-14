# Host-side RADE modem loopback check (macOS/Linux, with ASAN).
#
# Builds the vendored radae tree + vendored opus for the HOST and runs a
# tx→rx loopback for BOTH waveforms (V1 and V2), driving the same buffer
# contract as AudioEngine::processModemFrame. This exercises acquisition,
# sync, the decoder networks and EOO — the paths an APK build gate never
# runs. Use it after every radae/opus vendor update:
#
#   cmake -B build -DCMAKE_BUILD_TYPE=Debug tools/radecheck
#   cmake --build build -j8 && ./build/loopback
#
# Exit code 0 = both waveforms synced and decoded cleanly under ASAN.
# (v1.6.2 exists because this check did not: the V2 decoder aborted on
# sync due to a missing opus-nnet patch, crashing the app on-air.)
cmake_minimum_required(VERSION 3.22)
project(radecheck C CXX)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -g -fsanitize=address -fno-omit-frame-pointer")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -g -fsanitize=address -fno-omit-frame-pointer")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -fsanitize=address")

set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_DEEP_PLC ON CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
add_subdirectory(${CPP_DIR}/opus opus)

add_library(rade STATIC
    ${CPP_DIR}/radae/rade_api.c
    ${CPP_DIR}/radae/rade_enc.c
    ${CPP_DIR}/radae/rade_dec.c
    ${CPP_DIR}/radae/rade_tx.c
    ${CPP_DIR}/radae/rade_rx.c
    ${CPP_DIR}/radae/rade_acq.c
    ${CPP_DIR}/radae/rade_ofdm.c
    ${CPP_DIR}/radae/rade_bpf.c
    ${CPP_DIR}/radae/rade_dsp.c
    ${CPP_DIR}/radae/rade_enc_data.c
    ${CPP_DIR}/radae/rade_dec_data.c
    ${CPP_DIR}/radae/rade_enc_v2.c
    ${CPP_DIR}/radae/rade_dec_v2.c
    ${CPP_DIR}/radae/rade_tx_v2.c
    ${CPP_DIR}/radae/rade_rx_v2.c
    ${CPP_DIR}/radae/rade_sync.c
    ${CPP_DIR}/radae/rade_v2_ofdm.c
    ${CPP_DIR}/radae/rade_enc_v2_data.c
    ${CPP_DIR}/radae/rade_dec_v2_data.c
    ${CPP_DIR}/radae/rade_sync_data.c
    ${CPP_DIR}/radae/kiss_fft.c
    ${CPP_DIR}/radae/kiss_fftr.c
    ${CPP_DIR}/radae/rade_nnet.c
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
)
target_link_libraries(rade PUBLIC opus)

add_executable(loopback loopback.c)
target_include_directories(loopback PRIVATE ${CPP_DIR}/radae)
target_link_libraries(loopback PRIVATE rade m)
