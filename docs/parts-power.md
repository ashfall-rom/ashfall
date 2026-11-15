# AshFall Parts: Power page, live graph and Power tile

Phase-6 feature **#124** (contract `docs/phase6.md` §9), including the fan-graph
fix **#132**.  This page documents the Power page, its graph, its presets, the
properties it writes and reads, and the states it shows when `ashfall-hwd` is
missing.  The other pages are described in `docs/parts.md`; the daemon side
(cpufreq, SELinux, `power_mw`) lives in `docs/rp5/hw.md`.

The page is one `PreferenceFragmentCompat` (`PowerFragment`) in the existing
page shell (`PowerActivity` extends `PartsActivity`), so it has the same
collapsing toolbar, insets, day/night and pure-black behaviour as every other
AshFall page.  There is no Apply button: every control writes its property and
`ashfall-hwd` applies it within one tick.

## 1. Page

| Row | Kind | Effect |
|---|---|---|
| Status | graph row (not focusable) | live 60 s plot, legend, unavailable state |
| Preset | single-choice rows | applies the preset's caps, governor and GPU cap |
| Edit presets | row + dialog | Duplicate, Rename (user presets), Delete (user presets), Reset presets |
| Silver cores | slider 300..1804 MHz | `persist.ashfall.perf.cpu_max_silver` |
| Gold cores | slider 710..2419 MHz | `persist.ashfall.perf.cpu_max_gold` |
| Prime core | slider 844..2841 MHz | `persist.ashfall.perf.cpu_max_prime` |
| CPU governor | single-choice dialog | `persist.ashfall.perf.cpu_gov` (`schedutil`/`conservative`/`powersave`) |
| GPU max frequency | single-choice dialog | `persist.ashfall.perf.gpu_max_mhz` |
| Override throttling | switch + danger dialog | `ashfall.perf.throttle_override` (not persistent) |
| Advanced (hidden) | switch | `persist.ashfall.perf.throttle_override_permanent` |
| Reset to stock | row | stock caps, `schedutil`, 670 MHz GPU, override off |
| Version | info row | seven taps reveal Advanced (shared UI pref with the Fan page) |

* The cap sliders move by 50 MHz per D-pad press (a 1 MHz press over a
  1500-step slider is unusable) and by the pixel under the finger on a drag;
  the daemon clamps every request to the highest available step `<=` the
  request and publishes the effective value, so the row can read
  `Cap 1171 MHz · now 1209 MHz`.
* Every slider/selector change edits the **active preset in place** (built-ins
  included), so the preset row always shows the values the page applies.
  "Reset presets" restores the #124 table and deletes the user presets.
* With the **powersave** governor selected the three cap sliders are disabled
  and say that they are inert: `powersave` pins every policy to its lowest step
  (300/710/844 MHz) and ignores `scaling_max_freq`.  The governor row repeats
  the note.
* `walt` and `performance` are not offered (no use case that does not break the
  preset model); an unknown governor read back from the property shows as
  `schedutil`, which is the property's default.
* 940 and 1000 MHz are **manual-only** GPU steps: they are written to the
  property but never stored in a preset (a preset's GPU field is clamped to
  305..850 MHz), and the GPU row says so while one of them is selected.
  Selecting 1000 MHz always goes through the thermal/stability confirmation.
* "Reset to stock" writes `0` to the three caps - the contract's own "stock
  maximum" (`docs/phase6.md` §9.3) - plus `schedutil`, 670 MHz and the override
  released, and selects the built-in Balanced preset.
* The Page follows the Settings look: framework `Switch`/`SeekBar` rows,
  theme text appearances, focus/ripple from `?android:attr/selectableItemBackground`,
  and the `LinearLayoutManager` extra-layout-space workaround so the D-pad can
  reach every row on a page longer than one screen.

