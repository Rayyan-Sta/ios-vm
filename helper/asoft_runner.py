#!/usr/bin/env python3
"""asoft_runner: executes /etc/asoft-setup.json inside the helper VM (stdlib only).

Protocol on the serial console (host = Android app):
  helper -> host   `ASOFT {json}`            events: step/stage/log/done
  host   -> helper `ASOFT-GO <stage-id>`     releases a stage that has wait_for_host
"""
import json
import os
import plistlib
import re
import subprocess
import sys
import tarfile
import time
import zipfile

MANIFEST = os.environ.get("ASOFT_MANIFEST", "/etc/asoft-setup.json")
CFG_DEV = os.environ.get("ASOFT_CFG_DEV", "/dev/vdd")
OUT_DEV = os.environ.get("ASOFT_OUT_DEV", "/dev/vdc")


def emit(**kw):
    print("ASOFT " + json.dumps(kw), flush=True)


class StepError(Exception):
    pass


class Ctx:
    def __init__(self, variables, cfg):
        self.v = dict(variables)
        self.cfg = cfg

    def sub(self, x):
        if isinstance(x, str):
            return re.sub(r"\{([\w.\-]+)\}", lambda m: str(self.v.get(m.group(1), m.group(0))), x)
        if isinstance(x, list):
            return [self.sub(i) for i in x]
        if isinstance(x, dict):
            return {k: self.sub(v) for k, v in x.items()}
        return x


def load_cfg():
    try:
        with open(CFG_DEV, "rb") as f:
            raw = f.read(1 << 20).split(b"\0", 1)[0]
        return json.loads(raw or b"{}")
    except (OSError, ValueError):
        return {}


def run(cmd, **kw):
    emit(ev="log", msg=" ".join(map(str, cmd)))
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, **kw)
    if r.stdout:
        print(r.stdout, flush=True)
    if r.returncode:
        raise StepError("%s exited %d" % (cmd[0], r.returncode))
    return r.stdout


# ---------------------------------------------------------------- step handlers
def h_mkfs_mount(c, s):
    run(["mkfs.ext4", "-F", "-q", s["dev"]])
    os.makedirs(s["mount"], exist_ok=True)
    run(["mount", s["dev"], s["mount"]])


def h_unzip_dev(c, s):
    # The IPSW is attached as a raw virtio-blk device; zipfile only needs seek(0, 2) + reads.
    with zipfile.ZipFile(s["dev"]) as z:
        infos = z.infolist()
        total = sum(i.file_size for i in infos) or 1
        done = 0
        for n, info in enumerate(infos):
            z.extract(info, s["dest"])
            done += info.file_size
            if n % 25 == 0:
                emit(ev="step", id=s["id"], state="progress", msg="%d%%" % (done * 100 // total))


def h_resolve(c, s):
    with open(s["manifest"], "rb") as f:
        pl = plistlib.load(f)
    board = s["board"].lower()
    cands = [b for b in pl["BuildIdentities"]
             if b.get("Info", {}).get("DeviceClass", "").lower() == board
             and b["Info"].get("RestoreBehavior") == s.get("behavior", "Erase")]
    if not cands:
        raise StepError("no build identity for %s/%s" % (board, s.get("behavior")))
    cands.sort(key=lambda b: "Customer" not in b["Info"].get("Variant", ""))  # prefer the Customer variant
    for name, comp in cands[0]["Manifest"].items():
        path = comp.get("Info", {}).get("Path")
        if path:
            c.v["path." + name] = os.path.join(c.v["ipsw"], path)
    emit(ev="log", msg="variant: " + cands[0]["Info"].get("Variant", "?"))


def h_img4_decrypt(c, s):
    comp = s["component"]
    src = c.v.get("path." + comp)
    if not src:
        raise StepError("component %s not in BuildManifest" % comp)
    key = c.cfg.get("keys", {}).get(comp)          # IV+key hex, supplied by the user in keys.json
    if not key and s.get("key_required"):
        raise StepError("keys.json has no entry for %s" % comp)
    os.makedirs(os.path.dirname(s["out"]), exist_ok=True)
    run(["img4", "-i", src, "-o", s["out"]] + (["-k", key] if key else []))


def h_exec(c, s):
    run(s["argv"], cwd=s.get("cwd"))


def h_export_tar(c, s):
    with open(OUT_DEV, "r+b") as dev:
        with tarfile.open(fileobj=dev, mode="w", format=tarfile.USTAR_FORMAT) as t:
            for f in s["files"]:
                t.add(f["src"], arcname=f["name"])
        dev.flush()
        os.fsync(dev.fileno())


def h_wait_usb(c, s):
    end = time.time() + s.get("timeout", 600)
    while time.time() < end:
        out = subprocess.run(["lsusb"], stdout=subprocess.PIPE, text=True).stdout
        if (s.get("vendor", "05ac") + ":") in out:
            return
        time.sleep(2)
    raise StepError("emulated iPhone never appeared on USB (start order: helper first, then iOS)")


HANDLERS = {
    "mkfs_mount": h_mkfs_mount, "unzip_dev": h_unzip_dev, "resolve_components": h_resolve,
    "img4_decrypt": h_img4_decrypt, "exec": h_exec, "export_tar": h_export_tar, "wait_usb": h_wait_usb,
}


def await_go(stage):
    emit(ev="stage", id=stage, state="waiting")
    while True:
        line = sys.stdin.readline()
        if not line:
            raise SystemExit("console closed")
        if line.strip() == "ASOFT-GO " + stage:
            return


def main():
    with open(MANIFEST) as f:
        man = json.load(f)
    ctx = Ctx(man.get("vars", {}), load_cfg())
    for st in man["stages"]:
        if st.get("wait_for_host"):
            await_go(st["id"])
        for raw in st["steps"]:
            s = ctx.sub(raw)
            emit(ev="step", id=s["id"], state="start")
            try:
                HANDLERS[s["type"]](ctx, s)
                emit(ev="step", id=s["id"], state="ok")
            except Exception as e:                      # noqa: BLE001
                if s.get("optional"):
                    emit(ev="step", id=s["id"], state="skip", msg=str(e))
                    continue
                emit(ev="step", id=s["id"], state="fail", msg=str(e))
                sys.exit(1)
        emit(ev="stage", id=st["id"], state="done")
    emit(ev="done")


if __name__ == "__main__":
    main()
