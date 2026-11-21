# Idle drain and suspend (#233)

The RP5 drains the battery while the panel is off: the user reported ~35 % in
10 h, and the audit in #233 measured a flat ~150 mA with **95 % of the mAh
unattributed** by Android's power model.  That signature is not an app - it is
the SoC never reaching a low-power state.

This page records the measurement harness, the baseline, what was fixed on the
AshFall side, and what is left (which is mostly vendor/kernel, see #255).

## 1. Why the bench number was wrong

With the charger attached the USB PHY never transitions to L2
(`dwc3 ... could not transition HS PHY to L2`, #255 §3) and `a800000.ssusb`
stays an active wakeup source, so **the AP does not suspend at all** while
plugged in: `uptime` advances as fast as wall time.  Any drain measured on the
bench is therefore the plugged-in idle state, not sleep.  Every measurement
below was taken **unplugged, on battery, screen off**.

## 2. Harness

```bash
scripts/device/lock.sh run drain-233 --ttl 2400 -- \
    scripts/bench/idle-drain.sh --minutes 20 --interval 5 \
        --out /tmp/opencode/drain-baseline --label baseline
```

`idle-drain.sh` samples `charge_counter`, the battery level and the device
clock every `--interval` minutes, then pulls `dumpsys batterystats --history`
once and hands it to `drain-summary.py`, which reports:

| metric | source |
|---|---|
| realtime window | device `date` at the first and last sample |
| screen off | `+screen` / `-screen` history transitions |
| awake | `+running` / `-running` (CLOCK_MONOTONIC, so suspend is excluded) |
| suspend ratio | `(screen off - awake) / screen off` |
| awake episodes | `+running` transitions, with median and p90 length |
| drain | `charge_counter` in µAh, plus the 1 % level for %/h |
| wake reasons | every `wake_reason=0:"..."` recorded in the window |

`--compare <baseline> <experimental>` prints the two runs side by side with the
change and a better/worse verdict, and each run leaves a `summary.json`.

The harness refuses to run on the charger for the reason in §1.

## 3. Baseline (main `7d69b77`, unplugged, WiFi on, no SIM)

20 minutes, screen off the whole time, fan on the Quiet curve, *with
`com.android.rkpdapp` disabled* (see §5 - it storms once per boot and would
otherwise pollute the sample):

```
realtime        20m 04s
screen off      20m 04s (100.0%)
awake           14m 10s (70.6% of screen off), 683 episodes, median 1 s, p90 2 s
suspended        5m 54s (29.4% of screen off)
charge          3952.5 -> 3901.2 mAh  (-51.3 mAh, -153.5 mA average)
battery level   82% -> 80%  (-5.98 %/h)
wake reasons:
   688x  Abort: Pending Wakeup Sources: [timerfd]
    19x  Abort: Some devices failed to suspend, or early wake event detected
    19x  Abort: Pending Wakeup Sources: qcom_rx_wakelock
     3x  Abort: Callback failed on alarmtimer.0.auto ... returned -16
     2x  Abort: Last active Wakeup Source: mhi0
     1x  Abort: Callback failed on 0000:01:00.0 in pci_pm_suspend ... -11
     1x  432 msoc-delta:448 dma-grant
```

## 3a. Same run after the fixes (§4)

20 minutes would have been 5 samples 5 minutes apart; the on-device loop below
took 27 m 40 s because `sleep` is CLOCK_MONOTONIC and stops while the system is
suspended - which is itself a measurement of the suspend ratio.  Values are
normalised, so the comparison is fair:

| metric | baseline | after |
|---|---|---|
| awake / screen off | 70.6 % | 71.0 % |
| awake episodes | 34.0 / min | 34.1 / min |
| suspend aborts | 34.3 / min | 34.4 / min |
| average current | 153.5 mA | 151.4 mA |

**The panel-off work did not move the drain** - as predicted in §4, the daemons
were never the cause.  It removes ~360 `ashfall.status.*` writes per minute and
~10 k property polls per hour, which is right, but it is worth ~1 % of the
current.  The `%/h` column of the harness is useless on a 20-minute window (1 %
of a 4000 mAh pack is 40 mAh); the fuel-gauge current is the number to read.

## 3b. What actually wakes the AP

The `debug.ashfall.wakeups` snapshots (§6a) taken at both ends of the run, with
their `wakeup_sources` counters differenced:

```
  1072 wakes (38.7/min)  [timerfd]
    26 wakes ( 0.9/min)  a800000.ssusb
    21 wakes ( 0.8/min)  qcom_rx_wakelock
     6 wakes ( 0.2/min)  alarmtimer.0.auto
     2 wakes ( 0.1/min)  mhi0
```

So it is a **userspace `timerfd`, every ~1.55 s**, and the abort message is the
same source: it fires, the AP wakes, and the still-active source then blocks the
next suspend attempt.  Read that as a **1.75 s cycle**: 688 aborts in 20 minutes, and 683 awake
episodes whose median length is 1 s.  The wake itself has a legitimate cause,
but the AP then stays up for ~1 s while the `timerfd` wakeup source is still
pending - the multiplier the audit in #233 described.  At 154 mA and ~6 %/h the
same night would be ~50 %, and a device that stayed suspended would be well
under 1 %/h.

## 4. What was fixed on the AshFall side

None of these can prevent `s2idle` entry (they use non-wakeup timers), but each
one is work that has no reason to happen with the panel off:

| where | before | after |
|---|---|---|
| `ashfall-hwd` status | ~9 `ashfall.status.*` writes every 2 s, each bumping the global property serial and waking every property waiter | no status writes while the panel is off; one fresh publish on the transition |
| `ashfall-hwd` loop | `prop_wait_any()` clamped to 500 ms | 2 s while the panel is off (still ≤ the fan/thermal tick) |
| `ashfall-rp5-input` | property refresh every 250 ms (14 400/h) | 2 s after 2 s without input (1 800/h), 250 ms again on the next event |
| `LedSyncService` (Match screen) | 400 ms supervisor tick continued while paused for screen off | tick stops with the panel; the `DisplayManager` listener re-arms it on wake |
| PlayTime (SystemUI) | 2 s settings re-read continued while the panel was off | stops on `ACTION_SCREEN_OFF`, rescheduled on `ACTION_SCREEN_ON` |

The status publish is the one that matters most in wall-clock terms: those
writes are why the daemon was waking *other* processes, not just itself.

## 5. Measured non-causes

* **`com.android.rkpdapp` (Widevine provisioning).**  The live audit found nine
  concurrent `#WidevineProvisioner#` jobs, each holding a `SystemJobService`
  wakelock, all killed by the 10-minute JobScheduler timeout and immediately
  re-enqueued, while `JobScheduler.JobStatus` logged `Exempted app
  com.android.rkpdapp considered buggy`.  The device has no vendor RKP HAL
  (`/vendor/bin/hw` has no RKP service), so the job can never succeed.  It is a
  **boot-time storm**, bounded by WorkManager's retry count: disabling the
  package removes it, and the baseline above was taken with it disabled - the
  1.75 s cycle and the 154 mA did **not** change, so it is not the steady-state
  cause.  Not shipped as a change for that reason; a `pm disable-user --user 0
  com.android.rkpdapp` is the field workaround if the per-boot burst is
  annoying.
* **The AshFall daemons.**  `ashfall-hwd` used 70 s of CPU in 18 h, the input
  daemon 11 s.  Their `poll()` / `__system_property_wait()` timeouts are
  CLOCK_MONOTONIC timers, which do not keep the AP awake or block suspend.
* **Fan and LEDs.**  Duty 0 and mode `off` while the panel is off, verified on
  the device.
* **The WLAN radio.**  `/sys/kernel/wifi/power_stats` shows the chip in deep
  sleep 63 099 times; it is the AP that stays up.

## 6a. On-demand suspend snapshot

`ashfall-hwd` runs as root, so it can read the two things a non-root shell
cannot.  A new value in a shell-settable property writes a one-shot snapshot to
its own log:

```bash
adb shell setprop debug.ashfall.wakeups 1
adb shell logcat -d -s ashfall-hwd | grep 'wakeups|'
```

It logs every line of `/sys/kernel/debug/wakeup_sources` (event counts, expire
counts, total block time) and every process holding a `timerfd`, with the
`clockid` and `it_value` from `/proc/<pid>/fdinfo/<fd>` - `clockid 9` is
`CLOCK_BOOTTIME_ALARM`, the only kind that can wake the AP.  Logcat instead of
a file: no sepolicy exception for writing into `/data/local/tmp`, and shell can
already read the daemon's log buffer.

## 6. Open (vendor/kernel, tracked in #255)

* **Naming the timerfd owner.**  The device has exactly **9 `CLOCK_*_ALARM`
  timerfds** and exactly **9 `[timerfd]` wakeup sources**, in creation order:
  the Qualcomm health HAL, audioserver, two in `system_server`
  (`CLOCK_REALTIME_ALARM`, disarmed, and AlarmManager's next alarm, minutes
  away) and five in `com.android.bluetooth` (12 min, 1 h, three disarmed).
  None of them is *armed* with a 1.5 s period, so the owner re-arms a short
  timeout in bursts and the snapshot happens to catch it idle.
  * **Bluetooth is ruled out**: with `svc bluetooth disable` the source still
    woke the AP 92 times in 120 s (0.77/s, unchanged).
  * The WiFi branch could not be tested from the host - `svc wifi disable`
    kills the wireless-adb link being used to drive the test.
  * The remaining step is one line of information: the `ticks` field of
    `/proc/<pid>/fdinfo/<fd>`, i.e. the expiration counter per timerfd.  The
    counter that grows by ~38/min names the owner outright.
* **Old hypothesis, now discarded.**  `ashfall-hwd` can now answer the
  "who holds the timerfd" half on demand (§6a), and the first snapshots rule
  out the obvious answer: the only `CLOCK_BOOTTIME_ALARM` timerfds on the
  device are the health HAL's ~50 s poll, `system_server`/AlarmManager's next
  alarm (minutes away), two Bluetooth alarms (12 min / 1 h) and three disarmed
  ones.  **There is no 1.75 s alarm**, so `[timerfd]` is the *consequence*: the
  AP is woken by something else, and the still-pending AlarmManager timerfd
  then blocks the next suspend attempt and stretches one wake into ~1 s of
  awake time.  The next step is a `wakeup_sources` diff over an unplugged idle
  window (event_count per source), which is exactly the `event_count` column
  in the same snapshot.
* `cnss_pci 0000:01:00.0 ... pci_pm_suspend ... -11` and the D3cold resume
  failure (#255 §1).
* `qcom_rx_wakelock` (19 in 20 min) and `mhi0` (2): the WLAN RX path and the
  modem PCIe endpoint waking the AP.
* `a800000.ssusb` cannot suspend while charging (#255 §3), which is also why
  this class of bug is invisible on the bench.

## 7. adb root on userdebug

`PRODUCT_NOT_DEBUGGABLE_IN_USERDEBUG` is empty in
`vendor/ashfall/products/ashfall_gsi_arm64.mk` (after the LineageOS inherit
that sets it).  It must be *empty*, not `false`: the value is exported with
`add_json_bool()`, which reads any non-empty string as true.  The built image
reports `ro.debuggable=1`.

Android 13 has a second gate that `ro.debuggable` alone does not open: adbd
runs `adbroot_service`, which only restarts as root when the user has turned on
Developer options -> **Root access** (state in `/data/adbroot/enabled`; only
uid `system` may write it, so `adb shell` cannot).  So `adb root` still needs
that one toggle; without it, §6a is the way to look at suspend on the device.

## 8. Acceptance mapping

| criterion (#233) | state |
|---|---|
| idle drain in %/h for at least two configurations | one configuration measured (WiFi on, no SIM); `idle-drain.sh` is the harness for the rest |
| compared with the previous build | `--compare` in the harness; table in §4/§9 |
| no AshFall daemon wakes the device more than required with the panel off | done in §4 |
| a regression test or a documented harness | `scripts/bench/idle-drain.sh` + this page |
| name and fix the actual drain | open, §6 |

## 9. Repro

```bash
# baseline / experimental, 20 min each, device unplugged and screen off
scripts/device/lock.sh run drain --ttl 2400 -- \
    scripts/bench/idle-drain.sh --minutes 20 --out /tmp/opencode/drain-a --label a
scripts/device/lock.sh run drain --ttl 2400 -- \
    scripts/bench/idle-drain.sh --minutes 20 --out /tmp/opencode/drain-b --label b
scripts/bench/drain-summary.py --compare /tmp/opencode/drain-a /tmp/opencode/drain-b
```
