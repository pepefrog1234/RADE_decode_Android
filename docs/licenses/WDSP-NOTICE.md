# WDSP PureSignal — License Notice

This app vendors the **PureSignal** adaptive TX predistortion engine from
**WDSP**, a Software-Defined Radio DSP library by **Warren Pratt, NR0V**
(with the Linux/pthread port by **John Melton, G0ORX/N6LYT**, as distributed
in the g0orx Linux port used by piHPSDR).

- License: **GNU General Public License, version 2 or (at your option) any
  later version (GPL-2.0-or-later)**
- Source repository (Linux port): <https://github.com/g0orx/wdsp>
- Author contact (per source headers): warren@wpratt.com

## Vendored files

Located in `app/src/main/cpp/wdsp_ps/`:

| File | Origin | Notes |
|------|--------|-------|
| `calcc.c`, `calcc.h` | WDSP `calcc.c` / `calcc.h` | PureSignal calibration computer. One-line include change (`comm.h` → `wdsp_min.h`). |
| `iqc.c`, `iqc.h` | WDSP `iqc.c` / `iqc.h` | TX I/Q correction applier. One-line include change. |
| `delay.c`, `delay.h` | WDSP `delay.c` / `delay.h` | Sub-sample delay used for TX/feedback time alignment. One-line include change. |
| `linux_port.h` | WDSP `linux_port.h` | Windows→pthread shims. One-line platform-guard change (adds `__ANDROID__`). |
| `wdsp_port.c` | Extracted from WDSP `linux_port.c` | Critical-section + thread-start shims actually used by the above; also holds the minimal channel-global storage. |
| `wdsp_utils.c` | Extracted from WDSP `utilities.c` (`malloc0`) and `fir.c` (`fir_bandpass`) | Only the two functions the vendored engine needs; avoids WDSP's fftw dependency. |
| `wdsp_min.h` | New file derived from WDSP `comm.h` / `TXA.h` / `channel.h` | Minimal standalone replacement for `comm.h` (which pulls in fftw and all WDSP modules). |
| `puresignal.h`, `puresignal.cpp` | New files (this project) | C++/JNI facade over the engine; distributed under the same GPL terms as the combined work. |

All original GPL copyright headers are preserved in the vendored files.
Every local modification is marked with a `// [RADE]` comment.

## Combined-work licensing

WDSP is GPL-licensed. By incorporating these files, the combined native
library (`librade_jni.so`) and the application distribution that includes it
are distributed under the terms of the **GNU General Public License**
(GPL-2.0-or-later). Complete corresponding source for the vendored code is
included in this repository at `app/src/main/cpp/wdsp_ps/`.
