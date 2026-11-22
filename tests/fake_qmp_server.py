#!/usr/bin/env python3
"""Minimal fake QEMU QMP server: greeting, capabilities, id-echoing replies, an async event."""
import json, os, socket, sys

path, logp = sys.argv[1], sys.argv[2]
if os.path.exists(path):
    os.remove(path)
srv = socket.socket(socket.AF_UNIX)
srv.bind(path)
srv.listen(1)
conn, _ = srv.accept()
f = conn.makefile("rw", newline="\n")
f.write('{"QMP": {"version": {}, "capabilities": ["oob"]}}\r\n'); f.flush()
with open(logp, "w") as log:
    for line in f:
        m = json.loads(line)
        log.write(line); log.flush()
        if m["execute"] == "x-ios-vm-info":
            f.write('{"return": {"dram-base": 34359738368, "fb-valid": true}, "id": %d}\r\n' % m["id"])
            f.write('{"timestamp": {"seconds": 1}, "event": "RESUME"}\r\n')
        else:
            f.write('{"return": {}}\r\n')   # input-send-event carries no id: client must drop the reply
        f.flush()
