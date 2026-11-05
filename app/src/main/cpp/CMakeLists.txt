cmake_minimum_required(VERSION 3.22.1)
# SDK CMake 3.22.1 on Windows crashes resolving cached NDK binutils with CMP0125 NEW.
cmake_policy(SET CMP0125 OLD)
project(moto_rnnoise LANGUAGES C CXX)

set(RNNOISE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/rnnoise")

add_library(moto_rnnoise SHARED
    rnnoise_jni.cpp
    ${RNNOISE_DIR}/src/denoise.c
    ${RNNOISE_DIR}/src/rnn.c
    ${RNNOISE_DIR}/src/pitch.c
    ${RNNOISE_DIR}/src/kiss_fft.c
    ${RNNOISE_DIR}/src/celt_lpc.c
    ${RNNOISE_DIR}/src/nnet.c
    ${RNNOISE_DIR}/src/nnet_default.c
    ${RNNOISE_DIR}/src/parse_lpcnet_weights.c
    ${RNNOISE_DIR}/src/rnnoise_data.c
    ${RNNOISE_DIR}/src/rnnoise_tables.c
)

target_include_directories(moto_rnnoise PRIVATE
    ${RNNOISE_DIR}/include
    ${RNNOISE_DIR}/src
)
target_compile_features(moto_rnnoise PRIVATE c_std_99 cxx_std_17)
target_compile_options(moto_rnnoise PRIVATE -O3 -fvisibility=hidden)
target_compile_definitions(moto_rnnoise PRIVATE DISABLE_DEBUG_FLOAT=1)
# vec.h selects upstream NEON on Android ARM and SSE2 on x86_64. Do not
# enable fast-math: the JNI boundary deliberately rejects non-finite output.
target_link_libraries(moto_rnnoise PRIVATE m)

# NDK r28 already emits 16 KiB-compatible ELF segments. Keep the requirement
# explicit so a future toolchain downgrade cannot silently change alignment.
target_link_options(moto_rnnoise PRIVATE "-Wl,-z,max-page-size=16384")
