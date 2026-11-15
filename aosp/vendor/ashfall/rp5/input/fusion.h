/*
 * AshFall RP5 input: GamepadFusion (#138).
 *
 * Fusion is a mode of this daemon, not a second daemon: every external
 * USB/Bluetooth gamepad is opened, grabbed and merged with the internal MCU
 * pad into the single uinput device this daemon already owns.  While fusion is
 * on, apps that enumerate every input device (RetroArch and friends) see one
 * Xbox-layout controller and cannot double-input.
 *
 * This file holds the logic only - pad registry state, the merge/remap rules,
 * property parsing and the status text - so the host tests can drive all of it
 * with a fake sink and no /dev/input, /dev/uinput or hardware.
 *
 * Rules taken from issue #138:
 *   - a node is a gamepad only when it carries EV_KEY/BTN_GAMEPAD (or
 *     BTN_SOUTH/BTN_A/BTN_TRIGGER) and EV_ABS; a motion-only node
 *     (INPUT_PROP_ACCELEROMETER, ABS_X/Y/Z, no gamepad button) is never
 *     opened, never grabbed, and keeps feeding Android's sensor path;
 *   - buttons are the union of every pad, axes resolve per axis to the most
 *     recent non-resting value, triggers are the maximum of the pads, and the
 *     merge emits deltas only, one SYN per batch, with a full re-report after
 *     any topology change;
 *   - the pad key is vendor:product:connection (lowercase hex, usb/bt/mcu) so
 *     the same pad keeps its layout across reboots and re-enumeration.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#ifndef ASHFALL_FUSION_H
#define ASHFALL_FUSION_H

#include "session.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define FUSION_MAX_PADS 8
#define FUSION_MAX_REMAP 32
#define FUSION_KEY_MAX 32  /* "vendor:product:connection" plus a "~N" suffix */
#define FUSION_NAME_MAX 64
#define FUSION_KEY_WORDS 24    /* (KEY_MAX 0x2ff) + 1 bits */
#define FUSION_ABS_MAX 0x40    /* ABS_MAX + 1; pad axis arrays are indexed by code */

#define FUSION_AXIS_COUNT 8 /* slots, in the MCU daemon's axis order */

/* Settings properties (docs/phase6.md §9.3).  <key> is vendor:product:conn. */
#define PROP_FUSION "persist.ashfall.pad.fusion"
#define PROP_FUSION_LAYOUTS "persist.ashfall.pad.layouts"
#define PROP_FUSION_REMAP "persist.ashfall.pad.remap"

/* uinput identities: fusion on is the well-known Xbox One S pad, fusion off is
 * exactly the device this daemon has always created. */
#define FUSION_NAME_ON "AshFall Fusion Controller"
#define FUSION_NAME_OFF "AshFall RP5 Gamepad"
#define FUSION_VID 0x045e
#define FUSION_PID 0x028e
#define FUSION_MCU_VID 0x2020
#define FUSION_MCU_PID 0x3001

#define FUSION_BITS_BYTES(bits) (((bits) + 7) / 8)

enum fusion_layout {
    FUSION_LAYOUT_DEFAULT = 0,
    FUSION_LAYOUT_XBOX,
    FUSION_LAYOUT_NINTENDO,
    FUSION_LAYOUT_SWAP_AB,
    FUSION_LAYOUT_SWAP_XY,
    FUSION_LAYOUT_CUSTOM,
    FUSION_LAYOUT_COUNT,
};

enum fusion_connection {
    FUSION_CONN_USB = 0,
    FUSION_CONN_BT,
    FUSION_CONN_MCU,
};

enum fusion_axis {
    FUSION_AXIS_L2 = 0,
    FUSION_AXIS_R2,
    FUSION_AXIS_LX,
    FUSION_AXIS_LY,
    FUSION_AXIS_RX,
    FUSION_AXIS_RY,
    FUSION_AXIS_HATX,
    FUSION_AXIS_HATY,
};

struct fusion_remap {
    uint16_t from;
    uint16_t to;
};

/* One pad's persisted layout + custom from->to code map. */
struct fusion_pad_config {
    char key[FUSION_KEY_MAX];
    enum fusion_layout layout;
    struct fusion_remap map[FUSION_MAX_REMAP];
    int map_count;
};

