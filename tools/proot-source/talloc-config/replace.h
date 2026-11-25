/* Bionic configuration for Samba talloc 2.4.3. Upstream talloc.c is compiled unchanged. */
#ifndef DSHA_TALLOC_REPLACE_H
#define DSHA_TALLOC_REPLACE_H
#include <assert.h>
#include <ctype.h>
#include <errno.h>
#include <inttypes.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stddef.h>
#include <string.h>
#include <strings.h>
#include <sys/types.h>
#include <unistd.h>
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 3
#define HAVE_INTPTR_T 1
#define HAVE_VA_COPY 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#define discard_const(ptr) ((void *)(uintptr_t)(const void *)(ptr))
#endif
