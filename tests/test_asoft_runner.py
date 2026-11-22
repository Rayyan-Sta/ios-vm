import importlib.util, os, plistlib, tempfile, unittest, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("runner", os.path.join(HERE, "..", "helper", "asoft_runner.py"))
runner = importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)

MANIFEST = {"BuildIdentities": [
    {"Info": {"DeviceClass": "n104ap", "RestoreBehavior": "Update", "Variant": "Customer Upgrade Install (IPSW)"},
     "Manifest": {"KernelCache": {"Info": {"Path": "WRONG"}}}},
    {"Info": {"DeviceClass": "n104ap", "RestoreBehavior": "Erase", "Variant": "Customer Erase Install (IPSW)"},
     "Manifest": {"KernelCache": {"Info": {"Path": "kernelcache.release.iphone12"}}}}]}


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()

    def test_unzip_resolve_export(self):
        ipsw = os.path.join(self.d, "fake.ipsw")
        with zipfile.ZipFile(ipsw, "w") as z:
            z.writestr("BuildManifest.plist", plistlib.dumps(MANIFEST))
            z.writestr("kernelcache.release.iphone12", b"KC")
        with open(ipsw, "ab") as f:
            f.write(b"\0" * 300)                      # block-device padding
        dest = os.path.join(self.d, "ipsw")
        c = runner.Ctx({"ipsw": dest}, {})
        runner.h_unzip_dev(c, {"id": "u", "dev": ipsw, "dest": dest})
        runner.h_resolve(c, {"manifest": dest + "/BuildManifest.plist", "board": "n104ap", "behavior": "Erase"})
        self.assertEqual(c.v["path.KernelCache"], dest + "/kernelcache.release.iphone12")
        self.assertEqual(c.sub("{path.KernelCache}"), c.v["path.KernelCache"])

        out = os.path.join(self.d, "out.img")
        with open(out, "wb") as f:
            f.write(b"\0" * (4 << 20))
        runner.OUT_DEV = out
        src = os.path.join(self.d, "kc.bin")
        open(src, "wb").write(b"hello-kernel")
        runner.h_export_tar(c, {"files": [{"src": src, "name": "kernelcache.bin"}]})
        data = open(out, "rb").read()
        self.assertEqual(data[:15], b"kernelcache.bin")
        self.assertEqual(int(data[124:135], 8), 12)    # what TarExtract.kt parses
        self.assertEqual(data[512:524], b"hello-kernel")

    def test_missing_key_is_an_error(self):
        c = runner.Ctx({"path.RestoreSEP": "/x"}, {"keys": {}})
        with self.assertRaises(runner.StepError):
            runner.h_img4_decrypt(c, {"component": "RestoreSEP", "out": "/tmp/o", "key_required": True})


if __name__ == "__main__":
    unittest.main()
