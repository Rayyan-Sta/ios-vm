#!/bin/sh
# OPTIONAL USER-SUPPLIED STEP: filesystem patches applied to the restored root volume.
# The fork documents these on its "Filesystem Patches" wiki page. They need a WRITABLE APFS mount,
# which the stock helper kernel does not have (linux-apfs-rw is out-of-tree and experimental).
# If you cannot mount APFS read-write here, skip this stage and patch the image offline on macOS.
echo "fs-patches.sh is a stub" >&2
exit 1
