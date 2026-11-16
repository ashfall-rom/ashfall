# Gamepad Fusion (#138)

Fusion is a mode of the AshFall input daemon (`ashfall-rp5-input`): every
connected pad — the internal MCU pad, USB pads and Bluetooth pads — drives **one**
standard Xbox-layout controller, so emulators, launchers and games need one
binding.  This page is the app side of the contract: the Controller page's
Fusion category, the one Quick Settings tile and its dialog.  The behaviour,
rationale and the daemon work stay in issue #138 and
[phase6.md](phase6.md) §9; this file is the implementation description and the
verification record.

Code: `aosp/vendor/ashfall/apps/AshFallParts/`

| Part | File |
|---|---|
| Socket client, properties, reply parsing, shared state text | `src/org/ashfall/parts/ipc/FusionClient.java` |
| Controller page: Fusion category | `src/org/ashfall/parts/ControllerActivity.java` |
| Homepage row summary | `src/org/ashfall/parts/ControllerSummary.java` |
| Quick Settings tile | `src/org/ashfall/parts/qs/FusionTileService.java` |
| Quick Settings dialog | `src/org/ashfall/parts/qs/FusionDialog.java` |
| Strings (`fusion_*`) | `res/values/strings_fusion.xml` |
| QS dialog layouts | `res/layout/fusion_qs_dialog.xml`, `res/layout/fusion_qs_pad.xml` |

## 1. Controller page

The **Fusion** category sits above the existing tester / calibration / deadzone
rows (the gamepad menu is where the user asked for it).  No other page changed:
the manifest, `TilePreferencesActivity` (long-press routing) and the page shell
were wired by the orchestrator before this work started.

| Row | Control |
|---|---|
| Fusion controller | main switch, `persist.ashfall.pad.fusion` (`0` off, off by default) |
| Connected pads | one row per pad from `FUSION STATUS`: pad name + current layout; tap opens the layout dialog |
| Rescan / restart | sends `FUSION RESCAN` |
| State | live line: `2 pads · Fusion` / `no external pad` / `Unavailable` |

* The switch writes the property **and** sends `FUSION ON`/`FUSION OFF`, so the
  state changes even before the daemon's next property read.  Off is the default
  and the daily-driver behaviour: nothing is grabbed, the internal pad
  enumerates exactly as before.
* The pad list is fetched on a background thread when the page resumes and
  every 3 s while it is visible.  A `FusionClient.Status` that is missing or
  unparseable shows `Unavailable`; there is no error dialog and no
  retry loop — the daemon is allowed to be absent.
* Rows are rebuilt only when the reply actually changed (key, layout or name),
  so a tick never steals D-pad focus.
* The **layout dialog** offers Default / Xbox / Nintendo / swap A-B / swap X-Y /
  Custom.  The checked entry is the layout the daemon reports.  A choice writes
  `persist.ashfall.pad.layouts` (survives reboot) and sends
  `FUSION LAYOUT <key> <MODE>` to apply it live; Custom instead goes through the
  map dialog below, and saving the map is what sets `CUSTOM`, so cancelling it
  changes nothing.
