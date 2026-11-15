/*
 * AshFall RP5 input: GamepadFusion (#138) - registry state, merge/remap rules,
 * settings parsing and status text.
 *
 * Kept free of /dev/input, /dev/uinput and sysfs so the host tests can drive
 * every rule with a fake sink (tests/test_input.c).
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#include "fusion.h"
#include "plat.h"
#include "uinput_dev.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/inotify.h>

#include <linux/input.h>

const uint16_t fusion_axis_codes[FUSION_AXIS_COUNT] = {
    ABS_BRAKE, ABS_GAS, ABS_X, ABS_Y, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y,
};

/* Keys the fusion uinput device advertises.  The MCU set plus what external
 * Xbox/PlayStation-style pads and the Android key handler need.  A pad code
 * outside this list is dropped (and logged once by the daemon): the device
 * capabilities cannot grow without recreating the uinput node, which would
 * make every app see a new controller. */
const uint16_t fusion_key_caps[] = {
    BTN_TL,       BTN_TR,       BTN_TL2,      BTN_TR2,
    BTN_SELECT,   BTN_START,    BTN_THUMBL,   BTN_THUMBR,
    BTN_A,        BTN_B,        BTN_X,        BTN_Y,
    BTN_MODE,     BTN_C,        BTN_Z,
    BTN_DPAD_UP,  BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT,
    KEY_BACK,     KEY_HOMEPAGE, KEY_MENU,     KEY_VOLUMEUP,
    KEY_VOLUMEDOWN, KEY_PLAYPAUSE,
};
const int fusion_key_cap_count = (int)(sizeof(fusion_key_caps) /
                                       sizeof(fusion_key_caps[0]));

/* --- small helpers -------------------------------------------------------- */

bool fusion_bits_test(const uint8_t *bits, int bit)
{
    if (!bits || bit < 0)
        return false;
    return (bits[bit / 8] >> (bit % 8)) & 1;
}

void fusion_bits_set(uint8_t *bits, int bit)
{
    if (!bits || bit < 0)
        return;
    bits[bit / 8] |= (uint8_t)(1u << (bit % 8));
}

bool fusion_key_supported(uint16_t code)
{
    for (int i = 0; i < fusion_key_cap_count; i++) {
        if (fusion_key_caps[i] == code)
            return true;
    }
    return false;
}

static bool has_gamepad_button(const uint8_t *keybits, size_t len)
{
    static const uint16_t buttons[] = {
        BTN_GAMEPAD, BTN_SOUTH, BTN_A, BTN_TRIGGER,
    };

    for (size_t i = 0; i < sizeof(buttons) / sizeof(buttons[0]); i++) {
        if ((size_t)(buttons[i] / 8) < len && fusion_bits_test(keybits, buttons[i]))
            return true;
    }
    return false;
}

/* --- layout modes --------------------------------------------------------- */

const char *fusion_layout_name(enum fusion_layout layout)
{
    switch (layout) {
    case FUSION_LAYOUT_DEFAULT:
        return "DEFAULT";
    case FUSION_LAYOUT_XBOX:
        return "XBOX";
    case FUSION_LAYOUT_NINTENDO:
        return "NINTENDO";
    case FUSION_LAYOUT_SWAP_AB:
        return "SWAP_AB";
    case FUSION_LAYOUT_SWAP_XY:
        return "SWAP_XY";
    case FUSION_LAYOUT_CUSTOM:
        return "CUSTOM";
    default:
        break;
    }
    return "DEFAULT";
}

bool fusion_layout_parse(const char *text, enum fusion_layout *out)
{
    if (!text || !out)
        return false;
    for (int i = 0; i < FUSION_LAYOUT_COUNT; i++) {
        if (!strcasecmp(text, fusion_layout_name((enum fusion_layout)i))) {
            *out = (enum fusion_layout)i;
            return true;
        }
    }
    return false;
}

const char *fusion_connection_name(enum fusion_connection connection)
{
    switch (connection) {
    case FUSION_CONN_BT:
        return "bt";
    case FUSION_CONN_MCU:
        return "mcu";
    case FUSION_CONN_USB:
    default:
        break;
    }
    return "usb";
}

void fusion_key_format(uint16_t vendor, uint16_t product,
                       enum fusion_connection connection, char *buf,
                       size_t len)
{
    if (!buf || len == 0)
        return;
    snprintf(buf, len, "%04x:%04x:%s", vendor, product,
             fusion_connection_name(connection));
}