/* A pad the registry currently sees. */
struct fusion_pad {
    bool present;
    char key[FUSION_KEY_MAX];
    char name[FUSION_NAME_MAX];
    enum fusion_connection connection;
    enum fusion_layout layout;
    struct fusion_remap map[FUSION_MAX_REMAP];
    int map_count;
    uint32_t keys[FUSION_KEY_WORDS]; /* codes as heard from the pad, pre-remap */
    int abs[FUSION_AXIS_COUNT];
    int64_t abs_seq[FUSION_AXIS_COUNT]; /* merge order of the last abs value */
    /* Source range of each axis slot, so a pad that does not speak the uinput
     * range (BT HID pads often use -127..127, 10-bit triggers) is rescaled
     * instead of clamped.  Defaults to the target range, i.e. identity. */
    int abs_min[FUSION_AXIS_COUNT];
    int abs_max[FUSION_AXIS_COUNT];
    int64_t last_seen_ms;
};

struct fusion_state {
    bool enabled;
    bool rescan; /* set by FUSION RESCAN, consumed by the daemon loop */
    int64_t seq; /* merge order counter for "most recent non-resting" */

    struct fusion_pad pads[FUSION_MAX_PADS];
    int pad_count;

    struct fusion_pad_config configs[FUSION_MAX_PADS];
    int config_count;

    /* What the uinput device was last told; INT_MIN marks an axis that must be
     * re-reported (topology change, device recreation). */
    uint32_t reported_keys[FUSION_KEY_WORDS];
    int reported_abs[FUSION_AXIS_COUNT];

    /* Last property text seen.  The app (system_app) is the writer of
     * persist.ashfall.pad.*; this daemon only reads them, and adopts a value
     * only when its text changes so a FUSION command is not reverted by the
     * next 250 ms poll. */
    char enabled_text[8];
    char layouts_text[512];
    char remap_text[512];
};

/* absinfo read from the pad, indexed by ABS code. */
struct fusion_absinfo {
    bool present;
    int minimum;
    int maximum;
};

extern const uint16_t fusion_axis_codes[FUSION_AXIS_COUNT];

/* Advertised key set of the fusion uinput device. */
extern const uint16_t fusion_key_caps[];
extern const int fusion_key_cap_count;

/* True when the fusion device advertises this key code (the daemon drops the
 * rest, since uinput capabilities cannot grow after creation). */
bool fusion_key_supported(uint16_t code);

/* --- names, keys, layout modes ------------------------------------------ */

const char *fusion_layout_name(enum fusion_layout layout);
bool fusion_layout_parse(const char *text, enum fusion_layout *out);
const char *fusion_connection_name(enum fusion_connection connection);

/* "vendor:product:connection", lowercase hex, space-free and stable. */
void fusion_key_format(uint16_t vendor, uint16_t product,
                       enum fusion_connection connection, char *buf,
                       size_t len);

/* Layout mode mapping for a single key code (identity for DEFAULT/XBOX and
 * for CUSTOM, whose work is done by the from->to map). */
uint16_t fusion_layout_code(enum fusion_layout layout, uint16_t code);

/* Layout mode followed by the pad's custom map, i.e. what the merge sees. */
uint16_t fusion_map_code(const struct fusion_pad *pad, uint16_t code);

/* --- classification ------------------------------------------------------ */

bool fusion_bits_test(const uint8_t *bits, int bit);
void fusion_bits_set(uint8_t *bits, int bit);

/* True for a node carrying a gamepad button and at least one EV_ABS axis.
 * The daemon probes with a read-only open (that is how the capabilities are
 * read at all); a non-pad node is closed again immediately and is never
 * grabbed or held, which is what keeps a motion-only node on the sensor path. */
bool fusion_classify(const uint8_t *keybits, size_t keybits_len,
                     const uint8_t *absbits, size_t absbits_len,
                     const uint8_t *props, size_t props_len);

/* A motion-only node: accelerometer property, or ABS_X/Y/Z without one of the
 * gamepad buttons.  Such a node is never opened and never grabbed (#138). */
bool fusion_is_motion_only(const uint8_t *keybits, size_t keybits_len,
                           const uint8_t *absbits, size_t absbits_len,
                           const uint8_t *props, size_t props_len);

/* Self-detection: our own uinput device must never be opened as a "pad", or
 * fusion would feed back into itself. */
bool fusion_is_self(uint16_t vendor, uint16_t product, const char *name);

/* One probed /dev/input/event* node, as the daemon saw it. */
struct fusion_node {
    uint16_t vendor;
    uint16_t product;
    enum fusion_connection connection;
    const char *name;
    bool gamepad;     /* fusion_classify() on the node's capability bits */
    bool motion_only; /* fusion_is_motion_only(), for the log line */
};

