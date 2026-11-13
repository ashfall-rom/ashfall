# Retroid Pocket 5 FOTA (ADUPS/abupdate) — protocol and retail packages

Status: researched 2026-11-13 against the stock RP5 FOTA app from the
pre-release `1.0.0.31` image (`/system/priv-app/FOTA/FOTA.apk`, package
`com.rp.fota`, ADUPS/abupdate iot SDK `2.1.8`).

The short version:

- The app talks to **`https://iotapi.abupdate.com`**, not to `iotapi.adups.com`.
- `1.0.0.31` is a **pre-release project** and its `build.prop` credentials are
  stale: the server answers `1002 The project does not exist` for
  `productId=1723789976`, so the retail update channel cannot see it.
- The live retail project is **`productId=1731375894`** with
  `ro.fota.device=RP5_A133` (the retail `.34` image), not `RP5`.
- The retail project currently serves updates up to **`1.0.0.189`**, and it
  serves a **full 2.43 GB `1.0.0.189` OTA** for source `1.0.0.168`. That full
  package is the most direct route from the pre-release `.31` to `.189`.

This document is read-only research. The helper script performs only device
registration and version check; it never calls download/upgrade/report
endpoints.

## 1. Endpoints and request shape

Base URL from `SDKConfig.init()`:

```
https://iotapi.abupdate.com
```

(Test mode would use `http://iotapi-ota-test.abupdate.com`; the app hard-codes
test mode off. `https://iotapi.adups.com` exists as a legacy constant and uses
a private CA in `assets/adcom.bks`, but this APK's production path selects the
abupdate host.)

### 1.1 Product bootstrap (`/product/obtainProduct`)

Used when a device does not have a productId/productSecret yet. It maps the
firmware identity to the project credentials:

```
POST /product/obtainProduct
Content-Type: application/json

{
  "oem": "Moorechip",
  "models": "RP5_A133",
  "platform": "SM8250",
  "deviceType": "pad_phone",
  "timestamp": 1794600000,
  "sign": "<md5(md5(models + deviceType + oem + platform))>"
}
```

The response `data` is a hex string containing AES-128-ECB ciphertext. The key
is the ASCII bytes of characters 8..23 of the same `sign` (the SDK calls
`sign.substring(8, 24)`); decrypting yields `productId_productSecret`.

This is how the retail project was identified:

```
oem=Moorechip models=RP5_A133 platform=SM8250 deviceType=pad_phone
  -> 1731375894_ed5fd6153a174b9cbcfbbc1f22da7076
```

The pre-release identity `models=RP5` maps to `1729474068`, a project with no
published packages (see §2).

### 1.2 Device registration (`/register/<productId>`)

```
POST /register/<productId>
Content-Type: application/json

{
  "mid": "2f184581",
  "oem": "Moorechip",
  "models": "RP5_A133",
  "platform": "SM8250",
  "deviceType": "pad_phone",
  "sdkversion": "2.1.8",
  "appversion": "1.0.0-20241024_205810",
  "version": "1.0.0.34",
  "networkType": "WIFI",
  "timestamp": 1794600000,
  "sign": "<hmac-md5(mid + productId + timestamp, productSecret)>"
}
```

`mid` is the device serial (`ro.fota.midType=sn`; the app uses
`Build.getSerial()`). Registration returns a device identity:

```json
{"status":1000,"msg":"success","data":{
  "deviceId":"c637a1507627e45d",
  "deviceSecret":"0af56ce6452d0f20a6df697cedefed3c",
  "sha256":"a57780bdb4baaefac4f992663c61ffeba466c8e1abe805e7090a736e9a9112eb"
}}
```

### 1.3 Check version (`/product/<productId>/<deviceId>/ota/checkVersion`)

```
POST /product/<productId>/<deviceId>/ota/checkVersion
Content-Type: application/json

{
  "mid": "2f184581",
  "version": "1.0.0.169",
  "networkType": "WIFI",
  "timestamp": 1794600000,
  "sign": "<hmac-md5(deviceId + productId + timestamp, deviceSecret)>"
}
```

