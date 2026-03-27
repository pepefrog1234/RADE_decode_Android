/* config.h - Configuration for RADE + Opus (FARGAN/LPCNet) on Android/iOS
 *
 * This file is shared by both RADE C sources and Opus headers.
 * It combines the minimal RADE config with the Opus-generated config
 * needed for FARGAN/LPCNet support.
 */
#ifndef CONFIG_H
#define CONFIG_H

/* ── RADE configuration ─────────────────────────────────────── */
#define OPUS_HAVE_RTCD 0

/* ── Opus DNN / FARGAN / LPCNet configuration ───────────────── */
#define DISABLE_DEBUG_FLOAT 1
#define ENABLE_DEEP_PLC 1
#define ENABLE_DRED 1
#define ENABLE_HARDENING 1
#define ENABLE_OSCE 1
#define ENABLE_OSCE_BWE 1
#define ENABLE_RES24 1
#define FLOAT_APPROX 1

/* Standard headers (all available on Apple platforms) */
#define HAVE_DLFCN_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LRINT 1
#define HAVE_LRINTF 1
#define HAVE_STDINT_H 1
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_MEMORY_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define STDC_HEADERS 1

/* Package info (from Opus configure) */
#define PACKAGE_BUGREPORT "opus@xiph.org"
#define PACKAGE_NAME "opus"
#define PACKAGE_STRING "opus unknown"
#define PACKAGE_TARNAME "opus"
#define PACKAGE_URL ""
#define PACKAGE_VERSION "unknown"

/* sizeof types */
#define SIZEOF_INT 4
#define SIZEOF_LONG 8
#define SIZEOF_LONG_LONG 8
#define SIZEOF_SHORT 2

/* Opus version */
#define OPUS_VERSION "unknown"

#endif /* CONFIG_H */
