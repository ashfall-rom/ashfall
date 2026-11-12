# Retroid Pocket 5: Unbrick and Restore-to-Stock Guide

> *"Yo chat, this RP5 unbrick is MASSIVE."* A full write-up of how the brick was fixed on 2026-11-12, plus a repeatable recipe for the next time.

---

## Table of contents

1. [TL;DR, the 30-second version](#1-tldr-the-30-second-version)
2. [What was actually wrong](#2-what-was-actually-wrong)
3. [Timeline of the repair](#3-timeline-of-the-repair)
4. [Your setup (remote machine, Docker, Proxmox)](#4-your-setup-remote-machine-docker-proxmox)
5. [Button combos (memorize these)](#5-button-combos-memorize-these)
6. ["What do I see?" → "What do I do?"](#6-what-do-i-see--what-do-i-do)
7. [Prerequisites: toolbox container, firmware, USB 2.0](#7-prerequisites-toolbox-container-firmware-usb-20)
8. [Procedure A: reflash Android over fastboot](#8-procedure-a-reflash-android-over-fastboot)
9. [Procedure B: kill U-Boot / ROCKNIX leftovers (abl + devinfo)](#9-procedure-b-kill-u-boot--rocknix-leftovers-abl--devinfo)
10. [Procedure C: flash the matching firmware set](#10-procedure-c-flash-the-matching-firmware-set)
11. [Procedure D: "Cannot load Android system" in recovery](#11-procedure-d-cannot-load-android-system-in-recovery)
12. [EDL (Qualcomm 9008) deep dive](#12-edl-qualcomm-9008-deep-dive)
13. [Partitions you must NEVER flash from someone else's dump](#13-partitions-you-must-never-flash-from-someone-elses-dump)
14. [Backups: what exists, and how to restore it](#14-backups-what-exists-and-how-to-restore-it)
15. [Forensics toolkit (how the root cause was found)](#15-forensics-toolkit-how-the-root-cause-was-found)
16. [Error message → meaning → fix](#16-error-message--meaning--fix)
17. [Host machine changes and cleanup](#17-host-machine-changes-and-cleanup)
18. [After the fix: updates, ROCKNIX, staying safe](#18-after-the-fix-updates-rocknix-staying-safe)
19. [Reference: device facts and partition table](#19-reference-device-facts-and-partition-table)
20. [Links](#20-links)

---

## 1. TL;DR, the 30-second version

**Symptom:** the RP5 booted to a **U-Boot** text screen ("EFI boot manager: Cannot load any image") instead of Android.

**Root cause:** a ROCKNIX / OpenABL install had stored its own boot settings (`RocknixAblVer 1.1.7`, `BootMode`, `BootSourceMode`, `DevMode`, `AltVolumeUp`, ...) inside the **`devinfo`** partition. Reflashing stock firmware does **not** touch `devinfo`, and Retroid's stock bootloader read those leftovers as "boot Loader Mode". Loader Mode is Retroid's own U-Boot in the `loader` partition, which looks for Linux and finds nothing.

**Fix, in order:**

1. Flash the stock Android images (boot, vendor_boot, super, ...) over **fastboot**.
2. Flash the stock **`abl`** (Android bootloader) to both slots.
3. Over **EDL**, zero the ROCKNIX block in **`devinfo`**. **This was the real fix.**
4. Flash the matching **Qualcomm firmware** set (tz, keymaster, hyp, modem, ...) so it matches the Android build, and erase `metadata`.
5. In recovery, answer the "Cannot load Android system" prompt with **Try again**.
6. Android boots. The device showed up as `18d1:4ee1` (MTP), which means normal Android was running. 🎉

**The hidden boss:** EDL **does not work over USB 3 passthrough** into the Proxmox VM. Switching the VM's USB passthrough to **USB 2.0** made EDL work instantly.

---

## 2. What was actually wrong

### 2.1 The boot chain on an RP5 (Snapdragon 865 / SM8250, codename `kona`)

```
 Power on
   │
   ▼
 PBL (Primary Boot Loader, in ROM, can't be changed) ──► EDL mode (05c6:9008) if requested
   │
   ▼
 XBL + xbl_config  (partitions on UFS LUN1/LUN2)
   │
   ▼
 UEFI core ──► ABL ("abl" partition, Qualcomm LinuxLoader, Retroid-modified)
                 │
                 ├── Vol Down held ─────────────► fastboot mode (18d1:d00d)
                 ├── recovery requested ────────► recovery_a/_b
                 ├── "Loader Mode" (devinfo!) ──► "loader" partition = Retroid's U-Boot 2024.10
                 │                                   └─► bootefi bootmgr → looks for Linux/EFI → fails
                 └── normal ───────────────────► boot_a + init_boot_a + vendor_boot_a + dtbo_a
                                                     └─► Android 13 (super = system/vendor/product)
```

### 2.2 Why stock reflashing alone didn't fix it

- `devinfo` is a tiny (4 KB) per-device partition. It holds the unlock state, the charger-screen flag, rollback info, and also whatever a third-party bootloader decides to store there.
- **OpenABL / ROCKNIX ABL v1.1.7** stores its settings as named records inside `devinfo`, starting around offset `0x998`.
- Stock flashing guides **don't touch `devinfo`**, and they shouldn't flash someone else's copy either, because it's device-specific.
- So after flashing stock `abl` (verified byte-identical over EDL!), the device **still** booted to U-Boot. The stock ABL includes the string `Booting Into Loader Mode`, and the leftover records sent it there.

### 2.3 Byte diff of `devinfo` (your device vs. a clean unit)

```
 @0x0   dev:   41 4e 44 52 4f 49 44 2d 42 4f 4f 54 21 01 01 01   ANDROID-BOOT!...
        stock: 41 4e 44 52 4f 49 44 2d 42 4f 4f 54 21 01 01 00   (byte 0xF = charger screen flag → KEEP)
 @0x990 dev:   ... 52 6f 63 6b 6e 69 78 41 62 6c 56 65 72 ...    RocknixAblVer
 @0x9d0 dev:   ... 31 2e 31 2e 37 ...                            1.1.7
 @0x9f0 dev:   ... 42 6f 6f 74 53 6f 75 72 63 65 4d 6f 64 65     BootSourceMode
 @0xa50 dev:   ... 4d 6f 64 65 6c 53 65 6c 65 63 74 69 6f 6e     ModelSelection
 @0xa90 dev:   ... Retroid Pocket 5
 @0xab0 dev:   ... 44 65 76 4d 6f 64 65                          DevMode
 @0xb10 dev:   ... 42 6f 6f 74 4d 6f 64 65                       BootMode
 @0xb60 dev:   ... 41 6c 74 56 6f 6c 75 6d 65 55 70              AltVolumeUp
 @0xbc0 dev:   ... 53 68 6f 77 4d 6f 64 65 6c 4d 65 6e 75        ShowModelMenu
        stock: all zeros from 0x900 to the end
```

Every difference was either header byte `0xF` (kept) or within `0x998 – 0xCA0` (zeroed).

### 2.4 Second problem: firmware/Android mismatch

Your RP5 had **newer retail Qualcomm firmware** (tz, keymaster, hyp, modem, dsp, aop, ...) than the October 2024 Android build in the dump. On this Android, `/data` uses **metadata encryption with hardware-wrapped keys**:

```
/dev/block/bootdevice/by-name/userdata  /data  f2fs  ...  latemount,wait,check,formattable,
  fileencryption=aes-256-xts:aes-256-cts:v2+inlinecrypt_optimized+wrappedkey_v0,
  metadata_encryption=aes-256-xts:wrappedkey_v0,keydirectory=/metadata/vold/metadata_encryption, ...
```

Hardware-wrapped keys depend on the keymaster code inside TrustZone, so we flashed the firmware set **from the same package** as the Android build. Together with answering recovery's prompt, Android booted.

---

## 3. Timeline of the repair

| Time (UTC) | What happened |
|---|---|
| ~17:00 | Connected to `ai@192.168.1.50`. RP5 seen as `18d1:d00d` (fastboot). Bootloader **unlocked**, slot `a`, product `kona`. |
| 17:0x | `fastboot getvar all` got interrupted, which **wedged** fastboot. Hard power-off, then Vol Down + Power fixed it. |
| 17:0x | Moved everything into a Docker container (`rp5`) at your request. Removed the host packages installed before that. |
| 17:12–17:32 | Downloaded the 3.1 GB stock dump `Android13_RP5_V1.0.0.31_20241026_190102_user` (5 × `.7z` parts). `7z t` → *Everything is Ok*. |
| 17:3x | Partition sizes checked against `fastboot getvar partition-size:*`. All matched. |
| 17:3x–17:4x | Flashed the OS partitions to both slots plus `super` (12 GB, 7 sparse chunks, 91 s). `erase userdata` (351 s), `erase metadata`, `set_active a`. **FLASH_OK**. |
| 17:4x | Boot → **U-Boot screen** (your photo). |
| 17:4x | Flashed stock `abl_a` / `abl_b` over fastboot → **still U-Boot**. |
| 17:5x–18:2x | Tried EDL. It kept hanging at `Device detected :)` with `Timed out`. Culprits: the host `qcserial` driver grabbing the device, and **USB 3 passthrough in the VM**. |
| 18:25 | You switched Proxmox USB passthrough to **USB 2.0**. EDL handshake worked at 29 MB/s. |
| 18:26 | EDL readback: `abl`, `boot`, `init_boot`, `vendor_boot`, `dtbo`, `vbmeta` **all match stock**, so the fastboot writes were real. |
| 18:2x | Read every firmware partition. The boot chain matched stock, and the Qualcomm blobs differed (newer retail firmware). Found Retroid's **U-Boot inside the `loader` partition**. |
| 18:3x | Decompressed ABL and found `Booting Into Loader Mode`. Diffed `misc` (identical) and `devinfo` (**ROCKNIX block found**). |
| 18:32 | Backed up `devinfo`, wrote a cleaned copy, read it back → **VERIFIED**. `edl reset`. |
| 18:32 | Boot → **Android recovery** (`TKQ1.240621.001`) over adb. No more U-Boot. |
| 18:3x | Recovery: "Cannot load Android system". Read the fstab from vendor_boot: f2fs + wrapped-key encryption. |
| 18:4x | Backed up your 26 firmware partitions, then flashed the matching firmware set to both slots (26/26 OK). `erase metadata`, reboot. |
| 18:4x | Recovery again. You picked **Try again** → **Android booted** (`18d1:4ee1` MTP). ✅ |

---

## 4. Your setup (remote machine, Docker, Proxmox)

| Thing | Value |
|---|---|
| Remote machine | `192.168.1.50`, user `ai`, Ubuntu 26.04, a **Proxmox VM** |
| USB passthrough | Must be **USB 2.0** for EDL. USB 3 worked for fastboot but **broke EDL**. |
| Toolbox container | `rp5`, image `rp5tools`, run with `--privileged -v /dev/bus/usb:/dev/bus/usb` |
| Data volume | `rp5work` → host path `/var/lib/docker/volumes/rp5work/_data` (inside the container: `/work`) |
| Dockerfile | `/home/ai/rp5build/Dockerfile` |
| EDL tool | `bkerler/edl`, in the container at `/opt/edlenv/bin/edl` |

### 4.1 Handy shell helper (put it in `~/.bashrc` on the remote)

```bash
rp5() { sudo docker exec -it rp5 "$@"; }      # run a command inside the toolbox
rp5sh() { sudo docker exec -it rp5 bash; }     # get a shell inside the toolbox
```

Example: `rp5 fastboot devices`, `rp5 adb devices`, `rp5sh`.

### 4.2 Proxmox USB passthrough

Proxmox docs: <https://pve.proxmox.com/wiki/USB_Devices_in_Virtual_Machines>

- In the VM's **Hardware → USB Device** entry for the Retroid, **untick "Use USB3"**. In `/etc/pve/qemu-server/<vmid>.conf`, that's the `usb3=1` flag on the `usbN:` line.
- Passing through by **port** (not vendor/product ID) is more robust. The RP5 changes its USB ID depending on mode:
  - `18d1:d00d` = fastboot (bootloader)
  - `05c6:9008` = EDL
  - `18d1:d001` = recovery (adb)
  - `18d1:4ee1` = Android MTP
  - If you pass through by ID, the device vanishes from the VM every time it switches mode.
- After changing it, `lsusb -t` on the VM should show the device at **480M** (high-speed), not 5000M.

> I don't know exactly which option you changed in Proxmox. The effect was that the device enumerated as `new high-speed USB device ... using xhci_hcd` (USB 2.0) instead of `new SuperSpeed USB device`, and EDL started working right away. Keep it that way, or at least switch to it before using EDL.

---

## 5. Button combos (memorize these)

| Goal | How |
|---|---|
| **Hard power-off** | Hold **Power ~15 s** until the screen is fully off |
| **Fastboot (bootloader)** | From off: hold **Volume Down + Power** until the fastboot screen appears |
| **EDL (most reliable)** | **Unplug USB** → power off (Power 15 s) → hold **Volume Up + Volume Down** and **plug USB in** → keep holding ~5 s → release. The screen stays **black**. |
| **EDL (alternative, from the package README)** | With USB plugged in: hold **Power + Volume Up + Volume Down** ~10 s. Screen black. |
| **Leave EDL** | `edl reset --loader=...` (best), or unplug and hold Power 15 s. **Holding Power alone did NOT always work in EDL.** |
| **Recovery menu navigation** | Volume keys move, Power selects |
| **ROCKNIX U-Boot menu** (if you ever reinstall ROCKNIX) | Hold **Volume Up** during restart (per the ROCKNIX RP5 guides) |

---

## 6. "What do I see?" → "What do I do?"

| You see | Meaning | Go to |
|---|---|---|
| U-Boot text, "EFI boot manager: Cannot load any image", submarine logo | ABL is booting the `loader` partition (Loader Mode). Likely `devinfo` leftovers or a non-stock `abl`. | [§9](#9-procedure-b-kill-u-boot--rocknix-leftovers-abl--devinfo) |
| Fastboot screen, `fastboot devices` works | Bootloader is fine | [§8](#8-procedure-a-reflash-android-over-fastboot) |
| `fastboot devices` lists it but every `getvar` hangs | Fastboot session wedged | Hard power-off, Vol Down + Power |
| Retroid logo forever / bootloop | OS images broken or mismatched | [§8](#8-procedure-a-reflash-android-over-fastboot), then [§10](#10-procedure-c-flash-the-matching-firmware-set) |
| Android recovery: "Cannot load Android system. Try again / Factory data reset" | Android failed to start, usually /data or encryption trouble | [§11](#11-procedure-d-cannot-load-android-system-in-recovery) |
| Black screen, `lsusb` shows `05c6:9008` | EDL mode | [§12](#12-edl-qualcomm-9008-deep-dive) |
| Black screen, nothing on USB | Off, or a dead battery | Charge 30 min, then try the fastboot combo, then the EDL combo |
| Android boots | 🎉 You're done | [§18](#18-after-the-fix-updates-rocknix-staying-safe) |

---

## 7. Prerequisites: toolbox container, firmware, USB 2.0

### 7.1 Is the container still there?

```bash
sudo docker ps -a --format '{{.Names}} {{.Status}}'        # expect: rp5 Up ...
sudo docker volume ls | grep rp5work
```

If `rp5` is stopped: `sudo docker start rp5`.

### 7.2 Rebuild the container from scratch (if it's gone)

`/home/ai/rp5build/Dockerfile` (updated at the end of this session to include EDL):

```dockerfile
FROM ubuntu:26.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      fastboot adb curl ca-certificates unzip p7zip-full python3 python3-pip python3-venv python3-dev \
      git build-essential libusb-1.0-0 usbutils lz4 cpio xxd \
 && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/edlenv \
 && git clone -q https://github.com/bkerler/edl /opt/edl \
 && cd /opt/edl && git submodule update --init -q \
 && /opt/edlenv/bin/pip install -q . \
 && ln -s /opt/edlenv/bin/edl /usr/local/bin/edl
WORKDIR /work
```

> ⚠️ I updated this Dockerfile and started a test build, but you asked me to stop before I checked the result, so **this exact Dockerfile hasn't been confirmed to build**. Every step in it was run by hand inside the current `rp5` container and worked. Gotchas found along the way:
> - Use **`fastboot adb`**, not `android-sdk-platform-tools` with `--no-install-recommends`. The latter silently leaves out fastboot.
> - `edl` needs **`build-essential python3-dev`**, or `pylzma` fails to build.

Build and run:

```bash
sudo docker build -t rp5tools /home/ai/rp5build
sudo docker volume create rp5work
sudo docker run -d --name rp5 --privileged \
  -v /dev/bus/usb:/dev/bus/usb -v rp5work:/work rp5tools sleep infinity
rp5 fastboot --version
rp5 edl --help | head
```

### 7.3 Keep the host's `qcserial` driver away from EDL

When the RP5 enters EDL, the host kernel's `qcserial` driver grabs it and **eats the one-time Sahara "hello"** packet. EDL then hangs at `Device detected :)`. Block it (temporary: `/run` is wiped on reboot):

```bash
sudo mkdir -p /run/modprobe.d
echo "install qcserial /bin/true" | sudo tee /run/modprobe.d/rp5-no-qcserial.conf
sudo modprobe -r qcserial usb_wwan 2>/dev/null
lsmod | grep qcserial || echo "qcserial not loaded ✔"
```

> `modprobe -r` alone is **not enough**: udev reloads the module the next time the device connects. That's why the `/run/modprobe.d` rule exists.

### 7.4 Firmware: where it comes from

There is **no official RP5 firmware download from Retroid** (their [software page](https://www.goretroid.com/pages/software) only has Pocket 2 files). The source used:

- **TheGammaSqueeze / Retroid_Pocket_Stock_Firmware**, release `Android13_RP5_V1.0.0.31_20241026_190102_user`
  <https://github.com/TheGammaSqueeze/Retroid_Pocket_Stock_Firmware/releases>
  - The author labels it **"PRE-RELEASE FIRMWARE, NOT INTENDED FOR RETAIL UNITS"**. It still worked on your retail unit, with the steps in this guide.
  - It's a **raw per-partition dump** (`flash/lunX_<name>.bin`), plus the firehose loader `prog_ufs_firehose_sm8250_lite_lp5.elf` and a Windows QFIL helper.
- Avoided: `Retroid_Pocket_5_20250121_QFIL.zip` on firmwaredrive.com / naijarom.com (unverifiable mirrors).

Already downloaded to `/work/dl/fw.7z.001 … .005` (3.0 GB). To re-download:

```bash
rp5 sh -c '
mkdir -p /work/dl && cd /work/dl
B=https://github.com/TheGammaSqueeze/Retroid_Pocket_Stock_Firmware/releases/download/Android13_RP5_V1.0.0.31_20241026_190102_user/Android13_RP5_V1.0.0.31_20241026_190102_user.7z
for i in 001 002 003 004 005; do curl -fL -C - -o fw.7z.$i $B.$i || exit 1; done
ls -l; 7z t fw.7z.001 | grep -E "Ok|Error"'
```

Expected sizes: `.001`–`.004` = **734003200** bytes each, `.005` = **182108376** bytes.

> 💡 Downloads run at ~2 MB/s (~25 min). Run long jobs with `docker exec -d` (detached) so an SSH drop doesn't kill them. `curl -C -` resumes where it left off.

### 7.5 What's inside the archive (123 files, 15.7 GB unpacked)

```
flash/lun0_*.bin   super (12 GB), vbmeta_system_a/b, vm-system_a/b, persist, frp, keystore, misc, metadata, nvdata*, ...
flash/lun1_*.bin   xbl_a, xbl_config_a, gpt_header, last_parti
flash/lun2_*.bin   xbl_b, xbl_config_b, ...
flash/lun3_*.bin   cdt, ddr, mdmddr, ...                 ← device-specific, NEVER flash
flash/lun4_*.bin   abl, aop, boot, cmnlib*, devcfg, devinfo, dsp, dtbo, featenabler, hyp, imagefv,
                   init_boot, keymaster, loader, modem, multiimg*, qupfw, recovery, splash, tz,
                   uefisecapp, uefivarstore, vbmeta, vendor_boot, vm-linux, ...
flash/lun5_*.bin   fsg, fsc, modemst1/2, mdm1m9kefs*      ← device-specific (radio), NEVER flash
prog_ufs_firehose_sm8250_lite_lp5.elf                     ← the EDL loader
README.txt, QFILHelper.exe, qpst...zip, Qualcomm_USB_Driver...zip
```

Extract only what you need (example: the OS images):

```bash
rp5 sh -c 'cd /work/dl && 7z e -y -o/work/img fw.7z.001 README.txt prog_ufs_firehose_sm8250_lite_lp5.elf \
  flash/lun0_super.bin "flash/lun0_vbmeta_system_*" "flash/lun0_vm-system_*" \
  "flash/lun4_boot_*" "flash/lun4_init_boot_*" "flash/lun4_vendor_boot_*" "flash/lun4_dtbo_*" \
  "flash/lun4_vbmeta_?.bin" "flash/lun4_recovery_*" "flash/lun4_vm-linux_*" flash/lun4_splash.bin \
  "flash/lun4_abl_*"'
```

Already extracted on your machine: `/work/img` (OS images + abl + firehose) and `/work/stock` (firmware, misc, devinfo, loader, ...).

---

## 8. Procedure A: reflash Android over fastboot

Put the device in **fastboot** (Vol Down + Power).

### 8.1 Sanity checks first

```bash
rp5 fastboot devices                        # expect: 2f184581  fastboot
for v in product current-slot slot-count unlocked secure; do rp5 fastboot getvar $v 2>&1 | head -1; done
# product: kona / current-slot: a / slot-count: 2 / unlocked: yes / secure: no
```

> ⚠️ **Don't run `fastboot getvar all` and then Ctrl-C it.** Interrupting it wedged the bootloader: `devices` still worked, but every command hung. If that happens, power off hard (15 s) and go back into fastboot.

### 8.2 Compare image sizes with the partitions (paranoia that pays off)

```bash
rp5 sh -c 'for p in boot_a vendor_boot_a init_boot_a dtbo_a recovery_a vbmeta_a vbmeta_system_a vm-linux_a vm-system_a splash super; do
  timeout 10 fastboot getvar partition-size:$p 2>&1 | head -1; done
  timeout 10 fastboot getvar max-download-size 2>&1 | head -1'
```

| Partition | Size | Image |
|---|---|---|
| boot_a/b | 0x6000000 (100663296) | lun4_boot_*.bin ✔ |
| vendor_boot_a/b | 0x6000000 | lun4_vendor_boot_*.bin ✔ |
| init_boot_a/b | 0x800000 | lun4_init_boot_*.bin ✔ |
| dtbo_a/b | 0x1800000 | lun4_dtbo_*.bin ✔ |
| recovery_a/b | 0x6400000 | lun4_recovery_*.bin ✔ |
| vbmeta_a/b, vbmeta_system_a/b | 0x10000 | ✔ |
| vm-linux_a/b | 0x2000000 | ✔ |
| vm-system_a/b | 0x8000000 | ✔ |
| splash | 0x2000000 | ✔ |
| super | 0x300000000 (12 GiB) | lun0_super.bin ✔ |
| max-download-size | 805306368 | fastboot auto-splits super into 7 sparse chunks |

### 8.3 The flash script (`/work/flash.sh`, already on your machine)

```sh
#!/bin/sh
set -e
cd /work/img
f() { echo "== $1 <- $2"; fastboot flash "$1" "$2"; }
for s in a b; do
  f boot_$s lun4_boot_$s.bin
  f init_boot_$s lun4_init_boot_$s.bin
  f vendor_boot_$s lun4_vendor_boot_$s.bin
  f dtbo_$s lun4_dtbo_$s.bin
  f recovery_$s lun4_recovery_$s.bin
  f vbmeta_$s lun4_vbmeta_$s.bin
  f vbmeta_system_$s lun0_vbmeta_system_$s.bin
  f vm-linux_$s lun4_vm-linux_$s.bin
  f vm-system_$s lun0_vm-system_$s.bin
done
f splash lun4_splash.bin
f super lun0_super.bin
echo "== wipe"; fastboot erase userdata; fastboot erase metadata
fastboot set_active a
echo FLASH_OK
```

Run it **detached** with a log (survives SSH drops):

```bash
sudo docker exec -d rp5 sh -c 'sh /work/flash.sh > /work/flash.log 2>&1; echo EXIT=$? >> /work/flash.log'
# watch it:
sudo docker exec rp5 tail -f /work/flash.log
```

Expected timing: small partitions ~20 s total, `super` ~91 s, **`erase userdata` ~351 s** (yes, 6 minutes, be patient), the rest instant. It ends with `FLASH_OK` / `EXIT=0`.

> The "`Did you mean to fastboot format this ext4 partition?`" warning during `erase userdata` is harmless.

### 8.4 Optional: flash stock `abl` too

```bash
rp5 sh -c 'cd /work/img && fastboot flash abl_a lun4_abl_a.bin && fastboot flash abl_b lun4_abl_b.bin && fastboot set_active a && fastboot reboot'
```

Stock `abl` = an ELF of 1048576 bytes, identical for A and B, with **no** "U-Boot" string in it.

### 8.5 Reboot

```bash
rp5 fastboot reboot
```

If you get **U-Boot**, go to [§9](#9-procedure-b-kill-u-boot--rocknix-leftovers-abl--devinfo). If you get **recovery**, go to [§11](#11-procedure-d-cannot-load-android-system-in-recovery).

---

## 9. Procedure B: kill U-Boot / ROCKNIX leftovers (abl + devinfo)

This is **the fix that actually unbricked it.**

### 9.1 Why EDL and not fastboot?

`devinfo` can't be read, and usually can't be safely written, through fastboot. EDL talks to the flash chip **below** every bootloader, so it can read and write any partition by name.

### 9.2 Steps

1. Proxmox USB passthrough on **USB 2.0** ([§4.2](#42-proxmox-usb-passthrough)).
2. `qcserial` blocked ([§7.3](#73-keep-the-hosts-qcserial-driver-away-from-edl)).
3. Device in **EDL** ([§5](#5-button-combos-memorize-these)). Check with `lsusb | grep 05c6:9008`.
4. Read and back up the current `devinfo` and `misc`:

```bash
rp5 sh -c 'mkdir -p /work/rb2 && cd /work/rb2 && mkdir -p logs &&
  edl --loader=/work/img/prog_ufs_firehose_sm8250_lite_lp5.elf --memory=ufs \
      r misc,devinfo misc.img,devinfo.img'
rp5 sh -c 'cp /work/rb2/devinfo.img /work/devinfo_backup_$(date +%Y%m%d_%H%M%S).img'
```

5. Check for ROCKNIX leftovers:

```bash
rp5 sh -c 'grep -a -c -E "RocknixAblVer|BootSourceMode|BootMode" /work/rb2/devinfo.img; xxd -s 0x980 -l 0x340 /work/rb2/devinfo.img | grep -v "0000 0000 0000 0000 0000 0000 0000 0000"'
```

If the count is **> 0**, you have leftovers. Clean them:

6. Build the cleaned copy (keeps bytes `0x000–0x8FF` exactly, zeros `0x900–0xFFF`):

```bash
rp5 python3 -I - <<'EOF'
src = "/work/rb2/devinfo.img"
a = open(src, "rb").read()
assert len(a) == 4096, f"devinfo should be 4096 bytes, got {len(a)}"
assert a[:13] == b"ANDROID-BOOT!", "devinfo magic missing, STOP and investigate"
tail = a[0x900:]
print("non-zero bytes in 0x900..end:", sum(1 for b in tail if b))
clean = a[:0x900] + bytes(len(a) - 0x900)
open("/work/devinfo_clean.img", "wb").write(clean)
print("wrote /work/devinfo_clean.img, header kept:", clean[:16].hex(" "))
EOF
```

> On your device, the stock dump's `devinfo` (from a clean unit) was **all zeros from 0x900 on**, and your bytes before 0x900 were identical apart from byte `0xF`. Zeroing `0x900+` was therefore exactly "remove ROCKNIX, keep everything else".

7. Write, read back, verify, reboot:

```bash
rp5 sh -c 'cd /work/rb2 && L=/work/img/prog_ufs_firehose_sm8250_lite_lp5.elf
  edl --loader=$L --memory=ufs w devinfo /work/devinfo_clean.img
  rm -f verify.img; edl --loader=$L --memory=ufs r devinfo verify.img
  cmp verify.img /work/devinfo_clean.img && echo VERIFIED &&
  edl reset --loader=$L'
```

Expected output: `Wrote /work/devinfo_clean.img to sector 296215.` → `Dumped sector 296215 ...` → `VERIFIED` → `bsp_target_reset() 1`.

> `edl reset --resetmode=reset --memory=ufs` printed the usage text (bad arguments). Plain `edl reset --loader=...` is what worked.

### 9.3 If U-Boot is still there after this

Read `abl` back and confirm it's stock:

```bash
rp5 sh -c 'cd /work/rb2 && edl --loader=/work/img/prog_ufs_firehose_sm8250_lite_lp5.elf --memory=ufs \
  r abl_a,abl_b abl_a.img,abl_b.img && cmp abl_a.img /work/img/lun4_abl_a.bin && cmp abl_b.img /work/img/lun4_abl_b.bin && echo ABL_IS_STOCK'
```

If it's **not** stock, write it over EDL (`edl ... w abl_a /work/img/lun4_abl_a.bin`, same for `_b`).

---

## 10. Procedure C: flash the matching firmware set

Use this when Android fails to come up (recovery "Cannot load Android system") and your device firmware is a **different version** than the Android you flashed.

### 10.1 Back up what's there first (over EDL)

This is already done for the pre-fix state: see [§14](#14-backups-what-exists-and-how-to-restore-it). To make a fresh one:

```bash
rp5 sh -c '
L=/work/img/prog_ufs_firehose_sm8250_lite_lp5.elf
D=/work/fw_backup_$(date +%Y%m%d_%H%M%S); mkdir -p $D/logs && cd $D
P=""; F=""
for p in aop cmnlib cmnlib64 devcfg dsp featenabler hyp imagefv keymaster modem multiimgoem tz uefisecapp; do
  for s in a b; do P="$P,${p}_$s"; F="$F,${p}_$s.img"; done; done
edl --loader=$L --memory=ufs r ${P#,} ${F#,}
sha256sum *.img > SHA256SUMS; ls | wc -l'
```

### 10.2 Flash (fastboot mode)

```bash
rp5 sh -c 'cd /work/stock && fail=0
for p in aop cmnlib cmnlib64 devcfg dsp featenabler hyp imagefv keymaster modem multiimgoem tz uefisecapp; do
  for s in a b; do
    if fastboot flash ${p}_$s lun4_${p}_$s.bin >/tmp/f.log 2>&1; then echo "${p}_$s OK"
    else echo "${p}_$s FAILED: $(tail -2 /tmp/f.log)"; fail=1; fi
  done
done
echo fail=$fail
fastboot erase metadata && fastboot set_active a && fastboot reboot'
```

Result on your device: **26/26 OK**.

> **Why these 13?** They are exactly the partitions that **differed** between your device and the package. Everything else in the boot chain (xbl, xbl_config, abl, loader, boot, init_boot, vendor_boot, dtbo, recovery, vbmeta, splash, qupfw, multiimgqti, mdtp, vm-linux) was already byte-identical.
>
> **Why erase `metadata`?** So vold generates fresh metadata-encryption keys with the matching keymaster, then formats `/data` itself (the fstab marks it `formattable`).
>
> **Risk:** Qualcomm anti-rollback. Flashing *older* TZ/keymaster can be refused on some units. That's recoverable, because EDL lives in ROM; restore your backup with [§14.2](#142-restore-the-device-firmware-backup-over-edl). On your RP5 it was accepted.

---

## 11. Procedure D: "Cannot load Android system" in recovery

You'll see Android recovery with **Try again** and **Factory data reset** (or "Reboot").

### 11.1 What's going on

When Android fails to start, it writes a "prompt and wipe data" request into the **`misc`** partition (the bootloader control block). Recovery shows this prompt **on every boot until someone answers it**. That's why, after the firmware fix, the device went **straight to recovery in ~15 s**, without even trying Android: the request was still there from the failed boot before.

### 11.2 What to do

1. Choose **Try again** first (Volume to move, Power to select). On your device, that booted Android. ✅
2. If it comes back to the same prompt, choose **Factory data reset**. This only touches internal storage, not the SD card.
3. If it still loops, redo [§10](#10-procedure-c-flash-the-matching-firmware-set), then [§8](#8-procedure-a-reflash-android-over-fastboot).

### 11.3 Peeking at recovery over adb (user build = limited)

```bash
rp5 adb devices -l                    # → 2f184581  recovery ... model:Retroid_Pocket_5
rp5 adb shell getprop ro.build.fingerprint
# qti/kona/kona:13/TKQ1.240621.001/eng.RP5.20241026.193331:user/release-keys
rp5 adb shell dmesg > /tmp/recovery_dmesg.txt      # dmesg IS readable here
rp5 adb reboot bootloader             # jump to fastboot from recovery
```

Not available on this production build: `adb root` ("adbd cannot run as root in production builds"), `/tmp/recovery.log` (permission denied), `/cache/recovery`.

### 11.4 Why formatting `/data` yourself doesn't help

- `fastboot format:f2fs userdata` failed in the container (`make_f2fs failed with status 1`), and the bootloader reports `partition-type:userdata` as `raw` anyway.
- More importantly, with **metadata encryption**, vold formats `/data` *through* the `dm-default-key` layer. A filesystem you create on the raw partition is invisible to Android. **Erase `metadata` instead**, and Android re-creates everything.

---

## 12. EDL (Qualcomm 9008) deep dive

### 12.1 The three things that broke EDL, in the order they were found

| # | Symptom | Cause | Fix |
|---|---|---|---|
| 1 | Hangs at `Device detected :)`, then nothing | Host `qcserial` driver grabbed the device and ate the one-time Sahara hello | [§7.3](#73-keep-the-hosts-qcserial-driver-away-from-edl) |
| 2 | `USBError(19, 'No such device')` then `Device is in Sahara error state, please reboot the device.` | `qcserial` was **auto-reloaded** by udev on reconnect | `/run/modprobe.d` rule, then re-enter EDL |
| 3 | `--debugmode` shows endless `DeviceClass - [LIB]: Timed out`, even with the tool already listening when the device connected | **USB 3 (SuperSpeed) passthrough** in the Proxmox VM: the PBL's bulk-IN replies never arrive | **Proxmox USB → USB 2.0.** Instantly fixed. |

Bonus gotchas:

- `edl --debugmode` crashes with `FileNotFoundError: .../logs/log.txt` unless a `logs/` dir exists in the current directory. `mkdir -p logs` first.
- `pkill -f "<pattern>"` over SSH can match **your own SSH command line** and kill the session (exit 255/143). Use `pkill -x <name>` or a PID.
- Holding **Power alone** did not always take the device out of EDL. **Unplugging** is the reliable way to make a fresh connection.
- The Sahara hello is sent **once** per EDL entry. If anything consumes it, you need a **fresh** EDL entry (unplug and re-enter).

### 12.2 Common EDL commands

```bash
L=/work/img/prog_ufs_firehose_sm8250_lite_lp5.elf
mkdir -p logs                                                          # in the cwd

edl --loader=$L --memory=ufs printgpt --lun=4                          # list partitions on LUN 4
edl --loader=$L --memory=ufs r boot_a boot_a.img                       # read one partition
edl --loader=$L --memory=ufs r abl_a,abl_b,boot_a abl_a.img,abl_b.img,boot_a.img   # read many
edl --loader=$L --memory=ufs w devinfo devinfo_clean.img               # write one partition
edl reset --loader=$L                                                  # reboot out of EDL
```

Speed over USB 2.0: ~29 MB/s reads.

### 12.3 "Arm-and-wait" pattern (start the tool BEFORE entering EDL)

```bash
cat > /tmp/wait_edl.sh <<'EOF'
#!/bin/sh
cd /work/rb2 && mkdir -p logs
for n in 1 2 3 4 5 6; do
  while ! lsusb 2>/dev/null | grep -q 05c6:9008; do sleep 0.2; done
  echo "attempt $n: EDL seen $(date +%T)"
  timeout 90 edl --loader=/work/img/prog_ufs_firehose_sm8250_lite_lp5.elf --memory=ufs \
     r abl_a,boot_a abl_a.img,boot_a.img 2>&1 | grep -vE "Capstone|Keystone|Timed out" | tail -4
  [ -f boot_a.img ] && break
  while lsusb 2>/dev/null | grep -q 05c6:9008; do sleep 0.5; done   # wait for unplug, retry
done
EOF
sudo docker cp /tmp/wait_edl.sh rp5:/work/wait_edl.sh
sudo docker exec rp5 sh /work/wait_edl.sh
```

---

## 13. Partitions you must NEVER flash from someone else's dump

The GammaSqueeze package is a **raw dump of one specific unit**. Its QFIL helper flashes *everything*, including that unit's calibration and identity. **Don't do that on your device.**

| Partition(s) | What it is | Why not |
|---|---|---|
| `persist` | Sensor/display/touch calibration | You'd get the dev unit's calibration |
| `modemst1`, `modemst2`, `fsg`, `fsc`, `mdm1m9kefs*` | Radio/NV data | Device-unique |
| `devinfo` | Unlock state, flags (and ROCKNIX junk) | Device-unique. **Clean it, don't replace it.** |
| `frp`, `keystore`, `secdata`, `ssd`, `storsec` | Security/identity | Device-unique |
| `cdt`, `ddr`, `mdmddr` | Board config + DDR training | Wrong values here can stop the device from booting |
| `nvdata1/2`, `qpdata*`, `rawdump`, `reserve*`, `ssign` | Misc per-device data | Device-unique |
| `apdp`, `msadp`, `spunvm`, `uefivarstore`, `vm-data`, `vm-keystore` | Per-device stores | Device-unique |
| `*_gpt_header`, `*_last_parti` | Partition tables | Only if you *know* the table is broken |
| `metadata`, `userdata`, `misc` | Runtime state | **Erase** instead of flashing |

**Safe to flash (software):** `boot`, `init_boot`, `vendor_boot`, `dtbo`, `recovery`, `vbmeta`, `vbmeta_system`, `vm-linux`, `vm-system`, `super`, `splash`, `abl`, `loader`, plus the firmware set in [§10](#10-procedure-c-flash-the-matching-firmware-set) (`aop cmnlib cmnlib64 devcfg dsp featenabler hyp imagefv keymaster modem multiimgoem tz uefisecapp`, and `qupfw multiimgqti xbl xbl_config` if they ever differ).

---

## 14. Backups: what exists, and how to restore it

### 14.1 What's in the `rp5work` volume

Host path: `/var/lib/docker/volumes/rp5work/_data/` (= `/work` in the container)

| Path | Contents | Size |
|---|---|---|
| `/work/dl/fw.7z.001-005` | The stock firmware archive | 3.0 G |
| `/work/img/` | Extracted OS images + abl + firehose loader | 13 G |
| `/work/stock/` | Extracted firmware partitions, loader, misc, devinfo | 1.8 G |
| **`/work/device_fw_backup/`** | **Your device's ORIGINAL firmware before the fix**: 26 × `{aop,cmnlib,cmnlib64,devcfg,dsp,featenabler,hyp,imagefv,keymaster,modem,multiimgoem,tz,uefisecapp}_{a,b}.img` + `uefivarstore.img` + `devinfo_device_backup.img` + `SHA256SUMS` | 956 M |
| `/work/devinfo_device_backup.img` | Your ORIGINAL devinfo (with the ROCKNIX block) | 4 K |
| `/work/devinfo_clean.img` | The cleaned devinfo that's on the device now | 4 K |
| `/work/rb/`, `/work/rb2/` | EDL read-backs used for diffing | ~2 G |
| `/work/vb/` | Unpacked vendor_boot ramdisk (fstab) | 70 M |
| `/work/flash.sh`, `/work/flash.log` | The fastboot flash script + its log | |
| `/work/recovery_dmesg.txt` | Kernel log from recovery | |

> 💾 **Copy `/work/device_fw_backup/` off this machine** (to your PC or a NAS). It's the only copy of your unit's retail firmware.
>
> ```bash
> sudo tar -C /var/lib/docker/volumes/rp5work/_data -czf ~/rp5-device-backup.tgz device_fw_backup devinfo_device_backup.img
> # then from your PC: scp ai@192.168.1.50:rp5-device-backup.tgz .
> ```

### 14.2 Restore the device firmware backup over EDL

Use this if the older firmware ever causes trouble (anti-rollback refusal, a broken modem, etc.):

```bash
rp5 sh -c 'cd /work/device_fw_backup && sha256sum -c SHA256SUMS | grep -v OK$; mkdir -p logs
L=/work/img/prog_ufs_firehose_sm8250_lite_lp5.elf
for p in aop cmnlib cmnlib64 devcfg dsp featenabler hyp imagefv keymaster modem multiimgoem tz uefisecapp; do
  for s in a b; do edl --loader=$L --memory=ufs w ${p}_$s ${p}_$s.img || echo "FAILED ${p}_$s"; done
done
edl reset --loader=$L'
```

(Or with fastboot, if the bootloader works: `fastboot flash ${p}_$s ${p}_$s.img`.)

> Note: retail firmware + this pre-release Android was **the combination that failed** ("Cannot load Android system"). Only restore it together with a matching newer Android, for example after an official OTA.

### 14.3 Make a full "golden" backup now that it works (recommended)

Everything **except** `userdata` (which is big and encrypted, so useless as a raw image). Takes a while at 29 MB/s, mostly for `super` (12 GB ≈ 7 min).

```bash
rp5 sh -c '
L=/work/img/prog_ufs_firehose_sm8250_lite_lp5.elf
D=/work/golden_$(date +%Y%m%d); mkdir -p $D/logs && cd $D
edl --loader=$L --memory=ufs printgpt > gpt.txt 2>&1
edl --loader=$L --memory=ufs rl . --skip=userdata --genxml
sha256sum * > SHA256SUMS 2>/dev/null; du -sh .'
```

`rl` = "read all partitions of all LUNs". `--skip=userdata` skips data and `--genxml` writes a rawprogram XML. Check `edl --help` for the exact flags of your edl version.

---

## 15. Forensics toolkit (how the root cause was found)

### 15.1 Diff the whole boot chain against stock (EDL)

```bash
rp5 sh -c '
mkdir -p /work/rb2/logs && cd /work/rb2
names=""; files=""
for f in /work/stock/*.bin; do b=$(basename $f .bin); p=${b#lun?_}; names="$names,$p"; files="$files,$p.img"; done
edl --loader=/work/img/prog_ufs_firehose_sm8250_lite_lp5.elf --memory=ufs r ${names#,} ${files#,}
for f in /work/stock/*.bin; do b=$(basename $f .bin); p=${b#lun?_}
  if [ ! -f $p.img ]; then echo "$p: NOT READ"
  elif cmp -s $p.img $f; then echo "$p: same"
  else echo "$p: DIFFERS  u-boot=$(grep -a -c -i u-boot $p.img)"; fi
done'
```

### 15.2 Find U-Boot inside the stock `loader` partition

```bash
rp5 python3 -I - <<'EOF'
import re, struct, zlib
d = open("/work/stock/lun4_loader_a.bin","rb").read()
assert d[:8] == b"ANDROID!"                       # it's a v0 Android boot image
ks = struct.unpack_from("<I", d, 8)[0]            # kernel size
ps = struct.unpack_from("<I", d, 36)[0]           # page size (4096)
k = zlib.decompressobj(31).decompress(d[ps:ps+ks])   # gzip + trailing DTB → use decompressobj
for s in sorted(set(m.group().decode() for m in re.finditer(rb"[\x20-\x7e]{8,}", k))):
    if re.search(r"^U-Boot 20|bootcmd=|bootmenu_\d=|bootdelay=", s): print(s)
EOF
```

Output (the smoking gun):

```
U-Boot 2024.10-rc4-00620-ga61af132fb90-dirty (Oct 17 2024 - 15:41:48 +0800)
bootcmd=bootefi bootmgr; pause
bootdelay=2
bootmenu_0=Reset=reset
bootmenu_1=Boot=bootefi bootmgr; pause
bootmenu_3=Enable USB mass storage=ums 0 scsi 0
bootmenu_8=Enable fastboot mode=run fastboot
...
```

So the U-Boot screen is **Retroid's own** (board strings "Retroid Pocket 5"), not a foreign bootloader.

> Gotcha: `gzip.decompress()` fails with `BadGzipFile ... (b'\xd0\r')` because a DTB is appended after the gzip stream. `zlib.decompressobj(31)` stops cleanly at the end of the stream.

### 15.3 Decompress ABL and search it

ABL is an ELF containing a UEFI firmware volume (`_FVH`) with an **LZMA**-compressed section (found at offset 12408 in this build):

```bash
rp5 python3 -I - <<'EOF'
import re, lzma
d = open("/work/img/lun4_abl_a.bin","rb").read()
best = b""
for m in re.finditer(rb"\x5d\x00\x00", d):
    try:
        x = lzma.LZMADecompressor(lzma.FORMAT_ALONE).decompress(d[m.start():])
        if len(x) > len(best): best = x
    except Exception: pass
print("decompressed", len(best))           # 553160
for m in re.finditer(rb"[\x20-\x7e]{6,}", best):
    t = m.group().decode()
    if re.search(r"Loader Mode|loader mode|Recovery Mode|misc|devinfo", t): print(hex(m.start()), t[:120])
EOF
```

Key hits: `Booting Into Loader Mode`, `Error %a loader mode: %r`, `Booting Into Recovery Mode`, `Error Reading FFBM info from misc`. Built from `/home/liuwen/uq865a13/VENDOR.13/...` (Retroid's SM8250 Android 13 tree).

### 15.4 Read the fstab out of `vendor_boot` (boot header v4)

```bash
rp5 sh -c 'mkdir -p /work/vb && cd /work/vb && python3 -I -c "
import struct
d=open(\"/work/img/lun4_vendor_boot_a.bin\",\"rb\").read(); assert d[:8]==b\"VNDRBOOT\"
ps=struct.unpack_from(\"<I\",d,12)[0]; rsz=struct.unpack_from(\"<I\",d,24)[0]; hs=struct.unpack_from(\"<I\",d,2096)[0]
st=((hs+ps-1)//ps)*ps; open(\"ramdisk.bin\",\"wb\").write(d[st:st+rsz]); print(hs,st)
" && lz4 -dc ramdisk.bin > rd.cpio && rm -rf rd && mkdir rd && cd rd && cpio -idm --quiet < ../rd.cpio
grep -hE "[[:space:]]/(data|metadata)[[:space:]]" $(find . -name "fstab*")'
```

Vendor boot v3/v4 header offsets: `page_size @12`, `vendor_ramdisk_size @24`, `header_size @2096` (v4 header = 2128 bytes, ramdisk at 4096). The ramdisk is **LZ4 legacy** (`02 21 4c 18`).

### 15.5 Pull recovery's kernel log

```bash
rp5 adb shell dmesg > /tmp/rec.txt
grep -E "Kernel command line|init: |Power-on reason|Power-off reason" /tmp/rec.txt | head -50
```

---

## 16. Error message → meaning → fix

| Message | Meaning | Fix |
|---|---|---|
| `EFI boot manager: Cannot load any image` + `No EFI system partition` + `Loading Boot0000 'scsi 0' failed` | ABL booted U-Boot (Loader Mode) | [§9](#9-procedure-b-kill-u-boot--rocknix-leftovers-abl--devinfo) |
| `Card did not respond to voltage select! : -110` | U-Boot probing an empty SD slot, harmless | — |
| `fastboot devices` OK but `getvar` hangs | Wedged fastboot session | Hard power-off, re-enter fastboot |
| `Did you mean to fastboot format this ext4 partition?` | Warning during `erase userdata` | Harmless |
| `make_f2fs failed with status 1` / `Cannot generate image for userdata` | fastboot's formatter is missing or incompatible | Don't format. Erase `metadata`. |
| `Warning: metadata type is raw, but f2fs was requested` | Bootloader doesn't report fs types | Same as above |
| `main - Device detected :)` then nothing | Sahara hello lost | qcserial block + USB 2.0 + fresh EDL entry |
| `DeviceClass - [LIB]: Timed out` (repeated, `--debugmode`) | No data from the device (USB 3 passthrough) | **Proxmox USB 2.0** |
| `USBError(19, 'No such device ...')` | Device disconnected / grabbed mid-handshake | Fresh EDL entry |
| `Device is in Sahara error state, please reboot the device.` | Handshake failed. PBL needs a restart. | Unplug, re-enter EDL |
| `FileNotFoundError: ... logs/log.txt` | edl `--debugmode` needs a `logs/` dir | `mkdir -p logs` |
| `ERROR: Failed building wheel for pylzma` | Missing compiler | `apt install build-essential python3-dev` |
| `adbd cannot run as root in production builds` | User build, as expected | Use `dmesg` and `getprop` |
| Recovery: `Cannot load Android system` | Android boot failed, request saved in `misc` | [§11](#11-procedure-d-cannot-load-android-system-in-recovery), and [§10](#10-procedure-c-flash-the-matching-firmware-set) if it repeats |
| `ControlPath too long` (ssh) | Unix socket path > 108 chars | Use a short `ControlPath` like `/tmp/x-cm` |

---

## 17. Host machine changes and cleanup

What was changed on `192.168.1.50` during this session:

| Change | Status | Undo |
|---|---|---|
| Installed `docker.io` | **Kept** (needed for the container) | `sudo apt purge docker.io` |
| Installed `android-sdk-platform-tools`, `adb`, `fastboot`, graphviz libs, `f2fs-tools`, `sqlite3`, ... (before you asked for Docker) | **Removed** | — |
| `pollinate` got removed by `apt autoremove` | **Reinstalled** | — |
| `/run/modprobe.d/rp5-no-qcserial.conf` (blocks `qcserial`) | Temporary, gone after a reboot | `sudo rm /run/modprobe.d/rp5-no-qcserial.conf` |
| `qcserial` module unloaded | Comes back after a reboot or a `modprobe qcserial` | — |
| Container `rp5` (`sleep infinity`, privileged) | **Still running** | `sudo docker rm -f rp5` |
| Images `rp5tools` (+ maybe `rp5tools-new` from the unverified rebuild) | Present | `sudo docker rmi rp5tools rp5tools-new` |
| Volume `rp5work` (~21 GB) | Present | `sudo docker volume rm rp5work` **after** copying `device_fw_backup` off |
| `/home/ai/rp5build/Dockerfile` | Present | `rm -r ~/rp5build` |

Free the big stuff but keep what matters:

```bash
# keep: dl/ (archive), device_fw_backup/, devinfo_*.img, flash.sh, firehose
sudo docker exec rp5 sh -c 'rm -rf /work/rb /work/rb2 /work/vb /work/abl_dec.bin && du -sh /work'
# /work/img (13 GB) can be re-extracted from /work/dl at any time (§7.5)
```

Stop the container when you're not using it (the volume stays):

```bash
sudo docker stop rp5      # later: sudo docker start rp5
```

---

## 18. After the fix: updates, ROCKNIX, staying safe

### 18.1 Your device right now

- Android 13, build `TKQ1.240621.001` / `eng.RP5.20241026.193331` (= **V1.0.0.31, pre-release**), slot **A**.
- Qualcomm firmware **matches** that build (older than what your unit shipped with).
- Bootloader is still **unlocked** (orange boot state). That's normal and is why the unlock warning shows at boot.
- `devinfo` is clean (no ROCKNIX records).

### 18.2 Getting to the current retail firmware

Check Retroid's **System Update** in Settings. An official OTA should bring both Android **and** the matching firmware up to date. I haven't verified that OTA works from this pre-release build. If it fails, a newer full dump (when one appears) plus [§8](#8-procedure-a-reflash-android-over-fastboot) and [§10](#10-procedure-c-flash-the-matching-firmware-set) is the path.

### 18.3 If you want ROCKNIX again (without bricking)

- Follow a guide that **backs up `abl`** first:
  - <https://github.com/amphyvi/rp5-dualboot>
  - <https://github.com/shuuri-labs/pocknix-os/blob/main/docs/install.md>
  - Restore path from those guides: *Settings → Handheld Settings → Run Script as Root → `restore_backup.sh`* in the `ROCKNIX_abl` folder.
- **Before installing anything, back up `devinfo` over EDL**. This brick showed that restoring `abl` is **not enough**: the ROCKNIX ABL also leaves settings in `devinfo`.
- To go back to stock later: stock `abl` ([§8.4](#84-optional-flash-stock-abl-too)) **and** clean `devinfo` ([§9](#9-procedure-b-kill-u-boot--rocknix-leftovers-abl--devinfo)).

### 18.4 Golden rules

1. **Never** flash a whole QFIL dump blindly. Skip the device-specific partitions ([§13](#13-partitions-you-must-never-flash-from-someone-elses-dump)).
2. **Back up before writing** anything over EDL, and **read back after writing**.
3. Keep **Proxmox USB on 2.0** for EDL.
4. Run long jobs **detached** (`docker exec -d`) with a log file.
5. Copy `device_fw_backup` (and later a golden backup) **off the machine**.

---

## 19. Reference: device facts and partition table

```
product:        kona (Snapdragon 865 / SM8250)
variant:        SM8 UFS
hw-revision:    20001
serial:         2f184581
slots:          a/b (active: a)
unlocked:       yes   secure: no
max-download:   805306368 (768 MiB)
EDL USB:        05c6:9008  "QUSB_BULK_CID:0412_SN:64ADE251"
firehose:       prog_ufs_firehose_sm8250_lite_lp5.elf
storage:        UFS, LUNs 0–6
```

Selected partition sizes (from `fastboot getvar partition-size:*`):

| Partition | Size (hex) | Size |
|---|---|---|
| super | 0x300000000 | 12 GiB |
| userdata | 0x1A21ED3000 | ~104.5 GiB |
| boot_a/b | 0x6000000 | 96 MiB |
| vendor_boot_a/b | 0x6000000 | 96 MiB |
| recovery_a/b | 0x6400000 | 100 MiB |
| init_boot_a/b | 0x800000 | 8 MiB |
| dtbo_a/b | 0x1800000 | 24 MiB |
| loader_a/b | 0xA00000 | 10 MiB |
| splash | 0x2000000 | 32 MiB |
| abl_a/b | 0x100000 | 1 MiB |
| imagefv_a/b | 0x200000 | 2 MiB |
| vbmeta_a/b | 0x10000 | 64 KiB |
| metadata | 0x4000000 | 64 MiB |
| devinfo | 0x1000 | 4 KiB (sector 296215 on LUN 4) |
| misc | — | 1 MiB (sector 30728) |

USB IDs by mode:

| Mode | USB ID | `lsusb` name (misleading) |
|---|---|---|
| Fastboot (ABL) | `18d1:d00d` | "Xiaomi Mi/Redmi 2 (fastboot)" |
| EDL | `05c6:9008` | "Qualcomm ... QDL mode" |
| Recovery (adb) | `18d1:d001` | "Nexus 4 (fastboot)" (it's actually adb, interface "ADB Interface") |
| Android (MTP) | `18d1:4ee1` | "Nexus/Pixel Device (MTP)" |

---

## 20. Links

**Firmware and tools**
- Stock firmware dumps (GammaSqueeze): <https://github.com/TheGammaSqueeze/Retroid_Pocket_Stock_Firmware/releases>
- RP5 release: <https://github.com/TheGammaSqueeze/Retroid_Pocket_Stock_Firmware/releases/tag/Android13_RP5_V1.0.0.31_20241026_190102_user>
- bkerler/edl (Qualcomm EDL/Firehose client): <https://github.com/bkerler/edl>
- Retroid official software page (Pocket 2 only): <https://www.goretroid.com/pages/software>

**ROCKNIX / dual-boot (where the leftovers came from)**
- ROCKNIX: <https://rocknix.org>
- RP5 dual-boot guide: <https://github.com/amphyvi/rp5-dualboot>
- pocknix install docs: <https://github.com/shuuri-labs/pocknix-os/blob/main/docs/install.md>
- Retro Game Corps ROCKNIX guide: <https://retrogamecorps.com/2025/03/03/linux-on-the-odin-2-rocknix-guide/>

**Android internals used here**
- Vendor boot partitions / header v4: <https://source.android.com/docs/core/architecture/partitions/vendor-boot-partitions>
- Metadata encryption: <https://source.android.com/docs/security/features/encryption/metadata>
- Fastboot (overview): <https://en.wikipedia.org/wiki/Fastboot>

**Proxmox**
- USB devices in VMs: <https://pve.proxmox.com/wiki/USB_Devices_in_Virtual_Machines>

**Avoided (unverified mirrors, listed so you recognize them)**
- firmwaredrive.com / naijarom.com "Retroid_Pocket_5_20250121_QFIL.zip"

---

*It's massive. 💀 Written 2026-11-12, right after the RP5 came back to life.*
