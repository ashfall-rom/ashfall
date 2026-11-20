# Performance tuning on the stock 5.15 GKI kernel (issue #130)

The RP5 runs the stock `5.15.148` Android GKI kernel and stock vendor modules.
There is no kernel fork in this ROM: the GPU overclock is a `vendor_boot` DTB
change ([`docs/rp5/gpu.md`](rp5/gpu.md)) and every other lever is a runtime
setting.  This page records what was measured on the device, what was kept and
what was rejected, so a rejected idea is not re-proposed without new evidence.

## 1. Method

* **Device:** RP5 `2f184581`, 8 GB, build `20261120-200437` (`cad575fa8be0`) for
  the control/tuned pair, build `20261120-213629` (`b0fc815703b0`) for the
  root-only candidates (a temporary daemon probe, §5), and build
  `20261120-223431` (`3b53d38`) for the kept idle-timer change.
* **Thermal state:** each run starts below 36 °C (`gpuss-0`).  The fan is
  forced to 100 % except where a test says otherwise.  The screen stays on.
* **Workload:** [`scripts/bench/ashfall-bench.c`](../scripts/bench/ashfall-bench.c),
  a time-bounded synthetic suite, pinned where it matters:
  * CPU: xorshift integer and double-FMA loops, one thread on the prime core
    (CPU 7) and eight threads pinned one per core;
  * RAM: 128 MB read / write / copy and a 64 MB pointer chase;
  * GPU: an EGL pbuffer (1024², ES 3.0), a 1024-iteration `normalize` fragment
    loop, an 8-layer additive blend and 1M-point vertex batches;
  * app cold start: `am start -W -S com.android.settings/.Settings`, median of
    seven starts after three warm-ups.
* **Repeats:** control and tuned are the median of three full runs; each
  candidate is the median of two runs on the same build and thermal state.  The
  run-to-run spread is ±3 % on CPU/GPU throughput and ±5 % on app start, so
  anything inside that band is reported as no change.
* The benchmark and the harness are reproducible:

```bash
scripts/bench/build-bench.sh
adb push out/bench/ashfall-bench /data/local/tmp/

# per run: cool to < 36 C, then
adb shell '/data/local/tmp/ashfall-bench all --seconds 2 --mem-mb 128'
```

The two configurations compared head-to-head:

| | CPU | GPU cap | fan |
|---|---|---|---|
| **Control** (ROM default) | `schedutil`, stock caps | 670 MHz | Quiet preset |
| **Tuned** | `performance`, stock caps | 1000 MHz | 100 % |

`performance` holds every policy at its maximum step; the caps stay at the
stock 1804 / 2419 / 2841 MHz.

## 2. Control vs tuned

Median of three runs each.  "x" is tuned / control (RAM latency: control /
tuned, lower is better).

| metric | control | tuned | x |
|---|---|---|---|
| CPU int, 1 thread (Mops/s) | 1415.0 | 1416.0 | 1.00 |
| CPU int, 8 threads (Mops/s) | 6688.3 | 6681.0 | 1.00 |
| CPU double FMA, 1 thread (Mops/s) | 2816.8 | 2829.2 | 1.00 |
| CPU double FMA, 8 threads (Mops/s) | 16476.3 | 16450.7 | 1.00 |
| RAM read (GB/s) | 18.3 | 18.3 | 1.00 |
| RAM write (GB/s) | 31.8 | 31.9 | 1.00 |
| RAM copy (GB/s) | 12.3 | 12.3 | 1.00 |
| RAM latency (ns) | 151.5 | 151.5 | 1.00 |
| GPU ALU fill (Mpix/s) | 31.3 | 43.7 | **1.40** |
| GPU blend (Mpix/s) | 15674.7 | 21777.2 | **1.39** |
| GPU vertex (Mvert/s) | 166.9 | 232.4 | **1.39** |
| App start p50 (ms) | 226 | 222 | 1.02 |