`networkType` is `WIFI`, `mobile`, `2G`, `3G`, `4G` or `no`. On Wi-Fi the app
adds no `cid/lac/mcc/mnc` fields. No cookies, auth headers or API keys are
used; the HMAC and the URL are the authentication.

The response for an available update has this shape (nested objects are
JSON-encoded strings in this deployment):

```json
{"status":1000,"msg":"success","data":{
  "releaseNotes": {"publishDate":"2025-11-29","version":"1.0.0.189",
                   "content":"[{\"country\":\"en\",\"content\":\"...\"}]"},
  "safe": {"encKey":null,"isEncrypt":0},
  "version": {"versionName":"1.0.0.189","versionAlias":"V1.0.0.189",
              "fileSize":27401697,"deltaID":"36702357",
              "deltaUrl":"http://iotdown-jd.mayitek.com/...zip",
              "bakUrl":"http://iotdown-jd.mayitek.com/...zip",
              "md5sum":"e90787b4a22dd694a69d0bedb8e0b6f5",
              "sha":"d628f4a2d44819c9295fa89089dcc86a338d59ca39f077ce3f01a8e32d4cf6ae",
              "segmentMd5":"[{\"num\":0,\"startpos\":0,\"md5\":\"...\",\"endpos\":5242880}]"},
  "policy": {"download":[...],"install":[...],"check":[...]}
}}
```

No new version:

```json
{"status":2101,"msg":"No new updates were found","data":null}
```

Useful statuses: `1000` success, `1001` productId invalid, `1002` project does
not exist, `1004` parameters missing, `2001` sign error, `2101` no new
updates. The API has **no explicit full/delta flag**. For the RP5, a full OTA
is ~2.4 GB and every delta is < 32 MB; confirm by unzipping the package: a
full OTA's `META-INF/com/android/metadata` has no `pre-build` line.

## 2. Why the pre-release `.31` sees nothing

| Firmware | `ro.fota.device` | productId | productSecret | Server result |
|---|---|---|---|---|
| pre-release `1.0.0.31` (build.prop) | `RP5` | `1723789976` | `8b88566349aa461b866f5bab93ea9e7f` | `1002 project does not exist` |
| project returned by `obtainProduct` for `RP5/SM8250/Moorechip/pad_phone` | `RP5` | `1729474068` | `ae7f35e2a6ed47e08ceb638af3d5e7a2` | registers, but **no packages** |
| retail `1.0.0.34` (official full image) | `RP5_A133` | `1731375894` | `ed5fd6153a174b9cbcfbbc1f22da7076` | registers and serves packages |

That is the root cause of "Current version is the latest version": the `.31`
image belongs to a dead/staging project, and the retail channel is keyed by a
different product and model name. There is no online `.31 -> retail` path.

## 3. Offered packages (retail project `1731375894`)

Queried with a synthetic but registered device (`mid=2f184581`). Every row is
an actual `checkVersion` response; sizes and hashes were verified against the
downloaded files.