The Fan page lost its Performance category (#124): the throttling override and
the GPU cap moved here.  In their place the Fan page keeps one row,
**Performance → Power**, which opens this page.

## 2. Graph

The graph reuses `ui/FanGraphView`, generalised into a small chart (one or two
y axes, any number of series, `NaN` = gap) instead of a second chart class.
The Fan page keeps its default chart, its 120 × 500 ms window and its API
(`History`, `setHistory`), so `ui/StatusPreference` is untouched.

| | Power page |
|---|---|
| Window | 30 samples, one per 2 s daemon tick = 60 s |
| Left axis | battery power 0..12 W, grid and labels at 0/6/12 W |
| Right axis | CPU and GPU as percent of the maximum available clock, labels at 0/50/100 % |
| CPU reference | 2841 MHz (prime stock clock, `docs/phase6.md` §9.3) |
| GPU reference | 1000 MHz (top of the phase-6 table; the app cannot read `gpu_available_frequencies` because it is sysfs) |
| Series | power (orange), CPU (blue), GPU (green) - the three day/night series colours of the Fan graph |
| GPU legend | `silver 1516 · gold 1958 · prime 2073 MHz` under the value legend |

* One sample per page tick, and the tick is the only appender.  This is the
  #132 fix applied to both pages: `FanFragment` no longer calls
  `updateStatus()` from `onCreatePreferences`, `onResume`, `onSwitchChanged` or
  `activate()`, so a fresh page contributes one sample per 500 ms tick and a
  preset tap no longer inserts an extra sample into the current window.
* `-1`/missing is a **gap**, never a drawn zero.  Power is `-1` while charging
  (a battery-side number is meaningless then); the legend shows `— W` and the
  note "charging — power not measured" appears under the plot.
* The row is neither focusable nor selectable and blocks focus in its children,
  so the D-pad skips it in both directions.  Axis labels use the theme's
  secondary text colour and the grid is that colour at 12 % alpha, so day,
  night and the pure-black RRO all stay readable.

## 3. Presets

Three built-ins, exactly as #124 (no Max preset, no TDP):

| Preset | silver | gold | prime | governor | GPU |
|---|---|---|---|---|---|
| Low power | 1171 | 1478 | 1632 | `conservative` | 305 |
| Balanced | stock (1804) | stock (2419) | stock (2841) | `schedutil` | 670 |
| Performance | stock (1804) | stock (2419) | stock (2841) | `schedutil` | 850 |

* Stored as JSON in device-protected storage
  (`/data/user_de/0/org.ashfall.parts/shared_prefs/power_presets.xml`, file
  `power_presets`), so the page, the tile and the Handheld summary work before
  the first unlock:

```json
{"version":1,"presets":[
  {"id":"builtin.low_power","name":"Low power","silver_mhz":1171,"gold_mhz":1478,
   "prime_mhz":1632,"governor":"conservative","gpu_mhz":305,"builtin":true},
  {"id":"builtin.balanced","name":"Balanced","silver_mhz":1804,"gold_mhz":2419,
   "prime_mhz":2841,"governor":"schedutil","gpu_mhz":670,"builtin":true},
  {"id":"builtin.performance","name":"Performance","silver_mhz":1804,
   "gold_mhz":2419,"prime_mhz":2841,"governor":"schedutil","gpu_mhz":850,
   "builtin":true},
  {"id":"user.1a2b3c4d","name":"Performance copy","silver_mhz":1804,...,
   "governor":"schedutil","gpu_mhz":850,"builtin":false}
]}
```

* Built-ins are re-seeded on load but keep the values the user gave them; only
  "Reset presets" restores the table.  Their name and id are fixed.  Built-ins
  are editable and duplicable, not renamable or deletable.
* A user preset is created by **Duplicate** (the only way, like the fan
  presets): it copies the active preset's values, gets `user.` + 8 hex
  characters and can be renamed and deleted.
* The active id lives in `persist.ashfall.power.preset`; the page, the tile,
  its dialog and the Handheld summary all resolve it through
  `Power.activePreset()`, which falls back to the built-in Balanced preset when
  the recorded id is empty or stale.  Deleting the active user preset and
  "Reset presets" remap the property, so it can never dangle.
* Applying a preset writes the three caps, the governor, the GPU cap and then
  the id.  The daemon only ever sees the applied values.
* At most 32 presets; the id is limited to the property interface's 32
  characters.

## 4. Properties

| Property | Type | Direction | Meaning |
|---|---|---|---|
| `persist.ashfall.perf.cpu_max_silver` | `ashfall_perf_prop` | app → daemon | MHz cap, `0` = stock maximum |
| `persist.ashfall.perf.cpu_max_gold` | `ashfall_perf_prop` | app → daemon | MHz cap, `0` = stock maximum |
| `persist.ashfall.perf.cpu_max_prime` | `ashfall_perf_prop` | app → daemon | MHz cap, `0` = stock maximum |
| `persist.ashfall.perf.cpu_gov` | `ashfall_perf_prop` | app → daemon | one governor for all three policies, default `schedutil` |
| `persist.ashfall.perf.gpu_max_mhz` | `ashfall_perf_prop` | app → daemon | GPU cap 305..1000 MHz, default 670 |
| `persist.ashfall.perf.throttle_override` | `ashfall_perf_prop` | app → daemon | `1` = software clock throttling off until reboot |
| `persist.ashfall.perf.throttle_override_permanent` | `ashfall_prop` | app → daemon | re-apply the override at every boot |
| `persist.ashfall.power.preset` | `ashfall_prop` | app only | active preset id |
| `ashfall.status.perf.power_mw` | `ashfall_status_prop` | daemon → app | battery power in mW, `-1` while charging/unknown |
| `ashfall.status.perf.cpu_mhz_silver/gold/prime` | `ashfall_status_prop` | daemon → app | effective `scaling_cur_freq`, `-1` unavailable |
| `ashfall.status.perf.gpu_mhz` | `ashfall_status_prop` | daemon → app | effective kgsl clock, `-1` unavailable |
| `ashfall.status.perf.gpu_max_mhz` / `.gpu_oc` | `ashfall_status_prop` | daemon → app | applied GPU cap / 0 stock, 1 OC, -1 no sysfs |
| `ashfall.status.perf.throttle` | `ashfall_status_prop` | daemon → app | 1 active, 0 restored, -1 unsupported |

The app never writes an `ashfall.status.*` property and never touches sysfs.
An invalid or missing value is treated as `-1` (unknown), never fabricated: a
cap that reads `0`, negative or above stock is clamped on read, an unknown
governor reads as `schedutil`, and a GPU request outside 305..1000 MHz is
stored as the stock 670 MHz (the same sanitising `hw/Perf` already does).

## 5. Quick Settings tile and dialog

`qs/PowerTileService` + `qs/PowerDialog` follow the Fan tile/dialog pair:

* label = active preset name; subtitle = `CPU 1804 · GPU 670 MHz`, or
  "No data" when the daemon is not publishing.  The tile is always **active**:
  three built-in presets always exist, so a power preset is always in effect.
* tap = a single-choice preset list (applies immediately) plus "Edit presets";
  the header shows the live power/CPU/GPU summary and refreshes every 2 s.
* long-press is the existing `qs/TilePreferencesActivity` route, which opens
  the Power page for the Power tile.
* the tile refreshes on `onStartListening`, on `onTileAdded` and after every
  preset change from the dialog.  "Edit presets" opens the Power page, whose
  own "Edit presets" row is the editor; there is no separate Power preset
  activity (the manifest belongs to the page shell, not to this worker).
* the dialog uses the platform QS dialog theme (`Theme.AshFall.QsDialog` →
  SettingsLib's expressive dialog theme on Android 16+), so it follows day,
  night and the pure-black overlay, and `ui.Ui.fitDialog()` keeps its footer on
  screen.

## 6. Unavailable states

| Situation | What the page shows |
|---|---|
| No `ashfall-hwd` (all status values `-1`) | the graph row reads "The power daemon is not responding."; the legend and the plot are hidden |
| Power unknown / charging (`power_mw = -1`) | `— W` in the legend, a gap in the orange line, note "charging — power not measured" |
| One cluster clock unknown | `—` in the clock legend for that cluster; the CPU percent series uses the fastest cluster that is known |
| GPU clock unknown | `—` for the GPU legend and a gap in the green line |
| GPU cap not published | `Requested N MHz — the GPU cap is not available (ashfall-hwd is not reporting).` |
| GPU sysfs unavailable (`gpu_oc = -1`) | `Requested N MHz — the GPU clock interface is unavailable on this kernel.` |
| Throttle state unsupported (`status = -1`) | the switch is off and its summary says the kernel has no override |
| Handheld summary, no daemon | `Balanced · live stats unavailable` |

Nothing is faked: a control still writes its property when the daemon is
absent (so the value is applied at the next daemon start), but the read-outs
stay in the unavailable state until the daemon publishes.

## 7. Emulator mock

The stock emulator has neither `ashfall-hwd` nor the `ashfall.*` property
contexts, so the page has an in-process fake daemon behind
`debug.ashfall.parts.power_mock` (development images only, ignored on a user
build), toggled with `tools/mock/power-perf.sh on|off|status`.  It applies the
caps/governor/GPU request immediately, clamps the GPU like the daemon, clamps
it to stock while the override is active, and publishes a synthetic 60 s status
cycle (1.2–8 W with a charging window in which power is `-1`, clocks under the
active caps).

## 8. Verification record

Preview build:

```
cd aosp/vendor/ashfall/apps/AshFallParts/preview && ./prepare.sh \
  && ANDROID_HOME=/root/android-sdk gradle --offline :app:assembleDebug
```

Emulator (Retroid_Pocket_5 AVD, 1920×1080, day/night, mock daemon) - filled in
by the run recorded with the PR:

* page, preset row, cap sliders, governor, GPU selector, throttle override,
  Reset to stock reachable with the D-pad;
* preset apply writes the three caps, the governor, the GPU cap and the id;
* powersave disables the cap sliders with the inert note;
* graph: axes, legend, charging gap, unavailable state;
* tile: label, subtitle, preset dialog, "Edit presets";
* #132: one sample per tick (no extra sample on page open or preset tap).
