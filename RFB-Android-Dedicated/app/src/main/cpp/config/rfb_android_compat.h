#ifndef RFB_ANDROID_COMPAT_H
#define RFB_ANDROID_COMPAT_H

/* Dedicated Android/Bionic compatibility shim for the RFB core.
 *
 * RFB's legacy h-system.h only includes <unistd.h> in its SET_UID branch.
 * The dedicated Android port intentionally disables SET_UID semantics, but
 * still needs normal POSIX file-descriptor APIs supplied by Bionic.
 */
#ifdef __ANDROID__
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <strings.h>

/* RFB's non-SET_UID fd_read/fd_write path uses the MS-style spellings for
 * large chunks. Android/Bionic provides the POSIX names. Keep this mapping
 * local to the Android target instead of changing upstream game logic. */
#ifndef _read
#define _read read
#endif
#ifndef _write
#define _write write
#endif
#ifndef _close
#define _close close
#endif
#ifndef _unlink
#define _unlink unlink
#endif

/* Binary/text mode distinction does not exist for POSIX descriptors. */
#ifndef O_BINARY
#define O_BINARY 0
#endif

/* RFB's h-config.h handles stricmp when Clang exposes the legacy bare
 * `linux` macro. Keep a fallback for strict modes that expose only
 * `__linux__`, without redefining the macros in the normal NDK mode. */
#ifndef linux
# ifndef HAS_STRICMP
#  define HAS_STRICMP 1
# endif
# ifndef stricmp
#  define stricmp strcasecmp
# endif
#endif
#endif /* __ANDROID__ */

#endif /* RFB_ANDROID_COMPAT_H */
