# PlayTime indicator: active-only battery estimate in the status bar

Phase-6 work for issue **#137**. SystemUI half: the estimator and the status-bar element, shipped
as one `frameworks/base` patch. The Settings > Battery page that writes the properties is
documented separately (`docs/settings-battery.md`, Settings worker). Telemetry comes from the
framework's `BatteryManager`; no daemon, sysfs access or sepolicy change is involved (section 5).

The stock Android estimate averages hours of standby, so a handheld shows "18 h remaining" and then
collapses to two hours under a 3D emulator. This indicator averages **screen-on current only**, over
a rolling window of the current session.

| File | Purpose |
|---|---|
| `patches/frameworks/base/0005-playtime-statusbar.patch` | estimator, status bar element, property listener, host tests |
| `docs/playtime.md` | this document |

Patch baseline: `LineageOS/android_frameworks_base` @ `22de88b7b8eb` plus AshFall patches `0001`-`0004`,
applied in sorted order. The patch touches only `packages/SystemUI`.

## 1. Estimator maths

`com.android.systemui.ashfall.PlayTimeEstimator` is pure Java: no `android.*` imports, no sysfs, no
properties, no direct clock. The caller injects a `Clock` and one sample per telemetry tick, which
is what makes the maths testable on the build host without an emulator.

- **Window.** Ring buffer of the last samples of the current session, pruned to the configured
  window (1 / 3 / 5 / 10 min, default 5). 512 slots is 10 min at the fastest 2 s interval.
- **Sample.** One per tick. Dropped while the screen is off, when `current_ma <= 0` (unknown), and
  when the current is implausible (`> 10000 mA`); a charging-state change purges the window, because
  discharge samples say nothing about charge current and vice versa.
- **Ready.** A number is only shown after one full configured interval of the current session *and*
  at least two samples. Before that the indicator shows `--`.
- **Discharging.** `remaining_min = remaining_mAh * 60 / avg_current_mA`, rounded **up**, so a
  positive amount never renders as `0m`.
- **Charging.** `minutes_to_full = (full_mAh - remaining_mAh) * 60 / avg_current_mA`, same rounding,
  prefixed with the configured charging symbol.
- **Remaining charge.** The battery's charge counter when it has one, else `capacity * level / 100`.
  `full_mAh` is the user override (`capacity_mah > 0`) or the capacity derived from the counter and
  the level. Without either the estimate is unavailable - it is never invented.
- **Noise.** An average `<= 20 mA` is not a measurement: `--` while discharging, symbol only while
  charging.
- **FULL.** Level `>= 100 %`, or while charging a charge counter with no headroom
  (`remaining >= full`). Shown as `<symbol>FULL`, never as `0m`.
- **Cap.** Anything past 48 h renders `>48h` in every format (a clock that long is meaningless).

### Formats

| `fmt` | 165 min | 45 min |
|---|---|---|
| `COMPACT` (default) | `2h 45m` | `45m` |
| `SHORT` | `2h45` | `0h45` |
| `COLON` | `2:45` | `0:45` |
| `DETAILED` | `2 hr 45 min` | `0 hr 45 min` |

### Detail modes

Values are joined with ` · ` (U+00B7). `W` is `4.8W` (`+4.8W` while charging), `V` is `3.85V`, `%` is
`72%`.

| `detail` | Shows |
|---|---|
| `TIME` (default) | time only |
| `TIME_W` | time + W |
| `TIME_W_V` | time + W + V |
| `TIME_V` | time + V |
| `W` | W only |
| `V` | V only |
| `W_V` | W + V |

Charging symbols: `BOLT` (default, `↯`), `PLUS` (`+`), `TEXT` (`CHG `), `EMOJI` (`⚡`).

Tap-to-cycle walks configured view -> W -> V -> % -> configured view. It is off by default; when it
is off the view is not clickable, so a tap in that area still expands the shade. When it is on, a tap
is ignored while the shade is expanded and while the primary bouncer is showing.

