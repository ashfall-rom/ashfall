# Bluetooth profiles on the RP5 (issue #106)

GSI Bluetooth had no classic profile services: accessories paired and then
disconnected after a few seconds, stayed in **Saved devices** and could never be
reconnected. This is a GSI configuration issue, not an accessory or vendor
problem. The fix is the `bluetooth.profile.*` block in
`aosp/vendor/ashfall/ashfall_common.mk`.

## Symptom

Reproduced with an 8BitDo Ultimate 2C (LE), a Rival 3 Wireless mouse (LE) and an
Edifier TWS1 Pro 2 speaker (BR/EDR). Discovery, bonding and encryption succeed,
then the ACL link drops:

```
CTL  Bond state changed : ... BT_BOND_STATE_BONDING ... HCI_SUCCESS
SEC  Encryption success : ... transport:BT_TRANSPORT_LE is_encrypted:T
CTL  Bond state changed : ... BT_BOND_STATE_BONDED ... HCI_SUCCESS
ACL  Disconnection initiated: ...[public] Le reason:HCI_ERR_PEER_USER
     comment:stack::l2cap::l2c_link::l2c_link_timeout All channels closed
```

`HCI_ERR_PEER_USER` here is Android's own L2CAP inactivity teardown, not the
peer giving up: `l2c_link_timeout()` calls
`btm_sec_disconnect(..., HCI_ERR_PEER_USER, "All channels closed")`
(`system/stack/l2cap/l2c_link.cc`) because the host never opened a profile
channel (A2DP, AVRCP, HID, HFP) after the bond. Discovery and bonding still work
(GATT/SMP for LE, SSP/SDP for BR/EDR); only profiles fail.

## Before state

Build `20261114-150832-ashfall_gsi_arm64-userdebug-cd3063cb4fb9`, captured
read-only over adb (2026-11-14):

```
$ adb shell getprop | grep -c bluetooth.profile
0

$ adb shell dumpsys bluetooth_manager | sed -n '/Enabled Profile Services/,/^$/p'
Enabled Profile Services:
  GATT
  HEARING_AID
```

The per-profile section showed `A2DP Source State: Disabled`,
`AVRCP Target Service not started`, `BR/EDR HID host Registered:false` and
`shim::legacy::hid status:BTIF_HH_DISABLED`. Three stale bonds were present
(Rival 3, 8BitDo, Edifier); they were recorded and left in place, not deleted.

## Root cause

A17 gates every profile service on a `bluetooth.profile.*` system property:

- `system/libsysprop/srcs/android/sysprop/BluetoothProperties.sysprop` declares
  the properties (`Readonly`, boolean, "Set by vendors overlay").
- `packages/modules/Bluetooth/.../btservice/Config.java` builds the enabled
  service list in `PROFILE_SERVICES_AND_FLAGS` from each service's `isEnabled()`.
- Only `GattService.isEnabled()` and `HearingAidService.isEnabled()` use
  `.orElse(true)`, so an unset property means *enabled*. Every other service
  uses `.orElse(false)`, so an unset property means *disabled*
  (`A2dpService.java:163`, `AvrcpTargetService.java:181`,
  `AvrcpControllerService.java:122`, `HidHostService.java`, `HeadsetService.java`,
  `BatteryService.java`, ...).

AOSP device products set the properties themselves:
`device/generic/goldfish/product/bluetooth.prop` and
`device/google/cuttlefish/shared/phone/product.prop` both enable A2DP source,
AVRCP target, HFP AG, HID host/device, OPP, MAP, MCP, PAN and PBAP.
`lineage_gsi_arm64` (and therefore `ashfall_gsi_arm64`) sets none, so a flashed
GSI starts with GATT and Hearing Aid only.

## Fix

`aosp/vendor/ashfall/ashfall_common.mk` adds these to
`PRODUCT_SYSTEM_PROPERTIES`:

| property | why |
|---|---|
| `bluetooth.profile.a2dp.source.enabled=true` | audio output to speakers/headsets |
| `bluetooth.profile.avrcp.target.enabled=true` | let the accessory control playback |
| `bluetooth.profile.avrcp.controller.enabled=true` | control playback on the accessory |
| `bluetooth.profile.hid.host.enabled=true` | mice, keyboards, gamepads (BR/EDR HID and LE HOGP) |
| `bluetooth.profile.hfp.ag.enabled=true` | headset/hands-free audio and mic |
| `bluetooth.profile.gatt.enabled=true` | GATT (already the default, pinned) |
| `bluetooth.profile.bas.client.enabled=true` | battery level of LE peripherals |
| `bluetooth.profile.opp.enabled=true` | object push (goldfish's harmless classic set) |
| `bluetooth.profile.map.server.enabled=true` | MAP server |
| `bluetooth.profile.pbap.server.enabled=true` | PBAP server |
| `bluetooth.profile.pan.nap.enabled=true` | PAN NAP |
| `bluetooth.profile.pan.panu.enabled=true` | PAN PANU |
| `bluetooth.profile.mcp.server.enabled=true` | MCP server (inert without LE Audio; enabled to match goldfish) |

LE Audio roles are pinned **off** instead of left unset: BAP broadcast
assist/source, BAP unicast client, CCP server, HAP client, CSIP set coordinator
and VCP controller. LE Audio is unverified on the RP5
(`docs/stock-analysis.md` §1: the standard Bluetooth audio HAL is AIDL v2, and
the HIDL `vendor.qti.hardware.bluetooth_audio@2.0` extension is a QTI provider,
not the A17 audio path). ASHA is a separate LE audio path and is already on by
platform default; it is left untouched, as is Hearing Aid. `hfp.hf` (phone
acting as a headset) and `hid.device` (phone as a peripheral) are not needed.

`AVRCP controller` goes beyond goldfish's set (goldfish enables only the target
role); a phone should also control playback on the speaker.