uint16_t fusion_layout_code(enum fusion_layout layout, uint16_t code)
{
    bool swap_ab = layout == FUSION_LAYOUT_NINTENDO ||
                   layout == FUSION_LAYOUT_SWAP_AB;
    bool swap_xy = layout == FUSION_LAYOUT_NINTENDO ||
                   layout == FUSION_LAYOUT_SWAP_XY;

    if (swap_ab) {
        if (code == BTN_A)
            return BTN_B;
        if (code == BTN_B)
            return BTN_A;
    }
    if (swap_xy) {
        if (code == BTN_X)
            return BTN_Y;
        if (code == BTN_Y)
            return BTN_X;
    }
    return code;
}

uint16_t fusion_map_code(const struct fusion_pad *pad, uint16_t code)
{
    uint16_t mapped;

    if (!pad)
        return code;
    mapped = fusion_layout_code(pad->layout, code);
    for (int i = 0; i < pad->map_count && i < FUSION_MAX_REMAP; i++) {
        if (pad->map[i].from == mapped)
            return pad->map[i].to;
    }
    return mapped;
}

/* --- classification ------------------------------------------------------- */

bool fusion_classify(const uint8_t *keybits, size_t keybits_len,
                     const uint8_t *absbits, size_t absbits_len,
                     const uint8_t *props, size_t props_len)
{
    (void)props;
    (void)props_len; /* the stock rule never needs the properties */
    if (!keybits || !absbits)
        return false;
    if (!has_gamepad_button(keybits, keybits_len))
        return false;
    for (int code = 0; code <= ABS_MAX; code++) {
        if ((size_t)(code / 8) >= absbits_len)
            break;
        if (fusion_bits_test(absbits, code))
            return true;
    }
    return false;
}

bool fusion_is_motion_only(const uint8_t *keybits, size_t keybits_len,
                           const uint8_t *absbits, size_t absbits_len,
                           const uint8_t *props, size_t props_len)
{
    /* A node that also carries a gamepad button is a gamepad, even when it
     * exposes motion on the same node (documented limitation, issue #138). */
    if (has_gamepad_button(keybits, keybits_len))
        return false;
    if ((size_t)(INPUT_PROP_ACCELEROMETER / 8) < props_len &&
        fusion_bits_test(props, INPUT_PROP_ACCELEROMETER))
        return true;
    for (int code = ABS_X; code <= ABS_Z; code++) {
        if ((size_t)(code / 8) < absbits_len &&
            fusion_bits_test(absbits, code))
            return true;
    }
    return false;
}

bool fusion_is_self(uint16_t vendor, uint16_t product, const char *name)
{
    bool ids = (vendor == FUSION_MCU_VID && product == FUSION_MCU_PID) ||
               (vendor == FUSION_VID && product == FUSION_PID);

    if (!ids)
        return false;
    /* A real Xbox pad is 045e:028e too; only our own name makes it ours. */
    if (!name || !name[0])
        return true;
    return strncmp(name, "AshFall ", 8) == 0;
}

bool fusion_node_mergeable(const struct fusion_node *node, char *key,
                           size_t key_len)
{
    if (!node)
        return false;
    if (key && key_len > 0)
        key[0] = '\0';
    if (fusion_is_self(node->vendor, node->product, node->name))
        return false;
    if (node->motion_only || !node->gamepad)
        return false;
    if (key && key_len > 0)
        fusion_key_format(node->vendor, node->product, node->connection, key,
                          key_len);
    return true;
}

bool fusion_hotplug_scan(unsigned int mask, const char *name)
{
    /*
     * The watch is on /dev/input, so a create/delete/move/attrib means "look
     * again".  A queue overflow must rescan too, otherwise a pad that
     * appeared while the queue was full is only found by the periodic scan.
     */
    if (!(mask & (IN_CREATE | IN_DELETE | IN_ATTRIB | IN_MOVED_FROM |
                  IN_MOVED_TO | IN_Q_OVERFLOW)))
        return false;
    if (!name || !name[0])
        return true; /* the directory itself changed */
    return strncmp(name, "event", 5) == 0;
}

