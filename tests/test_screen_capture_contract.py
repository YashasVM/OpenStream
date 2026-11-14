"""Platform wiring checks; physical projection consent/revocation needs device testing."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main"
NS = "{http://schemas.android.com/apk/res/android}"


class ScreenCaptureContract(unittest.TestCase):
    def test_projection_foreground_service_is_private_and_declared(self):
        manifest = ET.parse(ANDROID / "AndroidManifest.xml").getroot()
        permissions = {node.get(NS + "name") for node in manifest.findall("uses-permission")}
        self.assertIn("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION", permissions)
        self.assertIn("android.permission.FOREGROUND_SERVICE_MICROPHONE", permissions)
        service = next(node for node in manifest.findall("application/service")
                       if node.get(NS + "name") == ".screen.ScreenCaptureService")
        self.assertEqual(service.get(NS + "exported"), "false")
        self.assertEqual(set(service.get(NS + "foregroundServiceType").split("|")),
                         {"mediaProjection", "microphone"})

    def test_callback_registered_before_single_virtual_display_and_released(self):
        service = (ANDROID / "java/dev/openstream/app/screen/ScreenCaptureService.kt").read_text()
        self.assertLess(service.index("capture.registerCallback"), service.index("capture.createVirtualDisplay"))
        self.assertEqual(service.count("createVirtualDisplay("), 1)
        self.assertIn("display?.release()", service)
        self.assertIn("capture?.stop()", service)
        self.assertIn("return START_NOT_STICKY", service)
        self.assertIn("override fun onStop()", service)


if __name__ == "__main__":
    unittest.main()
