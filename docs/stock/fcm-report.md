# VINTF matrix comparison

Vendor manifest(s): manifest.xml, android.hardware.atrace@1.0-service.xml, android.hardware.boot@1.2.xml, android.hardware.cas@1.2-service.xml, android.hardware.drm-service.clearkey.xml, android.hardware.graphics.mapper-impl-qti-display.xml, android.hardware.health-service.qti.xml, android.hardware.security.keymint-service-qti.xml, android.hardware.sensors@2.1-multihal.xml, android.hardware.thermal@2.0-service.qti.xml, android.hardware.usb@1.2-service.xml, android.hardware.usb.gadget@1.1-service.xml, android.hardware.wifi@1.0-service.xml, android.hardware.wifi.hostapd.xml, android.hardware.wifi.supplicant.xml, bluetooth_audio.xml, bttpi-saidl.xml, c2_manifest_vendor.xml, identity-default.xml, memtrack_qti.xml, power.xml, vendor.qti.diag.hal.service.xml, vendor.qti.hardware.display.allocator-service.xml, vendor.qti.hardware.display.composer-service.xml, vendor.qti.hardware.lights.service.xml, vendor.qti.hardware.limits-service.xml, vendor.qti.hardware.perf.xml, vendor.qti.hardware.power.powermodule.xml, vendor.qti.hardware.servicetracker@1.2-service.xml, vendor.qti.hardware.umd@1.0-service.xml, vendor.qti.hardware.vibrator.service.xml, vendor.qti.hardware.wifidisplaysession-service.xml, vendor.qti.memory.pasrmanager@1.0-service.xml

Matrix levels: `7`, `8`, `202404`, `202504`, `202604`, `202704`

## Vendor HALs vs Android 17 compatibility matrices

`yes` = the HAL/version is accepted by that FCM level, `--` = not listed, `opt` = listed as optional.