void fusion_axis_sources(const struct fusion_absinfo *info,
                         uint16_t out[FUSION_AXIS_COUNT])
{
    bool trigger_brake;
    bool trigger_gas;
    bool z_is_trigger;

    if (!out)
        return;
    for (int i = 0; i < FUSION_AXIS_COUNT; i++)
        out[i] = 0;
    if (!info)
        return;

    if (info[ABS_X].present)
        out[FUSION_AXIS_LX] = ABS_X;
    if (info[ABS_Y].present)
        out[FUSION_AXIS_LY] = ABS_Y;
    if (info[ABS_HAT0X].present)
        out[FUSION_AXIS_HATX] = ABS_HAT0X;
    if (info[ABS_HAT0Y].present)
        out[FUSION_AXIS_HATY] = ABS_HAT0Y;

    /*
     * Axis classification, written fresh for external pads (the MCU path has
     * calibration instead).  Two conventions exist in the wild:
     *   - Android/VirtualGamepad: triggers on ABS_BRAKE/ABS_GAS, right stick on
     *     ABS_Z/ABS_RZ;
     *   - HID Xbox: triggers on ABS_Z/ABS_RZ as 0..255, right stick on
     *     ABS_RX/ABS_RY.
     * ABS_BRAKE/ABS_GAS always win, and a small unipolar ABS_Z/ABS_RZ range
     * means triggers.  Anything else keeps the right stick on ABS_Z/ABS_RZ,
     * which is what stops a resting trigger from pinning a stick axis.
     */
    trigger_brake = info[ABS_BRAKE].present;
    trigger_gas = info[ABS_GAS].present;
    z_is_trigger = false;
    if (!trigger_brake && !trigger_gas && info[ABS_Z].present &&
        info[ABS_Z].minimum >= 0 && info[ABS_Z].maximum <= 255) {
        z_is_trigger = true;
        out[FUSION_AXIS_L2] = ABS_Z;
        trigger_brake = true;
        if (info[ABS_RZ].present) {
            out[FUSION_AXIS_R2] = ABS_RZ;
            trigger_gas = true;
        }
    }
    if (trigger_brake && info[ABS_BRAKE].present)
        out[FUSION_AXIS_L2] = ABS_BRAKE;
    if (trigger_gas && info[ABS_GAS].present)
        out[FUSION_AXIS_R2] = ABS_GAS;

    if (info[ABS_RX].present && info[ABS_RY].present) {
        out[FUSION_AXIS_RX] = ABS_RX;
        out[FUSION_AXIS_RY] = ABS_RY;
    } else if (!z_is_trigger && info[ABS_Z].present) {
        out[FUSION_AXIS_RX] = ABS_Z;
        if (info[ABS_RZ].present)
            out[FUSION_AXIS_RY] = ABS_RZ;
    }
}

/* --- settings parsing ----------------------------------------------------- */

static bool parse_code(const char *text, size_t len, uint16_t *out)
{
    char buf[16];
    char *end = NULL;
    long value;

    if (!text || len == 0 || len >= sizeof(buf))
        return false;
    memcpy(buf, text, len);
    buf[len] = '\0';
    errno = 0;
    value = strtol(buf, &end, 0); /* decimal (304) and 0x hex both accepted */
    if (errno != 0 || end == buf || (end && *end != '\0'))
        return false;
    if (value <= 0 || value > KEY_MAX)
        return false;
    *out = (uint16_t)value;
    return true;
}

static const char *find_arrow(const char *text, size_t len)
{
    for (size_t i = 0; i + 1 < len; i++) {
        if (text[i] == '-' && text[i + 1] == '>')
            return text + i;
    }
    return NULL;
}

int fusion_parse_remap_list(const char *text, struct fusion_remap *out, int max)
{
    const char *p = text;
    int count = 0;

    if (!text || !out || max <= 0)
        return 0;
    while (*p && count < max) {
        const char *comma = strchr(p, ',');
        size_t len = comma ? (size_t)(comma - p) : strlen(p);
        const char *arrow = find_arrow(p, len);
        uint16_t from;
        uint16_t to;

        if (arrow && parse_code(p, (size_t)(arrow - p), &from) &&
            parse_code(arrow + 2, len - (size_t)(arrow - p) - 2, &to) &&
            from != to) {
            out[count].from = from;
            out[count].to = to;
            count++;
        }
        p = comma ? comma + 1 : NULL;
        if (!p)
            break;
    }
    return count;
}

void fusion_format_remap_list(const struct fusion_remap *map, int count,
                              char *buf, size_t len)
{
    size_t used = 0;

    if (!buf || len == 0)
        return;
    buf[0] = '\0';
    for (int i = 0; i < count; i++) {
        int n = snprintf(buf + used, len - used, "%s%u->%u",
                         i ? "," : "", map[i].from, map[i].to);

        if (n <= 0 || (size_t)n >= len - used)
            return;
        used += (size_t)n;
    }
}

/* Split a "key=<value>" entry.  Returns false for anything malformed. */
static bool split_entry(const char *text, size_t len, char *key, size_t key_len,
                        const char **value, size_t *value_len)
{
    const char *eq = memchr(text, '=', len);
    size_t klen;

    if (!eq)
        return false;
    klen = (size_t)(eq - text);
    if (klen == 0 || klen >= key_len)
        return false;
    memcpy(key, text, klen);
    key[klen] = '\0';
    for (size_t i = 0; i < klen; i++) {
        unsigned char c = (unsigned char)key[i];

        if (c <= ' ' || c == ';' || c == '=')
            return false; /* keys are space-free by contract (§9.8) */
    }
    *value = eq + 1;
    *value_len = len - klen - 1;
    return true;
}

