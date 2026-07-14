cmake_minimum_required(VERSION 3.22.1)
project(rfb_android C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(PROJECT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../..")
get_filename_component(PROJECT_ROOT "${PROJECT_ROOT}" ABSOLUTE)
set(RFB_ROOT "${PROJECT_ROOT}/vendor/rfb")
set(RFB_SRC "${RFB_ROOT}/src")
set(RFB_GENERATED "${PROJECT_ROOT}/generated")
set(RFB_THIRD_PARTY "${PROJECT_ROOT}/vendor/third_party")
set(MBEDTLS_ROOT "${RFB_THIRD_PARTY}/mbedtls")
set(CURL_ROOT "${RFB_THIRD_PARTY}/curl")

set(RFB_SOURCE_LIST "${RFB_GENERATED}/rfb_sources.cmake")
if(NOT EXISTS "${RFB_SOURCE_LIST}")
    message(FATAL_ERROR
        "RFB generated source list is missing. Run: python tools/prepare_source.py")
endif()
if(NOT EXISTS "${RFB_GENERATED}/rfb_main_android.c")
    message(FATAL_ERROR
        "Patched RFB main is missing. Run: python tools/prepare_source.py")
endif()
if(NOT EXISTS "${MBEDTLS_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR
        "Mbed TLS source is missing. Run: python tools/prepare_source.py")
endif()
if(NOT EXISTS "${CURL_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR
        "curl source is missing. Run: python tools/prepare_source.py")
endif()

include("${RFB_SOURCE_LIST}")

# ---------------------------------------------------------------------------
# Native HTTPS stack: Mbed TLS + static libcurl.
# These sources are prepared once by prepare_source.py, not fetched during
# per-ABI CMake configuration, so arm64/armv7/x86_64 builds are deterministic.
# ---------------------------------------------------------------------------
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
add_subdirectory("${MBEDTLS_ROOT}" "${CMAKE_BINARY_DIR}/mbedtls" EXCLUDE_FROM_ALL)

# Make curl's FindMbedTLS module consume the targets created above rather than
# trying to discover host libraries while cross-compiling for Android.
set(MBEDTLS_INCLUDE_DIR "${MBEDTLS_ROOT}/include" CACHE PATH "" FORCE)
set(MBEDTLS_LIBRARY mbedtls CACHE STRING "" FORCE)
set(MBEDX509_LIBRARY mbedx509 CACHE STRING "" FORCE)
set(MBEDCRYPTO_LIBRARY mbedcrypto CACHE STRING "" FORCE)
set(MBEDTLS_USE_STATIC_LIBS ON CACHE BOOL "" FORCE)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(BUILD_CURL_EXE OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_LIBCURL_DOCS OFF CACHE BOOL "" FORCE)
set(BUILD_MISC_DOCS OFF CACHE BOOL "" FORCE)
set(ENABLE_CURL_MANUAL OFF CACHE BOOL "" FORCE)
set(CURL_DISABLE_INSTALL ON CACHE BOOL "" FORCE)
set(CURL_ENABLE_EXPORT_TARGET OFF CACHE BOOL "" FORCE)
set(HTTP_ONLY ON CACHE BOOL "" FORCE)
set(ENABLE_THREADED_RESOLVER OFF CACHE BOOL "" FORCE)
set(CURL_USE_MBEDTLS ON CACHE BOOL "" FORCE)
set(CURL_USE_OPENSSL OFF CACHE BOOL "" FORCE)
set(CURL_USE_LIBPSL OFF CACHE BOOL "" FORCE)
set(CURL_USE_LIBSSH2 OFF CACHE BOOL "" FORCE)
set(CURL_USE_LIBSSH OFF CACHE BOOL "" FORCE)
set(USE_LIBIDN2 OFF CACHE BOOL "" FORCE)
set(USE_NGHTTP2 OFF CACHE BOOL "" FORCE)
set(USE_NGTCP2 OFF CACHE BOOL "" FORCE)
set(USE_QUICHE OFF CACHE BOOL "" FORCE)
set(USE_MSH3 OFF CACHE BOOL "" FORCE)
set(CURL_ZLIB OFF CACHE BOOL "" FORCE)
set(CURL_BROTLI OFF CACHE BOOL "" FORCE)
set(CURL_ZSTD OFF CACHE BOOL "" FORCE)
set(CURL_USE_PKGCONFIG OFF CACHE BOOL "" FORCE)
set(CURL_USE_CMAKECONFIG OFF CACHE BOOL "" FORCE)
add_subdirectory("${CURL_ROOT}" "${CMAKE_BINARY_DIR}/curl" EXCLUDE_FROM_ALL)

if(TARGET libcurl_static)
    set(RFB_CURL_TARGET libcurl_static)
elseif(TARGET CURL::libcurl)
    set(RFB_CURL_TARGET CURL::libcurl)
else()
    message(FATAL_ERROR "Prepared curl source did not define libcurl_static or CURL::libcurl")
endif()

add_library(rfb_android SHARED
    ${RFB_GAME_SOURCES}
    "${RFB_GENERATED}/rfb_main_android.c"
    "${CMAKE_CURRENT_LIST_DIR}/backend/main-android-rfb.c"
    "${CMAKE_CURRENT_LIST_DIR}/backend/rfb_jni.c"
)

target_include_directories(rfb_android PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/config"
    "${CMAKE_CURRENT_LIST_DIR}/backend"
    "${RFB_SRC}"
)

target_compile_definitions(rfb_android PRIVATE
    HAVE_CONFIG_H=1
    USE_ANDROID=1
    USE_CURL=1
    NETWORK_ENABLED=1
    CURL_STATICLIB=1
)

target_compile_options(rfb_android PRIVATE
    -include
    "${CMAKE_CURRENT_LIST_DIR}/config/rfb_android_compat.h"
    -Wall
    -Wextra
    # High-risk warning policy: fail rather than ship code with likely UB or
    # platform-dependent behavior. Style-only warnings remain warnings.
    -Werror=format-security
    -Werror=implicit-function-declaration
    -Werror=return-type
    -Werror=incompatible-pointer-types
    -Werror=int-conversion
    -Werror=uninitialized
    -Werror=array-bounds
    -Werror=logical-not-parentheses
    -Werror=tautological-overlap-compare
    -Werror=cast-function-type-mismatch
    -Werror=trigraphs
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-sign-compare
    -Wno-missing-field-initializers
    -fno-strict-aliasing
    -fwrapv
    -fno-trigraphs
)

find_library(log-lib log REQUIRED)
find_library(m-lib m REQUIRED)

target_link_libraries(rfb_android PRIVATE
    ${RFB_CURL_TARGET}
    mbedtls
    mbedx509
    mbedcrypto
    ${log-lib}
    ${m-lib}
)
