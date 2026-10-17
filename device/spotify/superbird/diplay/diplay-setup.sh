#!/system/bin/sh
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# DiPlay setup after each boot (docs/diplay.md). init runs it as root at
# sys.boot_completed. The steps give the same result each time.
# 1. App-ops that otherwise need screen dialogs:
#   SYSTEM_ALERT_WINDOW  draw over other apps
#   GET_USAGE_STATS      find other projection apps
#   WRITE_SETTINGS       display and audio settings
#   ACTIVATE_VPN         the VPN for the USB network link (no consent dialog)
# 2. DiPlay settings (shared_prefs). Needed at the first boot of a new /data, and when /data
#    is in RAM (data-setup.sh fallback).
#    display_width_physical_mm 150: CarPlay chooses its layout from the physical size.
#      DiPlay's default gives 5 x 4 small icons; 100 and 150 give 4 x 2.
#    display_fps 60: DiPlay's default without the key is 30.
#    speech_without_8_16_khz true (DiPlay 0.2.18+, "Clearer navigation voice over USB"): DiPlay
#      does not offer 8 and 16 kHz for navigation prompts and alerts. Without it, the iPhone
#      can send the Apple Maps voice as 8 kHz stereo, which sounds bad.
#    oem_label Home: the name of the car button in CarPlay's app list (DiPlay's default is
#      "BYD"). A tap opens the home screen (CptHome).
#    Siri key (diplay_wheel_map_zoom.xml): preset 4 sends KEY_F1 (Android F1 = 131, scan
#      code 59, device gpio-keys; scripts/mk-systemb-tail.py). DiPlay's CarPlay screen opens
#      Siri with an assigned key. Android 9 takes VOICE_ASSIST itself.
# 3. The car button icon (files/airplay-icon.png, DiPlay's "custom icon"): a house in the colors
#    of the home screen instead of the BYD logo. Only when no custom
#    icon is there, so an icon chosen in DiPlay stays.
# DiPlay does not start here: after a boot the home screen (CptHome)
# shows, and CarPlay starts from its tile, or from DiPlay's USB_DEVICE_ATTACHED filter when an
# iPhone connects.

PACKAGE=com.shihab.diplay
DATA=/data/data/$PACKAGE
PREFS=$DATA/shared_prefs/xcertplay_airplay.xml
SETTINGS="int:display_width_physical_mm=150 int:display_fps=60 boolean:speech_without_8_16_khz=true string:oem_label=Home"
WHEEL_PREFS=$DATA/shared_prefs/diplay_wheel_map_zoom.xml
ICON=$DATA/files/airplay-icon.png
ICON_SRC=/vendor/etc/diplay/carplay-home-icon.png
SIRI_KEY="131|59|gpio-keys"

for op in SYSTEM_ALERT_WINDOW GET_USAGE_STATS WRITE_SETTINGS ACTIVATE_VPN; do
    cmd appops set "$PACKAGE" "$op" allow
done

# Only missing keys are added, so a value that the user changes in DiPlay stays.
missing=""
for kv in $SETTINGS; do
    name=${kv#*:}
    grep -q "\"${name%%=*}\"" "$PREFS" 2>/dev/null || missing="$missing $kv"
done

siri_missing=""
grep -q "$SIRI_KEY" "$WHEEL_PREFS" 2>/dev/null || siri_missing=yes

if [ -d "$DATA" ] && { [ -n "$missing" ] || [ -n "$siri_missing" ]; }; then
    am force-stop "$PACKAGE"
    uid=$(stat -c %u "$DATA")
    mkdir -p "$DATA/shared_prefs"
    chown "$uid:$uid" "$DATA/shared_prefs"
    chmod 771 "$DATA/shared_prefs"
    entries=""
    for kv in $missing; do
        type=${kv%%:*}
        name=${kv#*:}
        if [ "$type" = string ]; then
            entries="$entries    <string name=\"${name%%=*}\">${name#*=}</string>\n"
        else
            entries="$entries    <$type name=\"${name%%=*}\" value=\"${name#*=}\" />\n"
        fi
    done
    if [ -n "$siri_missing" ]; then
        # DiPlay writes nothing else to this file unless the user changes wheel settings.
        printf "%s\n" "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' \
            '    <boolean name="siri_key" value="true" />' \
            "    <string name=\"key_SIRI\">$SIRI_KEY</string>" '</map>' > "$WHEEL_PREFS"
        chown "$uid:$uid" "$WHEEL_PREFS"
        chmod 660 "$WHEEL_PREFS"
    fi
    if [ -z "$missing" ]; then
        :
    elif [ -f "$PREFS" ]; then
        # Write into the existing file, so that its owner, mode, and label stay.
        sed "s#</map>#$entries</map>#" "$PREFS" > "$PREFS.new"
        cat "$PREFS.new" > "$PREFS"
        rm -f "$PREFS.new"
    else
        printf "%s\n<map>\n$entries</map>\n" "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" > "$PREFS"
        chown "$uid:$uid" "$PREFS"
        chmod 660 "$PREFS"
    fi
    restorecon -R "$DATA/shared_prefs"
fi

if [ -d "$DATA" ] && [ ! -f "$ICON" ] && [ -f "$ICON_SRC" ]; then
    uid=$(stat -c %u "$DATA")
    if [ ! -d "$DATA/files" ]; then
        mkdir "$DATA/files"
        chown "$uid:$uid" "$DATA/files"
        chmod 771 "$DATA/files"
    fi
    cp "$ICON_SRC" "$ICON.new"
    chown "$uid:$uid" "$ICON.new"
    chmod 600 "$ICON.new"
    mv "$ICON.new" "$ICON"
    restorecon -R "$DATA/files"
fi
