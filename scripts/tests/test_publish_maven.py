import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import urllib.error
import zipfile

SPEC = importlib.util.spec_from_file_location("publish_maven", Path(__file__).parents[1] / "publish-maven.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Repository:
    def __init__(self, files=None, lost_response=False):
        self.files = dict(files or {})
        self.puts = []
        self.lost_response = lost_response

    def open(self, request, timeout):
        name = request.full_url.split("/releases/", 1)[1]
        if request.method == "GET":
            if name not in self.files:
                raise urllib.error.HTTPError(request.full_url, 404, "missing", {}, None)
            return contextlib.closing(io.BytesIO(self.files[name]))
        self.puts.append(name)
        self.files[name] = request.data
        if self.lost_response:
            raise OSError("connection closed after storing bytes")
        return contextlib.closing(io.BytesIO(b""))


class PublicationTest(unittest.TestCase):
    def publish(self, files, repository):
        MODULE.publish(files, "https://maven.example/releases", "ci", "test-secret", repository)

    def test_conflict_is_found_before_any_write(self):
        repository = Repository({"second.jar": b"other bytes"})
        with self.assertRaisesRegex(ValueError, "Immutable release conflict"):
            self.publish({"first.jar": b"first", "second.jar": b"second"}, repository)
        self.assertEqual([], repository.puts)

    def test_resume_skips_identical_files_and_publishes_poms_last(self):
        repository = Repository({"existing.jar": b"same"})
        files = {"a.pom": b"pom", "existing.jar": b"same", "b.jar": b"payload"}
        self.publish(files, repository)
        self.assertEqual(["b.jar", "a.pom"], repository.puts)
        self.publish(files, repository)
        self.assertEqual(["b.jar", "a.pom"], repository.puts)

    def test_lost_upload_response_is_reconciled_without_replaying_write(self):
        repository = Repository(lost_response=True)
        self.publish({"a.jar": b"data"}, repository)
        self.assertEqual(["a.jar"], repository.puts)

    def test_metadata_is_updated_after_payloads_and_preserves_prior_versions(self):
        name = "me/kzheart/klib/klib-core/maven-metadata.xml"
        repository = Repository({name: b"<metadata><groupId>me.kzheart.klib</groupId><artifactId>klib-core</artifactId><versioning><versions><version>0.9.0</version></versions></versioning></metadata>"})
        files = {"me/kzheart/klib/klib-core/0.10.0/klib-core-0.10.0.pom": b"pom"}
        self.publish(files, repository)
        metadata = MODULE.ET.fromstring(repository.files[name])
        self.assertEqual(["0.9.0", "0.10.0"], [e.text for e in metadata.findall("versioning/versions/version")])
        self.assertEqual("0.10.0", metadata.findtext("versioning/latest"))
        self.assertEqual(name, repository.puts[-1])
        count = len(repository.puts)
        self.publish(files, repository)
        self.assertEqual(count, len(repository.puts))

    def test_upload_and_conflict_never_follow_redirects(self):
        handler = MODULE.NoRedirect()
        self.assertIsNone(handler.redirect_request(None, None, 302, "moved", {}, "https://elsewhere.invalid"))
        for url in ("http://maven.example/releases", "https://user:secret@maven.example/releases", "https://maven.example/releases?token=secret"):
            with self.assertRaises(ValueError):
                MODULE.publish({}, url, "ci", "secret", Repository())

    def test_bundle_requires_signatures_checksums_and_component_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "release.zip"
            prefix = "me/kzheart/klib/klib-compat-v1_12/1.0.0/klib-compat-v1_12-1.0.0"
            files = {}
            for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar"):
                name = prefix + suffix
                files[name] = b"content"
                files[name + ".asc"] = b"signature"
                for algorithm in ("md5", "sha1"):
                    files[name + "." + algorithm] = hashlib.new(algorithm, b"content").hexdigest().encode()
            def save():
                with zipfile.ZipFile(path, "w") as archive:
                    for name, content in files.items():
                        archive.writestr(name, content)
            save()
            self.assertEqual(files, MODULE.load_bundle(path, "klib", "1.0.0"))
            with self.assertRaisesRegex(ValueError, "component boundary"):
                MODULE.load_bundle(path, "guard-api", "1.0.0")
            files[prefix + ".jar.md5"] = b"wrong"
            save()
            with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
                MODULE.load_bundle(path, "klib", "1.0.0")
            del files[prefix + ".jar.asc"]
            save()
            with self.assertRaisesRegex(ValueError, "Missing release file"):
                MODULE.load_bundle(path, "klib", "1.0.0")


if __name__ == "__main__":
    unittest.main()
