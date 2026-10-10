"""Exercise actual delivery decisions with a stateful GitHub double; no external writes."""
import base64
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("release_delivery", ROOT / "scripts/release_delivery.py")
delivery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(delivery)


def digest(data):
    return hashlib.sha256(data).hexdigest()


class FakeGitHub:
    def __init__(self, m, apk_bytes, checksum_bytes, state="published"):
        self.m = m
        self.original_apk = apk_bytes
        self.original_checksums = checksum_bytes
        self.payloads = {m["apk_name"]: apk_bytes, Path(m["checksums_path"]).name: checksum_bytes}
        self.writes = []
        self.downloads = []
        self.state = state
        self.tag_sha = m["source_commit"]
        self.prerelease = False
        self.interrupt_upload = False
        self.broken_public_download = False
        self.corrupt_download = False
        self.ignore_publish = False
        self.api_failure = False
        self.source_version = m["version_name"]
        self.runs = [{"id": 20, "path": m["ci_workflow"], "head_sha": m["source_commit"],
                      "event": "push", "repository": {"full_name": m["repository"]},
                      "head_repository": {"full_name": m["repository"]},
                      "status": "completed", "conclusion": "success",
                      "html_url": "https://github.com/example/actions/runs/20"}]
        if state == "absent":
            self.payloads.clear()

    def api(self, path, optional=False):
        if self.api_failure:
            raise delivery.DeliveryError("GitHub API denied")
        if path.startswith("commits/"):
            return {"sha": self.tag_sha}
        if path.startswith("contents/"):
            source = (f'applicationId "{self.m["application_id"]}"\n'
                      f'versionName "{self.source_version}"\n'
                      f'versionCode {self.m["version_code"]}\nminSdk {self.m["min_sdk"]}\n')
            return {"encoding": "base64", "content": base64.b64encode(source.encode()).decode()}
        if path.startswith("actions/runs?"):
            return {"workflow_runs": copy.deepcopy(self.runs)}
        raise AssertionError(path)

    def get_release(self, tag):
        if self.state == "absent":
            return None
        return {"tag_name": tag, "draft": self.state == "draft", "prerelease": self.prerelease,
                "html_url": f'https://github.com/{self.m["repository"]}/releases/tag/{tag}',
                "assets": [{"name": name, "state": "uploaded", "size": len(data),
                            "digest": "sha256:" + digest(data),
                            "browser_download_url": f'https://github.com/{self.m["repository"]}/releases/download/{tag}/{name}'}
                           for name, data in self.payloads.items()]}

    def create_draft(self, m, root):
        self.writes.append("create-draft")
        self.state = "draft"

    def upload(self, tag, paths):
        for path in paths:
            if path.name in self.payloads:
                raise delivery.DeliveryError("Existing asset cannot be overwritten")
            self.payloads[path.name] = path.read_bytes()
            self.writes.append("upload:" + path.name)
            if self.interrupt_upload:
                self.interrupt_upload = False
                raise delivery.DeliveryError("Interrupted upload")

    def publish(self, m):
        self.writes.append("publish")
        if not self.ignore_publish:
            self.state = "published"

    def download(self, release, asset, target, expected_size):
        self.downloads.append((release["draft"], asset["name"]))
        if self.broken_public_download and not release["draft"]:
            raise delivery.DeliveryError("Public download unavailable")
        data = self.payloads[asset["name"]]
        target.write_bytes(b"tampered" if self.corrupt_download else data)


class ReleaseFixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "release/manifests").mkdir(parents=True)
        (self.root / "docs").mkdir()
        self.apk_bytes = b"signed-apk-test-fixture"
        self.m = json.loads((ROOT / "release/manifests/v3.0.0.json").read_text())
        self.m["apk_size"] = len(self.apk_bytes)
        self.m["apk_sha256"] = digest(self.apk_bytes)
        self.checksum_bytes = f'{self.m["apk_sha256"]}  {self.m["apk_name"]}\n'.encode()
        self.m["checksums_sha256"] = digest(self.checksum_bytes)
        (self.root / self.m["checksums_path"]).write_bytes(self.checksum_bytes)
        (self.root / self.m["notes_path"]).write_text("Approved release notes\n")
        self.apk = self.root / "original.apk"
        self.apk.write_bytes(self.apk_bytes)
        self.client = FakeGitHub(self.m, self.apk_bytes, self.checksum_bytes)

    def validate_fixture(self, path, m):
        delivery.require(path.read_bytes() == self.apk_bytes, "Fixture APK validation failed")

    def invoke(self, mode="audit", apk=None):
        return delivery.deliver(self.client, self.m, self.root, mode, apk, self.validate_fixture)

    def assert_blocked(self, mode="deliver", apk=None):
        with self.assertRaises(delivery.DeliveryError):
            self.invoke(mode, apk)
        self.assertEqual([], self.client.writes)