| format | vendor HAL | versions | 7 | 8 | 202404 | 202504 | 202604 | 202704 |
|---|---|---|---|---|---|---|---|---|
| aidl | `android.hardware.bluetooth.audio` | 2 | opt | ver! | ver! | ver! | ver! | ver! |
| aidl | `android.hardware.drm` | 1 | opt | opt | opt | opt | opt | opt |
| aidl | `android.hardware.health` | 1 | opt | opt | ver! | ver! | ver! | ver! |
| aidl | `android.hardware.identity` | 4 | opt | opt | opt | opt | opt | opt |
| aidl | `android.hardware.light` | 2 | opt | opt | opt | opt | opt | opt |
| aidl | `android.hardware.memtrack` | - | opt | opt | opt | opt | opt | opt |
| aidl | `android.hardware.power` | 3 | opt | ver! | ver! | ver! | ver! | ver! |
| aidl | `android.hardware.security.keymint` | 2 | opt | opt | opt | opt | opt | opt |
| aidl | `android.hardware.security.secureclock` | - | opt | opt | opt | opt | opt | opt |
| aidl | `android.hardware.security.sharedsecret` | - | opt | opt | opt | opt | opt | opt |
| aidl | `android.hardware.vibrator` | 2 | opt | opt | opt | opt | opt | opt |
| aidl | `android.hardware.wifi.hostapd` | - | opt | opt | opt | opt | opt | opt |
| aidl | `android.hardware.wifi.supplicant` | - | opt | opt | opt | opt | opt | opt |
| aidl | `vendor.qti.hardware.bttpi` | 2 | -- | -- | -- | -- | -- | -- |
| aidl | `vendor.qti.hardware.wifi.supplicant` | - | -- | -- | -- | -- | -- | -- |
| hidl | `android.hardware.atrace` | 1.0 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.audio` | 6.0 | opt | opt | -- | -- | -- | -- |
| hidl | `android.hardware.audio.effect` | 6.0 | opt | opt | -- | -- | -- | -- |
| hidl | `android.hardware.biometrics.face` | 1.0 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.biometrics.fingerprint` | 2.1 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.bluetooth` | 1.0 | opt | opt | -- | -- | -- | -- |
| hidl | `android.hardware.boot` | 1.2 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.camera.provider` | 2.4 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.cas` | 1.2 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.drm` | 1.3 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.gatekeeper` | 1.0 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.graphics.allocator` | 3.0, 4.0 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.graphics.composer` | 2.4 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.graphics.mapper` | 3.0, 4.0 | opt | opt | -- | -- | -- | -- |
| hidl | `android.hardware.media.c2` | 1.0 | opt | opt | -- | -- | -- | -- |
| hidl | `android.hardware.media.omx` | 1.0 | opt | opt | -- | -- | -- | -- |
| hidl | `android.hardware.nfc` | 1.2 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.secure_element` | 1.2 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.sensors` | 2.1 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.soundtrigger` | 2.3 | opt | opt | -- | -- | -- | -- |
| hidl | `android.hardware.tetheroffload.config` | 1.0 | opt | opt | -- | -- | -- | -- |
| hidl | `android.hardware.tetheroffload.control` | 1.1 | opt | opt | -- | -- | -- | -- |
| hidl | `android.hardware.thermal` | 1.0, 2.0 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.tv.cec` | 1.0 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.usb` | 1.2 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.usb.gadget` | 1.1 | opt | -- | -- | -- | -- | -- |
| hidl | `android.hardware.wifi` | 1.6 | opt | -- | -- | -- | -- | -- |
| hidl | `com.dsi.ant` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `com.qualcomm.qti.dpm.api` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `com.qualcomm.qti.imscmservice` | 2.2 | -- | -- | -- | -- | -- | -- |
| hidl | `com.qualcomm.qti.uceservice` | 2.3 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.display.color` | 1.5 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.display.config` | 2.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.display.postproc` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.nxp.hardware.nfc` | 2.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.data.factory` | 2.2 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.diaghal` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.esepowermanager` | 1.1 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.alarm` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.bluetooth_audio` | 2.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.bluetooth_sar` | 1.1 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.btconfigstore` | 2.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.cacert` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.camera.postproc` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.capabilityconfigstore` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.data.connection` | 1.1 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.data.iwlan` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.data.latency` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.display.allocator` | 3.0, 4.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.display.composer` | 3.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.display.mapper` | 3.0, 4.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.dsp` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.embmssl` | 1.1 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.factory` | 1.1 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.fm` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.fstman` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.iop` | 2.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.limits` | 1.2 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.mwqemadapter` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.perf` | 2.3 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.power.powermodule` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.qseecom` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.qteeconnector` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.sensorscalibrate` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.servicetracker` | 1.2 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.soter` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.tui_comm` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.umd` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.vpp` | 1.3 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.wifi.wifilearner` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.wifidisplaysession` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.wigig.netperftuner` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.hardware.wigig.supptunnel` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.ims.callinfo` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.ims.factory` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.imsrtpservice` | 3.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.memory.pasrmanager` | 1.1 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.power.pasrmanager` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.qspmhal` | 1.0 | -- | -- | -- | -- | -- | -- |
| hidl | `vendor.qti.spu` | 1.0 | -- | -- | -- | -- | -- | -- |

## Vendor HALs not in any Android 17 matrix

