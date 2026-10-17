# Display brightness

Android sets the backlight from the ambient light sensor (automatic brightness, on by default),
with the same curve as the stock software. The manual slider in Settings also works.

## Hardware

| Part | Facts |
|---|---|
| Backlight | `/sys/class/backlight/aml-bl`, `brightness` 1-255, Amlogic PWM backlight (30 kHz). **Inverted:** a higher value gives less light. The stock software used 51 (bright light) to 235 (dark); below 235 (more than 235) the backlight flickers. |
| Light sensor | ams/TAOS TMD2772, I2C `2-0039`, kernel IIO driver `tsl2x7x` (`iio:device0`). Ambient light only (proximity off). Gain 16×, integration time 0.111 s (the driver default and the stock value). The sensor is behind a dark window: a lit room gives a raw channel-0 count of about 33. The panel's own light does not reach it. |

Do not write the sensor's integration time: the driver's store function returns a wrong length,
so the writer repeats the write without end (`init` hangs).

## Lights HAL (`lights/lights.c`, `lights.superbird`)

Android's backlight level 1-255 maps linearly to `aml-bl` values `persist.plaything.bl_dark`
(default 235) .. `persist.plaything.bl_bright` (default 51). Level 0 (screen off) gives
`bl_dark`. The HAL service runs as `system`; `init.superbird.rc` gives `system` the
`brightness` file, and SELinux labels it `sysfs_leds`.

## Sensors HAL (`sensors/sensors.c`, `sensors.superbird`)

One on-change light sensor. Lux = channel-0 count / integration time ×
`persist.plaything.als_scale` (default 0.33: a lit room gives about 100 lux, a hand over the
sensor about 6). The scale is an estimate of real lux.

## Curve (framework overlay)

The points are the stock software's table (`sp-als-backlight`: count thresholds 12, 25, 50, 250,
500, 1000, 2000), converted to HAL lux (count × 2.97) and to Android levels through `lights.c`:

| HAL lux below | 36 | 74 | 149 | 743 | 1486 | 2973 | above |
|---|---|---|---|---|---|---|---|
| Android level | 1 | 118 | 160 | 187 | 222 | 243 | 255 |
| `aml-bl` value | 235 | 150 | 120 | 100 | 75 | 60 | 51 |

Android interpolates between the points and ramps the changes. Default level (manual mode):
118, the stock value for a lit room.

## Tuning without a build

- In automatic mode, the slider moves the curve; Android keeps the adjustment. The slider then
  shows the adjustment (middle = none), not the brightness.
- `persist.plaything.als_scale` shifts all lux values.
- `persist.plaything.bl_dark` and `persist.plaything.bl_bright` change the hardware range.
- Pitfall: in automatic mode, `settings put system screen_brightness N` is a user correction for
  Android 9 (a point in its short-term model), not a new level. Read `screen_brightness` only,
  or switch to manual mode first.
