# User-supplied helper steps

Two steps depend on firmware-specific procedures that are neither redistributable nor stable across
fork commits, so the manifest calls scripts you provide:

| script | called from | must produce |
|---|---|---|
| `forge-tickets.sh` | stage A `forge-tickets` | `/work/fw/ticket.der`, `/work/fw/sep-fw.img4` |
| `fs-patches.sh` (optional) | stage C | patched root volume |

Drop your versions in this folder before running `helper/build-helper-vm.sh`; they are copied into
the initrd at `/opt/asoft/user/`. Firmware keys are never baked in: they come from the `keys.json`
you pick in the app (written to the helper's config disk at runtime).
