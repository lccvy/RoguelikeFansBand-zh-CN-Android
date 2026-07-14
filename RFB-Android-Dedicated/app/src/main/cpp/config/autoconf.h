#ifndef RFB_ANDROID_AUTOCONF_H
#define RFB_ANDROID_AUTOCONF_H

#define ALLOW_XTRA_COLOURS 1
#define NETWORK_ENABLED 1
#define USE_CURL 1

#define DEFAULT_CONFIG_PATH "."
#define DEFAULT_DATA_PATH "."
#define DEFAULT_LIB_PATH "."

#define HAVE_DIRENT_H 1
#define HAVE_FCNTL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_MKDIR 1
#define HAVE_MKSTEMP 1
#define HAVE_STAT 1
#define HAVE_STDBOOL_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRINGS_H 1
#define HAVE_STRING_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE_USLEEP 1
#define HAVE__BOOL 1
#define STDC_HEADERS 1

#define PACKAGE "RoguelikeFansBand"
#define PACKAGE_BUGREPORT ""
#define PACKAGE_NAME "RoguelikeFansBand zh-CN"
#define PACKAGE_STRING "RoguelikeFansBand zh-CN 1.3.0.5"
#define PACKAGE_TARNAME "roguelikefansband-zh-cn"
#define PACKAGE_URL ""
#define PACKAGE_VERSION "1.3.0.5"
#define VERSION "1.3.0.5"
#define RETSIGTYPE void

/* The dedicated frontend is selected by USE_ANDROID in CMake.
 * GCU/SDL/X11/Windows frontends remain disabled; networking is provided by
 * the Android static libcurl + Mbed TLS build. */

#endif
