#!/usr/bin/env bash
# Host-side checks (no Android toolchain needed). Used by CI; runnable locally.
set -euo pipefail
cd "$(dirname "$0")/.."
T="$(mktemp -d)"; SRV=""
trap '[ -n "$SRV" ] && kill "$SRV" 2>/dev/null || true; rm -rf "$T"' EXIT
CPP=app/src/main/cpp

echo "== syntax: portable native sources"
for f in cpu/cpu_features.cpp gfx/guest_ram_map.cpp qmp/qmp_client.cpp; do
  g++ -std=c++20 -fsyntax-only -Wall -Wextra -Wno-unused-parameter -Werror -Itests/stub -I"$CPP" "$CPP/$f"
done

echo "== QMP client vs fake QEMU"
python3 tests/fake_qmp_server.py "$T/qmp.sock" "$T/server.log" & SRV=$!
for _ in $(seq 50); do [ -S "$T/qmp.sock" ] && break; sleep 0.1; done
g++ -std=c++20 -O1 -Itests/stub -I"$CPP" tests/qmp_test.cpp "$CPP/qmp/qmp_client.cpp" tests/stub/stublog.cpp -o "$T/qmp_test" -lpthread
"$T/qmp_test" "$T/qmp.sock"
wait "$SRV" || true; SRV=""
grep -q '"axis":"x","value":16384' "$T/server.log"          # 0.5 -> abs 16384
grep -q '"data":"audiovolup"' "$T/server.log"
grep -q '"type":"mtt"' "$T/server.log"

echo "== helper runner"
python3 -m unittest -q tests.test_asoft_runner

echo "== scripts / manifests"
python3 -m json.tool helper/asoft-setup.json >/dev/null
bash -n helper/build-helper-vm.sh tools/build-qemu-android.sh tools/build-deps-android.sh qemu-patches/apply.sh
sh -n helper/init
echo "all host checks passed"
