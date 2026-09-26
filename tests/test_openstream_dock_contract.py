"""Fast contracts for the legacy OBS dock and the native decoder merge seam.

The Linux release target intentionally does not compile the legacy OBS frontend
dock, so these checks stay source-level. The native source is still compiled
directly by the plugin build verification.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
DOCK = (ROOT / "obs-plugin/src/openstream-dock.cpp").read_text(encoding="utf-8")
SOURCE = (ROOT / "obs-plugin/src/openstream-source.cpp").read_text(encoding="utf-8")


class OpenStreamPluginContractTests(unittest.TestCase):
    def test_dock_is_minimal_and_has_no_ui_thread_update_networking(self) -> None:
        for required in (
            '"Test / connect"',
            '"Disconnect / release"',
            '"Apply zoom"',
            '"openstreamSourceName"',
            'obs_source_set_name',
            'openstream_start_camera_source',
            'openstream_stop_camera_source',
            'send("/zoom"',
            'send("/lens"',
            'send("/torch"',
            'send("/identify"',
        ):
            self.assertIn(required, DOCK)

        for removed in (
            "QNetworkAccessManager",
            "QNetworkRequest",
            "QNetworkReply",
            "checkForUpdates",
            "QDesktopServices",
            "View update",
        ):
            self.assertNotIn(removed, DOCK)

        # Dock buttons are bounded connect/disconnect plus peer-bound camera
        # controls (zoom, lens x2, torch x2, identify). Naming is a local OBS
        # source metadata edit, not a camera or network operation. Every camera
        # action goes through send() into the bounded control slot, never
        # through UI-thread networking.
        self.assertEqual(DOCK.count("new QPushButton"), 8)
        self.assertNotIn("obs_source_remove", DOCK)

    def test_video_decoder_has_one_coherent_definition(self) -> None:
        self.assertEqual(
            len(re.findall(r"\bbool\s+open_video_decoder\s*\(", SOURCE)), 1
        )
        start = SOURCE.index("bool open_video_decoder")
        end = SOURCE.index("bool open_audio_decoder", start)
        body = SOURCE[start:end]

        self.assertIn("AVMEDIA_TYPE_VIDEO", body)
        self.assertIn("*video_stream_index = best_stream", body)
        for broken_identifier in (
            r"\btype\b",
            r"\bis_audio\b",
            r"\bstream_index\b",
            r"\bopen_decoder\b",
        ):
            self.assertIsNone(re.search(broken_identifier, body))


if __name__ == "__main__":
    unittest.main()