| Source | Target | Kind | Size (bytes / MiB) | MD5 | SHA-256 (verified) | deltaID | Published | URL |
|---|---|---|---|---|---|---|---|---|
| `1.0.0.34` | `1.0.0.51` | delta | 12,199,282 / 11.6 | `86844622673f22d8c41e0a41990c2072` | `5d177fbfcde17ee3cf9970712c9d6b00e37a7e555890f749d819630303a0a009` | `25639224` | 2024-11-28 | `http://iotdown-jd.mayitek.com/1731375894/25639224/8147a56d-ea47-41f4-800c-ee12fdc2b344.zip` |
| `1.0.0.51` | `1.0.0.85` | delta | 31,718,524 / 30.3 | `6b9756533dcea00d2742de627d973152` | `9abc177729335f26fbe5bbcd9823ca8d32870d2fd1901a0bd3aca9c158b51d1e` | `27581807` | 2025-01-22 | `http://iotdown-jd.mayitek.com/1731375894/27581807/716b8522-7778-4037-8c7e-51b7614fca92.zip` |
| `1.0.0.85` | `1.0.0.152` | delta | 24,712,045 / 23.6 | `1cc30d00a00afc3ce88177d9878557ae` | `6bdedbcc7ebc27318bc5c1067ff68d1b4377cf53f560dc4b38ba0fefc35f967a` | `31922945` | 2025-06-19 | `http://iotdown-jd.mayitek.com/1731375894/31922945/fd9594b4-02f0-46d5-96f6-3d1d2202723d.zip` |
| `1.0.0.91` | `1.0.0.152` | delta | 23,707,723 / 22.6 | `e32574ef25323335338971b267cbe8de` | `4ccac2847a68eb0bf419c65ed7b60fb506027d33ab8e991ded50d57e5cf924ac` | `31922946` | 2025-06-19 | `http://iotdown-jd.mayitek.com/1731375894/31922946/1bfe8c9b-9ec4-4836-b640-dec7b035f035.zip` |
| `1.0.0.106` | `1.0.0.152` | delta | 21,640,944 / 20.6 | `0187acfebf86b456da88ba18d24c019c` | `e48b69d6394cd6403dd4f9165b9f352ef5c9bef4ae6eaf7d23d15722c14882c8` | `31922949` | 2025-06-19 | `http://iotdown-jd.mayitek.com/1731375894/31922949/c058dd8b-3d4e-4a26-9944-6b4ec7b23248.zip` |
| `1.0.0.152` | `1.0.0.169` | delta | 14,749,106 / 14.1 | `9ceca930103fe40e7283dba1a6b05cde` | `c331b14a786f0072d24f3cc139d06eee8056bb67a9bd4c5db614426f3ceb53c4` | `33647748` | 2025-08-28 | `http://iotdown-jd.mayitek.com/1731375894/33647748/0848b345-1f79-41c8-8c7f-e2cf457e61d7.zip` |
| `1.0.0.168` | `1.0.0.189` | **full** | 2,433,977,113 / 2321.2 | `33cdd9862b9eadbac3e14d4eca7dc32b` | `80f16fd5eb0477671c4d3e760a5afce7f4dc227e417cffff159e4a7424a4148f` | `36702356` | 2025-11-29 | `http://iotdown-jd.mayitek.com/1731375894/36702356/959cd801-32ca-4604-8912-11c62b017b80.zip` |
| `1.0.0.169` | `1.0.0.189` | delta | 27,401,697 / 26.1 | `e90787b4a22dd694a69d0bedb8e0b6f5` | `d628f4a2d44819c9295fa89089dcc86a338d59ca39f077ce3f01a8e32d4cf6ae` | `36702357` | 2025-11-29 | `http://iotdown-jd.mayitek.com/1731375894/36702357/0a41a775-f927-49fb-a3e3-045f09f6a5b9.zip` |

No package was offered for these sources (status `2101`):

`1.0.0.31`, `1.0.0.167`, `1.0.0.189`.

Notes:

- `.167` is a version seen in the wild, but this project has no package for it.
  `.189` is the latest, so it returns "no new updates".
- `.91`, `.106` and `.85` all jump straight to `.152`; only `.34` and `.51`
  chain through `.51`/`.85`.
- The API `sha` field for the `.34 -> .51` package is a 40-character SHA-1
  (`7318b21498c5d3eba8a2c6dae70f79520f6a7a03`), unlike every other row where
  it is a 64-character SHA-256. The table above lists the verified SHA-256 of
  the file; use that plus the MD5, or verify the API SHA-1 for this one row.

## 4. Package contents and signatures

Every downloaded package is a standard A/B `update_engine` zip:

```
META-INF/com/android/metadata
META-INF/com/android/metadata.pb
META-INF/com/android/otacert
apex_info.pb
care_map.pb
payload.bin
payload_properties.txt
```

The full `.189` package (`1.0.0.168 -> 1.0.0.189`) has:

