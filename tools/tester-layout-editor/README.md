# Tester layout editor

Temporary web editor for the AshFall Parts controller-tester layout. It writes
`aosp/vendor/ashfall/apps/AshFallParts/res/raw/tester_layout.json` in a ROM
worktree; the app reads that file through `TesterLayout` (schema v1).

## Run

```bash
python3 tools/tester-layout-editor/server.py \
  --repo /root/.t3/worktrees/ashfall/feat-tester-v2-84-... \
  --port 8787 \
  --token "$(python3 -c 'import secrets;print(secrets.token_urlsafe(18))')"
```

The server binds `0.0.0.0` and prints a URL. Use the machine's Tailscale IP:
`http://100.127.219.6:8787/?token=<token>`.

## Use

- Drag a control on the 16:9 canvas, or select it and edit `x`/`y`/`r`/`w`/`h`
  numerically. Arrow keys nudge the selected control; Shift moves faster.
- Amber dashed outlines mark overlapping bounding boxes. The panel lists the
  pairs so you can separate them.
- **Save layout** writes the JSON resource atomically. Reload restores the file
  from disk. There is no undo beyond Reload.
- The JSON is loaded when the tester view is created; a rebuild/install is
  needed to see a save on the device.

## Schema

`x`, `y` are the centre as fractions of the view width/height. For circles,
`r` is the radius as a fraction of the view height. For rectangles, `w` and `h`
are the full size as fractions of the view width/height. `status` is the
top-left of the RAW text block.

Known controls: `left_stick`, `right_stick`, `dpad`, `button_x`, `button_y`,
`button_a`, `button_b`, `l1`, `r1`, `l2`, `r2`, `back`, `home`, `select`,
`start`, `status`.

The app falls back per field to the built-in defaults for missing or invalid
values, and to the complete defaults for an unknown version or broken JSON, so
a bad save cannot crash the tester.

## Stop

`kill "$(pgrep -f 'tester-layout-editor/server.py' | head -1)"` from the
machine running the server.
