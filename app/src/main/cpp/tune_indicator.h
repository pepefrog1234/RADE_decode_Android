/*
 * tune_indicator.h - the RADE V2 tuning indication the receiver screen shows.
 *
 * rade_mistune() says which way an off-frequency V2 signal is off (+1 too high
 * in audio frequency, -1 too low). Two things about it need care before it is
 * shown to an operator:
 *
 *  - It is refreshed before the modem's state machine runs, so on the
 *    rade_rx() call that acquires it still carries the direction of the
 *    refused attempts while rade_sync() already reports sync.
 *  - Its first reading rests on about one second of signal. On a fading HF
 *    channel a frequency-selective fade can make it point the wrong way for
 *    a moment, mostly right at the start (12 of 320 simulated fading overs,
 *    0.2-2.6 s at a time), before it settles on the correct direction.
 *
 * So the indication is only passed on while the receiver is out of sync and
 * once the modem has reported the same direction for
 * TUNE_INDICATOR_CONFIRM_SAMPLES of audio without interruption (none of the
 * 320 overs showed a wrong direction that way). It is withdrawn immediately
 * when the modem withdraws it. A deep fade that does not move can still keep
 * the modem, and with it this indication, pointing the wrong way.
 *
 * Plain C so that tools/radecheck/tunecheck.c runs this very code.
 */

#ifndef TUNE_INDICATOR_H
#define TUNE_INDICATOR_H

#ifdef __cplusplus
extern "C" {
#endif

/* 3 s at the modem's 8 kHz sample rate. */
#define TUNE_INDICATOR_CONFIRM_SAMPLES (3 * 8000)

typedef struct {
    int raw;           /* the modem's direction on the last call, 0 in sync */
    int held_samples;  /* audio since it last changed, capped at the threshold */
} tune_indicator;

static inline void tune_indicator_reset(tune_indicator *t) {
    t->raw = 0;
    t->held_samples = 0;
}

/* Call after every rade_rx(): `mistune` = rade_mistune(), `in_sync` =
 * rade_sync() != 0, `nin` = the samples that call consumed. Returns the
 * indication to show: +1, -1 or 0. */
static inline int tune_indicator_update(tune_indicator *t, int mistune, int in_sync, int nin) {
    int raw = in_sync ? 0 : mistune;
    if (raw != t->raw) {
        t->raw = raw;
        t->held_samples = 0;
    }
    if (raw != 0 && t->held_samples < TUNE_INDICATOR_CONFIRM_SAMPLES) t->held_samples += nin;
    return (raw != 0 && t->held_samples >= TUNE_INDICATOR_CONFIRM_SAMPLES) ? raw : 0;
}

#ifdef __cplusplus
}
#endif

#endif
