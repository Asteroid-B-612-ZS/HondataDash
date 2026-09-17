#!/usr/bin/env python3
"""Reproducible SDK-tool fallback when Gradle artifacts are unavailable.

Resource linking, API-17 javac, D8, zipalign, signing and signature verification.
Does not run R8 shrinking; this is NOT a claim of a Gradle release build.
The caller supplies the existing signing key; no key is generated or embedded.
"""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile
import hashlib

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--resource-jar", required=True, type=Path, help="compile SDK android.jar, e.g. API 33")
    p.add_argument("--api-jar", required=True, type=Path, help="oldest supported API jar, API 17")
    p.add_argument("--aapt2", required=True, type=Path)
    p.add_argument("--zipalign", required=True, type=Path)
    p.add_argument("--apksigner", required=True, type=Path, help="apksigner.jar")
    p.add_argument("--r8", required=True, type=Path, help="r8.jar containing D8")
    p.add_argument("--keystore", required=True, type=Path)
    p.add_argument("--alias", default="androiddebugkey")
    p.add_argument("--ks-pass", default="pass:android", help="apksigner password spec; env:NAME for private keys")
    p.add_argument("--key-pass", default="pass:android")
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--demo", action="store_true",
                   help="allow a USE_DEMO=true source for the demo distribution")
    a = p.parse_args()
    assert not a.output.exists(), "Refusing to overwrite an existing artifact; choose a new output path."
    source = (ROOT / "app/src/main/java/io/github/asteroidb612zs/hondatadash/MainActivity.java").read_text()
    if a.demo:
        assert "private static final boolean USE_DEMO = true;" in source, "Not a demo configuration"
    else:
        assert "private static final boolean USE_DEMO = false;" in source, "Not an on-car configuration"
    gradle = (ROOT / "app/build.gradle").read_text()
    version = re.search(r'versionName "([^"]+)"', gradle).group(1)
    code = re.search(r"versionCode (\d+)", gradle).group(1)
    with tempfile.TemporaryDirectory(prefix="hondata-apk-") as tmp:
        t = Path(tmp)
        for name in ("generated", "classes", "dex"):
            (t / name).mkdir()
        run(a.aapt2, "compile", "--dir", ROOT / "app/src/main/res", "-o", t / "resources.zip")
        run(a.aapt2, "link", "-I", a.resource_jar, "--manifest", ROOT / "app/src/main/AndroidManifest.xml",
            "-A", ROOT / "app/src/main/assets",
            "--min-sdk-version", "17", "--target-sdk-version", "28", "--version-code", code,
            "--version-name", version, "--java", t / "generated", "-o", t / "resources.apk", t / "resources.zip")
        sources = sorted((ROOT / "app/src/main/java").rglob("*.java")) + sorted((t / "generated").rglob("*.java"))
        run("java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "11", "-cp", a.api_jar,
            "-d", t / "classes", *sources)
        with zipfile.ZipFile(t / "classes.jar", "w", zipfile.ZIP_DEFLATED) as z:
            for path in sorted((t / "classes").rglob("*.class")):
                z.write(path, path.relative_to(t / "classes"))
        run("java", "-cp", a.r8, "com.android.tools.r8.D8", "--min-api", "17", "--lib", a.api_jar,
            "--output", t / "dex", t / "classes.jar")
        with zipfile.ZipFile(t / "resources.apk", "a", zipfile.ZIP_DEFLATED) as z:
            for path in sorted((t / "dex").glob("*.dex")):
                z.write(path, path.name)
        run(a.zipalign, "-f", "4", t / "resources.apk", t / "aligned.apk")
        a.output.parent.mkdir(parents=True, exist_ok=True)
        run("java", "-jar", a.apksigner, "sign", "--ks", a.keystore, "--ks-key-alias", a.alias,
            "--ks-pass", a.ks_pass, "--key-pass", a.key_pass,
            "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", "false",
            "--v4-signing-enabled", "false", "--out", a.output, t / "aligned.apk")
        run("java", "-jar", a.apksigner, "verify", "--verbose", "--print-certs", a.output)
        run(a.zipalign, "-c", "4", a.output)
        run(a.aapt2, "dump", "badging", a.output)
    print("APK:", a.output)
    print("SHA256:", hashlib.sha256(a.output.read_bytes()).hexdigest())
    print("Build verified; installation, Android rasterization and real Bluetooth still require hardware.")


if __name__ == "__main__":
    main()
