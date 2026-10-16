#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_root"

# Kotlin uses -Xjdk-release=8; Java uses --release 8. Check outputs, not source language.
command -v javac >/dev/null || { echo 'A full JDK with javac is required.' >&2; exit 1; }

forbidden_api='\b(Map|List|Set)\.of[[:space:]]*\(|java\.net\.http|Files\.(readString|writeString)[[:space:]]*\(|Optional\.isEmpty[[:space:]]*\(|String\.(strip|isBlank|lines|repeat)[[:space:]]*\('
if rg -n --glob '*.java' "$forbidden_api" .; then
  echo 'A Java 9+ API reference was found in Java source.' >&2
  exit 1
fi

publishable_projects=(
  klib-core klib-command klib-config klib-lang klib-item klib-data klib-data-json
  klib-data-jdbc klib-data-sqlite klib-data-mysql klib-data-postgresql klib-ui klib-script
  klib-hook klib-compat klib-compat-v1_12 klib-compat-v1_20 klib-compat-v1_21
  klib-compat-v26 klib-remote klib-guard-api
)

class_count=0
for module in "${publishable_projects[@]}"; do
  artifact="$(find "$module/build/libs" -maxdepth 1 -type f -name '*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"
  if [[ -z "$artifact" ]]; then
    echo "No main JAR found for publishable module: ${module}" >&2
    exit 1
  fi

  module_class_count=0
  while IFS= read -r class_entry; do
    module_class_count=$((module_class_count + 1))
    class_count=$((class_count + 1))
    major_hex="$(unzip -p "$artifact" "$class_entry" | od -An -tx1 -j6 -N2 | tr -d '[:space:]')"
    if [[ "$major_hex" != '0034' ]]; then
      echo "Expected Java 8 classfile major 52, got 0x${major_hex}: ${artifact}!/${class_entry}" >&2
      exit 1
    fi
  done < <(unzip -Z1 "$artifact" | awk '/\.class$/')

  if [[ "$module_class_count" -eq 0 ]]; then
    echo "No class files found in publishable module JAR: ${artifact}" >&2
    exit 1
  fi
done

probe_dir="$(mktemp -d)"
trap 'rm -rf "$probe_dir"' EXIT
printf '%s\n' 'import java.util.Map;' 'final class Probe { Object value = Map.of(); }' > "$probe_dir/Probe.java"
if javac --release 8 -d "$probe_dir" "$probe_dir/Probe.java" >/dev/null 2>&1; then
  echo 'javac --release 8 unexpectedly accepted Map.of().' >&2
  exit 1
fi

echo "Java 8 compatibility check passed for ${class_count} class files."
