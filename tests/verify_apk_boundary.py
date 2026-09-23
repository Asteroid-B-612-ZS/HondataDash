#!/usr/bin/env python3
"""Inspect the actual post-R8 APK, including every DEX, for forbidden diagnostics."""
from pathlib import Path
import hashlib
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
with zipfile.ZipFile(sys.argv[1]) as apk:
    dex = [n for n in apk.namelist() if n.endswith('.dex')]
    assert dex, 'No DEX in APK'
    for name in dex:
        data = apk.read(name)
        for forbidden in (b'FlightRecorder', b'DiagnosticObserver', b'HondataDash/Diagnostics',
                          b'WRITE_EXTERNAL_STORAGE', b'Ljava/io/FileOutputStream;'):
            assert forbidden not in data, (name, forbidden)
    assert b'WRITE_EXTERNAL_STORAGE' not in apk.read('AndroidManifest.xml').replace(b'\x00', b'')
    assert not any(n.startswith('lib/') and n.endswith('.so') for n in apk.namelist()), 'Unexpected native library'
    for line in (ROOT / 'tools/fonts.sha256').read_text().splitlines():
        digest, name = line.split()
        member = name.removeprefix('app/src/main/')
        assert hashlib.sha256(apk.read(member)).hexdigest() == digest, member
print('PASS: final APK has no recorder/storage/native payload; all three packaged fonts match pinned bytes')
