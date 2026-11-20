# AshFall Parts: Power page, preset editor, live graph and Power tile

Phase-6 feature **#124** (contract `docs/phase6.md` §9), revised by the
hardware-test findings in **#156** (§2, §3, §4, §5, §6, §12, §15).  This page
documents the Power page, the preset editor, the live graph, the stress test,
the properties the app writes and reads, and the states it shows when
`ashfall-hwd` is missing.  The other pages are described in `docs/parts.md`; the
daemon side (cpufreq, SELinux) lives in `docs/rp5/hw.md`.  The battery power of
the graph is sampled by the app itself with the framework `BatteryManager`, not
by the daemon.

The page is one `PreferenceFragmentCompat` (`PowerFragment`) in the existing
page shell (`PowerActivity` extends `PartsActivity`), so it has the same
collapsing toolbar, insets, day/night and pure-black behaviour as every other
AshFall page.  The preset editor is a second shell activity
(`PowerPresetActivity`) that hosts a preset list and one editor screen per
preset.  There is no Apply button: every control writes its property and
`ashfall-hwd` applies it within one tick.

## 1. Page

| Row | Kind | Effect |
|---|---|---|
| Status | graph row (not focusable) | live 60 s plot, legend, unavailable state |
| Preset | single-choice rows | applies the preset's caps, governor and GPU cap |
| Edit presets | row | opens the preset editor (`PowerPresetActivity`) |
| CPU load / GPU load | sliders 0..100 % | starts/levels the `hw/Stress` load (#156 §4) |
| Stress test | status row | countdown; the row stops the load while it runs |
| Override throttling | switch + danger dialog | `ashfall.perf.throttle_override` (not persistent) |
| Advanced (hidden) | switch + danger dialog | `persist.ashfall.perf.throttle_override_permanent` |
| Reset to stock | row | stock caps, `schedutil`, 670 MHz GPU, override off, Balanced |
| Version | info row | seven taps reveal Advanced (shared UI pref with the Fan page) |

* The CPU caps, the governor and the GPU cap are **not** on this page any more
  (#156 §12): they belong to a preset and are edited in the preset editor.  The
  page keeps the live view, the preset list, the stress test, the throttling
  override and "Reset to stock".
* The page ticks every **500 ms** while it is visible and sets
  `ashfall.ui.power_live=1` in `onResume`/`0` in `onPause`, exactly like the Fan
  page's `ashfall.ui.fan_live` (#156 §2).  `ashfall-hwd` publishes the perf
  status at 500 ms only while the property is set and returns to its own 2 s tick
  when the page closes.  No new timer, no busy loop: the tick is one
  `Handler.postDelayed` chain with one graph sample per tick.
* The graph row is neither focusable nor selectable and blocks focus in its
  children, so the D-pad skips it in both directions.
* The Page follows the Settings look: framework `Switch`/`SeekBar` rows,
  theme text appearances, focus/ripple from `?android:attr/selectableItemBackground`,
  and the `LinearLayoutManager` extra-layout-space workaround so the D-pad can
  reach every row on a page longer than one screen.
* The Fan page has no Performance row any more (#156 §9): the Power page is a
  first-class page on the Handheld screen, so the link was redundant and was
  removed together with its strings.

## 2. Live graph

The graph reuses `ui/FanGraphView`, generalised into a small chart (one, two or
three y axes, any number of series, `NaN` = gap) instead of a second chart class.
The Fan page keeps its default chart, its 120 × 500 ms window and its API
(`History`, `setHistory`), so `ui/StatusPreference` is untouched.

| | Power page |
|---|---|
| Window | 120 samples, one per 500 ms page tick = 60 s (`FanGraphView.POWER_SAMPLES`) |
| Left axis | battery power 0..12 W, grid and labels at 0/6/12 W |
| Power source | `BatteryManager` (framework): sticky `ACTION_BATTERY_CHANGED` for `EXTRA_VOLTAGE` + `BATTERY_PROPERTY_CURRENT_NOW`; `power_mw = abs(current_ua) * voltage_mv / 1e6` |
| Right axis | **real CPU and GPU utilisation** 0..100 %, labels at 0/50/100 % |
| Third axis | **CPU/GPU clocks** 0..3000 MHz, shared axis, labels at 0/1000/2000/3000 MHz (`%d MHz`); no extra grid lines (the left-axis grid stays the only grid) |
| CPU load | `ashfall.status.perf.cpu_load_pct`: `/proc/stat` idle delta over the total delta, sampled by `ashfall-hwd` |
| GPU load | `ashfall.status.perf.gpu_load_pct`: kgsl `gpu_busy_percentage` |
| Clocks | plotted on the third (MHz) axis as four series (silver/gold/prime/GPU); the legend still carries `● silver · gold · prime · GPU MHz` with the exact numbers, each name in its own series colour |
| Series | power (orange), CPU load (blue), GPU load (green), silver/gold/prime/GPU clocks (silver/gold/prime/gpu clock colours) - seven series over the same 60 s window |

* **Usage is real load, never a clock percentage** (#156 §3).  The old chart
  plotted `scaling_cur_freq / max` and `gpu_mhz / 1000`, so an idle CPU sat at
  ~100 % and the GPU at its cap.  Both series now come from the daemon's own
  utilisation counters and are plotted as published.  The clocks are **added** as
  their own series on a shared 0..3000 MHz axis (#250.4); this is not a revert
  of #156 §3 and the utilisation lines are unchanged.
* The clocks are plotted over the same 60 s window from
  `ashfall.status.perf.cpu_mhz_silver/_gold/_prime` and
  `ashfall.status.perf.gpu_mhz`; `-1`/missing is a gap, never a drawn zero, and
  the four clock series share one honest MHz axis (no per-series maxes).
* The clocks are still shown, exactly, in the legend:
  `● silver 1516  ● gold 1958  ● prime 2073  ● GPU 670 MHz`.  Each segment is
  drawn as a coloured bullet plus coloured text in **its own line's colour**
  (`fan_graph_clock_silver/_gold/_prime/_gpu`, day and night), so the legend maps
  every clock name to its plotted series.  A clock the daemon has not published
  is `—` for that cluster only; the plotted series and the legend must not
  disagree.
* One sample per page tick, and the tick is the only appender (the #132 fix):
  a page open or a preset tap can no longer insert an extra sample.
* `-1`/missing is a **gap**, never a drawn zero.  Power is `-1` while charging
  (a battery-side number is meaningless then) and while the current or voltage
  cannot be read; only the charging case shows the note
  "charging — power not measured" under the plot, an unreadable current stays a
  silent gap.  A missing load or clock is a gap in its line.
* Axis labels use the theme's secondary text colour and the grid is that colour
  at 12 % alpha, so day, night and the pure-black RRO all stay readable.

## 3. Presets and the preset editor

Three built-ins, exactly as #124 (no Max preset, no TDP), with the #156 §6
governor change:

| Preset | silver | gold | prime | governor | GPU |
|---|---|---|---|---|---|
| Low power | 1171 | 1478 | 1632 | `conservative` | 305 |
| Balanced | stock (1804) | stock (2419) | stock (2841) | `schedutil` | 670 |
| Performance | stock (1804) | stock (2419) | stock (2841) | `performance` | 850 |

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
   "gold_mhz":2419,"prime_mhz":2841,"governor":"performance","gpu_mhz":850,
   "builtin":true},
  {"id":"user.1a2b3c4d","name":"Custom preset","silver_mhz":1804,...,
   "governor":"schedutil","gpu_mhz":940,"builtin":false}
]}
```

* The editor (`PowerPresetActivity`) lists **every** preset, the built-ins
  included, marks the active one and opens each on its own screen (#156 §5):
  * three cap sliders (50 MHz per D-pad press, full range on a touch drag),
    the governor selector and the GPU selector;
  * **Apply this preset** writes the caps, the governor, the GPU cap and the
    preset id;
  * **Duplicate** creates an editable copy (`user.` + 8 hex characters);
  * **Rename** and **Delete** for user presets;
  * **Reset to factory values** for a built-in.
* Editing a built-in is allowed and persists; only "Reset presets"/"Reset to
  factory values" restores the table.  A built-in keeps its id and name and
  cannot be renamed or deleted.
* An edit is saved immediately.  When the edited preset is the **active** one it
  is also written to the properties, so the daemon follows at once; editing an
  inactive preset only changes the stored preset and takes effect when it is
  applied.
* **GPU 940 and 1000 MHz are ordinary preset values** since #156 §5: the old
  "manual-only" clamp to 850 MHz is gone, so a preset can carry any step from
  305 to 1000 MHz.  1000 MHz still goes through the thermal/stability
  confirmation, and above stock the row warns that high clocks rely on the
  fan.
* The **governor selector offers `schedutil` / `conservative` / `powersave` /
  `performance`** (#156 §6).  `performance` holds every policy at its maximum
  step; a per-cluster cap still applies, because the daemon never raises
  `scaling_max_freq` above the cap - so "performance with a 1804 MHz silver
  cap" is a real, bounded setting.  `walt` stays hidden (no use case that does
  not break the preset model); an unknown governor read back from the property
  shows as `schedutil`, which is the property's default.
* With the **powersave** governor selected the three cap sliders are disabled
  and say that they are ignored: `powersave` pins every policy to its lowest step
  (300/710/844 MHz) and ignores `scaling_max_freq`.  The governor row repeats
  the note.
* The active id lives in `persist.ashfall.power.preset`; the page, the editor,
  the tile, its dialog and the Handheld summary all resolve it through
  `Power.activePreset()`, which falls back to the built-in Balanced preset when
  the recorded id is empty or stale.  Deleting the active user preset and
  "Reset presets" remap the property, so it can never dangle.
* "Reset to stock" writes `0` to the three caps - the contract's own "stock
  maximum" (`docs/phase6.md` §9.3) - plus `schedutil`, 670 MHz and the override
  released, restores the built-in Balanced preset to its table values and
  selects it, so the preset row cannot claim values the properties do not hold.
* At most 32 presets; the id is limited to the property interface's 32
  characters.

## 4. Properties

| Property | Type | Direction | Meaning |
|---|---|---|---|
| `persist.ashfall.perf.cpu_max_silver` | `ashfall_perf_prop` | app → daemon | MHz cap, `0` = stock maximum |
| `persist.ashfall.perf.cpu_max_gold` | `ashfall_perf_prop` | app → daemon | MHz cap, `0` = stock maximum |
| `persist.ashfall.perf.cpu_max_prime` | `ashfall_perf_prop` | app → daemon | MHz cap, `0` = stock maximum |
| `persist.ashfall.perf.cpu_gov` | `ashfall_perf_prop` | app → daemon | one governor for all three policies, default `schedutil`; `schedutil`/`conservative`/`powersave`/`performance` |
| `persist.ashfall.perf.gpu_max_mhz` | `ashfall_perf_prop` | app → daemon | GPU cap 305..1000 MHz, default 670 |
| `persist.ashfall.perf.throttle_override` | `ashfall_perf_prop` | app → daemon | `1` = software clock throttling off until reboot |
| `persist.ashfall.perf.throttle_override_permanent` | `ashfall_prop` | app → daemon | re-apply the override at every boot |
| `persist.ashfall.power.preset` | `ashfall_prop` | app only | active preset id |
| `ashfall.ui.power_live` | `ashfall_ui_prop` | app → daemon | `1` while a power UI is visible: publish the perf status every 500 ms instead of every 2 s |
| `ashfall.status.perf.power_mw` | `ashfall_status_prop` | not read by the app | kept in the daemon contract; the page samples `BatteryManager` instead (`power_mw = abs(current_ua) * voltage_mv / 1e6`) |
| `ashfall.status.perf.cpu_load_pct` | `ashfall_status_prop` | daemon → app | real CPU utilisation 0..100, `-1` unavailable |
| `ashfall.status.perf.gpu_load_pct` | `ashfall_status_prop` | daemon → app | real GPU utilisation 0..100, `-1` unavailable |
| `ashfall.status.perf.cpu_mhz_silver/gold/prime` | `ashfall_status_prop` | daemon → app | effective `scaling_cur_freq`, `-1` unavailable |
| `ashfall.status.perf.gpu_mhz` | `ashfall_status_prop` | daemon → app | effective kgsl clock, `-1` unavailable |
| `ashfall.status.perf.gpu_max_mhz` / `.gpu_oc` | `ashfall_status_prop` | daemon → app | applied GPU cap / 0 stock, 1 OC, -1 no sysfs |
| `ashfall.status.perf.throttle` | `ashfall_status_prop` | daemon → app | 1 active, 0 restored, -1 unsupported |

The app never writes an `ashfall.status.*` property and never touches sysfs.
An invalid or missing value is treated as `-1` (unknown), never fabricated: a
cap that reads `0`, negative or above stock is clamped on read, an unknown
governor reads as `schedutil`, a load outside 0..100 reads as `-1` (a gap), and
a GPU request outside 305..1000 MHz is stored as the stock 670 MHz (the same
sanitising `hw/Perf` already does).

## 5. Quick Settings tile and dialog

`qs/PowerTileService` + `qs/PowerDialog` follow the Fan tile/dialog pair:

* label = active preset name; subtitle = `CPU 1804 · GPU 670 MHz`, or
  "No data" when the daemon is not publishing.  The tile is always **active**:
  three built-in presets always exist, so a power preset is always in effect.
* tap = a single-choice preset list (applies immediately) plus "Edit presets";
  the header shows the live power/CPU/GPU summary and refreshes every 500 ms
  while the dialog is open (`ashfall.ui.power_live`, same counter as the page).
* "Edit presets" opens `PowerPresetActivity`, the same editor the page opens
  (#156 §5); long-press is the existing `qs/TilePreferencesActivity` route,
  which opens the Power page for the Power tile.
* the tile refreshes on `onStartListening`, on `onTileAdded` and after every
  preset change from the dialog.
* the dialog uses the platform QS dialog theme (`Theme.AshFall.QsDialog` →
  SettingsLib's expressive dialog theme on Android 16+), so it follows day,
  night and the pure-black overlay.

### 5.1 The Done button overflow fix (#156 §15)

The Done button and the last footer row could be laid out past the rounded card
at 1920x1080 landscape.  `ui.Ui.fitDialog()` sized the dialog before the window
was attached, from the display height, and assumed the window would get that
height.  A Quick Settings dialog is hosted **inside the QS panel**, and the
window manager clamps it to the panel (`990x780` px on the 1080p emulator
instead of the requested `826`), while the content stayed at the requested
height: the Done button fell below the card and was clipped.  The helper is now
generic and used by the Fan, Lighting, Power and colour-picker dialogs (the
Fusion dialog has its own sizing pass):

1. the first pass measures the content at the requested width and sizes the list
   to the space the header and footer leave;
2. a one-shot post-attach pass compares the height the window **really** got with
   the content and gives the list `actual - chrome`, where the chrome is
   measured without a cap, then re-applies the window layout.  The list scrolls
   only when it genuinely overflows.

The first pass also fixes a second, pre-existing bug: when the content was
taller than the cap, the measured "natural" height equalled the cap, so the old
`target != natural` test skipped the list sizing entirely.

Evidence (1080p, 360 dpi): the window frame is `[465,150][1455,930]` and the
Power dialog's Done button lands at `[519,795][1401,903]` - fully inside the
card with the 12 dp bottom padding - for the day and night themes.  The last
footer row ("Edit presets") is at `678..786`, and only the preset list itself
scrolls when there are more presets than fit.  (The `x2` of Done and of every
end-aligned widget is `1401` rather than the card's `1455` since #166: the card
and its content now share one width, `@dimen/dialog_width`, so the 24 dp end
inset is inside the card instead of being clipped by it.)

## 6. Stress test (#156 §4)

The page reuses `hw/Stress` and the Fan page's controls and rules; there is no
second stress implementation.

* CPU load and GPU load sliders, 0..100 %; raising one starts the load at that
  level, lowering it re-levels it, and 0/0 idles the workers.
* The status row shows the running state and the countdown
  (`Running — stops automatically in 9:50`) and stops the load when activated.
* The session stops itself after `Stress.MAX_RUN_MS` (10 minutes) and the page
  also stops it in `onPause`/`onDestroyView`, so leaving the page never leaves
  load behind.  A fresh visit always starts at 0 %.
* The graph shows the load while it runs: the daemon's real `cpu_load_pct` /
  `gpu_load_pct` rise, and the emulator's in-process mock floors its synthetic
  load at the active stress level so the effect is visible without a device.
* The GPU slider reports "GPU load is unavailable" when the
  offscreen GL context cannot be created; the CPU load keeps running.

## 7. Unavailable states

| Situation | What the page shows |
|---|---|
| No `ashfall-hwd` (no perf status at all) | the graph row reads "Live power data is unavailable."; the orange power line still draws from `BatteryManager` |
| Neither telemetry nor a battery reading | the graph row reads "Live power data is unavailable."; the legend and the plot are hidden |
| Charging | `— W` in the legend, a gap in the orange line, note "charging — power not measured" |
| Battery current/voltage unreadable | `— W` and a gap, no charging note (an unknown sample, not a measured zero) |
| Load not published | `CPU —%` / `GPU —%` and a gap in that line |
| One cluster clock unknown | `—` in the clock legend for that cluster |
| GPU clock unknown | `—` for the GPU clock legend |
| GPU cap not published | the editor's GPU row shows the preset value without an "actual" claim |
| GPU clamped by the override | the editor's GPU row adds `Requested N MHz — using 670 MHz while throttling is overridden` for the active preset |
| Throttle state unsupported (`status = -1`) | the switch is off and its summary says it is not supported |
| Handheld summary, no daemon | `Balanced · live stats unavailable` |

Nothing is faked: a control still writes its property when the daemon is
absent (so the value is applied at the next daemon start), but the read-outs
stay in the unavailable state until the daemon publishes.  The power series is
independent of the daemon, so it is live even then.

## 8. Emulator mock

The stock emulator has neither `ashfall-hwd` nor the `ashfall.*` property
contexts, so the page has an in-process fake daemon behind
`debug.ashfall.parts.power_mock` (development images only, ignored on a user
build), toggled with `tools/mock/power-perf.sh on|off|status`.  It applies the
caps/governor/GPU request immediately, clamps the GPU like the daemon, clamps
it to stock while the override is active, and publishes a synthetic 60 s status
cycle at 500 ms: 1.2–8 W with a charging window in which power is `-1`, clocks
under the active caps, and a CPU/GPU load cycle floored at the stress test's own
levels.  The flag replaces the `BatteryManager` sample as well, so the page and
the graph can be exercised without touching the emulated battery.

## 9. Verification record

Preview build (`aosp/vendor/ashfall/apps/AshFallParts/preview`):

```
./prepare.sh && ANDROID_HOME=/root/android-sdk gradle --offline :app:assembleDebug
-> BUILD SUCCESSFUL
```

Emulator: `Retroid_Pocket_5` AVD (1920x1080, density 360), preview APK
installed, mock on (`tools/mock/power-perf.sh on`), 2026-11-15.

* **500 ms page.**  Three screenshots 0.6 s apart all changed in the legend and
  plot area (5 762 / 5 534 changed pixels between frames), so the page refreshes
  faster than 1 s; with the mock on, `ashfall.ui.power_live=1` is written at
  every resume tick (the emulator denies the write, as it denies every
  `ashfall.*` property; the RP5 applies it).
* **Real load.**  The legend read `6.6 W · CPU 29 % · GPU 27 %` with
  `silver 1561 · gold 2050 · prime 2634 · GPU 439 MHz`: the percentages are the
  daemon's load properties, not the clock ratios (a 2634 MHz prime clock would
  have plotted 92 % on the old chart).  The three series are drawn in the day
  palette (`#C2410C` 2 653 px, `#1D4ED8` 3 334 px, `#15803D` 2 702 px in the
  plot) and in the night palette (`#FFB74D` 284, `#64B5F6`, `#81C784`) on a
  pure-black background.
* **No clock rows.**  The D-pad walk on the page is Low power → Balanced →
  Performance → Edit presets → CPU load → GPU load → Override throttling →
  Reset to stock → version (one row per press, the graph row skipped); there is
  no Silver/Gold/Prime/GPU row on the page.  Eight `UP` presses walked back in
  the same order.
* **Stress test.**  D-pad `RIGHT` three times on CPU load changed the row from
  `0 %` to `3 %`, the status row became
  `Running — stops automatically in 9:50`, and leaving the page (Home) and
  returning showed both sliders back at `0 %` and the row idle again.
* **Preset editor.**  `Edit presets` opens `PowerPresetActivity` with every
  preset listed (`Low power`, `Balanced` marked `Active`, `Performance` showing
  `1804 / 2419 / 2841 MHz · performance · GPU 850 MHz`).  Opening Performance
  showed the three cap sliders, the governor row
  (`performance · all cores` plus "the caps still apply"), and the GPU row.  The governor dialog listed
  `schedutil / conservative / powersave / performance` with `performance`
  checked.  The GPU dialog listed all six steps; selecting 940 MHz persisted it
  in the preset (`"gpu_mhz":940` in `power_presets.xml`) and "Reset to factory
  values" restored 850.  D-pad `LEFT` on Silver cores wrote 1754 MHz into the
  built-in Performance preset and the list showed it after Back, i.e. built-in
  edits persist.  "New preset" created `user.ea00cf94`, Rename persisted
  `Quietishi`, Delete removed it from the store and popped back to the list.
* **QS dialog.**  `cmd statusbar expand-settings` + `click-tile` opened the
  dialog with the live header `5.1 W · CPU 2224 · GPU 603 MHz`, the four
  presets, `Edit presets` and `DONE`; `DONE` is `[519,795][1401,903]` inside the
  `[465,150][1455,930]` window (the `1401` is the #166 fix: the content is
  inset 24 dp from the card edge), in both themes.  The D-pad walked Low power →
  Balanced → Performance → the user preset → Edit presets → DONE.  The Fan
  dialog, which shares `Ui.fitDialog`, still fits (`DONE` at
  `[519,795][1401,903]` in the same `990x780` window).
* **Pure black.**  The LineageOS pure-black RRO only exists on the device image,
  but this AVD's night theme is already `#000000`; the page and the editor were
  captured on it and every colour comes from the theme or the graph's own
  day/night palette.
* **Not verified here.**  The daemon side: `ashfall.ui.power_live` following the
  property, `cpu_load_pct`/`gpu_load_pct` and the `performance` governor
  validation belong to the `ashfall-hwd` worker (same issue, different PR); the
  emulator denies every `ashfall.*` property write, so only the app side is
  proven here.