/* Walk a ';'-separated property list. */
static int walk_entries(const char *text, void *ctx,
                        bool (*visit)(void *ctx, const char *key,
                                      const char *value, size_t value_len))
{
    const char *p = text;
    int accepted = 0;

    if (!text)
        return 0;
    while (*p) {
        const char *semi = strchr(p, ';');
        size_t len = semi ? (size_t)(semi - p) : strlen(p);
        char key[FUSION_KEY_MAX];
        const char *value;
        size_t value_len;

        if (len > 0 && split_entry(p, len, key, sizeof(key), &value, &value_len)) {
            if (visit(ctx, key, value, value_len))
                accepted++;
        }
        p = semi ? semi + 1 : NULL;
        if (!p)
            break;
    }
    return accepted;
}

struct layouts_ctx {
    struct fusion_pad_config *out;
    int max;
    int count;
};

static bool visit_layout(void *ctx, const char *key, const char *value,
                         size_t value_len)
{
    struct layouts_ctx *state = ctx;
    char text[32];
    enum fusion_layout layout;
    struct fusion_pad_config *entry;

    if (value_len == 0 || value_len >= sizeof(text) || state->count >= state->max)
        return false;
    memcpy(text, value, value_len);
    text[value_len] = '\0';
    if (!fusion_layout_parse(text, &layout))
        return false;
    entry = &state->out[state->count++];
    memset(entry, 0, sizeof(*entry));
    snprintf(entry->key, sizeof(entry->key), "%s", key);
    entry->layout = layout;
    return true;
}

int fusion_parse_layouts(const char *text, struct fusion_pad_config *out,
                         int max)
{
    struct layouts_ctx state;

    if (!out || max <= 0)
        return 0;
    memset(&state, 0, sizeof(state));
    state.out = out;
    state.max = max;
    return walk_entries(text, &state, visit_layout);
}

struct remap_ctx {
    struct fusion_pad_config *out;
    int max;
    int count;
};

static bool visit_remap(void *ctx, const char *key, const char *value,
                        size_t value_len)
{
    struct remap_ctx *state = ctx;
    struct fusion_remap map[FUSION_MAX_REMAP];
    char text[256];
    struct fusion_pad_config *entry;
    int count;

    if (value_len >= sizeof(text) || state->count >= state->max)
        return false;
    memcpy(text, value, value_len);
    text[value_len] = '\0';
    count = fusion_parse_remap_list(text, map, FUSION_MAX_REMAP);
    if (count <= 0)
        return false; /* do not burn a slot (or hide the next entry) */
    entry = &state->out[state->count++];
    memset(entry, 0, sizeof(*entry));
    snprintf(entry->key, sizeof(entry->key), "%s", key);
    memcpy(entry->map, map, sizeof(map[0]) * (size_t)count);
    entry->map_count = count;
    return true;
}

int fusion_parse_remap(const char *text, struct fusion_pad_config *out, int max)
{
    struct remap_ctx state;

    if (!out || max <= 0)
        return 0;
    memset(&state, 0, sizeof(state));
    state.out = out;
    state.max = max;
    return walk_entries(text, &state, visit_remap);
}

/* --- settings ------------------------------------------------------------- */

void fusion_defaults(struct fusion_state *fs)
{
    if (!fs)
        return;
    memset(fs, 0, sizeof(*fs));
    for (int i = 0; i < FUSION_AXIS_COUNT; i++)
        fs->reported_abs[i] = INT_MIN;
    fs->enabled = false; /* off by default (docs/phase6.md §9.7) */
}

static struct fusion_pad_config *config_find(struct fusion_state *fs,
                                             const char *key)
{
    if (!fs || !key)
        return NULL;
    for (int i = 0; i < fs->config_count; i++) {
        if (!strcmp(fs->configs[i].key, key))
            return &fs->configs[i];
    }
    return NULL;
}

static struct fusion_pad_config *config_create(struct fusion_state *fs,
                                               const char *key)
{
    struct fusion_pad_config *config = config_find(fs, key);

    if (config)
        return config;
    if (fs->config_count >= FUSION_MAX_PADS || strlen(key) >= FUSION_KEY_MAX)
        return NULL;
    config = &fs->configs[fs->config_count++];
    memset(config, 0, sizeof(*config));
    snprintf(config->key, sizeof(config->key), "%s", key);
    config->layout = FUSION_LAYOUT_DEFAULT;
    return config;
}

