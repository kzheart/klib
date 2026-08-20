#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_base="${TMPDIR:-/tmp}"
declare -a audit_roots=()

cleanup() {
    local directory
    for directory in "${audit_roots[@]}"; do
        if [[ "$directory" == "$temporary_base"/klib-remote-verify.* && -d "$directory" ]]; then
            rm -rf -- "$directory"
        fi
    done
}
trap cleanup EXIT

reject_local_resolution() {
    local example_directory="$1"
    local pattern='mavenLocal[[:space:]]*\(|includeBuild[[:space:]]*\(|flatDir[[:space:]]*\(|dependencySubstitution|\bproject[[:space:]]*\(|files[[:space:]]*\(|fileTree[[:space:]]*\('

    if rg --glob '*.gradle' --glob '*.gradle.kts' "$pattern" "$example_directory"; then
        echo "Local dependency resolution is not allowed in $example_directory" >&2
        return 1
    fi
    if find "$example_directory" -type f -name '*.jar' -not -path '*/build/*' -print -quit | grep -q .; then
        echo "Local JAR found in $example_directory" >&2
        return 1
    fi
}

verify_example() {
    local name="$1"
    local task="$2"
    local required_download="$3"
    local example_directory="$project_root/examples/$name"
    local audit_root
    audit_root="$(mktemp -d "$temporary_base/klib-remote-verify.$name.XXXXXX")"
    audit_roots+=("$audit_root")

    mkdir -p "$audit_root/gradle-home" "$audit_root/m2" "$audit_root/project-cache"
    [[ -z "$(find "$audit_root/gradle-home" -mindepth 1 -print -quit)" ]]
    [[ -z "$(find "$audit_root/m2" -mindepth 1 -print -quit)" ]]
    reject_local_resolution "$example_directory"

    GRADLE_USER_HOME="$audit_root/gradle-home" \
        "$project_root/gradlew" \
        -p "$example_directory" \
        --project-cache-dir "$audit_root/project-cache" \
        -Dmaven.repo.local="$audit_root/m2" \
        --no-daemon \
        --no-build-cache \
        --no-configuration-cache \
        --refresh-dependencies \
        --info \
        clean "$task" 2>&1 | tee "$audit_root/build.log"

    if ! rg -q "$required_download" "$audit_root/build.log"; then
        echo "Expected remote download was not observed for $name" >&2
        return 1
    fi
}

verify_example \
    "remote-klib-plugin" \
    "verifyPluginJar" \
    'Downloading https://plugins\.gradle\.org/m2/me/kzheart/klib/me\.kzheart\.klib\.gradle\.plugin/0\.2\.0/'

verify_example \
    "remote-klib-cloud-plugin" \
    "check" \
    'Downloading https://repo\.maven\.apache\.org/maven2/me/kzheart/klib/klib-guard-api/0\.1\.0/'

echo "Remote-only examples resolved and built successfully with separate empty caches."