* The **only** measurable gain is the GPU clock cap: +40 % on all three GPU
  tests, i.e. the 670 → 1000 MHz step (1.49x clock, 1.40x delivered).
* CPU and RAM are unchanged because **`schedutil` already reaches the maximum
  step on every cluster under sustained load** (the clock sampler observed
  1804 / 2419 / 2841 MHz during the control runs).  `performance` buys nothing
  on throughput and costs idle power, so it stays a user choice, not a default.
* App start is inside the noise band.

## 3. GPU clock sweep

Same GPU-only test, `Performance` preset, cap changed through the Parts editor.

| cap (MHz) | ALU (Mpix/s) | blend (Mpix/s) | vertex (Mvert/s) |
|---|---|---|---|
| 670 (stock) | 31.3 | 15 675 | 166.9 |
| 765 | 35.7 | 17 882 | 190.4 |
| 850 | 39.7 | 19 830 | 211.5 |
| 940 | 43.7 | 21 743 | 232.6 |
| 1000 | 43.7 | 21 777 | 232.4 |

* 670 → 940 scales linearly with the clock (+40 %).
* **940 → 1000 delivers nothing** (+6.4 % clock, 0 % throughput) even though
  the clock sampler shows the GPU at 1000 MHz during the run (26 of 40 samples
  at 1 GHz).  The chip is at its ALU/power ceiling, not at a governor limit.
* Recommendation: 940 MHz is the efficient performance top; 1000 MHz remains an
  opt-in experimental step (it is stable on this unit, see §4).

## 4. Sustained load and the fan

Six minutes of mixed load (GPU pass, CPU pass, repeat) at 1000 MHz.

| fan | GPU ALU first / second half (Mpix/s) | CPU 8T (Mops/s) | gpuss-0 / gpuss-1 max | cpuss-0 max | fan |
|---|---|---|---|---|---|
| Manual 100 % | 43.5 / 43.5 | 6805 / 6799 | 45.5 / 49.4 °C | 58.8 °C | 12 300 rpm |
| Quiet curve | 43.5 / 43.4 | 6804 / 6804 | 47.5 / 51.0 °C | 61.1 °C | 22 % / 3 600 rpm |

* No throttle (`thermal_pwrlevel=0`), no drop in either half, no GPU fault.
* The default Quiet curve is **sufficient** for sustained 1000 MHz: 2 °C warmer
  than a forced 100 % fan and 3x quieter.  The 100 % fan is a noise trade-off,
  not a performance requirement.
* Battery current at 100 % fan was higher (-941 mA peak) than on the Quiet
  curve (-584 mA peak) because the fan itself draws power.

## 5. Root-only candidates (temporary daemon probe)

The scheduler PELT multiplier, MGLRU and the KGSL idle timer live in root-only
nodes (`ro.debuggable=0`, no `su`).  They were A/B'd through a temporary
`ashfall-hwd` probe that applied `debug.ashfall.perf.*` properties
(build `20261120-213629`); the probe is **not** part of the shipped daemon -
only the idle-timer rule in §5.3 is.

All candidates were measured at the tuned configuration; the same-build
baseline is `probe-base` (two runs).

| candidate | CPU 8T (Mops/s) | app start p50 (ms) | GPU ALU (Mpix/s) |
|---|---|---|---|
| baseline (`pelt=1`, MGLRU off, idle 80 ms) | 6896 | 231.5 | 43.8 |
| PELT 2 (16 ms) | 6882 | 231.0 | 43.7 |
| PELT 4 (8 ms) | 6846 | 230.0 | 43.7 |
| MGLRU on (`lru_gen/enabled=0x0003`) | 6871 | 239.5 | 43.7 |
| KGSL idle timer 10 s | 6879 | 228.5 | 43.7 |

### 5.1 PELT multiplier — rejected

No effect outside the ±3 % band.  With `performance` the governor does not need
PELT to ramp, and the workload is steady-state.  PELT 4 stays a possible
latency tuning for a `schedutil` UI, but nothing here justifies changing the
default.

### 5.2 MGLRU — rejected (no evidence, no workload)