static void apply_config(struct fusion_state *fs)
{
    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        struct fusion_pad *pad = &fs->pads[i];
        struct fusion_pad_config *config;

        if (!pad->present)
            continue;
        config = config_find(fs, pad->key);
        pad->layout = config ? config->layout : FUSION_LAYOUT_DEFAULT;
        pad->map_count = config ? config->map_count : 0;
        if (config)
            memcpy(pad->map, config->map, sizeof(pad->map));
    }
}

/* One pad has one config: a layout entry and a remap entry for the same key
 * are merged, never kept as two entries. */
static void config_merge(struct fusion_state *fs,
                         const struct fusion_pad_config *parsed)
{
    struct fusion_pad_config *config = config_find(fs, parsed->key);

    if (config) {
        config->layout = parsed->layout != FUSION_LAYOUT_DEFAULT
                             ? parsed->layout
                             : config->layout;
        if (parsed->map_count > 0) {
            config->map_count = parsed->map_count;
            memcpy(config->map, parsed->map, sizeof(config->map));
        }
        return;
    }
    if (fs->config_count >= FUSION_MAX_PADS)
        return;
    fs->configs[fs->config_count++] = *parsed;
}

void fusion_settings_read(struct fusion_state *fs)
{
    char enabled[8];
    char layouts[512];
    char remap[512];
    struct fusion_pad_config parsed[FUSION_MAX_PADS];
    int count;
    bool changed = false;

    if (!fs)
        return;

    enabled[0] = '\0';
    prop_get_str(PROP_FUSION, enabled, sizeof(enabled));
    if (strcmp(enabled, fs->enabled_text) != 0) {
        snprintf(fs->enabled_text, sizeof(fs->enabled_text), "%s", enabled);
        fs->enabled = !strcmp(enabled, "1");
    }

    layouts[0] = '\0';
    remap[0] = '\0';
    prop_get_str(PROP_FUSION_LAYOUTS, layouts, sizeof(layouts));
    prop_get_str(PROP_FUSION_REMAP, remap, sizeof(remap));
    if (strcmp(layouts, fs->layouts_text) != 0 ||
        strcmp(remap, fs->remap_text) != 0) {
        snprintf(fs->layouts_text, sizeof(fs->layouts_text), "%s", layouts);
        snprintf(fs->remap_text, sizeof(fs->remap_text), "%s", remap);
        fs->config_count = 0;
        memset(parsed, 0, sizeof(parsed));
        count = fusion_parse_layouts(layouts, parsed, FUSION_MAX_PADS);
        for (int i = 0; i < count; i++)
            config_merge(fs, &parsed[i]);
        memset(parsed, 0, sizeof(parsed));
        count = fusion_parse_remap(remap, parsed, FUSION_MAX_PADS);
        for (int i = 0; i < count; i++)
            config_merge(fs, &parsed[i]);
        changed = true;
    }
    if (changed)
        apply_config(fs);
}

void fusion_format_settings(const struct fusion_state *fs, char *layouts,
                            size_t layouts_len, char *remap, size_t remap_len)
{
    size_t used;
    bool first = true;

    if (!fs || !layouts || !remap || layouts_len == 0 || remap_len == 0)
        return;

    layouts[0] = '\0';
    remap[0] = '\0';
    used = 0;
    for (int i = 0; i < fs->config_count; i++) {
        const struct fusion_pad_config *config = &fs->configs[i];
        int n;

        if (config->layout == FUSION_LAYOUT_DEFAULT)
            continue;
        n = snprintf(layouts + used, layouts_len - used, "%s%s=%s",
                     first ? "" : ";", config->key,
                     fusion_layout_name(config->layout));
        if (n <= 0 || (size_t)n >= layouts_len - used)
            break;
        used += (size_t)n;
        first = false;
    }

    used = 0;
    first = true;
    for (int i = 0; i < fs->config_count; i++) {
        const struct fusion_pad_config *config = &fs->configs[i];
        char list[256];
        int n;

        if (config->map_count <= 0)
            continue;
        fusion_format_remap_list(config->map, config->map_count, list,
                                 sizeof(list));
        n = snprintf(remap + used, remap_len - used, "%s%s=%s",
                     first ? "" : ";", config->key, list);
        if (n <= 0 || (size_t)n >= remap_len - used)
            break;
        used += (size_t)n;
        first = false;
    }

    /* Property values are capped at 92 bytes: say so when the app would have
     * to drop an entry. */
    if (strlen(layouts) >= 92)
        ah_logf(AH_LOG_WARN,
                "%s would be %zu bytes (limit 92); shorten the per-pad layouts",
                PROP_FUSION_LAYOUTS, strlen(layouts));
    if (strlen(remap) >= 92)
        ah_logf(AH_LOG_WARN,
                "%s would be %zu bytes (limit 92); shorten the per-pad remaps",
                PROP_FUSION_REMAP, strlen(remap));
}

