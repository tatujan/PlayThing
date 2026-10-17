# SELinux

SELinux is enforcing. The device policy is in `device/spotify/superbird/sepolicy/`. It uses only
public AOSP types (the device policy cannot use private ones), and it passes AOSP's
`neverallow` rules.

## Boot scripts

`init` starts each shell script with its own domain (`seclabel`); the entry point is
`/system/bin/sh` (`cpt_scripts.te`):

| Domain | Script | Mode | Access |
|---|---|---|---|
| `cpt_data_setup` | `data-setup.sh` (`docs/persistent-data.md`) | enforcing | the stock data partition (`cpt_stock_data_block_device`), the `plaything` folder on it (`cpt_stock_data_file`), loop devices, zram, e2fsck/mke2fs, `ro.plaything.*` (`cpt_data_prop`). Not a core domain: AOSP forbids `/mnt/vendor` to core domains. |
| `cpt_bt_setup` | `bt-setup.sh` | enforcing | GPIO and eFuse in sysfs, `bluetooth_prop` |
| `cpt_diplay_setup` | `diplay-setup.sh` (PlayThing) | permissive | It writes DiPlay's settings files on a new `/data`, which AOSP forbids to every domain except apps and `installd` (`neverallow` on `app_data_file`). Permissive domains are allowed in `userdebug` builds only. |

`init` itself does every mount (AOSP lets only `init` and a few system domains mount).

## Labels (`file_contexts`, `genfs_contexts`)

| Object | Type | Used by |
|---|---|---|
| `/dev/mali0` | `gpu_device` | SurfaceFlinger, apps |
| `/dev/ttyS1` | `hci_attach_dev` | the Bluetooth HAL |
| `/dev/block/zram*` | `swap_block_device` | swap; zram1 is the RAM `/data` |
| `/dev/block/data` | `cpt_stock_data_block_device` | `data-setup.sh` |
| `/mnt/vendor/cpt-stock/plaything/` | `cpt_stock_data_file` | the `/data` image |
| stock root file system (`context=` mount) | `cpt_stock_root_file` (read-only: a `contextmount_type`) | the Bluetooth HAL (firmware) |
| backlight in sysfs | `sysfs_leds` | the lights HAL |
| light sensor in sysfs | `sysfs_cpt_als` | the sensors HAL |
| `/sys/class/display`, frame buffer sysfs | `sysfs_cpt_display` | the HWC (`vinfo`), the gralloc |
| firmware path parameter | `sysfs_cpt_firmware_path` | `init` (decoder firmware) |

## Other rules

| Rule | Why |
|---|---|
| `init`: `module_load` on `vendor_file`, `sys_module` | `insmod` of `mali.ko` and the decoder modules from `/vendor/lib/modules` |
| `init`: wake lock, `block_suspend` | the wake lock in `init.superbird.rc` (no suspend) |
| `init`: `mounton` on `mnt_vendor_file` | the mounts at `/mnt/vendor/...` |
| composer service: `vndbinder_use`, `setsched` on `kernel` | the AOSP composer service opens `/dev/vndbinder` and sets SCHED_FIFO |
| `hal_bluetooth_default`: read `cpt_stock_root_file` | the Bluetooth firmware |
| `vold`: `getattr` on `cpt_stock_data_file` | its loop device check at start |
| `kernel`: device nodes in its devtmpfs | the kernel creates its own device nodes |
| `dontaudit` rules | `adbd`, `bluetooth` (netd lookup), `healthd` (`wake_alarm`), `installd` (quotas of the stock root mount), `netd` and `wificond` (module requests), `system_app` (`/sys/fs/bpf`): harmless requests of AOSP services |

## Debugging

- A permissive boot area: `scripts/mk-systemb-tail.py --selinux permissive`.
- Denials: `dmesg | grep avc` and `logcat -b all | grep avc`. A permissive log is not complete:
  the kernel logs each permissive denial only once. Early denials before `logd` runs can be
  missing; an `insmod` that fails with `EPERM` in early boot shows no denial.
- Runtime test without a new image (as root over ADB, `userdebug`): `setenforce 1`, restart the
  service (`setprop ctl.restart <service>`), read the denials, `setenforce 0`.
- A script domain can be tested with `runcon u:r:<domain>:s0 /system/bin/sh <script> <step>`.
