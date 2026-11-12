# AshFall device snapshot redaction rules (GNU sed -E).
# Applied in place by scripts/device/info.sh (and logs.sh --redact) to every
# text file of a snapshot before it is kept/committed. Goal: no serials,
# IMEI/MEID/ICCID/IMSI, Android ID, MAC or Bluetooth addresses leave this
# machine.
#
# 1. `serialno: X`, `(bootloader) serialno: X`, `serial: X`, `cpuid: X`
s/(^|[[:space:]])((\(bootloader\)[[:space:]]*)?(serialno|serial|cpuid))([[:space:]]*:[[:space:]]*)[^[:space:]]+/\1\2\5<redacted>/Ig
# 2. getprop identity properties: [ro.serialno]: [xxx]
s/^\[((ro|persist|sys|hardware)\.(boot\.)?serial(no)?|gsm\.(sim\.)?(imsi|iccid|serialnumber)|ril\.(serialnumber|imei|meid)|net\.hostname)\]: \[.*\]$/[\1]: [<redacted>]/I
# 3. telephony / identity lines from dumpsys, bugreports, logs
s/(IMEI|MEID|ICCID|IMSI|Subscriber ID|Device ID|Android ID|android_id|Bluetooth address|MAC address)([^:=]*[:=][[:space:]]*)[^[:space:],;]+/\1\2<redacted>/Ig
# 4. any MAC / Bluetooth address
s/([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}/<redacted-mac>/g
# 5. lsusb serial strings
s/SN:[[:space:]]*[^[:space:]]+/SN:<redacted>/g
s/(iSerial[[:space:]]+[0-9]+[[:space:]]+).*$/\1<redacted>/
# 6. `adb devices` serial column: "<serial>  device" (also indented in reports).
#    Only lines that really look like `adb devices` output are touched, so
#    ordinary words ("fastboot devices:", "ashfall device snapshot") survive.
s/^([[:space:]]*)[[:alnum:]_.:-]{6,}([[:space:]]+(device|recovery|sideload|unauthorized|offline))[[:space:]]*$/\1<redacted>\2/
s/^([[:space:]]*)[[:alnum:]_.:-]{6,}([[:space:]]+(device|recovery|sideload|unauthorized|offline)[[:space:]]+.*(product:|transport_id:|usb:).*)$/\1<redacted>\2/
# 7. `fastboot devices` serial column: "<serial>  fastboot"
s/^([[:space:]]*)[[:alnum:]_.:-]{6,}([[:space:]]+(fastboot|unknown|sideload)[[:space:]]*(usb:.*)?)$/\1<redacted>\2/
# 8. key=value serials from /proc/cmdline, dmesg, power_supply uevents
s/((androidboot[._a-z0-9]*serial(no)?)|([a-z0-9_]*serial_number)|serialno|serial)=[^[:space:]]+/\1=<redacted>/Ig
# 9. free-form serial labels
s/(Serial Number|SerialNo|Serial number)([[:space:]]*[:=][[:space:]]*)[^[:space:]]+/\1\2<redacted>/Ig