bool fusion_set_layout(struct fusion_state *fs, const char *key,
                       enum fusion_layout layout)
{
    struct fusion_pad_config *config;

    if (!fs || !key || !key[0] || layout >= FUSION_LAYOUT_COUNT)
        return false;
    config = config_create(fs, key);
    if (!config)
        return false;
    config->layout = layout;
    apply_config(fs);
    fusion_refresh(fs);
    return true;
}

bool fusion_set_remap(struct fusion_state *fs, const char *key,
                      const char *map_text)
{
    struct fusion_pad_config *config;
    struct fusion_remap map[FUSION_MAX_REMAP];
    int count;

    if (!fs || !key || !key[0] || !map_text)
        return false;
    config = config_create(fs, key);
    if (!config)
        return false;
    count = fusion_parse_remap_list(map_text, map, FUSION_MAX_REMAP);
    memset(config->map, 0, sizeof(config->map));
    memcpy(config->map, map, sizeof(map[0]) * (size_t)count);
    config->map_count = count;
    apply_config(fs);
    fusion_refresh(fs);
    return true;
}

static bool axis_is_trigger(int slot)
{
    return slot == FUSION_AXIS_L2 || slot == FUSION_AXIS_R2;
}

/* The range the fusion uinput device advertises for a slot: same helper the
 * device construction uses, so a pad is rescaled to exactly what we report. */
static void axis_target_range(int slot, int *minimum, int *maximum)
{
    if (slot < 0 || slot >= FUSION_AXIS_COUNT) {
        *minimum = -32768;
        *maximum = 32767;
        return;
    }
    uinput_dev_axis_range(fusion_axis_codes[slot], minimum, maximum);
}

static int scale_linear(int value, int src_min, int src_max, int dst_min,
                        int dst_max)
{
    long long span = (long long)src_max - src_min;
    long long out;

    if (span <= 0)
        return value;
    out = (long long)dst_min +
          ((long long)(value - src_min) * (dst_max - dst_min)) / span;
    if (out < dst_min)
        out = dst_min;
    if (out > dst_max)
        out = dst_max;
    return (int)out;
}

/* Sticks and hats have their rest position in the middle of the source range,
 * which is not the middle of the destination for an asymmetric pad. */
static int scale_centred(int value, int src_min, int src_max, int dst_min,
                         int dst_max)
{
    int centre = src_min + (src_max - src_min) / 2;

    if (src_max <= src_min)
        return value;
    if (value <= centre) {
        if (centre == src_min)
            return dst_min;
        return scale_linear(value, src_min, centre, dst_min, 0);
    }
    if (src_max == centre)
        return dst_max;
    return scale_linear(value, centre, src_max, 0, dst_max);
}

/* --- pad registry --------------------------------------------------------- */

int fusion_pad_index(const struct fusion_state *fs, const char *key)
{
    if (!fs || !key)
        return -1;
    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        if (fs->pads[i].present && !strcmp(fs->pads[i].key, key))
            return i;
    }
    return -1;
}

/* Two identical pads share vendor:product:connection, which the contract makes
 * the key.  The second one gets a "~2" suffix so neither pad is lost; the
 * suffix only depends on arrival order among otherwise identical pads. */
static void unique_key(const struct fusion_state *fs, const char *key,
                       char *buf, size_t len)
{
    snprintf(buf, len, "%s", key);
    for (int n = 2; n < 100 && fusion_pad_index(fs, buf) >= 0; n++)
        snprintf(buf, len, "%.*s~%d", FUSION_KEY_MAX - 6, key, n);
}

struct fusion_pad *fusion_pad_add(struct fusion_state *fs, const char *key,
                                  const char *name,
                                  enum fusion_connection connection,
                                  int64_t now_ms)
{
    struct fusion_pad *pad = NULL;
    struct fusion_pad_config *config;
    char unique[FUSION_KEY_MAX];

    if (!fs || !key || !key[0] || fs->pad_count >= FUSION_MAX_PADS)
        return NULL;
    unique_key(fs, key, unique, sizeof(unique));
    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        if (!fs->pads[i].present) {
            pad = &fs->pads[i];
            break;
        }
    }
    if (!pad)
        return NULL;

    memset(pad, 0, sizeof(*pad));
    pad->present = true;
    snprintf(pad->key, sizeof(pad->key), "%s", unique);
    snprintf(pad->name, sizeof(pad->name), "%s", name ? name : unique);
    pad->connection = connection;
    pad->last_seen_ms = now_ms;
    fs->pad_count++;

    config = config_find(fs, pad->key);
    if (!config) {
        /* The layout set over the socket may predate the pad showing up. */
        config = config_find(fs, key);
        if (!config)
            config = config_create(fs, pad->key);
    }
    if (config) {
        pad->layout = config->layout;
        pad->map_count = config->map_count;
        memcpy(pad->map, config->map, sizeof(pad->map));
    }
    for (int slot = 0; slot < FUSION_AXIS_COUNT; slot++)
        axis_target_range(slot, &pad->abs_min[slot], &pad->abs_max[slot]);
    fusion_refresh(fs);
    return pad;
}

