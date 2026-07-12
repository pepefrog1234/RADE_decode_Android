/*  wdsp_min.h

This file is part of a program that implements a Software-Defined Radio.

Copyright (C) 2013 Warren Pratt, NR0V

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

*/

/* [RADE] This header replaces WDSP's comm.h for the vendored PureSignal
 * subset (calcc.c, iqc.c, delay.c, wdsp_utils.c, wdsp_port.c).  comm.h
 * pulls in fftw3 and every WDSP module header; the PureSignal engine only
 * needs the pieces reproduced here:
 *   - the linux pthread port shims (linux_port.h)
 *   - the `complex`/PI/TWOPI/PORT definitions from comm.h
 *   - malloc0 (utilities.c) and fir_bandpass (fir.c) declarations
 *   - a minimal one-channel replacement for the txa[]/ch[] channel globals
 *     from TXA.h / channel.h (only the members calcc.c/iqc.c touch)
 */

#ifndef _wdsp_min_h
#define _wdsp_min_h

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <math.h>
#include <pthread.h>
#include <semaphore.h>

#ifdef __cplusplus
extern "C" {	/* [RADE] everything below (incl. linux_port.h shims) has C linkage */
#endif

#include "linux_port.h"

/* [RADE] from comm.h — math definitions */
#define PI								3.1415926535897932
#define TWOPI							6.2831853071795864

/* [RADE] from comm.h — miscellaneous */
typedef double complex[2];
#define PORT

/* [RADE] from utilities.h — required utility (defined in wdsp_utils.c) */
extern void *malloc0 (int size);

/* [RADE] from fir.h — pure-math FIR generator needed by delay.c
 * (defined in wdsp_utils.c; no fftw dependency) */
extern double* fir_bandpass (int N, double f_low, double f_high, double samplerate,
	int wintype, int rtype, double scale);

#include "delay.h"
#include "calcc.h"
#include "iqc.h"

/* [RADE] Minimal replacement for the WDSP channel globals.
 * WDSP keeps per-channel module pointers in `struct _txa txa[]` (TXA.h) and
 * per-channel DSP locks in `struct _channel ch[]` (channel.h).  calcc.c and
 * iqc.c reference only the members below.  The symbols are renamed with the
 * wdsp_ps_ prefix (via #define) so the generic names `txa`/`ch` cannot
 * collide with other libraries linked into rade_jni.so.  Storage is defined
 * in wdsp_port.c; the single channel (index 0) is owned by puresignal.cpp. */
#define WDSP_PS_MAX_CHANNELS 1
#define txa wdsp_ps_txa
#define ch  wdsp_ps_ch

struct _ps_txa
{
	struct
	{
		CALCC p;
		CRITICAL_SECTION cs_update;
	} calcc;
	struct
	{
		IQC p0, p1;
		// p0 for dsp-synchronized reference, p1 for other
	} iqc;
};

struct _ps_ch
{
	CRITICAL_SECTION csDSP;		// used to block dsp while parameters are updated or buffers flushed
};

extern struct _ps_txa txa[WDSP_PS_MAX_CHANNELS];
extern struct _ps_ch  ch[WDSP_PS_MAX_CHANNELS];

/* [RADE] Public WDSP PureSignal entry points defined in calcc.c that the
 * facade (puresignal.cpp) calls.  In upstream WDSP these are declared in
 * wdsp.h, which we do not vendor. */
extern void SetPSRunCal (int channel, int run);
extern void SetPSMox (int channel, int mox);
extern void GetPSInfo (int channel, int *info);
extern void SetPSReset (int channel, int reset);
extern void SetPSMancal (int channel, int mancal);
extern void SetPSAutomode (int channel, int automode);
extern void SetPSTurnon (int channel, int turnon);
extern void SetPSControl (int channel, int reset, int mancal, int automode, int turnon);
extern void SetPSLoopDelay (int channel, double delay);
extern void SetPSMoxDelay (int channel, double delay);
extern double SetPSTXDelay (int channel, double delay);
extern void SetPSHWPeak (int channel, double peak);
extern void GetPSHWPeak (int channel, double* peak);
extern void GetPSMaxTX (int channel, double* maxtx);
extern void SetPSPtol (int channel, double ptol);
extern void SetPSFeedbackRate (int channel, int rate);
extern void SetPSPinMode (int channel, int pin);
extern void SetPSMapMode (int channel, int map);
extern void SetPSStabilize (int channel, int stbl);
extern void ForceShutDown (CALCC a, IQC b, int timeout);

#ifdef __cplusplus
}
#endif

#endif
