#!/system/bin/sh
# Copyright (C) 2026 The PlayThing Authors
# SPDX-License-Identifier: GPL-3.0-or-later
# Bluetooth setup before the Android Bluetooth stack starts (BCM20703A2 on /dev/ttyS1).
# The same steps as the stock firmware (/etc/init.d/S89bluez).

# 1. Power on the chip: BT_EN is GPIO 493 (GPIOX_17), active low reset. After U-Boot it
#    is 0, so the chip does not answer HCI_RESET, and the stack times out. Low for 0.1 s, then high, then wait 0.3 s.
g=/sys/class/gpio/gpio493
[ -d $g ] || echo 493 > /sys/class/gpio/export
echo out > $g/direction
echo 0 > $g/value
sleep 0.1
echo 1 > $g/value
sleep 0.3

# 2. Set the Bluetooth address from the eFuse. The Android Bluetooth HAL needs it
#    ("Open: No Bluetooth Address!"). /sys/class/efuse/mac_bt is a hex dump
#    ("0x00: 30 e3 d6 00 6f e5"), so convert it to "aa:bb:cc:dd:ee:ff".
a=$(tr -s ' \n' ' ' < /sys/class/efuse/mac_bt | sed 's/^ *0x00: *//; s/ *$//; s/ /:/g')
case "$a" in
    ??:??:??:??:??:??) setprop persist.service.bdroid.bdaddr "$a" ;;
    *) echo "bt-setup: unexpected eFuse format" > /dev/kmsg ;;
esac