void fusion_pad_set_axis_range(struct fusion_state *fs, int index, int slot,
                               int minimum, int maximum)
{
    struct fusion_pad *pad;

    if (!fs || index < 0 || index >= FUSION_MAX_PADS)
        return;
    if (slot < 0 || slot >= FUSION_AXIS_COUNT)
        return;
    pad = &fs->pads[index];
    if (!pad->present || maximum <= minimum)
        return;
    pad->abs_min[slot] = minimum;
    pad->abs_max[slot] = maximum;
}

bool fusion_pad_remove(struct fusion_state *fs, const char *key)
{
    int index = fusion_pad_index(fs, key);

    if (index < 0)
        return false;
    memset(&fs->pads[index], 0, sizeof(fs->pads[index]));
    fs->pads[index].present = false;
    if (fs->pad_count > 0)
        fs->pad_count--;
    fusion_refresh(fs);
    return true;
}

void fusion_pad_clear(struct fusion_state *fs, int index)
{
    struct fusion_pad *pad;

    if (!fs || index < 0 || index >= FUSION_MAX_PADS)
        return;
    pad = &fs->pads[index];
    if (!pad->present)
        return;
    memset(pad->keys, 0, sizeof(pad->keys));
    memset(pad->abs, 0, sizeof(pad->abs));
    for (int i = 0; i < FUSION_AXIS_COUNT; i++)
        pad->abs_seq[i] = 0;
    fusion_refresh(fs);
}

void fusion_clear_pads(struct fusion_state *fs)
{
    if (!fs)
        return;
    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        memset(&fs->pads[i], 0, sizeof(fs->pads[i]));
        fs->pads[i].present = false;
    }
    fs->pad_count = 0;
}

/* --- merge --------------------------------------------------------------- */

static void set_pad_key(struct fusion_pad *pad, uint16_t code, bool down)
{
    if (code > KEY_MAX)
        return;
    if (down)
        pad->keys[code / 32] |= 1u << (code % 32);
    else
        pad->keys[code / 32] &= ~(1u << (code % 32));
}

void fusion_key_event(struct fusion_state *fs, int index, uint16_t code,
                      int32_t value)
{
    struct fusion_pad *pad;

    if (!fs || index < 0 || index >= FUSION_MAX_PADS)
        return;
    pad = &fs->pads[index];
    if (!pad->present)
        return;
    set_pad_key(pad, code, value != 0);
    /* FUSION STATUS reports how long ago the pad was last heard from. */
    pad->last_seen_ms = ah_now_ms();
}

void fusion_abs_event(struct fusion_state *fs, int index, int slot,
                      int32_t value, int64_t now_ms)
{
    struct fusion_pad *pad;
    int32_t clamped;

    if (!fs || index < 0 || index >= FUSION_MAX_PADS)
        return;
    if (slot < 0 || slot >= FUSION_AXIS_COUNT)
        return;
    pad = &fs->pads[index];
    if (!pad->present)
        return;
    {
        int dst_min;
        int dst_max;
        int32_t scaled = value;

        axis_target_range(slot, &dst_min, &dst_max);
        if (pad->abs_min[slot] != dst_min || pad->abs_max[slot] != dst_max) {
            if (axis_is_trigger(slot))
                scaled = scale_linear(value, pad->abs_min[slot],
                                      pad->abs_max[slot], dst_min, dst_max);
            else
                scaled = scale_centred(value, pad->abs_min[slot],
                                       pad->abs_max[slot], dst_min, dst_max);
        }
        if (scaled < dst_min)
            clamped = dst_min;
        else if (scaled > dst_max)
            clamped = dst_max;
        else
            clamped = scaled;
    }

    pad->abs[slot] = clamped;
    pad->abs_seq[slot] = ++fs->seq;
    pad->last_seen_ms = now_ms;
}

