# RP5 USB: adb / MTP on the GSI (issue #44)

**Status:** fixed system-side in `vendor/ashfall/rp5/usb/` (no framework patch,
no vendor-partition change, stock A13 vendor stays untouched).

## Symptom

With the AshFall GSI (LineageOS 24.0 / Android 17, `userdebug`) on the stock
Android 13 vendor:

- toggling USB debugging shows "USB debugging connected", but the notification
  disappears as soon as a host runs `adb devices`;
- the host never lists an `18d1:` device; `lsusb` on the build host is empty;
- the gadget is not bound at all: `/sys/class/udc/a600000.dwc3/state` is
  `not attached`, `sys.usb.state` is empty, and
  `dumpsys usb` reports `current_functions_applied=false`,
  `kernel_state=DISCONNECTED`;
- repetitive kernel errors:

  ```
  udc a600000.dwc3: failed to start g1: -19
  dwc3 a600000.dwc3: request 0000000000000000 was not queued to ep0out
  ```

  `-19` is `ENODEV` from the configfs UDC bind.

## Root cause

Two stock-vendor behaviours combine; both are keyed off the GSI being
`userdebug` instead of the stock `user` build.

1. **The vendor seeds a factory/QMMI USB composition on non-user builds.**
   `/vendor/bin/init.qcom.usb.sh` (stock A13 vendor image) is:

   ```sh
   if [ "$(getprop persist.vendor.usb.config)" == "" -a "$(getprop ro.build.type)" != "user" ]; then
       ...
       case "$(getprop ro.baseband)" in
           "apq")
               setprop persist.vendor.usb.config diag,adb,serial_cdev
   ```

   Stock ships `ro.build.type=user` (`docs/stock/props-system.txt`) and
   `ro.baseband=apq`, so on stock the branch is skipped; on the GSI
   (`ro.build.type=userdebug`, `ro.debuggable=1`) it runs and persists

   ```
   [persist.vendor.usb.config]: [diag,adb,serial_cdev]
   [persist.sys.usb.qmmi.func]: [diag,adb,serial_cdev]
   ```

   `/vendor/etc/init/hw/init.qcom.usb.rc` then copies this into
   `sys.usb.config`/`vendor.usb.config` at `on boot`. Live GSI props:

   ```
   [ro.baseband]: [apq]
   [ro.build.type]: [userdebug]
   [sys.usb.config]: [diag,adb,serial_cdev]
   [sys.usb.configfs]: [2]
   [sys.usb.controller]: [a600000.dwc3]
   [vendor.usb.config]: [diag,adb,serial_cdev]
   [persist.vendor.usb.config]: [diag,adb,serial_cdev]
   [vendor.usb.use_gadget_hal]: [1]
   [vendor.usb.use_ffs_mtp]: [1]
   ```

2. **The QTI gadget HAL rewrites ADB-only requests into that composition.**
   The stock vendor provides
   `android.hardware.usb.gadget@1.1-service-qti` and sets
   `vendor.usb.use_gadget_hal=1`, so `sys.usb.configfs=2`. A17's
   `UsbDeviceManager` talks to that HAL. Its `UsbGadget::setupFunctions()`
   (source: `vendor/qcom/opensource/usb/hal/UsbGadget.cpp` in the LineageOS
   tree, the same code as the stock blob) does:

   ```cpp
   std::string vendorProp =
       GetProperty(VENDOR_USB_PROP, GetProperty(PERSIST_VENDOR_USB_PROP, ""));
   ...
   } else if (functions == GadgetFunction::ADB &&
              !vendorProp.empty() && vendorProp != "adb") {
       // override adb-only with additional QTI functions
       if (vendorProp.find("adb") == std::string::npos) vendorProp += ",adb";
       addFunctionsFromPropString(vendorProp, ffsEnabled, i);
   ```

   `vendor.usb.config=diag,adb,serial_cdev` selects the composition
   `diag,adb,serial_cdev` (`0x05C6/0x901F`) from
   `/vendor/etc/usb_compositions.conf`, i.e. `ffs.diag` + `ffs.adb` +
   `cser.dun.0`.

   On the stock *system* the QTI diag userspace opens `/dev/ffs-diag` and
   writes its FunctionFS descriptors. On the GSI, `vendor.diag-router` never
   runs (no `init.svc.vendor.diag-router`, no `diag-router` process) and
   `/dev/ffs-diag/ep0` has no user. FunctionFS without descriptors makes the
   composite bind fail, which is the `ENODEV` above. The whole gadget never
   pulls up, so the PC sees nothing and adbd loops:

   ```
   adbd    : timed out while waiting for FUNCTIONFS_BIND, trying again
   adbd    : UsbFfs: connection terminated: monitor thread finished
   ```

   The framework then falls back to charging/MTP and retries ADB, looping:

   ```
   UsbDeviceManager: setUsbConfig(1) request:4      # ADB
   UsbDeviceManager: notifyCurrentFunction request:4 status:1   # ERROR
   UsbDeviceManager: setUsbConfig(4) request:5      # MTP fallback
   UsbDeviceManager: notifyCurrentFunction request:5 status:0
   ```

   That is why the "USB debugging connected" notification vanishes.