Charging cycle (optional, off by default) rotates 2-3 of TIME/PERCENT/WATTS/VOLTS every
`chg_cycle_ms` while charging; it pauses with the screen off.

## 2. Where the element lives in this tree

Measured in the baseline tree, not assumed: the status bar **root is still the View layout**
`res/layout/status_bar.xml` inflated into `PhoneStatusBarView`, but the end side and the battery are
being migrated to Compose (`StatusBarRoot.kt`, `BatteryWithPercent`, flags
`new_status_bar_icons` / `status_bar_system_status_icons_in_compose`). `BatteryMeterView` is no
longer part of the collapsed status bar at all - it survives in the QS/shade battery chip.

So the indicator is a `TextView` (`PlayTimeIndicatorView extends TextView implements
DarkIconDispatcher.DarkReceiver`) added to the `PhoneStatusBarView` hierarchy by
`PlayTimeIndicator`, attached from `PhoneStatusBarViewController.onViewAttached()` and detached in
`onViewDetached()`. That works whether the icons around it are Views or Compose, because
`status_bar.xml` is the View container in both cases.

Colour comes from the same source as the clock and battery text: `DarkIconDispatcher.getTint(areas,
view, tint)` in `onDarkChanged`, so light, dark and tinted backgrounds all match.

**`REPLACE_PERCENT`** is the one placement that needs the Compose side: the stock percentage is
rendered by `BatteryWithPercent`, so `StatusBarRoot.addBatteryComposable` suppresses it
(`showPercent = setting && !PlayTimeSettings.replacesBatteryPercent()`), and the indicator takes the
slot right after the battery view. The suppression recomposes from the same property listener; it is
not a poll. With `new_status_bar_icons` disabled there is no stock percentage in the status bar, so
there is nothing to suppress and the text simply sits in the battery slot.

## 3. Placements

| `place` | Where |
|---|---|
| `BATTERY_LEFT` (default) | before the battery view inside `system_icons` |
| `BATTERY_RIGHT` | after the battery view |
| `CLOCK_LEFT` | immediately before the clock view in its container |
| `CLOCK_RIGHT` | immediately after the clock view |
| `REPLACE_PERCENT` | after the battery view, with the stock percentage hidden |

The clock slot follows whichever clock is visible: left (`@id/clock`), centre (`@id/clock_center`)
or right (`@id/clock_right`). The indicator is added at an explicit index, so the battery icon keeps
its own margins and the layout order of the status bar is unchanged apart from the one new child.
It is `wrap_content`, single line, at `size_sp`, with 4 dp of horizontal padding.

If `system_icons` is hidden (`status_bar_system_status_icons_in_compose`), the end-side container is
used instead so the indicator still appears; if no suitable parent exists at all, the indicator hides
itself rather than throwing.

## 4. Properties (read by SystemUI, written by Settings > Battery)

Read with `SystemProperties.addChangeCallback` - a listener, not a poll. The callback fires for any
property change in the system, so it is coalesced onto the main thread, the schema is re-read once
and listeners are only notified when the parsed values actually changed. Nothing is scheduled while
the indicator is off.

| Property | Values | Default |
|---|---|---|
| `persist.ashfall.batt.show` | `0`/`1` | `0` (off) |
| `persist.ashfall.batt.place` | `BATTERY_LEFT`/`BATTERY_RIGHT`/`CLOCK_LEFT`/`CLOCK_RIGHT`/`REPLACE_PERCENT` | `BATTERY_LEFT` |
| `persist.ashfall.batt.detail` | `TIME`/`TIME_W`/`TIME_W_V`/`TIME_V`/`W`/`V`/`W_V` | `TIME` |
| `persist.ashfall.batt.fmt` | `COMPACT`/`SHORT`/`COLON`/`DETAILED` | `COMPACT` |
| `persist.ashfall.batt.window` | `1`/`3`/`5`/`10` min | `5` |
| `persist.ashfall.batt.interval` | `2`..`30` s | `5` |
| `persist.ashfall.batt.chg_symbol` | `BOLT`/`PLUS`/`TEXT`/`EMOJI` | `BOLT` |
| `persist.ashfall.batt.chg_cycle` | `0`/`1` | `0` |
| `persist.ashfall.batt.chg_cycle_ms` | `500`..`5000` | `1000` |
| `persist.ashfall.batt.chg_meters` | 2-3 of `TIME,PERCENT,WATTS,VOLTS` | `TIME,PERCENT` |
| `persist.ashfall.batt.tap` | `0`/`1` | `0` (off) |
| `persist.ashfall.batt.size_sp` | `10`..`16` | `12` |
| `persist.ashfall.batt.capacity_mah` | `0` = auto, else mAh | `0` |

