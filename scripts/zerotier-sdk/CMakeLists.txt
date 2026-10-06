cmake_minimum_required(VERSION 3.22)
project(ZeroTierOneJNI LANGUAGES C CXX)

if(NOT ZT_SOURCE_DIR)
    message(FATAL_ERROR "Set ZT_SOURCE_DIR to the separate ZeroTierOne checkout")
endif()

file(GLOB ZT_NODE_SOURCES CONFIGURE_DEPENDS "${ZT_SOURCE_DIR}/node/*.cpp")

add_library(ZeroTierOneJNI SHARED
    ${ZT_NODE_SOURCES}
    "${ZT_SOURCE_DIR}/osdep/Http.cpp"
    "${ZT_SOURCE_DIR}/osdep/OSUtils.cpp"
    "${ZT_SOURCE_DIR}/ext/http-parser/http_parser.c"
    "${ZT_SOURCE_DIR}/java/jni/ZT_jnicache.cpp"
    "${ZT_SOURCE_DIR}/java/jni/ZT_jniutils.cpp"
    "${ZT_SOURCE_DIR}/java/jni/com_zerotierone_sdk_Node.cpp"
)

target_include_directories(ZeroTierOneJNI PRIVATE
    "${ZT_SOURCE_DIR}/include"
    "${ZT_SOURCE_DIR}/node"
    "${ZT_SOURCE_DIR}/osdep"
    "${ZT_SOURCE_DIR}/ext"
    "${ZT_SOURCE_DIR}/ext/http-parser"
    "${ZT_SOURCE_DIR}/ext/prometheus-cpp-lite-1.0/core/include"
    "${ZT_SOURCE_DIR}/ext/prometheus-cpp-lite-1.0/simpleapi/include"
    "${ZT_SOURCE_DIR}/ext/prometheus-cpp-lite-1.0/3rdparty/http-client-lite/include"
    "${CMAKE_SYSROOT}/usr/include"
)

target_compile_definitions(ZeroTierOneJNI PRIVATE ZT_SDK=1 ZT_BUILD_PLATFORM=4)
target_compile_features(ZeroTierOneJNI PRIVATE cxx_std_17)
target_compile_options(ZeroTierOneJNI PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:-include${CMAKE_CURRENT_SOURCE_DIR}/android-pthread-affinity-compat.h>
)
target_link_libraries(ZeroTierOneJNI PRIVATE log)