- `aidl:vendor.qti.hardware.bttpi` (2)
- `aidl:vendor.qti.hardware.wifi.supplicant` (no version)
- `hidl:com.dsi.ant` (1.0)
- `hidl:com.qualcomm.qti.dpm.api` (1.0)
- `hidl:com.qualcomm.qti.imscmservice` (2.2)
- `hidl:com.qualcomm.qti.uceservice` (2.3)
- `hidl:vendor.display.color` (1.5)
- `hidl:vendor.display.config` (2.0)
- `hidl:vendor.display.postproc` (1.0)
- `hidl:vendor.nxp.hardware.nfc` (2.0)
- `hidl:vendor.qti.data.factory` (2.2)
- `hidl:vendor.qti.diaghal` (1.0)
- `hidl:vendor.qti.esepowermanager` (1.1)
- `hidl:vendor.qti.hardware.alarm` (1.0)
- `hidl:vendor.qti.hardware.bluetooth_audio` (2.0)
- `hidl:vendor.qti.hardware.bluetooth_sar` (1.1)
- `hidl:vendor.qti.hardware.btconfigstore` (2.0)
- `hidl:vendor.qti.hardware.cacert` (1.0)
- `hidl:vendor.qti.hardware.camera.postproc` (1.0)
- `hidl:vendor.qti.hardware.capabilityconfigstore` (1.0)
- `hidl:vendor.qti.hardware.data.connection` (1.1)
- `hidl:vendor.qti.hardware.data.iwlan` (1.0)
- `hidl:vendor.qti.hardware.data.latency` (1.0)
- `hidl:vendor.qti.hardware.display.allocator` (3.0, 4.0)
- `hidl:vendor.qti.hardware.display.composer` (3.0)
- `hidl:vendor.qti.hardware.display.mapper` (3.0, 4.0)
- `hidl:vendor.qti.hardware.dsp` (1.0)
- `hidl:vendor.qti.hardware.embmssl` (1.1)
- `hidl:vendor.qti.hardware.factory` (1.1)
- `hidl:vendor.qti.hardware.fm` (1.0)
- `hidl:vendor.qti.hardware.fstman` (1.0)
- `hidl:vendor.qti.hardware.iop` (2.0)
- `hidl:vendor.qti.hardware.limits` (1.2)
- `hidl:vendor.qti.hardware.mwqemadapter` (1.0)
- `hidl:vendor.qti.hardware.perf` (2.3)
- `hidl:vendor.qti.hardware.power.powermodule` (1.0)
- `hidl:vendor.qti.hardware.qseecom` (1.0)
- `hidl:vendor.qti.hardware.qteeconnector` (1.0)
- `hidl:vendor.qti.hardware.sensorscalibrate` (1.0)
- `hidl:vendor.qti.hardware.servicetracker` (1.2)
- `hidl:vendor.qti.hardware.soter` (1.0)
- `hidl:vendor.qti.hardware.tui_comm` (1.0)
- `hidl:vendor.qti.hardware.umd` (1.0)
- `hidl:vendor.qti.hardware.vpp` (1.3)
- `hidl:vendor.qti.hardware.wifi.wifilearner` (1.0)
- `hidl:vendor.qti.hardware.wifidisplaysession` (1.0)
- `hidl:vendor.qti.hardware.wigig.netperftuner` (1.0)
- `hidl:vendor.qti.hardware.wigig.supptunnel` (1.0)
- `hidl:vendor.qti.ims.callinfo` (1.0)
- `hidl:vendor.qti.ims.factory` (1.0)
- `hidl:vendor.qti.imsrtpservice` (3.0)
- `hidl:vendor.qti.memory.pasrmanager` (1.1)
- `hidl:vendor.qti.power.pasrmanager` (1.0)
- `hidl:vendor.qti.qspmhal` (1.0)
- `hidl:vendor.qti.spu` (1.0)

## Vendor HALs whose version is not accepted by FCM `7`

None.

Android 17 no longer enforces the `optional` attribute at runtime (deprecated after Android 15); missing HALs are a VTS concern, not a boot-blocking VINTF error. Unknown vendor HALs are ignored with a warning; version mismatches are what produce the "internal problem" dialog.

## Mandatory-looking HALs that appear only in newer matrices (not needed at level 7)