`lru_gen/enabled` accepts 7 and reports `0x0003` (MGLRU on).  Throughput and
app start do not move; the suite has no memory-pressure phase and the device
has 8 GB with a 4 GB zram.  Enabling MGLRU by default would be a change without
a measured win, so it stays off.  Revisit with a named Winlator/emulator
workload that actually swaps.

### 5.3 KGSL idle timer — kept for the Performance preset

`idle_timer` is how long the GPU may stay at its clock after the last
submission (stock 80 ms).  A cold-burst test (20 ALU frames after 3 s of idle,
repeated; A-B-A) isolates the ramp cost:

| idle timer | cold-burst ALU (Mpix/s) |
|---|---|
| 10 000 ms | 43.8, 43.9, 43.8, 43.9 (A) / 43.8, 43.9, 43.9 (A2) |
| 80 ms (stock) | 39.1, 39.1, 39.2 (B) |

* **+12 %** average throughput for short bursts separated by a few seconds,
  because the GPU no longer drops to 305 MHz and re-ramps inside the burst.
* Energy over 60 s of the same bursty pattern (screen on): **-450.8 mA** with
  10 s vs **-466.5 mA** with 80 ms - no penalty (the bursts finish sooner).
* Sustained games render continuously, so this changes nothing there; it is a
  UI/menu/short-action win.

Kept: `ashfall-hwd` writes **10 000 ms** while the CPU governor is
`performance` (the Performance preset) and **80 ms** otherwise, and drops back
to 80 ms while the thermal override is active.  See `docs/rp5/gpu.md` §4.3.

## 6. Rejected / not changed

| candidate | why |
|---|---|
| `performance` governor as the default | no throughput gain over `schedutil`; CPU already reaches max steps under load, and it costs idle power |
| GPU cap 1000 MHz as the recommended top | 0 % over 940 MHz on this unit (§3); keep 940 recommended, 1000 experimental |
| PELT 2 / 4 | no measurable change (§5.1) |
| MGLRU | no measurable change; no memory-pressure workload to prove a win (§5.2) |
| cpuset isolation (`top-app=4-7`) | not measurable with a throughput suite; needs a frame-time workload.  No change without evidence |
| phantom process killer | only for Winlator/Box64 chroots, none on this device |
| Turnip-Z, UFS `none`/`iostats=0`, `add_random=0` | per-game / I/O-stall tuning; no game workload and no measured stalls |
| kernel defconfig, `-O3`, `CONFIG_HZ_1000`, `sched_energy_aware=0`, DDR/LLCC locking | already rejected with source evidence in issue #130; unchanged |
| thermal-engine stop / throttle override as a boost | the override clamps the GPU to 670 MHz by design; stopping thermal-engine is unsafe |

## 7. Reproduce

```bash
# 1. build and push the bench
scripts/bench/build-bench.sh
adb push out/bench/ashfall-bench /data/local/tmp/

# 2. control (Balanced preset, Quiet fan)
adb shell 'settings put global window_animation_scale 0'
adb shell '/data/local/tmp/ashfall-bench all --seconds 2 --mem-mb 128'

# 3. tuned (Performance preset, fan Manual 100 %)
#    Power page -> Performance, Fan presets -> Manual -> 100 % -> Apply
adb shell 'for p in 0 4 7; do cat /sys/devices/system/cpu/cpufreq/policy$p/scaling_governor; done'
adb shell 'cat /sys/class/kgsl/kgsl-3d0/gpuclk'
adb shell '/data/local/tmp/ashfall-bench all --seconds 2 --mem-mb 128'

# 4. clock sweep: Power presets -> Performance -> GPU max frequency

# 5. idle-timer burst test (cold, no warm-up)
adb shell 'for i in 1 2 3; do sleep 3; /data/local/tmp/ashfall-bench gpu --cold --frames 20; done'
```

The harness writes one JSON file per run; `scripts/bench/summarize.py <dir>`
prints the medians and the control ratio used in the tables above.
