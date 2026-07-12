/*  wdsp_port.c

This file is part of a program that implements a Software-Defined Radio.

Copyright (C) 2013 Warren Pratt, NR0V and John Melton, G0ORX/N6LYT

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.

The author can be reached by email at

warren@wpratt.com
john.d.melton@googlemail.com

*/

/* [RADE] Minimal extraction from WDSP linux_port.c for the vendored
 * PureSignal engine.  Only the primitives calcc.c / iqc.c / delay.c use are
 * kept (critical sections + detached thread start/exit); the semaphore /
 * event / work-queue shims are not vendored.  Also defines the storage for
 * the minimal one-channel txa[]/ch[] globals declared in wdsp_min.h. */

#include "wdsp_min.h"	// [RADE] was #include "linux_port.h" + "comm.h"

/* [RADE] storage for the minimal channel globals (declared in wdsp_min.h;
 * note txa/ch are #defined to wdsp_ps_txa/wdsp_ps_ch there) */
struct _ps_txa txa[WDSP_PS_MAX_CHANNELS];
struct _ps_ch  ch[WDSP_PS_MAX_CHANNELS];

/********************************************************************************************************
*													*
*	Linux Port Utilities										*
*													*
********************************************************************************************************/

#if defined(linux) || defined(__APPLE__) || defined(__ANDROID__)	// [RADE] added __ANDROID__ (matches linux_port.h)

void InitializeCriticalSectionAndSpinCount(pthread_mutex_t *mutex,int count) {
	pthread_mutexattr_t mAttr;
	pthread_mutexattr_init(&mAttr);
#if defined(__APPLE__) || defined(__ANDROID__)	// [RADE] bionic (like MacOS) has no PTHREAD_MUTEX_RECURSIVE_NP
	// DL1YCF: MacOS X does not have PTHREAD_MUTEX_RECURSIVE_NP
	pthread_mutexattr_settype(&mAttr,PTHREAD_MUTEX_RECURSIVE);
#else
	pthread_mutexattr_settype(&mAttr,PTHREAD_MUTEX_RECURSIVE_NP);
#endif
	pthread_mutex_init(mutex,&mAttr);
	pthread_mutexattr_destroy(&mAttr);
	// ignore count
}

void EnterCriticalSection(pthread_mutex_t *mutex) {
	pthread_mutex_lock(mutex);
}

void LeaveCriticalSection(pthread_mutex_t *mutex) {
	pthread_mutex_unlock(mutex);
}

void DeleteCriticalSection(pthread_mutex_t *mutex) {
	pthread_mutex_destroy(mutex);
}

HANDLE wdsp_beginthread( void( __cdecl *start_address )( void * ), unsigned stack_size, void *arglist) {
	pthread_t threadid;
	pthread_attr_t  attr;
	int rc = 0;

	if ((rc = pthread_attr_init(&attr))) {	// [RADE] parenthesized assignments (clang -Wparentheses); logic unchanged
 	    return (HANDLE)-1;
	}

	if(stack_size!=0) {
	    if ((rc = pthread_attr_setstacksize(&attr, stack_size))) {
	        return (HANDLE)-1;
	    }
	}

        if(( rc = pthread_attr_setdetachstate(&attr,PTHREAD_CREATE_DETACHED))) {
            return (HANDLE)-1;
        }

	if ((rc = pthread_create(&threadid, &attr, (void*(*)(void*))start_address, arglist))) {
	     return (HANDLE)-1;
	}

        //pthread_attr_destroy(&attr);
#if !defined(__APPLE__) && !defined(__ANDROID__)	// [RADE] bionic pthread_setname_np also names other threads, but keep parity with the Apple branch and skip it
	// DL1YCF: this function does not exist on MacOS. You can only name the
        //         current thread.
        rc=pthread_setname_np(threadid, "WDSP");
#endif

	return (HANDLE)threadid;

}

void _endthread() {
	int res;
	pthread_exit((void *)&res);
}

#endif
