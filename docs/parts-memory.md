# AshFall Memory page (`#141`)

Status: implemented 2026-11-15 for issue **#141**; behaviour and risks stay in
that issue, the shared contract (property tables, one writer per path) is
`docs/phase6.md` §9.3, and the page list is `docs/parts.md` §2.

The page is `Settings > AshFall settings > Memory` (`MemoryActivity`,
`MemoryFragment`).  It has two controls and a live status block:

| Row | Control | Property |
|---|---|---|
| zram (status) | read-out: compressed size and ratio | `ashfall.status.mem.*` |
| Swap (status) | read-out: used / total swap | `ashfall.status.mem.*` |
| Free storage | read-out: free / total `/data` | `StatFs("/data")` |
| Enable zram | main switch row, off by default | `persist.ashfall.mem.zram_mb` |
| zram size | slider `0 .. MemTotal/2`, step 256 MB, value in MB | `persist.ashfall.mem.zram_mb` |
| Swapfile size | slider `0 .. 16384 MB`, step 256 MB, value in MB | `persist.ashfall.mem.swap_mb` |

Everything applies immediately; there is no Apply button and no page-level
switch.  Both controls are **off by default**: a swapfile and zram are policy
choices, so the ROM never switches them on behind the user's back.

## 1. zram

* The slider maximum is read from **`/proc/meminfo` when the page opens**:
  `zramMaxMb() = (MemTotal/2)` rounded **down** to a 256 MB step.  Nothing is
  hardcoded - the RP5 ships with 8 GB and 12 GB, so the cap is roughly 3.6 GB on
  one variant and 5.4 GB on the other, and the emulator's 2.9 GB `MemTotal`
  gives 1280 MB.  A value above the cap (for example `setprop
  persist.ashfall.mem.zram_mb 4096`) is shown clamped at the cap; the daemon
  clamps it the same way and publishes the effective value.
