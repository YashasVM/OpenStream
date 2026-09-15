from pathlib import Path


SOURCE = Path("android/app/src/main/java/dev/openstream/app/MainActivity.kt")


def test_obs_slot_render_cache_renders_initial_empty_state_and_tracks_bitrate():
    source = SOURCE.read_text(encoding="utf-8")

    assert "private var lastObsSlotRenderKeys: List<String>? = null" in source
    assert (
        '"${device.sourceInstanceId}|${device.displayLabel}|${device.busy}|'
        '${device.bitrateMbps}|${reservedBy == device.sourceInstanceId}|$phoneConnected"'
        in source
    )