Invalid or missing values fall back to the default in the table (same rule as `docs/phase5.md` §2).
Values are matched case-insensitively and clamped, so `window=7` becomes 5 and
`chg_meters=TIME` (only one meter) becomes `TIME,PERCENT`. `tap` defaults to off: the indicator is
touch-neutral until the user asks for tap-to-cycle.

## 5. Telemetry: BatteryManager, not a daemon property

The source is the framework's battery service, through `android.os.BatteryManager`. There is no
`ashfall.status.power.*` read, no sysfs access, no second sampler and no new service - a `coredomain`
may not read `sysfs_batteryinfo` (platform `neverallow`) and on the RP5 those files are a vendor
label, so SystemUI asks the framework instead.

| Value | Source | Mapping |
|---|---|---|
| current | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)` | `abs(uA) / 1000` mA, sign rectified |
| voltage | `ACTION_BATTERY_CHANGED`, `EXTRA_VOLTAGE` | mV as is |
| level | `ACTION_BATTERY_CHANGED`, `EXTRA_LEVEL` / `EXTRA_SCALE` | `level * 100 / scale` |
| charging | `ACTION_BATTERY_CHANGED`, `EXTRA_STATUS` | `CHARGING` or `FULL` |
| remaining charge | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CHARGE_COUNTER)` | `uAh / 1000` mAh when `>= 0` |
| capacity | derived | `charge_mah * 100 / level` when `level > 0`, else unknown |

`BATTERY_PROPERTY_CHARGE_COUNTER`, `BATTERY_PROPERTY_CURRENT_NOW` and the extras are optional on
some devices: `getIntProperty` returning `Integer.MIN_VALUE` (unsupported) or a negative charge
counter becomes "unknown" (`-1`), exactly as before, so the indicator shows `--` rather than a number
derived from a missing value. Plausibility bounds still apply (`0 < current <= 10000 mA`,
`2500 <= voltage <= 5500 mV`, `0 <= level <= 100`).

Update strategy - no polling of the battery:

- A receiver for the sticky `ACTION_BATTERY_CHANGED` is registered only while the indicator is shown
  and the screen is on. The sticky intent gives the initial state immediately and every later
  broadcast refreshes level, voltage, charging state, charge counter and the derived capacity, and
  takes one sample with a fresh current. It is unregistered with `show=0`, on screen off and on
  detach.