/* Merge decision for a probed node, plus the stable
 * "vendor:product:connection" key (#156).  False means "leave the node
 * alone": our own uinput device, a key-only node, or a motion-only node that
 * must stay on Android's sensor path. */
bool fusion_node_mergeable(const struct fusion_node *node, char *key,
                           size_t key_len);

/* Hotplug: does this /dev/input inotify event require a rescan? */
bool fusion_hotplug_scan(unsigned int mask, const char *name);

/* Which ABS code feeds each merged axis slot (0 = the pad lacks it).  External
 * pads disagree here: Android/VirtualGamepad pads use ABS_BRAKE/ABS_GAS for
 * the triggers and ABS_Z/ABS_RZ for the right stick, while HID Xbox pads use
 * ABS_Z/ABS_RZ for the triggers and ABS_RX/ABS_RY for the right stick. */
void fusion_axis_sources(const struct fusion_absinfo *info,
                         uint16_t out[FUSION_AXIS_COUNT]);

/* --- settings ------------------------------------------------------------ */

void fusion_defaults(struct fusion_state *fs);

/* Read persist.ashfall.pad.* into *fs.  Missing or malformed values fall back
 * to the defaults; nothing here can fail or crash. */
void fusion_settings_read(struct fusion_state *fs);

/* "key=LAYOUT;..." / "key=304->305;..." for the current settings (only the
 * non-default entries).  Used for logging; this daemon never writes the
 * properties - the app owns them (docs/phase6.md §9.3). */
void fusion_format_settings(const struct fusion_state *fs, char *layouts,
                            size_t layouts_len, char *remap, size_t remap_len);

/* "key=LAYOUT;key=LAYOUT" / "key=304->305,305->304;..."; returns the number of
 * valid entries and silently skips garbage. */
int fusion_parse_layouts(const char *text, struct fusion_pad_config *out,
                         int max);
int fusion_parse_remap(const char *text, struct fusion_pad_config *out,
                       int max);
int fusion_parse_remap_list(const char *text, struct fusion_remap *out, int max);
void fusion_format_remap_list(const struct fusion_remap *map, int count,
                              char *buf, size_t len);

/* Layout/remap for a pad key; created on demand.  false on a bad key/layout. */
bool fusion_set_layout(struct fusion_state *fs, const char *key,
                       enum fusion_layout layout);
bool fusion_set_remap(struct fusion_state *fs, const char *key,
                      const char *map_text);

/* --- pad registry (state only; the fds live in the daemon) --------------- */

/* Source range of one axis slot, from EVIOCGABS.  Never set means "the pad
 * already reports the uinput range" (identity). */
void fusion_pad_set_axis_range(struct fusion_state *fs, int index, int slot,
                               int minimum, int maximum);

int fusion_pad_index(const struct fusion_state *fs, const char *key);
struct fusion_pad *fusion_pad_add(struct fusion_state *fs, const char *key,
                                  const char *name,
                                  enum fusion_connection connection,
                                  int64_t now_ms);
bool fusion_pad_remove(struct fusion_state *fs, const char *key);
void fusion_pad_clear(struct fusion_state *fs, int index);

/* --- merge --------------------------------------------------------------- */

void fusion_key_event(struct fusion_state *fs, int index, uint16_t code,
                      int32_t value);
void fusion_abs_event(struct fusion_state *fs, int index, int slot,
                      int32_t value, int64_t now_ms);

/* Emit the difference to what was last reported, one SYN per batch. */
int fusion_flush(struct fusion_state *fs, session_emit_fn emit, void *ctx);

/* Re-report the full merged state on the next flush (topology change while the
 * uinput device stays alive): every axis is sent again, keys only on change. */
void fusion_refresh(struct fusion_state *fs);

/* The uinput device was (re)created: the kernel holds no state, so the next
 * flush must send every held key and every axis. */
void fusion_reset_reported(struct fusion_state *fs);

/* Release everything still reported (fusion off / shutdown), reusing the
 * session.c release rules; the reported state is then clean and dirty-marked. */
int fusion_release(struct fusion_state *fs, session_emit_fn emit, void *ctx);

/* Forget every pad without emitting (fusion off). */
void fusion_clear_pads(struct fusion_state *fs);

/* --- status -------------------------------------------------------------- */

/* "OK FUSION <0|1> PADS <n>" plus one
 * "PAD <key> <LAYOUT> <seen_ms> <name>" line per pad (protocol: §9.8). */
size_t fusion_status_format(const struct fusion_state *fs, int64_t now_ms,
                            char *buf, size_t len);

#endif /* ASHFALL_FUSION_H */