class ReleaseDeliveryTests(ReleaseFixture):
    def test_published_release_requires_real_download(self):
        receipt = self.invoke()
        self.assertEqual("VERIFIED_PUBLIC_DELIVERY", receipt["status"])
        self.assertEqual(2, len(self.client.downloads))
        self.assertTrue(all(not draft for draft, _ in self.client.downloads))
        self.assertEqual([], self.client.writes)

    def test_checksum_only_release_cannot_pass(self):
        del self.client.payloads[self.m["apk_name"]]
        self.assert_blocked("audit")
        self.assert_blocked("deliver")

    def test_missing_release_without_apk_cannot_create_empty_release(self):
        self.client = FakeGitHub(self.m, self.apk_bytes, self.checksum_bytes, "absent")
        self.assert_blocked()

    def test_bad_local_apk_cannot_create_release(self):
        self.client = FakeGitHub(self.m, self.apk_bytes, self.checksum_bytes, "absent")
        self.apk.write_bytes(b"unsigned")
        self.assert_blocked(apk=self.apk)

    def test_new_release_is_verified_as_draft_before_publication(self):
        self.client = FakeGitHub(self.m, self.apk_bytes, self.checksum_bytes, "absent")
        self.invoke("deliver", self.apk)
        self.assertEqual("create-draft", self.client.writes[0])
        self.assertEqual("publish", self.client.writes[-1])
        self.assertTrue(any(draft for draft, _ in self.client.downloads))
        self.assertTrue(any(not draft for draft, _ in self.client.downloads))

    def test_existing_release_missing_apk_is_repaired_without_republishing(self):
        del self.client.payloads[self.m["apk_name"]]
        self.invoke("deliver", self.apk)
        self.assertEqual(["upload:" + self.m["apk_name"]], self.client.writes)

    def test_missing_checksum_is_repaired_from_approved_file(self):
        del self.client.payloads[Path(self.m["checksums_path"]).name]
        self.invoke("deliver")
        self.assertEqual(["upload:V3.0_CHECKSUMS.txt"], self.client.writes)

    def test_idempotent_delivery_does_not_write_or_delete(self):
        self.invoke("deliver", self.apk)
        self.invoke("deliver", self.apk)
        self.assertEqual([], self.client.writes)

    def test_same_name_different_digest_is_never_overwritten(self):
        self.client.payloads[self.m["apk_name"]] = b"x" * len(self.apk_bytes)
        self.assert_blocked(apk=self.apk)

    def test_checksum_conflict_blocks_even_when_apk_is_missing(self):
        self.client.payloads.pop(self.m["apk_name"])
        self.client.payloads["V3.0_CHECKSUMS.txt"] = b"invalid"
        self.assert_blocked(apk=self.apk)

    def test_tampered_download_is_detected_despite_correct_api_digest(self):
        self.client.corrupt_download = True
        self.assert_blocked("audit")

    def test_public_download_failure_is_not_success(self):
        self.client.broken_public_download = True
        self.assert_blocked("audit")

    def test_upload_interruption_leaves_draft_and_retry_completes(self):
        self.client = FakeGitHub(self.m, self.apk_bytes, self.checksum_bytes, "absent")
        self.client.interrupt_upload = True
        with self.assertRaisesRegex(delivery.DeliveryError, "Interrupted"):
            self.invoke("deliver", self.apk)
        self.assertEqual("draft", self.client.state)
        self.assertNotIn("publish", self.client.writes)
        self.invoke("deliver", self.apk)
        self.assertEqual("published", self.client.state)
        self.assertEqual(1, self.client.writes.count("upload:" + self.m["apk_name"]))

    def test_publish_api_success_without_public_release_is_failure(self):
        self.client.state = "draft"
        self.client.ignore_publish = True
        with self.assertRaisesRegex(delivery.DeliveryError, "still a draft"):
            self.invoke("deliver")

    def test_post_publish_download_failure_never_returns_success(self):
        self.client.state = "draft"
        self.client.broken_public_download = True
        with self.assertRaisesRegex(delivery.DeliveryError, "Public download"):
            self.invoke("deliver")
        self.assertEqual(["publish"], self.client.writes)

    def test_staged_draft_can_publish_without_local_apk(self):
        self.client.state = "draft"
        self.invoke("deliver")
        self.assertEqual(["publish"], self.client.writes)

    def test_audit_cannot_publish_a_draft(self):
        self.client.state = "draft"
        self.assert_blocked("audit")

    def test_prerelease_cannot_be_promoted_implicitly(self):
        self.client.prerelease = True
        self.assert_blocked(apk=self.apk)

    def test_moved_tag_blocks_all_writes(self):
        self.client.tag_sha = "0" * 40
        self.assert_blocked(apk=self.apk)

    def test_source_version_mismatch_blocks_all_writes(self):
        self.client.source_version = "3.0.1"
        self.assert_blocked(apk=self.apk)

    def test_api_failure_is_not_treated_as_missing_release(self):
        self.client.api_failure = True
        self.assert_blocked(apk=self.apk)

    def test_missing_or_failed_or_pending_ci_blocks_all_writes(self):
        for state in ("missing", "failure", "in_progress"):
            with self.subTest(state=state):
                original = copy.deepcopy(self.client.runs)
                if state == "missing":
                    self.client.runs = []
                elif state == "failure":
                    self.client.runs[0]["conclusion"] = "failure"
                else:
                    self.client.runs[0]["status"] = "in_progress"
                self.assert_blocked(apk=self.apk)
                self.client.runs = original

    def test_old_success_cannot_hide_new_failure(self):
        failed = copy.deepcopy(self.client.runs[0])
        failed.update(id=21, conclusion="failure")
        self.client.runs.append(failed)
        self.assert_blocked(apk=self.apk)

    def test_foreign_commit_workflow_or_pr_ci_is_not_production_evidence(self):
        for field, value in (("head_sha", "0" * 40), ("path", ".github/workflows/other.yml"),
                             ("event", "pull_request"), ("head_repository", {"full_name": "other/repo"})):
            with self.subTest(field=field):
                original = copy.deepcopy(self.client.runs[0])
                self.client.runs[0][field] = value
                self.assert_blocked(apk=self.apk)
                self.client.runs[0] = original

    def test_committed_manifests_have_valid_approved_checksums(self):
        for path in (ROOT / "release/manifests").glob("*.json"):
            delivery.load_manifest(ROOT, path.stem)

    def test_manifest_rejects_paths_versions_and_missing_fields(self):
        path = self.root / "release/manifests/v3.0.0.json"
        for field, value in (("checksums_path", "../outside"), ("tag", "v3.0.1"),
                             ("version_code", True), ("ci_workflow", ".github/workflows/weak.yml"),
                             ("apk_sha256", "not-a-hash"), ("apk_name", "../app.apk")):
            with self.subTest(field=field):
                m = dict(self.m, **{field: value})
                path.write_text(json.dumps(m))
                with self.assertRaises(delivery.DeliveryError):
                    delivery.load_manifest(self.root, "v3.0.0")

    def test_manifest_cannot_recalculate_hash_to_accept_changed_checksum(self):
        (self.root / "release/manifests/v3.0.0.json").write_text(json.dumps(self.m))
        (self.root / self.m["checksums_path"]).write_text("modified")
        with self.assertRaisesRegex(delivery.DeliveryError, "checksum file changed"):
            delivery.load_manifest(self.root, "v3.0.0")