static void merge_state(const struct fusion_state *fs, uint32_t *keys,
                        int *abs)
{
    int64_t best_seq[FUSION_AXIS_COUNT];

    memset(keys, 0, sizeof(uint32_t) * FUSION_KEY_WORDS);
    for (int a = 0; a < FUSION_AXIS_COUNT; a++) {
        abs[a] = 0;
        best_seq[a] = 0;
    }

    for (int i = 0; i < FUSION_MAX_PADS; i++) {
        const struct fusion_pad *pad = &fs->pads[i];

        if (!pad->present)
            continue;
        for (int w = 0; w < FUSION_KEY_WORDS; w++) {
            uint32_t bits = pad->keys[w];

            while (bits) {
                int bit = __builtin_ctz(bits);
                uint16_t code = (uint16_t)(w * 32 + bit);
                uint16_t mapped = fusion_map_code(pad, code);

                bits &= bits - 1;
                if (mapped <= KEY_MAX)
                    keys[mapped / 32] |= 1u << (mapped % 32);
            }
        }
        for (int a = 0; a < FUSION_AXIS_COUNT; a++) {
            int value = pad->abs[a];

            if (axis_is_trigger(a)) {
                if (value > abs[a])
                    abs[a] = value;
            } else if (value != 0 && pad->abs_seq[a] > best_seq[a]) {
                best_seq[a] = pad->abs_seq[a];
                abs[a] = value;
            }
        }
    }
}

int fusion_flush(struct fusion_state *fs, session_emit_fn emit, void *ctx)
{
    uint32_t keys[FUSION_KEY_WORDS];
    int abs[FUSION_AXIS_COUNT];
    int emitted = 0;

    if (!fs || !emit)
        return 0;
    merge_state(fs, keys, abs);

    for (int w = 0; w < FUSION_KEY_WORDS; w++) {
        uint32_t diff = keys[w] ^ fs->reported_keys[w];

        fs->reported_keys[w] = keys[w];
        while (diff) {
            int bit = __builtin_ctz(diff);
            uint16_t code = (uint16_t)(w * 32 + bit);

            diff &= diff - 1;
            emit(ctx, EV_KEY, code, (keys[w] >> bit) & 1);
            emitted++;
        }
    }
    for (int a = 0; a < FUSION_AXIS_COUNT; a++) {
        if (fs->reported_abs[a] == abs[a])
            continue; /* INT_MIN never matches, so dirty axes always report */
        emit(ctx, EV_ABS, fusion_axis_codes[a], abs[a]);
        fs->reported_abs[a] = abs[a];
        emitted++;
    }
    if (emitted > 0)
        emit(ctx, EV_SYN, SYN_REPORT, 0);
    return emitted;
}

void fusion_refresh(struct fusion_state *fs)
{
    if (!fs)
        return;
    for (int a = 0; a < FUSION_AXIS_COUNT; a++)
        fs->reported_abs[a] = INT_MIN;
}

void fusion_reset_reported(struct fusion_state *fs)
{
    if (!fs)
        return;
    memset(fs->reported_keys, 0, sizeof(fs->reported_keys));
    fusion_refresh(fs);
}

int fusion_release(struct fusion_state *fs, session_emit_fn emit, void *ctx)
{
    if (!fs || !emit)
        return 0;
    return session_release_bits(fs->reported_keys, FUSION_KEY_WORDS,
                                fs->reported_abs, fusion_axis_codes,
                                FUSION_AXIS_COUNT, emit, ctx);
}

/* --- status --------------------------------------------------------------- */

static void sanitize_name(const char *in, char *out, size_t len)
{
    size_t used = 0;

    if (!out || len == 0)
        return;
    out[0] = '\0';
    if (!in)
        return;
    for (size_t i = 0; in[i] && used + 1 < len; i++) {
        unsigned char c = (unsigned char)in[i];

        /* The name is the last field of the line: keep it printable and
         * space-safe, never let a kernel-provided '\\n' end the frame. */
        out[used++] = (c < 0x20 || c == 0x7f) ? ' ' : (char)c;
    }
    out[used] = '\0';
}

size_t fusion_status_format(const struct fusion_state *fs, int64_t now_ms,
                            char *buf, size_t len)
{
    size_t used;

    if (!buf || len == 0)
        return 0;
    buf[0] = '\0';
    if (!fs)
        return 0;

    used = (size_t)snprintf(buf, len, "OK FUSION %d PADS %d\n",
                            fs->enabled ? 1 : 0, fs->pad_count);
    if (used >= len)
        return len - 1;

    for (int i = 0; i < FUSION_MAX_PADS && used + 1 < len; i++) {
        const struct fusion_pad *pad = &fs->pads[i];
        char name[FUSION_NAME_MAX];
        int64_t age;
        int n;

        if (!pad->present)
            continue;
        age = now_ms - pad->last_seen_ms;
        if (age < 0)
            age = 0;
        sanitize_name(pad->name, name, sizeof(name));
        n = snprintf(buf + used, len - used, "PAD %s %s %lld %s\n", pad->key,
                     fusion_layout_name(pad->layout), (long long)age, name);
        if (n <= 0)
            break;
        if ((size_t)n >= len - used)
            return len - 1;
        used += (size_t)n;
    }
    return used;
}
