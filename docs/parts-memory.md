# AshFall Memory page (`#141`, swapfile only after `#156` §7)

Status: implemented 2026-11-15 for issue **#141**, reduced to the swapfile on
2026-11-15 by **#156** §7.  The shared contract (property tables, one writer per
path) is `docs/phase6.md` §9.3, and the page list is `docs/parts.md` §2.

The page is `Settings > AshFall settings > Memory` (`MemoryActivity`,
`MemoryFragment`).  It has one control and a live status block:

| Row | Control | Property |
|---|---|---|
| Swap (status) | read-out: used / total swap | `ashfall.status.mem.swap_*` |
| Free storage | read-out: free / total `/data` | `StatFs("/data")` |
| Swapfile size | slider `0 .. 16384 MB`, step 256 MB, value in MB | `persist.ashfall.mem.swap_mb` |

Everything applies immediately; there is no Apply button and no page-level
switch.  The swapfile is **off by default**: it is a policy choice, so the ROM
never switches it on behind the user's back.

## 1. Why zram is gone

The first cut of the page (`#141`) also offered a zram switch and a
`0 .. MemTotal/2` slider writing `persist.ashfall.mem.zram_mb`, with
`ashfall-hwd` owning `/sys/block/zram0`.  That cannot work on this device:
**Android init already owns `/dev/block/zram0`** and brings up 4 GB of
compressed swap at boot.  A second owner that runs `swapoff`, `reset`,
`disksize` and `swapon` on the same device races init and swaps under the
platform's feet.

`#156` §7 therefore removes the feature end to end - the row, the switch, the
slider, the property, the status values and the daemon code.  Compressed swap
stays exactly where the platform put it, and the page deals only with the
swapfile, which AshFall owns on `/data`.  The page is now a **single swapfile
page**, and nothing in the app or in `ashfall-hwd` reads or writes `zram0`.

## 2. Swapfile

* The slider is `0 .. 16384 MB`, step 256 MB, and deliberately **not derived
  from RAM**: 16 GB is allowed on the 8 GB unit.  Verified on the 3 GB
  emulator, where the slider reaches 16384 MB.
