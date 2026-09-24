#!/usr/bin/env python3
"""V3.0 promotion contract: product source must be identical to the road-tested V2.1.1 candidate."""
from pathlib import Path
import subprocess
import re

ROOT = Path(__file__).resolve().parents[1]
ROAD_TESTED = "3e933e56a3ef4a8c3ba149bb91db822023da0777"
BUILD = (ROOT / "app/build.gradle").read_text()
STRINGS = (ROOT / "app/src/main/res/values/strings.xml").read_text()

def sh(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()

# The entire installable product surface is frozen to the road-tested candidate.
for path in ("app/src", "app/proguard-rules.pro"):
    diff = subprocess.check_output(["git", "diff", ROAD_TESTED, "--", path], cwd=ROOT, text=True)
    assert not diff, f"V3.0 product source changed after road-test freeze: {path}"

assert re.search(r"\bversionCode\s+57\b", BUILD)
assert 'versionName "3.0.0"' in BUILD
assert 'applicationId "io.github.asteroidb612zs.hondatadash"' in BUILD
assert re.search(r"\bminSdk\s+17\b", BUILD)
assert re.search(r"\btargetSdk\s+28\b", BUILD)

# Formal V3 source must not silently use an ephemeral CI debug signing key.
release_block = BUILD.split("release {", 1)[1].split("}", 1)[0]
assert "signingConfig signingConfigs.debug" not in release_block

assert "Hondata Dash OEM" in STRINGS
assert "IT3" not in STRINGS and "HF3" not in STRINGS

print("PASS: V3.0 promotion is product-logic identical to road-tested 3e933e56")