* **Custom mapping** (v1, decided in #138 §9.7) is the raw `from->to` code list:
  picking Custom opens a text field pre-filled with the pad's map
  (`304->305,305->304`).  Save validates the list, stores it in
  `persist.ashfall.pad.remap` and sends `FUSION REMAP <key> <map>`.  An empty
  list clears the entry and keeps the mode Custom, which the daemon treats as
  "no remap".  Record-a-button is a follow-up, not part of v1.
* No hide row, no per-app rules, no visible/hidden mode: the switch is the whole
  story.

Property formats (docs/phase6.md §9.2/§9.8):

| Property | Format |
|---|---|
| `persist.ashfall.pad.fusion` | `0` / `1` |
| `persist.ashfall.pad.layouts` | `key=LAYOUT;key=LAYOUT`, key = `vendor:product:connection` |
| `persist.ashfall.pad.remap` | `key=304->305,305->304;...` |

The app is the only writer of the three; the daemon reads and applies them,
and reports the effective layout back in `FUSION STATUS`.  Unknown layouts and
unknown keys are ignored by both sides (the app sanitises to `DEFAULT`).

## 2. Homepage summary

`ControllerSummary` keeps the deadzone text and appends the fusion state on the
same line: `Stick deadzone 5% · 3% · 2 pads -> Fusion`.  The homepage summarizes
every row from the UI thread, so the pad list comes from a cache
(`FusionClient.cache()`) refreshed in the background at most every 5 s and only
while fusion is on; the UI thread never blocks on the socket.

## 3. Quick Settings

One tile, following the Fan/Lighting pattern:

* Label **Fusion**, subtitle the pad count (`2 pads`, `no external pad`,
  `Off`, `unavailable`), active when fusion is on.  If the switch is on but the
  daemon does not answer, the tile reports `STATE_UNAVAILABLE` instead of
  pretending the merge works.
* Tap opens `FusionDialog` (`showDialog`, so the window is `TYPE_QS_DIALOG`);
  long-press opens the Controller page through `qs/TilePreferencesActivity`,
  which already routes the Fusion tile.
* Refresh: `onStartListening()` (2 s tick while the panel is open),
  `onTileAdded()`, and after every dialog change through
  `FusionDialog.Listener.onChanged()`.
* The dialog uses the Fan/Lighting chrome (docs/parts.md §3): the header
  (gamepad icon, **Fusion** title, the state line and the switch) and the footer
  never scroll, and the connected-pad list with its inline layout options is the
  only scrolling area.  Under the list sit a divider and the fixed action row —
  **Rescan / restart** on the start, **More settings** on the end — then **Done**
  across the bottom.  The layout options expand **in place** under the pad row —
  a second dialog window would be drawn behind the Quick Settings window and be
  unreachable (the Lighting dialog keeps its colour picker inline for the same
  reason).  Custom mapping itself is edited on the Controller page; the inline
  hint says so and More settings is one press away.
* The dialog is measured after the runtime pad rows exist: the card's natural
  height, clamped to 85 % of the display's **window metrics** (the current
  rotation, not the service context's resources), with the remainder given to
  the `ScrollView`.  The header, action row and Done therefore stay fully inside
  the card and only the pad list scrolls when it genuinely overflows (#156 §16).
  The measurement is the same contract as `Ui.fitDialog()` and the layout is
  ready for that shared helper — the local copy in `FusionDialog.fitWindow`
  measures the uncapped height first and uses the window metrics, both of which
  the helper needs before this dialog can switch to it.
* The width is not a local number: `fitWindow()` sizes the window from
  `@dimen/dialog_width` through `Ui.dialogWidth()`, like `Ui.fitDialog()`, and
  the root is `match_parent` (#166).  Taps are serialised by the shared
  `qs/DialogGate` (docs/parts.md §3), so a burst of taps after a boot opens one
  dialog instead of a stack.

## 4. Socket protocol

`/dev/socket/ashfall_input`, `SOCK_SEQPACKET`, one text command per packet
(docs/phase6.md §9.8).  `FusionClient` follows `CalClient` exactly: connect,
one request, read the reply, disconnect.  No `su`, no `dumpsys`, no shell.

| Request | Reply the client accepts |
|---|---|
| `FUSION STATUS` | `OK FUSION <0\|1> PADS <n>` then one `PAD <key> <LAYOUT> <seen_ms> <name...>` per pad |
| `FUSION ON` / `FUSION OFF` | `OK` (anything else is a refusal) |
| `FUSION LAYOUT <key> <MODE>` | `OK` / `ERR <reason>` |
| `FUSION REMAP <key> <map>` | `OK` / `ERR <reason>` |
| `FUSION RESCAN` | `OK` |

Client rules, and why they are lenient:

* The pad lines may arrive in the same packet as the header or in their own
  packets; the client keeps reading until `PADS <n>` lines have arrived or a
  600 ms pad timeout expires.
* A header that is not `OK FUSION <0|1> PADS <n>` — a stale daemon, an
  `ERR`, a truncated reply — degrades to `null` status, which every surface
  renders as `Unavailable`.  A missing pad is not an error either; it is
  the `no external pad` state.
* An unknown `<LAYOUT>` is sanitised to `DEFAULT`; a `seen_ms` that does not
  parse is `-1`, and the pad is still listed (the name is what the user reads).
* The pad name is the rest of the line, spaces allowed (`split(" ", 5)`).

## 5. Verification record

### 5.1 Natural (RP5)

Not run by this worker: the RP5 is off-limits (another session owns the flash
slot).  Hardware evidence — two pads driving one device, the grab/release
transition and a layout change on the 8BitDo — belongs to the orchestrator's
integration run; the daemon PR carries the native side.

### 5.2 Emulator (`Retroid_Pocket_5`, API 33, 1920×1080, `wm density 360`)

Fusion needs a daemon, and the stock emulator has none, so both states are
proven.  The rendered UI was checked through the accessibility tree
(`uiautomator dump`), which gives the exact strings, the checked layout entry,
the tile subtitle and the D-pad focus — screenshots were captured as well
(`page-day.png`, `page-night.png`, `qs-dialog-day.png`, `qs-dialog-night.png`)
but a theme *look* review (pure black) is an orchestrator/hardware item.

1. **Daemon unavailable (no feeder).**  The Controller page shows the Fusion
   category above the tester rows, the switch off, `daemon unavailable` in both
   the pad list and the state line.  With fusion switched on and the feeder
   stopped, the page and the dialog still say `daemon unavailable` and the tile
   subtitle is `unavailable` — the tile stays tappable on purpose, so the switch
   can still be turned off.  Nothing crashes, nothing retries in a loop.
2. **Fake daemon (`tools/mock/AshfallMockDaemon.c`).**  The existing test-only
   mock now answers the fusion commands as well: two stand-in pads
   (`2f24:0001:mcu` "AshFall RP5 Gamepad" and `2dc8:3106:bt`
   "8BitDo Ultimate 2C"), the state read from `persist.ashfall.pad.fusion`,
   layouts persisted per child in `/data/local/tmp/ashfall-mock-layouts` and
   `OK` for REMAP/RESCAN.  It sends the header and each `PAD` line as
   **separate packets**, which exercises the harder framing.  REMAP and RESCAN
   are logged to `/data/local/tmp/ashfall-mock.log`.

| Check | Result |
|---|---|
| Switch off by default; tapping it writes `persist.ashfall.pad.fusion=1` and the state follows | pass |
| `FUSION STATUS` two pads listed with name + layout (`Layout: Default` / `Layout: Default`) | pass |
| Layout dialog: six entries, the daemon-reported entry checked; choosing Nintendo wrote `2f24:0001:mcu=NINTENDO` and the mock logged `FUSION LAYOUT 2f24:0001:mcu NINTENDO`; the row then read `Layout: Nintendo` | pass |
| Custom editor: `abc` refused with the dialog kept open and no property write; an empty list cleared the entry and set `CUSTOM`; `304->305,305->304` stored `2dc8:3106:bt=304->305,305->304` and logged `FUSION REMAP 2dc8:3106:bt 304->305,305->304` | pass |
| Rescan row and the dialog's button both logged `FUSION RESCAN` | pass |
| State line `2 pads -> Fusion`; with fusion on and no daemon `daemon unavailable` | pass |
| QS tile: `Fusion` + `2 pads`, `Fusion` + `Off` when off, `Fusion` + `unavailable` when on without a daemon; active state follows the switch | pass |
| QS dialog: switch, state line, both pads with layouts, inline layout options (Nintendo checked, choosing Xbox wrote the property and `FUSION LAYOUT`), Rescan, More settings, Done — header and footer fixed, see the geometry below | pass |
| More settings opens `ControllerActivity`; long-press on the tile from the launcher opens `ControllerActivity` | pass |
| D-pad: focus starts on the switch, centre toggles it, two presses down reach a pad row and centre opens its layout dialog | pass |
| Day/night: page 232.7 / 30.3 and dialog 158.0 / 35.6 average brightness (`cmd uimode night`) | pass |
| Homepage summary line | **not exercised**: `HandheldActivity` crashes on resume in the shell (`Preference already has a SummaryProvider set`, `HandheldActivity.java:174`, present on `main` before this change and outside this worker's ownership).  `ControllerSummary` itself is a normal `SummaryProvider` and is exercised by the page's identical `FusionClient.stateText()` path. |

#### Dialog geometry (#156 §16)

`uiautomator dump` on the same AVD (`wm size 1920x1080`, `wm density 360`),
fresh install, mock running: the switch and Done must be inside both the screen
and the card, and the pad list must be the only scrolling view.  Values are
`[x1,y1][x2,y2]` screen pixels.

| State | Card | Switch | Done | ScrollView |
|---|---|---|---|---|
| collapsed (day and night) | `[465,172][1455,908]` | `[1385,218][1455,326]` | `[519,773][1455,881]` | `[519,345][1455,636]` |
| pad row expanded (day and night) | `[465,81][1455,999]` | `[1385,127][1455,235]` | `[519,864][1455,972]` | `[519,254][1455,727]` |

* The expanded card is exactly 918 px, i.e. the 85 % cap of 1080; Done ends at
  972, 108 px above the card's bottom edge and fully on screen.  Nothing is
  clipped at the top either: the switch starts at 127, 46 px below the card.
* `uiautomator` reports `scrollable=true` for `fusion_qs_scroll` and `false` for
  the root, the switch, the action row and Done.  After a swipe inside the
  scroll area the first pad row left the view while the switch and Done bounds
  were unchanged, so only the pad list scrolls.
* The same bounds hold in day and night; the card surface probe is
  `(255,255,255)` in day and `(66,66,66)` with `cmd uimode night yes`.  A
  `TileService` context only picks up the ui mode when it is (re)created, so the
  night check force-stops the app after the mode change.
* Daemon unavailable (mock killed with the dialog open): same geometry, the
  state line reads `daemon unavailable` and the pad list is replaced by the
  note, with Done still inside the card.

**#166 (dialog width and tap gate).**  The window is now sized from
`@dimen/dialog_width` (440 dp) and the root is `match_parent`, so every
end-aligned widget stops at the card's 24 dp inset instead of at the card edge:
subtract 54 px (24 dp at 360 dpi) from the `x2` of the switch, **More settings**
and **Done** in the table above (`1455 → 1401`).  The card is unchanged at
990 px; content keeps its 54 px inset on both sides (`519 … 1401`).  Re-measured
on the same AVD (preview build, `cmd statusbar add-tile` + `click-tile`), in the
daemon-unavailable state so the collapsed card is shorter than the 2-pad run
above:

| Element | After #166 |
|---|---|
| card (window) | `[465,225][1455,855]` |
| `fusion_qs_root` | `[465,225][1455,752]` |
| switch | `[1295,271][1401,379]` |
| `fusion_qs_scroll` | `[519,398][1401,480]` |
| `fusion_qs_rescan` | `[519,500][805,608]` |
| `fusion_qs_more` | `[1154,500][1401,608]` |
| Done | `[519,617][1401,725]` |

The card is 630 px here because Fusion keeps a 280 dp minimum height, not
because of the fit helper; with two pads the natural height wins as recorded
above.
The gate is proven on the same AVD: six `click-tile` calls in 1 s on a
force-stopped app printed five `Fusion: tap ignored, the dialog is still
opening` lines and one `Fusion: dialog shown 1052 ms after the tap`, and
`dumpsys window` showed exactly one `ty=QS_DIALOG` window (`990x630`) and one
`fusion_qs_root`.  A warm second tap logged `dialog shown 200 ms after the tap`,
so the gate reopens on show.  The Fan and Power dialogs were re-measured the
same way: Done ends at `1401`, `Edit presets` at `[519,678][1401,786]` inside
the `[465,150][1455,930]` card, day and night.

Screenshots (`final-day.png`, `final-day-expanded.png`, `final-night.png`,
`final-night-expanded.png`, `final-unavailable.png`) and the `uiautomator` XML
dumps were captured on the emulator; only the bounds above are needed to
re-check the contract.

`persist.*` writes need a permissive policy on the stock emulator (a debug
`/data` APK is not `system_app`): `adb shell setenforce 0` while exercising the
page, `setenforce 1` afterwards, exactly as the Fan status feeder in
[parts.md](parts.md) §6.2 documents.  The mock's socket is `chmod 666`d by
`tools/mock/build.sh --run` for the same reason; the real init socket is
`0660 system:system` and needs neither.

### 5.3 Build

`preview/prepare.sh && gradle --offline :app:assembleDebug` — BUILD SUCCESSFUL.
The Soong module uses the same `src/` and `res/` (no new dependency, no
manifest change).

## 6. Follow-ups (not in this change)

* Record-a-button for the custom mapping (v1 is the raw code list).
* Per-pad rumble strength and a motion-device selector, if the daemon measures a
  need for them (issue #138 "Motion").