Note the ADB-only request matters: A17 changed
`UsbDeviceManager.getChargingFunctions()` to return **ADB** when USB debugging
is enabled (previously MTP). So the first/default request with debugging on is
ADB-only, which is exactly the branch the QTI HAL rewrites.

## Live proof of the mechanism (non-persistent)

The physical USB link and the whole gadget stack are fine; only the ADB-only
composition selection is wrong. Asking the framework for a function set that
*includes* MTP (so the QTI `functions == ADB` override does not apply) brings
USB up on the stock vendor immediately:

```
$ scripts/device/run.sh adb -s 192.168.0.77:35521 shell \
      'svc usb setFunctions mtp; sleep 3; cat /sys/class/udc/a600000.dwc3/state'
configured
$ # host:
$ lsusb | grep 18d1
Bus 001 Device 028: ID 18d1:4ee2 AshFall SM8250-HDK_CID:0412_SN:64ADE251
$ scripts/device/run.sh adb devices -l
List of devices attached
2f184581               device usb:1-3 product:ashfall_gsi_arm64 model:AshFall_GSI_arm64
192.168.0.77:35521     device product:ashfall_gsi_arm64 model:AshFall_GSI_arm64
```

`dumpsys usb` then reports `current_functions_applied=true` and
`kernel_state=CONFIGURED`. The experiment is transient (`svc` does not touch
`persist.*`); the device was left on the framework default afterwards.

## Fix

`vendor/ashfall/rp5/usb.mk` installs
`/system/etc/init/ashfall_usb.rc`. On `boot` (after
`/vendor/etc/init/hw/init.qcom.usb.rc`, because init parses
`/system/etc/init/hw/init.rc` and its imports before `/system/etc/init/*.rc`)
it restores the stock-user-build value:

```
on boot
    setprop persist.vendor.usb.config adb
    setprop vendor.usb.config adb
    setprop sys.usb.config ${persist.sys.usb.config}
```

The QTI HAL's ADB-only override now sees `vendor.usb.config=adb`, skips the
QTI diag composition and builds its standard Android `adb` composition. With
`persist.sys.usb.config=mtp,adb` (or the user picking "File transfer") the
HAL builds standard `mtp,adb`; with debugging off it builds `mtp`/`ptp` as
usual. No diag FunctionFS is involved, so the `-19` bind failure disappears.

The change is confined to the GSI system partition: the stock vendor, its
HALs and its init scripts are untouched. `persist.vendor.usb.config=adb` is
also the value the stock *user* build ends up with (its script explicitly sets
it empty), so this does not diverge from stock behaviour.

## Test checklist (after flashing)

1. Boot the GSI. With USB debugging **on** and the cable in a PC:
   - `lsusb` shows `18d1:4ee1`/`18d1:4ee2`/`18d1:4e11` (18d1 family);
   - `adb devices` lists the device and stays listed (the debugging
     notification must not disappear);
   - `adb shell getprop sys.usb.state` is non-empty and
     `/sys/class/udc/a600000.dwc3/state` is `configured` or
     `not attached` only while nothing is plugged in.
2. `adb shell dumpsys usb | grep kernel_state` -> `CONFIGURED` while plugged.
3. Select **File transfer** in the USB notification: host shows MTP and adb
   still works (PID `18d1:4ee2`).
4. Turn USB debugging off: USB still enumerates for charging/MTP (no ADB
   interface), and turning it back on brings `adb` back without a reboot.
5. `adb reboot` (or unplug/replug) and repeat 1: the fix is applied at every
   boot, not just the first one.
6. Check `logcat -b all -d | grep -E 'udc a600000|FUNCTIONFS_BIND'` is free of
   the `failed to start g1: -19` / `timed out while waiting for
   FUNCTIONFS_BIND` loop.

## Build

Builds are orchestrated serially (`docs/PLAN.md`: workers never run
`scripts/build.sh`; soong analysis alone peaked at ~46 GB RSS and OOM-ed the
build host). This worker did not produce the final image; the PR carries a
"Build request" section with the branch and head SHA.

Expected in the build output:

- `installed-files.txt`: `/system/etc/init/ashfall_usb.rc` must be present.
- `build.prop`: unchanged by this fix (no new product properties).
- No framework patch, no vendor/product/system_ext change; the fix is one rc
  file copied into `/system/etc/init/`.

Expected on the device after flashing (and after `adb reboot`):

- `getprop persist.vendor.usb.config` -> `adb`
- `getprop vendor.usb.config` -> `adb` (or the value of
  `persist.sys.usb.config` after the vendor's property trigger runs)
- with a host attached and USB debugging on: `sys.usb.state` non-empty,
  `/sys/class/udc/a600000.dwc3/state` = `configured`, host `lsusb` shows an
  `18d1:` device;
- `logcat -b all -d` has no `udc a600000.dwc3: failed to start g1: -19` and
  adbd no longer logs `timed out while waiting for FUNCTIONFS_BIND`.