- `payload.bin` = 2,433,971,162 bytes; `FILE_SIZE=2433971162`
- metadata `post-build=qti/kona/kona:13/TKQ1.240621.001/eng.RP5.20251128.173135:user/release-keys`
- **no `pre-build`** line (full OTA, source-independent by design)
- payload partitions: `boot`, `dtbo`, `odm`, `product`, `system`, `system_ext`,
  `vbmeta`, `vbmeta_system`, `vendor`
- `otacert` subject `C=CH, ST=Guangdong, L=Shenzhen, O=Moorechip, OU=Moorechip,
  CN=Moorechip, emailAddress=liuwen@moorechip.net`, SHA-256 fingerprint
  `9D:DA:35:10:DD:8A:0F:32:A1:D3:80:1D:79:65:7B:0A:E4:0F:B1:B9:ED:A6:74:9C:3B:F8:F9:DD:CA:FB:80:91`

That fingerprint is identical to `releasekey.x509.pem` in the retail `.34`
image's `/system/etc/security/otacerts.zip`, so the full `.189` OTA is signed
by the same platform release key as retail RP5 firmware. It is **not** the
same certificate as the FOTA APK's signing cert (that is a different Moorechip
app key), which is expected.

The `.34 -> .51` delta metadata has `pre-build=...eng.RP5.20241118.141622...`
and `post-build=...eng.RP5.20241127.215442...`; the other deltas similarly
name their exact source and target builds. Deltas are only valid from the
named source version.

## 5. Getting from `1.0.0.31` to `1.0.0.189`

### Option A (recommended): local update with the full `.189` package

The `.168 -> .189` artifact is a full OTA, not a delta:

1. Copy `1.0.0.168-full-1.0.0.189.zip` to the RP5 as
   `/sdcard/update.zip` (or any file the FOTA file picker can reach).
2. Open **Settings > System > Retroid Pocket FOTA > menu > Local update**.
3. Select the zip and confirm. The app parses `payload.bin` /
   `payload_properties.txt` and hands it to `update_engine` (`ota-type=AB`,
   virtual A/B is enabled on the RP5).
4. Reboot when prompted. The inactive slot is written; the device should come
   up as `1.0.0.189`.

Why this can work from `.31`: the full OTA has no `pre-build` or `pre-device`
constraint, includes a complete set of OS partitions plus `boot`/`dtbo`, and
is signed with the retail release key. The remaining uncertainty is whether
the pre-release `.31` system trusts that same release key (see risks).

### Option B (fallback): flash retail `.34`, then take the online chain

If local update rejects the full package (signature, anti-rollback or
partition-layout check), put the device on a known retail base first:

1. Flash the official retail `.34` full QFIL image from
   `/root/archive/fota/RP5_20241118.7z` (build
   `RP5_V1.0.0.34_20241118_143604_user`) with QFIL/EDL. This is a full
   QFIL/EDL package, not an OTA zip, and it rewrites the partition set.
2. Boot retail `.34` and use the normal online FOTA chain:
   `.34 -> .51 -> .85 -> .152 -> .169 -> .189`.
   The server returns the newest reachable target for each source, so the
   shortest online chain is the five hops above.
3. Alternatively, after `.34`, local-update the full `.189` package directly
   (Option A), which avoids the intermediate hops.

The `.34` QFIL package itself is not an OTA; do not rename it to
`update.zip`. It is only the fallback for getting onto the retail project.

### Why not just query `.31` on the retail project?

`checkVersion` with `version=1.0.0.31` against project `1731375894` returns
`2101 No new updates were found`; `.31` is not a published source in the
retail project. The server has no `.31 -> retail` delta. The only ways across
the project boundary are a full package or a full-image flash.

## 6. Risks and caveats

- **Signing-key mismatch on `.31`.** The full `.189` OTA is signed with the
  retail release key (fingerprint above). The retail `.34` image trusts it;
  the pre-release `.31` image probably does too (same Moorechip release-keys
  vendor), but that was not verified because the `.31` full image was not
  available here. Before flashing, compare
  `/system/etc/security/otacerts.zip` on the running `.31` with the fingerprint
  in §4. If it differs, local update will fail signature verification and
  Option B is required.
