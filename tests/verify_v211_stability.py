#!/usr/bin/env python3
"""V2.1.1 stability-candidate lock.

This verifier intentionally treats visual.3@db607ab as the product baseline and
allows only the explicitly approved truthfulness/calmness delta. It is not a
replacement for Core14 replay or the broader OEM/refinement suites.
"""
from pathlib import Path
import subprocess
import re

ROOT = Path(__file__).resolve().parents[1]
BASELINE = "db607ab745767f019465ae173aca05f6639af9fc"
MAIN = ROOT / "app/src/main/java/io/github/asteroidb612zs/hondatadash/MainActivity.java"
PALETTE = ROOT / "app/src/main/java/io/github/asteroidb612zs/hondatadash/DashboardPalette.java"
DATA = ROOT / "app/src/main/java/io/github/asteroidb612zs/hondatadash/data"
BUILD = ROOT / "app/build.gradle"

# Exact production source lock after the five approved stability changes.
EXPECTED_MAIN_BLOB = "55f58de5e1b334fbc0d42822685000b1b52a522c"
EXPECTED_PALETTE_BLOB = "a3ad1470425420afd2a06377faf5a7ef4c9057ab"
EXPECTED_ADMISSION_BLOB = "57a1857fdda90c9d4c75dc17afb0d00815ade459"
EXPECTED_ENGINE_STATE_BLOB = "65f304d6548c057abfc96cead2f4c1faa78978ac"


def sh(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def method(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 0
    for i in range(brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[start:i + 1]
    raise AssertionError("unterminated method: " + signature)


def main():
    # Frozen protocol / engine semantics remain byte-for-byte identical to visual.3.
    data_diff = subprocess.check_output(
        ["git", "diff", BASELINE, "--", str(DATA.relative_to(ROOT))],
        cwd=ROOT, text=True)
    assert not data_diff, "data/protocol/engine semantic layer changed"

    assert sh("git", "hash-object", str(MAIN.relative_to(ROOT))) == EXPECTED_MAIN_BLOB, (
        "MainActivity differs from the reviewed V2.1.1 stability source")
    assert sh("git", "hash-object", str(PALETTE.relative_to(ROOT))) == EXPECTED_PALETTE_BLOB, (
        "DashboardPalette changed after visual.3 freeze")
    assert sh("git", "hash-object",
              "app/src/main/java/io/github/asteroidb612zs/hondatadash/data/CombustionDisplayAdmission.java") == EXPECTED_ADMISSION_BLOB
    assert sh("git", "hash-object",
              "app/src/main/java/io/github/asteroidb612zs/hondatadash/data/EngineStateTracker.java") == EXPECTED_ENGINE_STATE_BLOB

    build = BUILD.read_text()
    assert 'versionCode 56' in build
    assert 'versionName "2.1.1-stability.1"' in build
    assert 'applicationId "io.github.asteroidb612zs.hondatadash"' in build

    current = MAIN.read_text()
    previous = sh("git", "show", BASELINE + ":" + str(MAIN.relative_to(ROOT)))

    # 1) F.P truthfulness: BT42 0x191 is already bar. Unverified target tracking
    # is deliberately disabled rather than comparing incompatible units.
    assert 'String.format(Locale.US, "%.1f", fp)' in current
    assert 'String.format(Locale.US, "%.1f", fp / 100.0)' not in current
    assert 'Fuel Pressure bar' in current
    assert 'fuelPressureAlert.update(' not in method(current, "public void onDataReceived(")
    assert 'fuelPressureAlert.reset();' in method(current, "public void onDataReceived(")

    # 2) IGN recovery sentinel is presentation-only and bounded.
    assert 'IGN_RECOVERY_SENTINEL_GUARD_MS = 500L' in current
    assert 'now <= ignRecoverySentinelGuardUntilMs' in current
    assert 'renderHeldCombustionCard(i);' in method(current, "public void onDataReceived(")

    # Admission/state semantics themselves must remain frozen.
    for sig in (
        "private int getIgnSemanticColor(",
        "private int getIatColor(",
        "private int getMapSemanticColor(",
        "private boolean isSemanticFramePlausible(",
    ):
        assert method(current, sig) == method(previous, sig), sig + " changed unexpectedly"

    # 3) Closed-loop A/F colour is calmer; WOT protection remains exactly fast.
    attack = method(current, "private long getAfAttackMs(")
    assert 'state.isWot()) return severity >= 2 ? 100L : 150L' in attack
    assert 'return severity >= 2 ? 1000L : 1500L' in attack
    assert '250L : 350L' not in attack
    assert method(current, "private int getAfSeverity(") == method(previous, "private int getAfSeverity(")
    assert method(current, "private boolean isAfColorContext(") == method(previous, "private boolean isAfColorContext(")

    # 4) KC text is rate-limited to the existing 200 ms auxiliary cadence while
    # the established threshold/event evaluation remains per-frame and unchanged.
    on_data = method(current, "public void onDataReceived(")
    kc = on_data[on_data.index("Double kc = data.get(KNOCK_CTRL_PID);"):
                 on_data.index("// 更新 4 个爆震缸")]
    assert 'if (updateAuxiliary)' in kc
    assert 'boolean shouldFlash = pct > 65' in kc
    assert 'if (pct < 55)' in kc
    assert 'pct > 65' in method(previous, "public void onDataReceived(")

    # 5) A/F target marker is structural, not a green safety lamp.
    marker = method(current, "private void updateAfTargetMarker(")
    assert 'DashboardPalette.SCALE_TARGET' in marker
    assert 'DashboardPalette.common(color)' not in marker

    # Deliberately deferred product-strategy experiments remain untouched.
    assert method(current, "private int getIatColor(") == method(previous, "private int getIatColor(")
    assert method(current, "private int getIgnSemanticColor(") == method(previous, "private int getIgnSemanticColor(")

    print("PASS: V2.1.1 stability candidate exact source lock and five approved deltas")


if __name__ == "__main__":
    main()
