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
| State | live line: `2 pads -> Fusion` / `no external pad` / `daemon unavailable` |

* The switch writes the property **and** sends `FUSION ON`/`FUSION OFF`, so the
  state changes even before the daemon's next property read.  Off is the default
  and the daily-driver behaviour: nothing is grabbed, the internal pad
  enumerates exactly as before.
* The pad list is fetched on a background thread when the page resumes and
  every 3 s while it is visible.  A `FusionClient.Status` that is missing or
  unparseable shows `daemon unavailable`; there is no error dialog and no
  retry loop — the daemon is allowed to be absent.
* Rows are rebuilt only when the reply actually changed (key, layout or name),
  so a tick never steals D-pad focus.
* The **layout dialog** offers Default / Xbox / Nintendo / swap A-B / swap X-Y /
  Custom.  The checked entry is the layout the daemon reports.  A choice writes
  `persist.ashfall.pad.layouts` (survives reboot) and sends
  `FUSION LAYOUT <key> <MODE>` to apply it live.
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
* The dialog: the switch, the state line, the connected-pad list and Rescan,
  with **More settings** and **Done** in the footer.  The layout options expand
  **in place** under the pad row — a second dialog window would be drawn behind
  the Quick Settings window and be unreachable (the Lighting dialog keeps its
  colour picker inline for the same reason).  Custom mapping itself is edited on
  the Controller page; the inline hint says so and More settings is one press
  away.
* The dialog is sized with `Ui.fitDialog()` after the runtime pad rows exist, so
  the footer is always on screen and only the pad list scrolls.

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
  renders as `daemon unavailable`.  A missing pad is not an error either; it is
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

### 5.2 Emulator (`Retroid_Pocket_5`, API 36, 1920×1080, `wm density 360`)

Fusion needs a daemon, and the stock emulator has none, so both states are
proven:

1. **Daemon unavailable (no feeder).**  The page shows the Fusion category, the
   switch off, `daemon unavailable` in the state line and in the pad list; the
   tile reports `unavailable` when switched on.  Nothing crashes, nothing
   retries in a loop.
2. **Fake daemon (`tools/mock/AshfallMockDaemon.c`).**  The existing test-only
   mock now answers the fusion commands as well: two stand-in pads
   (`2f24:0001:mcu` "AshFall RP5 Gamepad" and `2dc8:3106:bt`
   "8BitDo Ultimate 2C"), the state read from `persist.ashfall.pad.fusion`,
   in-memory layouts and `OK` for REMAP/RESCAN.  It sends the header and each
   `PAD` line as **separate packets**, which exercises the harder framing.

| Check | Result |
|---|---|
| Switch off by default, page and tile | pass |
| `FUSION STATUS` two pads listed with name + layout | pass |
| Layout dialog: six entries, current one checked, choosing Nintendo sends `FUSION LAYOUT` and the row follows | pass |
| Custom editor: invalid list refused, valid list stored, empty list clears | pass |
| Rescan row and dialog button send `FUSION RESCAN` (`ashfall-mock.log`) | pass |
| State line `2 pads -> Fusion` / `no external pad` / `daemon unavailable` | pass |
| Homepage summary `Stick deadzone … · 2 pads -> Fusion` | pass |
| QS tile label/subtitle/active state and dialog (day, night, pure black) | pass |
| D-pad: switch, pad rows, inline layout options, Rescan, More settings, Done | pass |
| No feeder: daemon-unavailable state everywhere | pass |

`persist.*` writes need a permissive policy on the stock emulator (a debug
`/data` APK is not `system_app`): run `adb shell setenforce 0` while exercising
the page and restore `setenforce 1` afterwards, exactly as the Fan status feeder
in [parts.md](parts.md) §6.2 documents.  The mock's socket is `chmod 666`d by
`tools/mock/build.sh --run` for the same reason; the real init socket is
`0660 system:system` and needs neither.

### 5.3 Build

`preview/prepare.sh && gradle --offline :app:assembleDebug` — see the PR.

## 6. Follow-ups (not in this change)

* Record-a-button for the custom mapping (v1 is the raw code list).
* Per-pad rumble strength and a motion-device selector, if the daemon measures a
  need for them (issue #138 "Motion").