- **Anti-rollback.** `.189` `vbmeta` may carry a higher rollback index than
  `.31`. Once `.189` boots, downgrading to `.31`/`.34` may be refused. Keep the
  EDL/QFIL restore path and a data backup.
- **Partition layout.** The full OTA includes dynamic partitions and
  `boot`/`dtbo`, but not `vendor_boot`/`init_boot`/bootloader/modem. The RP5
  `.31` and `.189` are both Android 13 `kona` builds; the kernel/boot chain is
  expected to be compatible, but this is the second thing to verify on
  hardware if the device bootloops.
- **`.168` full package is a special case.** The only full `.189` package we
  found is offered for `.168`. The payload is a normal full OTA, so it should
  be source-independent, but it was published for `.168`. If it refuses `.31`,
  use Option B.
- **HTTP download URLs.** Package URLs are plain HTTP on
  `iotdown-jd.mayitek.com`. Always verify the MD5 and SHA-256 from §3 before
  flashing; do not trust the transport.
- **API is rate-limited / stateful.** Registration creates a device record.
  Don't poll in a loop. Reuse `--device-id`/`--device-secret` for repeated
  checks. Never call `reportDownResult`, `reportUpgradeResult`,
  `reportErrorLog`, `reportBehavior`, `notify/response`, or the MQTT
  login/logout endpoints from a research client.
- **Package URLs are per-project and per-delta.** The `iotdown-jd` links are
  returned to the registered client; they worked at research time but may
  expire or be rotated.

## 7. Reproducing the queries

```bash
# Retail RP5 default profile, source version 1.0.0.169
python3 scripts/firmware/fota-check.py --version 1.0.0.169

# Pre-release/dead project (expected: 2101 no updates)
python3 scripts/firmware/fota-check.py --version 1.0.0.31 \
  --product-id 1729474068 --product-secret ae7f35e2a6ed47e08ceb638af3d5e7a2

# Any project, without registering again (two fewer requests)
python3 scripts/firmware/fota-check.py --version 1.0.0.34 \
  --device-id c637a1507627e45d --device-secret 0af56ce6452d0f20a6df697cedefed3c

# Legacy ADUPS host uses a private CA; only if you really need it:
python3 scripts/firmware/fota-check.py --base-url https://iotapi.adups.com \
  --insecure --version 1.0.0.169
```

The script defaults to the retail RP5 project (`1731375894`,
`RP5_A133`). Use `--product-id` / `--product-secret` / `--oem` / `--model` for
other projects. It prints a normalized JSON object; `package.kind` is a size
heuristic (`full` for >= 1 GB, `delta` otherwise), not a server flag.

## 8. Artifacts

Downloaded for verification (kept outside the repository, in
`/root/archive/fota/`):

- `/root/archive/fota/RP5_20241118.7z` — official retail `.34` QFIL image
  (2.4 GB, SHA-256 `81dc021bfdde867e77b5fa5555fbcca8e9bfd7783612d6154ca7a2ae1cc79d70`)
- `/root/archive/fota/rp5-ota/1.0.0.34-to-1.0.0.51-delta.zip`
- `/root/archive/fota/rp5-ota/1.0.0.51-to-1.0.0.85-delta.zip`
- `/root/archive/fota/rp5-ota/1.0.0.85-to-1.0.0.152-delta.zip`
- `/root/archive/fota/rp5-ota/1.0.0.152-to-1.0.0.169-delta.zip`
- `/root/archive/fota/rp5-ota/1.0.0.169-to-1.0.0.189-delta.zip`
- `/root/archive/fota/rp5-ota/1.0.0.168-full-1.0.0.189.zip`

The `.91 -> .152` and `.106 -> .152` deltas were not downloaded (they are
redundant with the `.85 -> .152` hop for a `.34`-based device); their hashes
and URLs are in §3.

None of these binaries, nor any decompiled app code, belong in this
repository.
