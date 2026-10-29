#!/usr/bin/env python3
"""Publish a verified release ZIP with immutable, byte-exact resumability."""
import argparse
import base64
import datetime
import hashlib
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import xml.etree.ElementTree as ET


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def load_bundle(path, component, version):
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", version):
        raise ValueError("Release version must be a three-part semantic version")
    files = {}
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            parts = entry.filename.split("/")
            if len(parts) != 6 or parts[:3] != ["me", "kzheart", "klib"] or parts[4] != version:
                raise ValueError("Unexpected bundle path: " + entry.filename)
            artifact = parts[3]
            if not re.fullmatch(r"klib-[a-z0-9_-]+", artifact):
                raise ValueError("Unexpected artifact: " + artifact)
            if (artifact == "klib-guard-api") != (component == "guard-api"):
                raise ValueError("Bundle crosses component boundary")
            stem = artifact + "-" + version
            allowed = {stem + suffix for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar")}
            allowed |= {name + suffix for name in list(allowed) for suffix in (".asc", ".md5", ".sha1")}
            if parts[5] not in allowed or entry.filename in files:
                raise ValueError("Unexpected or duplicate bundle file: " + entry.filename)
            files[entry.filename] = archive.read(entry)
    artifacts = {name.split("/")[3] for name in files}
    if not artifacts:
        raise ValueError("Empty release bundle")
    for artifact in artifacts:
        prefix = f"me/kzheart/klib/{artifact}/{version}/{artifact}-{version}"
        for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar"):
            name = prefix + suffix
            for required in (name, name + ".asc", name + ".md5", name + ".sha1"):
                if required not in files:
                    raise ValueError("Missing release file: " + required)
            for algorithm in ("md5", "sha1"):
                if hashlib.new(algorithm, files[name]).hexdigest() != files[name + "." + algorithm].decode().strip():
                    raise ValueError("Checksum mismatch: " + name)
    return files


def publish(files, repository, username, password, opener=None):
    parsed = urllib.parse.urlsplit(repository)
    if parsed.scheme != "https" or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Repository must be a clean HTTPS URL")
    if not username or not password:
        raise ValueError("MAVEN_REPOSITORY_USERNAME and MAVEN_REPOSITORY_PASSWORD are required")
    opener = opener or urllib.request.build_opener(NoRedirect())
    authorization = "Basic " + base64.b64encode((username + ":" + password).encode()).decode()

    def request(name, data=None):
        headers = {"Authorization": authorization, "Cache-Control": "no-cache"}
        if data is not None:
            headers["Content-Type"] = "application/octet-stream"
            if name.endswith("/maven-metadata.xml"):
                headers["X-Generate-Checksums"] = "true"
        req = urllib.request.Request(repository.rstrip("/") + "/" + name, data=data,
                                     method="GET" if data is None else "PUT", headers=headers)
        try:
            with opener.open(req, timeout=120) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            error.close()
            if data is None and error.code == 404:
                return None
            raise RuntimeError(f"Repository returned HTTP {error.code} for {name}") from None

    # Inspect the whole bundle before the first write; never overwrite different bytes.
    missing = []
    for name, content in files.items():
        existing = request(name)
        if existing is None:
            missing.append(name)
        elif existing != content:
            raise ValueError("Immutable release conflict: " + name)
    # POMs make coordinates discoverable: publish them only after all payloads.
    for name in sorted(missing, key=lambda item: (item.endswith(".pom"), item)):
        try:
            request(name, files[name])
        except (RuntimeError, OSError):
            # A lost response is ambiguous: reconcile the exact bytes, never blindly retry PUT.
            if request(name) != files[name]:
                raise
        if request(name) != files[name]:
            raise ValueError("Published bytes do not match: " + name)
    # Maven metadata is mutable by design; advertise versions only after payload verification.
    coordinates = {tuple(name.split("/")[:5]) for name in files if len(name.split("/")) == 6}
    for *group, artifact, version in sorted(coordinates):
        name = "/".join(group + [artifact, "maven-metadata.xml"])
        current = request(name)
        metadata = ET.fromstring(current) if current is not None else ET.Element("metadata")
        expected_group = ".".join(group)
        for tag, value in (("groupId", expected_group), ("artifactId", artifact)):
            element = metadata.find(tag)
            if element is None:
                element = ET.SubElement(metadata, tag)
                element.text = value
            elif element.text != value:
                raise ValueError("Metadata coordinate mismatch: " + name)
        versioning = metadata.find("versioning")
        if versioning is None:
            versioning = ET.SubElement(metadata, "versioning")
        versions = versioning.find("versions")
        if versions is None:
            versions = ET.SubElement(versioning, "versions")
        declared = {element.text for element in versions.findall("version")}
        if version in declared:
            continue
        declared.add(version)
        if any(not re.fullmatch(r"\d+\.\d+\.\d+", item or "") for item in declared):
            raise ValueError("Unexpected version in metadata: " + name)
        ordered = sorted(declared, key=lambda item: tuple(map(int, item.split("."))))
        versions.clear()
        for item in ordered:
            ET.SubElement(versions, "version").text = item
        for tag, value in (("latest", ordered[-1]), ("release", ordered[-1]),
                           ("lastUpdated", datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d%H%M%S"))):
            element = versioning.find(tag)
            if element is None:
                element = ET.SubElement(versioning, tag)
            element.text = value
        content = ET.tostring(metadata, encoding="utf-8", xml_declaration=True)
        try:
            request(name, content)
        except (RuntimeError, OSError):
            if request(name) != content:
                raise
        if request(name) != content:
            raise ValueError("Metadata read-back mismatch: " + name)
    print(f"Verified {len(files)} release files; uploaded {len(missing)}; identical existing files retained")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle")
    parser.add_argument("--component", required=True, choices=("klib", "guard-api"))
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    files = load_bundle(args.bundle, args.component, args.version)
    publish(files, os.environ.get("MAVEN_REPOSITORY_URL", "https://maven.kzheart.me/releases"),
            os.environ.get("MAVEN_REPOSITORY_USERNAME", ""), os.environ.get("MAVEN_REPOSITORY_PASSWORD", ""))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # No request objects, headers, credentials, or full server response bodies in CI logs.
        print(f"Publication failed: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