- The sample tick at `persist.ashfall.batt.interval` (2-30 s, the user's sample interval) reads the
  cached broadcast state plus one `BATTERY_PROPERTY_CURRENT_NOW` value and feeds the estimator. No
  timer, Handler message or registered receiver is left behind while the indicator is off.

Configuration still comes from `persist.ashfall.batt.*` through the property listener described
above - that half is unchanged.

## 6. Unavailable states

Half-finished states are user-visible, so they are explicit:

| State | Shown |
|---|---|
| Before the first full interval, after a screen-on purge, after a charging-state change | `--` |
| Average current `<= 20 mA` while discharging | `--` |
| Charging, but no usable charge current or no capacity | charging symbol only (`↯`, `+`, `CHG`, `⚡`) |
| Charging with no headroom, or level 100 % | `<symbol>FULL` |
| Estimate past 48 h | `>48h` |
| `show=0`, no suitable parent view, battery properties unsupported (`-1` everywhere) | no indicator / `--`, never a fabricated number |

Screen off stops sampling; screen on purges the window, so a 10 min standby gap cannot leak standby
drain into the new session. That purge is the main accuracy win of the design.

## 7. Tests

`packages/SystemUI/tests/src/com/android/systemui/ashfall/PlayTimeEstimatorTest.java` - plain JUnit 4
in the SystemUI test target; the estimator has no Android dependency, so the same file also runs on
the host:

```
javac -d out -cp junit-4.13.2.jar:hamcrest-core-1.3.jar \
    packages/SystemUI/src/com/android/systemui/ashfall/PlayTimeEstimator.java \
    packages/SystemUI/tests/src/com/android/systemui/ashfall/PlayTimeEstimatorTest.java
java -cp out:junit-4.13.2.jar:hamcrest-core-1.3.jar \
    org.junit.runner.JUnitCore com.android.systemui.ashfall.PlayTimeEstimatorTest
```

Covered: no number before one full interval, single sample is never an estimate, window expiry,
average over the window, screen-off pause + screen-on purge, charging-state purge, noise current,
unknown level/capacity, invalid current not sampled, charging time-to-full, charging without usable
current, FULL from level and from the charge counter, capacity override, the four time formats, the
`>48h` cap, no `0m` rounding, all seven detail modes, `+` watt prefix while charging, unknown values
as `--`, tap cycle order and rendering, every parser fallback and clamp, cycle meter order, settings
comparison.

## 8. Integration checks

1. `scripts/patches.sh apply` (or `git am --3way` on a pristine base + `0001`-`0004`) applies `0005`
   cleanly; the verifier repeats this on a clean checkout.
2. Build the ROM (orchestrator) - SystemUI compiles, `SystemUITests` picks the test up from the
   default source glob, no `Android.bp` change needed.
3. On the device: `setprop persist.ashfall.batt.show 1` without restarting SystemUI, then compare
   the estimate against `dumpsys battery` (level, voltage, charge counter) and against a known load;
   unplug/replug to see the charging branch and the window purge.
4. Screen off for 10 min, screen on: `--` first, then a fresh number that does not inherit standby
   drain.
5. Charging: counts up with the configured symbol; at the top it says `FULL`; the charging cycle
   rotates the configured meters and stops with the screen off.
6. Every placement and detail mode; `REPLACE_PERCENT` with the stock percentage both on and off.
7. Landscape 1920x1080, 360 dpi, largest `size_sp`, longest string (`>48h · 12.3W · 4.20V`): the
   indicator must not clip, overlap the clock or push it off-screen. Check `dumpsys SurfaceFlinger
   --latency-clear` / a screenshot with the clock at both ends of the bar.
8. Light, dark and tinted (wallpaper) status bars: colour matches the clock and battery text.
9. Settings > Battery rows apply live and survive reboot; invalid values fall back to the defaults
   above.
10. Process cost: with `show=0`, `dumpsys gfxinfo` and `dumpsys activity processes` show no periodic
    work from this feature (no Handler messages, no listener left behind).

## 9. Known limits

- Lights-out (doze) dimming is applied by `PhoneStatusBarTransitions` to the containers it knows
  about; the indicator is not alpha-faded with the status icons. It is only visible while the status
  bar is, so this does not show in practice.
- Reading `persist.ashfall.batt.*` from `system_app` needs `get_prop(system_app, ashfall_prop)`,
  which the hwd worker adds to `aosp/vendor/ashfall/sepolicy/ashfall_props.te` (issue #137
  section 1). Without it every settings read is denied and the indicator stays off - the documented
  "not configured" state, not a crash. Telemetry itself needs no sepolicy change any more: it comes
  from `BatteryManager`.
- The Settings page is out of scope here; it writes the properties above and nothing else.
