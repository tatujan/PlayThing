# RAM boot inputs

`env-android.txt`: the env for a RAM boot of the Android image with `scripts/sbt-ramboot.py`.
It joins `BOARD_KERNEL_CMDLINE` (`device/spotify/superbird/BoardConfig.mk`) with the stock
display and pstore settings (`sbt --get_env`). It has no `root=` and no `init=`:
Android init is `/init` in the ramdisk, and `/system` comes from `system_b` (fstab).

Debug: `printk.devkmsg=on` stops the rate limit on init messages in the kernel
log (and thus in pstore). Do not use `console=tty0`: the framebuffer console resets the kernel.

DTB: let U-Boot load the stock DTB and remove the Android early-mount node in RAM
(`--dtb-emmc boot_a --pre-cmd "fdt addr 0x1000000" --pre-cmd "fdt rm /firmware/android"`).
With `--dtb FILE` and the Android ramdisk, the kernel did not reach ramoops (cause open).

```sh
PY=out/tools/superbird-tool/.venv/bin/python
python3 scripts/mkuimage-ramdisk.py out/aosp/ramdisk.img out/aosp/ramdisk.uimg
$PY scripts/sbt-ramboot.py --env scripts/ramboot/env-android.txt \
    --kernel out/kernel/android/Image --initrd out/aosp/ramdisk.uimg --dtb-emmc boot_a \
    --pre-cmd "fdt addr 0x1000000" --pre-cmd "fdt rm /firmware/android"
```
