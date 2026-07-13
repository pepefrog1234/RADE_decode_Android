/*  puresignal.h — C++ facade over the vendored WDSP PureSignal engine
 *
 *  [RADE] New file (not from WDSP). Wraps one calcc (calibration computer)
 *  + iqc (TX I/Q correction applier) pair on a single fixed channel.
 *
 *  The underlying engine is GPL-2.0-or-later, Copyright (C) 2013-2019
 *  Warren Pratt, NR0V — see docs/licenses/WDSP-NOTICE.md.
 *
 *  Threading contract:
 *   - psCreate/psDestroy: any thread (exclusive; serialized against all calls)
 *   - psApply:  audio/TX thread (holds the WDSP per-channel DSP lock while
 *               correcting, mirroring WDSP's dsp-thread behavior)
 *   - psFeed:   network/feedback thread (WDSP's own cs_update lock serializes
 *               the calibration state machine; a facade mutex guards the
 *               float->double scratch buffers)
 *   - the calibration solver runs on its own detached pthread, spawned by
 *     the engine (WDSP _beginthread shim) from within psFeed's state machine
 *
 *  IMPORTANT integration notes (Phase 3):
 *   - While run==true, psApply must keep being called with TX samples:
 *     after a successful calibration the solver thread busy-waits until the
 *     new coefficients have been ramped in by psApply (WDSP SetTXAiqcStart /
 *     SetTXAiqcSwap handshake, ~tup=5 ms of samples).
 *   - psSetRun(false) takes effect through the calibration state machine,
 *     i.e. on the NEXT psFeed calls; keep feeding/applying for a few blocks
 *     so the correction ramps out cleanly (or just call psDestroy, which
 *     force-shuts the engine down).
 *   - `hardwarePeak` passed to psCreate is the nominal peak of the TX/DAC
 *     reference fed to psFeed. CALCC scales that hardware-domain reference
 *     back to the IQC [0,1] envelope domain.
 */

#ifndef PURESIGNAL_H
#define PURESIGNAL_H

/** Create the engine (calcc + iqc) for interleaved I/Q at `rate` Hz.
 *  `blockSize` is the nominal number of complex samples per psFeed/psApply
 *  call (larger calls are processed in blockSize chunks). `hardwarePeak`
 *  is the positive full-scale peak of the hardware TX reference (HL2
 *  Protocol 1 is approximately 0.24). Recreates the engine if one already
 *  exists. Returns false on bad arguments. */
bool psCreate(int rate, int blockSize, double hardwarePeak);

/** Force-shutdown and free the engine. Safe to call when not created. */
void psDestroy();

/** Feed simultaneous TX-reference and RX-feedback blocks to the calibration
 *  computer (WDSP pscc flow). Both buffers are interleaved I/Q float,
 *  nSamples complex samples (2*nSamples floats), at the create rate. */
void psFeed(const float* txRefIQ, const float* fbIQ, int nSamples);

/** Apply the current predistortion to TX I/Q in place (WDSP xiqc).
 *  Interleaved I/Q float, nSamples complex samples. Pass-through (buffer
 *  untouched) when the engine is not created or no correction is active. */
void psApply(float* txIQ, int nSamples);

/** Copy the 16-int calibration state vector (WDSP GetPSInfo):
 *   [0]  spline-fit result for the feedback scale (0 = OK, -1000 = no data)
 *   [1..3] spline-fit results for the magnitude/cos/sin corrections (0 = OK)
 *   [4]  feedback level indicator = (int)(256 * hw_scale / rx_scale)
 *   [5]  count of solver attempts
 *   [6]  solution sanity-check bitfield (scheck: 0 = OK)
 *   [7]  feedback-fit sanity-check bitfield (rxscheck: 0 = OK)
 *   [8]  count of accepted coefficient swaps
 *   [9]  count of rejected solutions
 *   [10] last solver result (0 none, 1 accepted, 2 rejected)
 *   [11..12] unused (0)
 *   [13] iqc envelope-bin watchdog count (a collection that cannot finish
 *        while correction spans all bins is reset when this reaches 6)
 *   [14] 1 while a correction is being applied (iqc running)
 *   [15] calibration state machine state (0 RESET, 1 WAIT, 2 MOXDELAY,
 *        3 SETUP, 4 COLLECT, 5 MOXCHECK, 6 CALC, 7 DELAY, 8 STAYON, 9 TURNON)
 *  Zeroed when the engine is not created. */
void psGetInfo(int* info16);

/** Copy a 32-float summary of the last ACCEPTED correction model:
 *   [0..15]  ym — magnitude correction at each envelope-bin start (the
 *            inverse of the fitted PA AM/AM curve; a compressing PA reads
 *            as ym rising toward the top bins)
 *   [16..31] correction phase at each bin start, degrees (inverse of the
 *            fitted AM/PM; only the SPAN across bins is meaningful — the
 *            absolute value includes the constant ref/feedback path offset)
 *  Bins the fit never populated (and everything when no accepted solution
 *  exists or the engine is down) read 0. Sourced from WDSP's display
 *  arrays, which calc() fills with the fitted splines only when scOK. */
void psGetModel(float* out32);

/** true: start one manual calibration (WDSP mancal + SetPSMox(1)).
 *  false: reset the calibration state machine and ramp the correction out
 *  (WDSP SetPSControl reset + SetPSMox(0)) — see header note about feeding
 *  a few more blocks. */
void psSetRun(bool run);

/** Start exactly one manual calibration while keyed. Unlike automode this
 *  stops in STAYON after accepting a solution instead of continuously
 *  replacing coefficients. */
void psStartSingleCalibration();

/** Update only WDSP's MOX state, preserving accepted correction coefficients.
 *  Use this for normal PTT transitions; reset/destroy is reserved for turning
 *  the PureSignal feature off or tearing the engine down. */
void psSetMox(bool mox);

/** Enable/disable WDSP automode (continuous re-calibration, Thetis default).
 *  Turning it on from the post-one-shot STAYON state ramps the current
 *  correction out once, recollects, and then refines continuously (~1/s,
 *  loop delay 0); each accepted refinement swaps coefficients seamlessly.
 *  Turning it off leaves the last accepted correction applied (STAYON).
 *  MOX must be managed separately (psSetMox). */
void psSetAdaptive(bool on);

#endif // PURESIGNAL_H
