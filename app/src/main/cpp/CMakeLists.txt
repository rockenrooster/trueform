cmake_minimum_required(VERSION 3.22.1)
project(trueform CXX)

# FFmpeg static libraries from scripts/build-ffmpeg.sh (docs/plan/01-native-decoder.md).
set(FF ${CMAKE_SOURCE_DIR}/../../../../third_party/ffmpeg)
if(NOT EXISTS ${FF}/lib/${ANDROID_ABI}/libavcodec.a)
  message(FATAL_ERROR "FFmpeg is not built for ${ANDROID_ABI}. Run scripts/build-ffmpeg.sh (docs/plan/01-native-decoder.md).")
endif()

add_library(trueform SHARED tf_decoder.cpp tf_jni.cpp)
target_compile_features(trueform PRIVATE cxx_std_20)
target_compile_options(trueform PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti -O2 -fvisibility=hidden)
target_include_directories(trueform PRIVATE ${FF}/include)
foreach(lib avformat avcodec swresample avutil)   # this order: each needs the ones after it
  target_link_libraries(trueform PRIVATE ${FF}/lib/${ANDROID_ABI}/lib${lib}.a)
endforeach()
target_link_libraries(trueform PRIVATE log m)
target_link_options(trueform PRIVATE -Wl,-Bsymbolic -Wl,--gc-sections -Wl,--exclude-libs,ALL)
