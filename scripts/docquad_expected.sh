#!/usr/bin/env bash
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
makeacopy="${MAKEACOPY:-$here/../../makeacopy-github}"
src="$makeacopy/app/src/main/java/de/schliweb/makeacopy/ml/docquad"
dir="$1"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
pkg="$work/src/de/schliweb/makeacopy/ml/docquad"
mkdir -p "$pkg" "$work/out"
for f in DocQuadPostprocessor DocQuadScore DocQuadLetterbox; do
  sed -e '/lombok/d' -e '/@UtilityClass/d' "$src/$f.java" > "$pkg/$f.java"
done
cat > "$pkg/DocQuadOrtRunner.java" <<'JAVA'
package de.schliweb.makeacopy.ml.docquad;
public final class DocQuadOrtRunner {
  public record Outputs(float[][][][] cornerHeatmaps, float[][][][] maskLogits) {}
}
JAVA
javac -nowarn -d "$work/out" "$pkg"/*.java "$here/docquad_expected/Expected.java"
python3 -I -c 'import json,sys; [print(e["name"], e["width"], e["height"]) for e in json.load(open(sys.argv[1]))]' "$dir/index.json" \
  | java -cp "$work/out" Expected "$dir"