* The row always shows free space (`4.5 GB free`), and adds
  `Leaves too little free storage — it will be refused.` when the
  request plus the daemon's 2 GB reserve does not fit.  The page **warns**, it
  does not clamp the write: the daemon is the single enforcement point
  (issue #141, "free-space guard"), and a clamp in two places is how the two
  sides drift apart.
* The app never creates, `swapon`s or deletes the file; that is `ashfall-hwd`
  (`/data/ashfall/`, labelled swap file) and the API writes only the property.
* The value label is the size in MB (`1024 MB`, `Off` at 0).
* The app writes the property and nothing else.  `ashfall-hwd` owns the file,
  the `mkswap`/`swapon`/`swapoff` order and the boot re-apply, and it publishes
  what it actually applied.  A request the daemon has not (yet) applied is shown
  as `Requested N MB — M MB in use` instead of being silently
  corrected in the UI.

## 3. The warning is part of the feature

Two permanent places, no way to opt out:

1. **Row summary**, every time the page is open:

   > Swap is far slower than RAM, and heavy use wears the storage out.

2. **Confirmation before the first enable** (`onSwapSize` -> `confirmSwap`):
   `Create a swapfile?` with the same facts in plain words, the size, and
   `Cancel` / `Create`.  It appears on **every** off -> on transition, which is
   stricter than the issue's "before the first enable" minimum, and it has no
   "do not show again" - the dialog cannot be dismissed into a state where the
   warning is gone.  Cancelling writes nothing and returns the slider to 0.

The warning states the cost - slower than RAM, wears the storage out - and
never presents swap as a performance option.

## 4. Live status and unavailable states

The page reads the three swap status properties on a **1 s** tick while it is
resumed.  It is a reader only - `ashfall-hwd` is the single sampler and the
single writer of `ashfall.status.*` (docs/phase6.md §9.3), and the page opens
no sysfs node.

| Property | Used for |
|---|---|
| `ashfall.status.mem.swap_mb` | swapfile applied size; `0` off, `-1` unavailable |
| `ashfall.status.mem.swap_total_kb` | `SwapTotal` from `/proc/meminfo` |
| `ashfall.status.mem.swap_used_kb` | `SwapTotal - SwapFree` |

| State | What the page shows |
|---|---|
| `swap_mb = -1` | swap slider disabled, `The swapfile is not available on this build` |
| no daemon yet | status values `—`, the slider disabled, Handheld row `Unavailable` |
| a status value is `-1` | that value renders as `—`; never a fabricated `0` |
| `swap_mb = 0` | Swap status `Off`; `Free storage` still reports |

`-1` never reads as a number: the row says why instead of failing at write
time.

## 5. Verification record (2026-11-15, host only, no RP5)

Preview build (`cd preview && ./prepare.sh && ANDROID_HOME=/root/android-sdk
gradle --offline :app:assembleDebug`): **BUILD SUCCESSFUL**.

Emulator `Retroid_Pocket_5` (API 33, 1920x1080, `wm density 360`), single-tenant
lease `emulator-memory` via `scripts/device/lock.sh`, daemon status mocked with
`tools/mock/mem-status.sh` (never on the RP5):

| Check | Result |
|---|---|
| page rows, fresh install | `Status`: `Swap` `Off`, `Free storage` `4.7 GB free of 5.8 GB`; `Swapfile` -> `Swapfile size` `Off`; no other control on the page |
| swap off -> on (D-pad to the slider, right) | `Create a swapfile?` with the warning; `persist.ashfall.mem.swap_mb` still empty; `Back`/`CANCEL` leaves it empty and the slider at `Off`; `CREATE` writes `256` |
| D-pad step | `256 -> 512 MB`, one 256 MB step per right press; at `16128 MB` one more press gives `16384 MB` and further presses stop there |
| free-space guard | at `16128/16384 MB` the row adds `Leaves too little free storage — it will be refused.` (4.7 GB free) |
| live status | `337 MB used of 1.0 GB` (feeder), `4.7 GB free of 5.8 GB` |
| unavailable (`mem-status.sh start unavailable`) | Swap status `—`; the slider is `enabled=false` with `The swapfile is not available on this build`; D-pad right cannot change it |
| Handheld row (`MemorySummary`) | `Swap 1.0 GB`; `Unavailable` when swap is `-1` |
| day / night | white page (5.0 % near-black) -> `(0,0,0)` page background, white text, 87.1 % of the screen near-black |
| D-pad traversal | status rows and the footer are skipped (not selectable); `Swapfile size` is the only focus stop and stays reachable |

Pure black is the LineageOS black-theme RRO, which only exists on the device
build; on the stock emulator the night surface measures `(0,0,0)` already, and
the page adds no colour of its own (theme attributes only), so it follows the
RRO like the rest of the app.

The Handheld row was verified with the three-line `#152` fix applied locally
(`HandheldActivity` registered a `SummaryProvider` and called `setSummary()` on
the same row, so the page crashed before the row could be seen on this base).
The fix is on `main` (17fd40e) but not in `integ/wave2` yet; it was reverted
after the check and is not part of this change.

## 6. Deviations and notes

* The slider row lives in `MemoryFragment.StepSlider` rather than a new
  top-level file: `ui/SliderPreference` steps by one unit, which is meaningless
  for a size in MB, and the page may only write its own `Memory*` files.  It
  reuses the same row anatomy (`title/value/summary/SeekBar`, row-level D-pad)
  through `res/layout/memory_slider.xml`.
* The confirmation appears on every off -> on transition, not only the first
  one (stricter than issue #141, and it keeps the app free of a "warning seen"
  flag that could be cleared).
* The placeholder string `home_memory_summary_empty` ("zram and swapfile") in
  `res/values/strings.xml` was removed with the feature; nothing references it
  since `MemorySummary` builds the row from the daemon status.  `docs/parts.md`
  still describes the page as "zram and swapfile" in its one-line page table -
  that file belongs to the docs owner.
* `docs/phase6.md` §9.3 still lists the removed zram properties; the daemon
  worker removes them together with the native code.

## 7. Mocking the status values

`tools/mock/mem-status.sh` (test-only, mirrors `tools/mock/fan-status.sh`):

```
tools/mock/mem-status.sh start [normal|unavailable]
tools/mock/mem-status.sh status
tools/mock/mem-status.sh stop
```

It publishes the three `ashfall.status.mem.*` swap values once a second, reports
the `persist.ashfall.mem.swap_mb` value the page wrote as applied, and with
`unavailable` publishes `-1` for everything so the disabled state can be
inspected.  `ashfall.*` has no property context on a stock image, so the
emulator needs `adb root` and `adb shell setenforce 0` while the page is
exercised (`setenforce 1` afterwards).
