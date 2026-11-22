#!/bin/sh
# Inputs : $1 extracted IPSW dir   $2 board (n104ap)   $3 decrypted SEP firmware   $4 output dir (/work/fw)
# Outputs: $4/ticket.der   forged root ticket (documented in the fork's "Emulator File Setup" wiki)
#          $4/sep-fw.img4  SEP firmware re-wrapped with the forged SEP ticket (STEP 3: yours to provide)
set -eu
IPSW="$1"; BOARD="$2"; SEP_RAW="$3"; OUT="$4"; T=/opt/qemu-tools
mkdir -p "$OUT"

# 1. root ticket (unsigned iOS version -> forged ticket)
python3 "$T/create_apticket.py" "$BOARD" "$IPSW/BuildManifest.plist" "$T/ticket.shsh2" "$OUT/ticket.der"
# 2. SEP ticket
python3 "$T/create_septicket.py" "$BOARD" "$IPSW/BuildManifest.plist" "$T/ticket.shsh2" "$OUT/sep_root_ticket.der"
# 3. wrap the SEP firmware with that ticket: exact img4 arguments depend on your fork commit, see the wiki.
exec /opt/asoft/user/wrap-sep.sh "$SEP_RAW" "$OUT/sep_root_ticket.der" "$OUT/sep-fw.img4"