- FCM `8`: `aidl:android.hardware.audio.core`
- FCM `8`: `aidl:android.hardware.audio.effect`
- FCM `8`: `aidl:android.hardware.audio.sounddose`
- FCM `8`: `aidl:android.hardware.automotive.can`
- FCM `8`: `aidl:android.hardware.automotive.remoteaccess`
- FCM `8`: `aidl:android.hardware.automotive.ivn`
- FCM `8`: `aidl:android.hardware.bluetooth`
- FCM `8`: `aidl:android.hardware.boot`
- FCM `8`: `aidl:android.hardware.broadcastradio`
- FCM `8`: `aidl:android.hardware.cas`
- FCM `8`: `aidl:android.hardware.confirmationui`
- FCM `8`: `aidl:android.hardware.gatekeeper`
- FCM `8`: `aidl:android.hardware.radio.sap`
- FCM `8`: `aidl:android.hardware.radio.ims`
- FCM `8`: `aidl:android.hardware.radio.ims.media`
- FCM `8`: `aidl:android.hardware.secure_element`
- FCM `8`: `aidl:android.hardware.tetheroffload`
- FCM `8`: `aidl:android.hardware.thermal`
- FCM `8`: `aidl:android.hardware.tv.hdmi.cec`
- FCM `8`: `aidl:android.hardware.tv.hdmi.earc`
- FCM `8`: `aidl:android.hardware.tv.hdmi.connection`
- FCM `8`: `aidl:android.hardware.tv.input`
- FCM `8`: `aidl:android.hardware.usb.gadget`
- FCM `8`: `aidl:android.hardware.wifi`
- FCM `8`: `native:mapper`
- FCM `202404`: `aidl:android.hardware.audio.core`
- FCM `202404`: `aidl:android.hardware.audio.effect`
- FCM `202404`: `aidl:android.hardware.audio.sounddose`
- FCM `202404`: `aidl:android.hardware.automotive.can`
- FCM `202404`: `aidl:android.hardware.macsec`
- FCM `202404`: `aidl:android.hardware.automotive.remoteaccess`
- FCM `202404`: `aidl:android.hardware.automotive.ivn`
- FCM `202404`: `aidl:android.hardware.bluetooth`
- FCM `202404`: `aidl:android.hardware.bluetooth.ranging`
- FCM `202404`: `aidl:android.hardware.bluetooth.finder`
- FCM `202404`: `aidl:android.hardware.bluetooth.lmp_event`
- FCM `202404`: `aidl:android.hardware.boot`
- FCM `202404`: `aidl:android.hardware.broadcastradio`
- FCM `202404`: `aidl:android.hardware.cas`
- FCM `202404`: `aidl:android.hardware.confirmationui`
- FCM `202404`: `aidl:android.hardware.gatekeeper`
- FCM `202404`: `aidl:android.hardware.security.secretkeeper`
- FCM `202404`: `aidl:android.hardware.media.c2`
- FCM `202404`: `aidl:android.hardware.radio.sap`
- FCM `202404`: `aidl:android.hardware.radio.ims`
- FCM `202404`: `aidl:android.hardware.radio.ims.media`
- FCM `202404`: `aidl:android.hardware.secure_element`
- FCM `202404`: `aidl:android.hardware.security.authgraph`
- FCM `202404`: `aidl:android.hardware.tetheroffload`
- FCM `202404`: `aidl:android.hardware.thermal`
- FCM `202404`: `aidl:android.hardware.threadnetwork`
- FCM `202404`: `aidl:android.hardware.tv.hdmi.cec`
- FCM `202404`: `aidl:android.hardware.tv.hdmi.earc`
- FCM `202404`: `aidl:android.hardware.tv.hdmi.connection`
- FCM `202404`: `aidl:android.hardware.tv.input`
- FCM `202404`: `aidl:android.hardware.usb.gadget`
- FCM `202404`: `aidl:android.hardware.wifi`
- FCM `202404`: `native:mapper`
- FCM `202504`: `aidl:android.hardware.audio.core`
- FCM `202504`: `aidl:android.hardware.audio.effect`
- FCM `202504`: `aidl:android.hardware.automotive.can`
- FCM `202504`: `aidl:android.hardware.macsec`
- FCM `202504`: `aidl:android.hardware.automotive.remoteaccess`
- FCM `202504`: `aidl:android.hardware.automotive.ivn`
- FCM `202504`: `aidl:android.hardware.bluetooth`
- FCM `202504`: `aidl:android.hardware.bluetooth.ranging`
- FCM `202504`: `aidl:android.hardware.bluetooth.socket`
- FCM `202504`: `aidl:android.hardware.bluetooth.finder`
- FCM `202504`: `aidl:android.hardware.bluetooth.lmp_event`
- FCM `202504`: `aidl:android.hardware.boot`
- FCM `202504`: `aidl:android.hardware.broadcastradio`
- FCM `202504`: `aidl:android.hardware.cas`
- FCM `202504`: `aidl:android.hardware.confirmationui`
- FCM `202504`: `aidl:android.hardware.gatekeeper`
- FCM `202504`: `aidl:android.hardware.security.secretkeeper`
- FCM `202504`: `aidl:android.hardware.media.c2`
- FCM `202504`: `aidl:android.hardware.radio.sap`
- FCM `202504`: `aidl:android.hardware.radio.ims`
- FCM `202504`: `aidl:android.hardware.radio.ims.media`
- FCM `202504`: `aidl:android.hardware.secure_element`
- FCM `202504`: `aidl:android.hardware.security.authgraph`
- FCM `202504`: `aidl:android.hardware.security.see.hwcrypto`
- FCM `202504`: `aidl:android.hardware.security.see.storage`
- FCM `202504`: `aidl:android.hardware.security.see.authmgr`
- FCM `202504`: `aidl:android.hardware.tetheroffload`
- FCM `202504`: `aidl:android.hardware.thermal`
- FCM `202504`: `aidl:android.hardware.threadnetwork`
- FCM `202504`: `aidl:android.hardware.tv.hdmi.cec`
- FCM `202504`: `aidl:android.hardware.tv.hdmi.earc`
- FCM `202504`: `aidl:android.hardware.tv.hdmi.connection`
- FCM `202504`: `aidl:android.hardware.tv.input`
- FCM `202504`: `aidl:android.hardware.usb.gadget`
- FCM `202504`: `aidl:android.hardware.virtualization.capabilities`
- FCM `202504`: `aidl:android.hardware.wifi`
- FCM `202504`: `aidl:android.hardware.tv.mediaquality`
- FCM `202504`: `native:mapper`
- FCM `202604`: `aidl:android.hardware.audio.core`
- FCM `202604`: `aidl:android.hardware.audio.effect`
- FCM `202604`: `aidl:android.hardware.automotive.can`
- FCM `202604`: `aidl:android.hardware.macsec`
- FCM `202604`: `aidl:android.hardware.automotive.remoteaccess`
- FCM `202604`: `aidl:android.hardware.automotive.ivn`
- FCM `202604`: `aidl:android.hardware.bluetooth`
- FCM `202604`: `aidl:android.hardware.bluetooth.ranging`
- FCM `202604`: `aidl:android.hardware.bluetooth.socket`
- FCM `202604`: `aidl:android.hardware.bluetooth.gatt`
- FCM `202604`: `aidl:android.hardware.bluetooth.finder`
- FCM `202604`: `aidl:android.hardware.bluetooth.lmp_event`
- FCM `202604`: `aidl:android.hardware.boot`
- FCM `202604`: `aidl:android.hardware.broadcastradio`
- FCM `202604`: `aidl:android.hardware.cas`
- FCM `202604`: `aidl:android.hardware.confirmationui`
- FCM `202604`: `aidl:android.hardware.gatekeeper`
- FCM `202604`: `aidl:android.hardware.motioncontext`
- FCM `202604`: `aidl:android.hardware.npu`
- FCM `202604`: `aidl:android.hardware.security.secretkeeper`
- FCM `202604`: `aidl:android.hardware.media.c2`
- FCM `202604`: `aidl:android.hardware.radio.sap`
- FCM `202604`: `aidl:android.hardware.radio.ims`
- FCM `202604`: `aidl:android.hardware.radio.ims.media`
- FCM `202604`: `aidl:android.hardware.secure_element`
- FCM `202604`: `aidl:android.hardware.security.authgraph`
- FCM `202604`: `aidl:android.hardware.security.see.hwcrypto`
- FCM `202604`: `aidl:android.hardware.security.see.devicestate`
- FCM `202604`: `aidl:android.hardware.security.see.storage`
- FCM `202604`: `aidl:android.hardware.security.see.authmgr`
- FCM `202604`: `aidl:android.hardware.security.timestamp`
- FCM `202604`: `aidl:android.hardware.tetheroffload`
- FCM `202604`: `aidl:android.hardware.thermal`
- FCM `202604`: `aidl:android.hardware.threadnetwork`
- FCM `202604`: `aidl:android.hardware.tv.hdmi.cec`
- FCM `202604`: `aidl:android.hardware.tv.hdmi.earc`
- FCM `202604`: `aidl:android.hardware.tv.hdmi.connection`
- FCM `202604`: `aidl:android.hardware.tv.input`
- FCM `202604`: `aidl:android.hardware.usb.gadget`
- FCM `202604`: `aidl:android.hardware.virtualization.capabilities`
- FCM `202604`: `aidl:android.hardware.wifi`
- FCM `202604`: `aidl:android.hardware.tv.mediaquality`
- FCM `202604`: `native:mapper`
- FCM `202704`: `aidl:android.hardware.audio.core`
- FCM `202704`: `aidl:android.hardware.audio.effect`
- FCM `202704`: `aidl:android.hardware.automotive.can`
- FCM `202704`: `aidl:android.hardware.macsec`
- FCM `202704`: `aidl:android.hardware.automotive.remoteaccess`
- FCM `202704`: `aidl:android.hardware.automotive.ivn`
- FCM `202704`: `aidl:android.hardware.bluetooth`
- FCM `202704`: `aidl:android.hardware.bluetooth.ranging`
- FCM `202704`: `aidl:android.hardware.bluetooth.socket`
- FCM `202704`: `aidl:android.hardware.bluetooth.gatt`
- FCM `202704`: `aidl:android.hardware.bluetooth.finder`
- FCM `202704`: `aidl:android.hardware.bluetooth.lmp_event`
- FCM `202704`: `aidl:android.hardware.boot`
- FCM `202704`: `aidl:android.hardware.broadcastradio`
- FCM `202704`: `aidl:android.hardware.cas`
- FCM `202704`: `aidl:android.hardware.confirmationui`
- FCM `202704`: `aidl:android.hardware.gatekeeper`
- FCM `202704`: `aidl:android.hardware.motioncontext`
- FCM `202704`: `aidl:android.hardware.npu`
- FCM `202704`: `aidl:android.hardware.security.secretkeeper`
- FCM `202704`: `aidl:android.hardware.media.c2`
- FCM `202704`: `aidl:android.hardware.radio.sap`
- FCM `202704`: `aidl:android.hardware.radio.ims`
- FCM `202704`: `aidl:android.hardware.radio.ims.media`
- FCM `202704`: `aidl:android.hardware.secure_element`
- FCM `202704`: `aidl:android.hardware.security.authgraph`
- FCM `202704`: `aidl:android.hardware.security.see.hwcrypto`
- FCM `202704`: `aidl:android.hardware.security.see.devicestate`
- FCM `202704`: `aidl:android.hardware.security.see.storage`
- FCM `202704`: `aidl:android.hardware.security.see.authmgr`
- FCM `202704`: `aidl:android.hardware.security.timestamp`
- FCM `202704`: `aidl:android.hardware.tetheroffload`
- FCM `202704`: `aidl:android.hardware.thermal`
- FCM `202704`: `aidl:android.hardware.threadnetwork`
- FCM `202704`: `aidl:android.hardware.tv.hdmi.cec`
- FCM `202704`: `aidl:android.hardware.tv.hdmi.earc`
- FCM `202704`: `aidl:android.hardware.tv.hdmi.connection`
- FCM `202704`: `aidl:android.hardware.tv.input`
- FCM `202704`: `aidl:android.hardware.usb.gadget`
- FCM `202704`: `aidl:android.hardware.virtualization.capabilities`
- FCM `202704`: `aidl:android.hardware.wifi`
- FCM `202704`: `aidl:android.hardware.tv.mediaquality`
- FCM `202704`: `native:mapper`