### Why system properties

`PRODUCT_SYSTEM_PROPERTIES` land in `/system/build.prop` inside `system.img`.
That works with the GSI's `TARGET_COPY_OUT_PRODUCT := system/product`
(`build/make/target/board/BoardConfigGsiCommon.mk`): on the currently flashed
device `/product` is a symlink to `/system/product`, and our `system.img`
carries the product files, so the stock product partition is not in the path.
The system location stays correct even if a flashing flow keeps the stock
product partition (`docs/flashing.md`), and it is equally valid for the future
device build. (For reference, the existing `ro.ashfall.*` product properties
land in `/system/product/etc/build.prop` and do load, so both locations work
today.)

The properties cannot be changed at runtime on this device: `property_contexts`
labels them `bluetooth_config_prop` (`system_vendor_config_prop`), which only
`vendor_init`/`init_dev_config` can set at runtime; the Bluetooth domain has
`get_prop` only (`system/sepolicy/private/domain.te`,
`private/vendor_init.te`). With `ro.debuggable=0` there is no root, so
`adb shell setprop` is not an option and a rebuild is needed to test them.

## Verification

Static (already done for the change):

```sh
# every property must exist in the A17 sysprop declaration and property_contexts
grep 'prop_name: "bluetooth.profile' \
  system/libsysprop/srcs/android/sysprop/BluetoothProperties.sysprop
grep '^bluetooth.profile' system/sepolicy/private/property_contexts
```

After flashing:

```sh
adb shell getprop | grep bluetooth.profile
# expect the =true keys above (a2dp.source, avrcp.target/controller, hid.host,
# hfp.ag, gatt, bas.client, opp, map.server, pbap.server, pan.*, mcp.server)
# and the pinned LE Audio keys false (bap.*, ccp.server, hap.client,
# csip.set_coordinator, vcp.controller)

adb shell dumpsys bluetooth_manager | sed -n '/Enabled Profile Services/,/^$/p'
# expect GATT, A2DP, AVRCP, AVRCP_CONTROLLER, BATTERY, HEADSET, HEARING_AID,
# HID_HOST, MAP, MCP_SERVER, OPP, PAN, PBAP (in Config.java order)

adb shell dumpsys bluetooth_manager | grep -E 'A2dpOffloadEnabled|A2DP|HID|HEADSET'
```

Connection checks (accessories powered on):

- gamepad/mouse: bond, then stay connected (`ACL BR/EDR:Y` or `LE:Y` in
  `BluetoothRemoteDevices`), input working;
- speaker: A2DP connects and audio plays; no `l2c_link_timeout` right after the
  bond;
- power-cycle each accessory and toggle Bluetooth off/on: all reconnect from
  **Saved devices** without re-pairing;
- `adb logcat -b all -d | grep -E 'HCI_ERR_PEER_USER|l2c_link_timeout'` shows no
  drop immediately after bonding;
- no new `avc: denied` for the `bluetooth` domain:
  `adb logcat -b all -d | grep 'avc:.*bluetooth'`.

## A2DP offload caveat

The stock vendor advertises A2DP offload and it is left enabled:

```
ro.bluetooth.a2dp_offload.supported=true
persist.bluetooth.a2dp_offload.disabled=false
persist.bluetooth.a2dp_offload.cap=sbc-aac-aptx-aptxhd-ldac
persist.vendor.qcom.bluetooth.a2dp_offload_cap=sbc-aptx-aptxtws-aptxhd-aac-ldac-aptxadaptiver2
```

`dumpsys bluetooth_manager` reports `A2dpOffloadEnabled: true`. Do **not**
disable offload pre-emptively: it is the vendor's normal path and software
decoding uses more CPU. If the speaker connects but stays silent, check in this
order:

1. `dumpsys bluetooth_manager | grep -A25 'Audio mode:'` then look at the
   `A2DP:` block - is the speaker the active device?
2. `logcat -b all -d | grep -Ei 'a2dp|bta_av|offload|codec'` - codec start or
   vendor session errors?
3. Only then build a test image with
   `persist.bluetooth.a2dp_offload.disabled=true` in
   `PRODUCT_SYSTEM_PROPERTIES`. The property is labelled
   `bluetooth_a2dp_offload_prop` and shell cannot set it, so this is a build (or
   privileged/root) change. If software decoding fixes it, the vendor offload
   path is the culprit and the prop can be kept with a comment.

## Build request

The worker never builds; the orchestrator builds serially. After the build:

- `build.prop` in the artifact must contain the `bluetooth.profile.*` lines
  (the build-host artifact dump of `/system/build.prop`).
- `installed-files.txt` unchanged (no new files); only `build.prop` changes.
- Flash `system.img`, then run the `getprop` / `dumpsys` commands above.

## User test checklist

1. Settings -> Bluetooth: 8BitDo gamepad pairs and stays connected; movement and
   buttons work (Parts tester).
2. Rival 3 mouse pairs and the pointer moves.
3. Edifier TWS1 Pro 2 pairs, plays audio, and media controls work.
4. Power each accessory off/on: it reconnects from **Saved devices**.
5. Bluetooth off/on: all three reconnect.
6. BLE scan and a new pairing still work; Wi-Fi stays connected during audio
   playback.
