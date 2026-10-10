#!/usr/bin/env python3
"""Verify signed release delivery; fail closed and never replace existing assets.

The approved manifest is an input, never regenerated from the file under test.
Only `deliver` may write GitHub state. Signing keys, tags and branches are untouched.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "Asteroid-B-612-ZS/HondataDash"


class DeliveryError(RuntimeError):
    pass


class CommandError(DeliveryError):
    pass


def require(condition, message):
    if not condition:
        raise DeliveryError(message)


def run(args):
    result = subprocess.run(args, text=True, capture_output=True, check=False)
    if result.returncode:
        raise CommandError(f"{args[0]} failed: {result.stderr.strip()}")
    return result.stdout


def sha256(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def approved_path(root, value, parent):
    require(isinstance(value, str), "Manifest paths must be strings")
    path = (root / value).resolve()
    require(path.is_relative_to((root / parent).resolve()),
            f"Manifest path must be inside {parent}: {value}")
    require(path.is_file(), f"Missing approved file: {value}")
    return path


def load_manifest(root, tag):
    require(isinstance(tag, str) and re.fullmatch(r"v\d+\.\d+\.\d+", tag),
            "An explicit stable vX.Y.Z tag is required")
    path = root / "release" / "manifests" / f"{tag}.json"
    require(path.is_file(), f"No approved release manifest for {tag}")
    m = json.loads(path.read_text(encoding="utf-8"))
    expected_keys = {
        "schema_version", "repository", "tag", "source_commit", "application_id",
        "version_name", "version_code", "min_sdk", "apk_name", "apk_size",
        "apk_sha256", "certificate_sha256", "checksums_path", "checksums_sha256",
        "notes_path", "ci_workflow", "latest",
    }
    require(set(m) == expected_keys, "Missing or unknown release manifest fields")
    require(type(m["schema_version"]) is int and m["schema_version"] == 1,
            "Unsupported release manifest schema")
    require(m["repository"] == REPOSITORY and m["tag"] == tag,
            "Manifest repository/tag mismatch")
    require(m["version_name"] == tag[1:], "Manifest version and tag disagree")
    require(m["application_id"] == "io.github.asteroidb612zs.hondatadash",
            "Only the production application may be published")
    for field in ("version_code", "min_sdk", "apk_size"):
        require(type(m[field]) is int and m[field] > 0, f"Invalid {field}")
    require(m["min_sdk"] == 17, "Changing API17 compatibility requires a separate policy change")
    require(isinstance(m["source_commit"], str) and
            re.fullmatch(r"[0-9a-f]{40}", m["source_commit"]), "Full source SHA required")
    for field in ("apk_sha256", "certificate_sha256", "checksums_sha256"):
        require(isinstance(m[field], str) and re.fullmatch(r"[0-9a-f]{64}", m[field]),
                f"Invalid approved digest: {field}")
    require(isinstance(m["apk_name"], str) and
            re.fullmatch(r"HondataDash[_-][A-Za-z0-9_.-]+\.apk", m["apk_name"]),
            "Invalid APK asset filename")
    require(m["ci_workflow"] == ".github/workflows/production-ci.yml",
            "Production CI cannot be replaced with a weaker gate")
    require(type(m["latest"]) is bool, "latest must be a boolean")
    checksum = approved_path(root, m["checksums_path"], "release")
    approved_path(root, m["notes_path"], "docs")
    require(sha256(checksum) == m["checksums_sha256"], "Approved checksum file changed")
    require(re.search(r"^" + m["apk_sha256"] + r"\s+\*?" +
                      re.escape(m["apk_name"]) + r"\s*$",
                      checksum.read_text(encoding="utf-8"), re.MULTILINE),
            "Checksum file does not identify the approved APK")
    return m


def verify_apk(path, m, aapt="aapt", apksigner="apksigner", runner=run):
    path = Path(path)
    require(path.is_file() and path.stat().st_size == m["apk_size"],
            "APK is missing, empty or has the wrong size")
    require(sha256(path) == m["apk_sha256"], "APK SHA-256 differs from approved manifest")
    badging = runner([aapt, "dump", "badging", str(path)])
    identity = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",
                         badging, re.MULTILINE)
    require(identity is not None, "Cannot read APK identity")
    require(identity.groups() == (m["application_id"], str(m["version_code"]), m["version_name"]),
            "APK package/version differs from approved release")
    require(re.search(r"^sdkVersion:'" + str(m["min_sdk"]) + r"'$", badging, re.MULTILINE),
            "APK minimum SDK differs from approved release")
    require("application-debuggable" not in badging, "Debuggable APK cannot be published")
    signer_command = ["java", "-jar", apksigner] if apksigner.endswith(".jar") else [apksigner]
    signature = runner(signer_command + ["verify", "--verbose", "--print-certs",
                                        "--min-sdk-version", str(m["min_sdk"]), str(path)])
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", signature)
    require([c.lower() for c in certificates] == [m["certificate_sha256"]],
            "APK signing certificate differs from approved release")
    require("Verified using v1 scheme (JAR signing): true" in signature,
            "API17 requires a verified v1 signature")


class GitHub:
    def __init__(self, repository=REPOSITORY):
        require(repository == REPOSITORY, "Unexpected destination repository")
        self.repository = repository

    def api(self, suffix, optional=False):
        try:
            return json.loads(run(["gh", "api", f"repos/{self.repository}/{suffix}"]))
        except CommandError as error:
            if optional and re.search(r"\bHTTP 404\b", str(error)):
                return None
            raise

    def get_release(self, tag):
        return self.api("releases/tags/" + quote(tag, safe=""), optional=True)

    def create_draft(self, m, root):
        run(["gh", "release", "create", m["tag"], "--repo", self.repository,
             "--draft", "--verify-tag", "--title", f"HondataDash V{m['version_name']}",
             "--notes-file", str(root / m["notes_path"])])

    def upload(self, tag, paths):
        # Never use --clobber: an existing conflicting file must stop delivery.
        run(["gh", "release", "upload", tag, "--repo", self.repository] + [str(p) for p in paths])

    def publish(self, m):
        run(["gh", "release", "edit", m["tag"], "--repo", self.repository,
             "--draft=false", "--latest=" + str(m["latest"]).lower()])

    def download(self, release, asset, target, expected_size):
        expected_url = (f"https://github.com/{self.repository}/releases/download/"
                        f"{quote(release['tag_name'], safe='')}/{quote(asset['name'], safe='')}")
        require(asset.get("browser_download_url") == expected_url, "Unexpected asset download URL")
        if release["draft"]:
            run(["gh", "release", "download", release["tag_name"], "--repo", self.repository,
                 "--pattern", asset["name"], "--dir", str(target.parent)])
        else:
            # Public delivery is checked without a token, using the real user download URL.
            request = Request(expected_url, headers={"User-Agent": "HondataDash-Release-Verification"})
            with urlopen(request, timeout=60) as response, target.open("wb") as output:
                count = 0
                while chunk := response.read(65536):
                    count += len(chunk)
                    require(count <= expected_size, "Downloaded asset exceeds approved size")
                    output.write(chunk)


def verify_source(client, m):
    commit = client.api("commits/" + quote(m["tag"], safe=""))
    require(commit.get("sha") == m["source_commit"], "Tag moved or does not match approved source SHA")
    source = client.api("contents/app/build.gradle?ref=" + m["source_commit"])
    require(source.get("encoding") == "base64", "Cannot read release source identity")
    build = base64.b64decode(source["content"], validate=False).decode("utf-8")
    for field, value in (("applicationId", m["application_id"]), ("versionName", m["version_name"])):
        require(re.search(r"\b" + field + r'\s+"' + re.escape(value) + r'"', build),
                f"Source {field} differs from release manifest")
    for field, value in (("versionCode", m["version_code"]), ("minSdk", m["min_sdk"])):
        require(re.search(r"\b" + field + r"\s+" + str(value) + r"\b", build),
                f"Source {field} differs from release manifest")
    query = urlencode({"head_sha": m["source_commit"], "per_page": 100})
    response = client.api("actions/runs?" + query)
    runs = [r for r in response.get("workflow_runs", [])
            if r.get("path") == m["ci_workflow"]
            and r.get("head_sha") == m["source_commit"]
            and r.get("event") in ("push", "workflow_dispatch")
            and r.get("repository", {}).get("full_name") == m["repository"]
            and r.get("head_repository", {}).get("full_name") == m["repository"]]
    require(runs, "No Production CI evidence for the exact approved source SHA")
    latest = max(runs, key=lambda r: r["id"])
    require(latest.get("status") == "completed" and latest.get("conclusion") == "success",
            "Latest Production CI for the approved source is not successful")
    return latest["html_url"]


def expected_assets(m, root):
    checksums = root / m["checksums_path"]
    return {
        m["apk_name"]: (m["apk_size"], m["apk_sha256"]),
        checksums.name: (checksums.stat().st_size, m["checksums_sha256"]),
    }


def inspect_release(release, m):
    require(release is not None, "Release does not exist")
    require(release.get("tag_name") == m["tag"] and release.get("prerelease") is False,
            "Release tag/type differs from approved formal release")
    require(type(release.get("draft")) is bool, "Release draft state is unknown")
    assets = release.get("assets", [])
    require(len({a["name"] for a in assets}) == len(assets), "Duplicate release asset names")
    return {a["name"]: a for a in assets}


def verify_remote(client, release, m, root, validator, complete=True, public=False):
    assets = inspect_release(release, m)
    if public:
        require(not release["draft"], "Release is still a draft; public delivery is incomplete")
    expected = expected_assets(m, root)
    missing = set(expected) - set(assets)
    require(not complete or not missing, "Missing required release assets: " + ", ".join(sorted(missing)))
    with tempfile.TemporaryDirectory(prefix="hondata-verify-") as temp:
        for name, (size, digest) in expected.items():
            if name not in assets:
                continue
            asset = assets[name]
            require(asset.get("state") == "uploaded" and asset.get("size") == size,
                    f"Incomplete or wrong-size release asset: {name}")
            if asset.get("digest"):
                require(asset["digest"] == "sha256:" + digest, f"Conflicting release digest: {name}")
            target = Path(temp) / name
            client.download(release, asset, target, size)
            require(target.is_file() and target.stat().st_size == size and sha256(target) == digest,
                    f"Downloaded release asset differs from approved file: {name}")
            if name == m["apk_name"]:
                validator(target, m)
    return missing


def deliver(client, m, root, mode="audit", apk=None, validator=verify_apk):
    require(mode in ("audit", "deliver"), "Unknown delivery mode")
    ci_url = verify_source(client, m)
    release = client.get_release(m["tag"])
    if mode == "audit":
        verify_remote(client, release, m, root, validator, public=True)
    else:
        if apk is not None:
            validator(Path(apk), m)
        missing = (verify_remote(client, release, m, root, validator, complete=False)
                   if release is not None else set(expected_assets(m, root)))
        require(m["apk_name"] not in missing or apk is not None,
                "Missing approved APK: supply --apk PATH or attach the signed APK to the draft")
        # All local and existing assets are validated BEFORE any write.
        verify_source(client, m)
        if release is None:
            client.create_draft(m, root)
            release = client.get_release(m["tag"])
            require(release is not None and release.get("draft") is True,
                    "New release must remain a draft until asset verification passes")
        if missing:
            with tempfile.TemporaryDirectory(prefix="hondata-upload-") as temp:
                files = []
                for name in sorted(missing):
                    source = Path(apk) if name == m["apk_name"] else root / m["checksums_path"]
                    target = Path(temp) / name
                    shutil.copyfile(source, target)
                    size, digest = expected_assets(m, root)[name]
                    require(target.stat().st_size == size and sha256(target) == digest,
                            "Upload input changed after validation")
                    files.append(target)
                client.upload(m["tag"], files)
        release = client.get_release(m["tag"])
        verify_remote(client, release, m, root, validator)
        if release["draft"]:
            verify_source(client, m)
            client.publish(m)
        release = client.get_release(m["tag"])
        # A successful API call alone is not successful delivery.
        verify_remote(client, release, m, root, validator, public=True)
    # Detect a concurrent tag move as well as an incomplete public download.
    require(client.api("commits/" + quote(m["tag"], safe="")).get("sha") == m["source_commit"],
            "Tag changed during delivery verification")
    return {
        "status": "VERIFIED_PUBLIC_DELIVERY",
        "tag": m["tag"], "source_commit": m["source_commit"],
        "production_ci": ci_url,
        "release_url": release["html_url"],
        "apk_url": inspect_release(release, m)[m["apk_name"]]["browser_download_url"],
        "apk_sha256": m["apk_sha256"], "certificate_sha256": m["certificate_sha256"],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("audit", "deliver", "verify-local"))
    parser.add_argument("--tag", help="Approved stable tag; audit defaults to latest public release")
    parser.add_argument("--apk", type=Path, help="Original signed APK; never an unsigned CI artifact")
    parser.add_argument("--aapt", default="aapt")
    parser.add_argument("--apksigner", default="apksigner", help="Executable or apksigner.jar")
    args = parser.parse_args()
    try:
        client = GitHub()
        tag = args.tag
        if not tag:
            require(args.mode == "audit", "--tag is mandatory for deliver and verify-local")
            tag = client.api("releases/latest")["tag_name"]
        m = load_manifest(ROOT, tag)
        validator = lambda path, manifest: verify_apk(path, manifest, args.aapt, args.apksigner)
        if args.mode == "verify-local":
            require(args.apk is not None, "verify-local requires --apk")
            validator(args.apk, m)
            print("VERIFIED_LOCAL_APK (release delivery has not been checked)")
            return 0
        receipt = deliver(client, m, ROOT, args.mode, args.apk, validator)
        print(json.dumps(receipt, indent=2))
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
                summary.write(f"## Verified release delivery: {tag}\n\n"
                              f"- [Release]({receipt['release_url']})\n"
                              f"- [Signed APK]({receipt['apk_url']})\n"
                              f"- [Source Production CI]({receipt['production_ci']})\n"
                              f"- APK SHA-256: `{receipt['apk_sha256']}`\n")
        return 0
    except (DeliveryError, OSError, ValueError, KeyError) as error:
        print(f"RELEASE DELIVERY FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
