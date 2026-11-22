#!/bin/sh
# USER-SUPPLIED STEP (intentionally not shipped).
#
# Inputs : $1 extracted IPSW dir   $2 board (n104ap)   $3 decrypted SEP firmware   $4 output dir (/work/fw)
# Outputs: $4/ticket.der   forged root ticket for the unsigned restore
#          $4/sep-fw.img4  SEP firmware re-wrapped with the forged SEP ticket
#
# Copy the exact commands for your fork commit from the fork's wiki (ChefKissInc/QEMUAppleSilicon:
# "Restore"/"Firmware" pages) - they use create_apticket / create_septicket + `img4 ... -M ... -T rsep`.
# The ticket template (ticket.shsh2) lives in /opt/qemu-tools (QEMUAppleSiliconTools).
echo "forge-tickets.sh is a stub: see helper/user-scripts/README.md" >&2
exit 1