* **Switch on with the size at 0** writes a quarter of RAM, rounded to a step
  (768 MB on the emulator's 3 GB) - a slider argument on a 0 MB row would
  otherwise leave zram enabled at zero bytes.  Switch off writes `0` and puts
  the slider back at 0.
* The value label is the size in MB (`1024 MB`, `Off` at 0); the row summary
  adds the percentage of RAM and the current cap (`34 % of RAM · maximum
  1280 MB`).
* The app writes the property and nothing else.  `ashfall-hwd` owns
  `/sys/block/zram0`, the compressor choice, the `swapoff`/`reset`/`disksize`/
  `swapon` order and the boot re-apply, and it publishes what it actually
  applied.  A request that the daemon has not (yet) applied is shown as
  `Requested N MB — ashfall-hwd reports M MB` instead of being silently
  corrected in the UI.
* zram is **priority 1**; the swapfile is priority 2.  Both controls coexist -
  they are independent pages worth of policy, not alternatives.

## 2. Swapfile

* The slider is `0 .. 16384 MB`, step 256 MB, and deliberately **not derived
  from RAM**: 16 GB is allowed on the 8 GB unit.  Verified on the 3 GB
  emulator, where the slider reaches 16384 MB.
* The row always shows free space on `/data` (`4.5 GB free on /data`), and adds
  `Larger than the free space on /data — ashfall-hwd will refuse it.` when the
  request plus the daemon's 2 GB reserve does not fit.  The page **warns**, it
  does not clamp the write: the daemon is the single enforcement point
  (issue #141, "free-space guard"), and a clamp in two places is how the two
  sides drift apart.
* The app never creates, `swapon`s or deletes the file; that is `ashfall-hwd`
  (`/data/ashfall/`, labelled swap file) and the API writes only the property.

## 3. The warning is part of the feature

Two permanent places, no way to opt out:

1. **Row summary**, every time the page is open:

   > A swapfile lives on the UFS flash: writes are orders of magnitude slower
   > than RAM, the device will feel sluggish once it actually swaps, and heavy
   > swapping wears the flash out. Swap is a last-resort overflow for memory
   > pressure. It is much slower than RAM.

2. **Confirmation before the first enable** (`onSwapSize` -> `confirmSwap`):
   `Create a swapfile?` with the same facts in plain words, the size, and
   `Cancel` / `Create`.  It appears on **every** off -> on transition, which is
   stricter than the issue's "before the first enable" minimum, and it has no
   "do not show again" - the dialog cannot be dismissed into a state where the
   warning is gone.  Cancelling writes nothing and returns the slider to 0.

Wording never presents swap as a performance option.  It is described as the
last-resort overflow for memory pressure, and the dialog's last sentence says
so explicitly ("not a performance option").

## 4. Live status and unavailable states

The page reads the six status properties on a **1 s** tick while it is
resumed.  It is a reader only - `ashfall-hwd` is the single sampler and the
single writer of `ashfall.status.*` (docs/phase6.md §9.3), and the page opens no
sysfs node.

| Property | Used for |
|---|---|
| `ashfall.status.mem.zram_mb` | zram applied size; `0` off, `-1` unavailable |
| `ashfall.status.mem.swap_mb` | swapfile applied size; `0` off, `-1` unavailable |
| `ashfall.status.mem.zram_used_kb` | `mm_stat` compressed data size |
| `ashfall.status.mem.zram_saved_kb` | RAM saved by the compressor |
| `ashfall.status.mem.swap_total_kb` | `SwapTotal` from `/proc/meminfo` |
| `ashfall.status.mem.swap_used_kb` | `SwapTotal - SwapFree` |

The zram row shows the **compressed** size, not `disksize`, because the
compressed size is the real cost: `778 MB compressed · 2.5:1`, with the ratio
computed as `(used + saved) / used` (i.e. `saved` is the uncompressed-minus-
compressed saving, the same quantity `mm_stat` reports as
`orig_data_size - compr_data_size`).

| State | What the page shows |
|---|---|
| `zram_mb = -1` | zram switch and slider disabled, `zram not available on this kernel` |
| `swap_mb = -1` | swap slider disabled, `The swapfile is not available on this build` |
| `/proc/meminfo` unreadable | zram row disabled, `Cannot read /proc/meminfo, so the zram limit cannot follow half of RAM` |
| no daemon yet (both `-1`) | status values `—`, both controls disabled, Handheld row `Not reporting` |
| a status value is `-1` | that value renders as `—`; never a fabricated `0` |

`-1` never reads as a number: the whole row says why instead of failing at
write time.

## 5. Verification record (2026-11-15, host only, no RP5)

Preview build (`cd preview && ./prepare.sh && ANDROID_HOME=/root/android-sdk
gradle --offline :app:assembleDebug`): **BUILD SUCCESSFUL**.

Emulator `Retroid_Pocket_5` (API 33, 1920x1080, `wm density 360`), single-tenant
lease `emulator-memory` via `scripts/device/lock.sh`, daemon status mocked with
`tools/mock/mem-status.sh` (never on the RP5):

| Check | Result |
|---|---|
| `MemTotal` read at page open | `3042032 kB` -> zram cap **1280 MB** (half, floored to 256) |
| A (D-pad centre) on `Enable zram` | `persist.ashfall.mem.zram_mb=768` (quarter of RAM, snapped) |
| D-pad right on `zram size` | `768 -> 1024 MB` (one 256 MB step), summary `34 % of RAM · maximum 1280 MB` |
| request above the cap (`setprop 4096`) | page shows `1280 MB`, the cap, not the request |
| swap slider | reaches `16384 MB` on the 3 GB emulator, `Larger than the free space on /data` shown |
| swap off -> on | warning dialog appears, property still empty; `Cancel` leaves `persist.ashfall.mem.swap_mb` unset and the slider at `Off`; `Create` writes `256` |
| daemon `-1` (`mem-status.sh start unavailable`) | status `—` for zram and Swap, both rows disabled with the messages above |
| live status | `778 MB compressed · 2.5:1`, `675 MB used of 2.0 GB`, `4.5 GB free of 5.8 GB` |
| Handheld row (`MemorySummary`) | `zram 1.0 GB · swap 2.0 GB`; the `Not reporting` branch (both values `-1`) is not reachable on screen while the shell's 3 s tick does not rebind that row, see §6 |
| day / night | white page, black text -> `(0,0,0)` page background with white text (88.6 % of the sampled screen is near-black) |
| D-pad traversal | status rows and the footer are skipped (not selectable); `Enable zram` -> `zram size` -> `Swapfile size` and back |

Pure black is the LineageOS black-theme RRO, which only exists on the device
build; on the stock emulator the night surface measures `(0,0,0)` already, and
the page adds no colour of its own (theme attributes only), so it follows the
RRO like the rest of the app.

## 6. Deviations and notes

* The slider row lives in `MemoryFragment.StepSlider` rather than a new
  top-level file: `ui/SliderPreference` steps by one unit, which is meaningless
  for a size in MB, and the page may only write its own `Memory*` files.  It
  reuses the same row anatomy (`title/value/summary/SeekBar`, row-level D-pad)
  through `res/layout/memory_slider.xml`.
* The confirmation appears on every off -> on transition, not only the first
  one (stricter than issue #141, and it keeps the app free of a "warning seen"
  flag that could be cleared).
* **The Handheld page crashes independently of this page**: `HandheldActivity`
  calls `Preference.setSummary()` on rows that already have a
  `SummaryProvider`, which AndroidX refuses (`IllegalStateException: Preference
  already has a SummaryProvider set`, `HandheldActivity.java:174`, the
  Controller row first).  That file is the orchestrator's, so this page does not
  change it; `MemorySummary` was verified through its provider with the three
  `setSummary` calls removed locally (patch reverted, not committed).  The fix
  belongs in `HandheldActivity`: drop the tick's `setSummary` calls and notify
  the rows, or keep the calls and drop `setSummaryProvider`.
* `docs/parts.md` already links this file from the page table; the rest of
  `docs/parts.md` stays where it is.

## 7. Mocking the status values

`tools/mock/mem-status.sh` (test-only, mirrors `tools/mock/fan-status.sh`):

```
tools/mock/mem-status.sh start [normal|unavailable]
tools/mock/mem-status.sh status
tools/mock/mem-status.sh stop
```

It publishes the six `ashfall.status.mem.*` values once a second, reports the
two `persist.ashfall.mem.*` values the page wrote as applied, and with
`unavailable` publishes `-1` for everything so both disabled states can be
inspected.  `ashfall.*` has no property context on a stock image, so the
emulator needs `adb root` and `adb shell setenforce 0` while the page is
exercised (`setenforce 1` afterwards).