class ApkIdentityTests(ReleaseFixture):
    # Signature and Android-tool outputs are independent fixtures, not real APK claims.
    def tool_output(self, args):
        if "badging" in args:
            return (f"package: name='{self.m['application_id']}' versionCode='57' versionName='3.0.0'\n"
                    "sdkVersion:'17'\n")
        return ("Verified using v1 scheme (JAR signing): true\n"
                "Signer #1 certificate SHA-256 digest: " + self.m["certificate_sha256"] + "\n")

    def test_valid_identity_and_signature(self):
        delivery.verify_apk(self.apk, self.m, runner=self.tool_output)

    def test_bad_hash_is_rejected_before_android_tools_run(self):
        self.apk.write_bytes(b"x" * len(self.apk_bytes))
        with self.assertRaisesRegex(delivery.DeliveryError, "SHA-256"):
            delivery.verify_apk(self.apk, self.m, runner=lambda _: self.fail("Tool must not run"))

    def test_wrong_version_package_sdk_debug_flag_certificate_and_v1(self):
        replacements = [("versionName='3.0.0'", "versionName='3.0.1'"),
                        ("versionCode='57'", "versionCode='58'"),
                        (self.m["application_id"], self.m["application_id"] + ".test"),
                        ("sdkVersion:'17'", "sdkVersion:'28'"),
                        ("sdkVersion:'17'", "sdkVersion:'17'\napplication-debuggable"),
                        (self.m["certificate_sha256"], "0" * 64),
                        ("signing): true", "signing): false")]
        for before, after in replacements:
            with self.subTest(before=before):
                def runner(args):
                    return self.tool_output(args).replace(before, after)
                with self.assertRaises(delivery.DeliveryError):
                    delivery.verify_apk(self.apk, self.m, runner=runner)

    def test_unsigned_apk_tool_failure_is_rejected(self):
        def runner(args):
            if "badging" in args:
                return self.tool_output(args)
            raise delivery.CommandError("Signature does not verify")
        with self.assertRaises(delivery.DeliveryError):
            delivery.verify_apk(self.apk, self.m, runner=runner)


class TransportTests(unittest.TestCase):
    def test_only_http_404_means_release_absent(self):
        client = delivery.GitHub()
        for code in (401, 403, 429, 500):
            with self.subTest(code=code), patch.object(delivery, "run", side_effect=delivery.CommandError(f"HTTP {code}")):
                with self.assertRaises(delivery.DeliveryError):
                    client.get_release("v3.0.0")
        with patch.object(delivery, "run", side_effect=delivery.CommandError("HTTP 404")):
            self.assertIsNone(client.get_release("v3.0.0"))

    def test_upload_has_no_overwrite_flag(self):
        with patch.object(delivery, "run", return_value="") as command:
            delivery.GitHub().upload("v3.0.0", [Path("/tmp/original.apk")])
            self.assertNotIn("--clobber", command.call_args.args[0])

    def test_unexpected_download_destination_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(delivery.DeliveryError, "Unexpected asset"):
                delivery.GitHub().download({"tag_name": "v3.0.0", "draft": False},
                    {"name": "app.apk", "browser_download_url": "https://untrusted.example/app.apk"},
                    Path(temp) / "app.apk", 1)


if __name__ == "__main__":
    unittest.main()
