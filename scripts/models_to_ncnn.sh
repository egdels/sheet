#!/usr/bin/env bash
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

pnnx_version="20261020"

[ $# -eq 4 ] || { sed -n '5,9p' "$0" >&2; exit 2; }
kind="$1"; onnx="$2"; out="$3"; name="$4"
case "$kind" in
  docquad) shapes=("inputshape=[1,3,256,256]") ;;
  det) shapes=("inputshape=[1,3,960,960]" "inputshape2=[1,3,640,1280]") ;;
  rec) shapes=("inputshape=[1,3,48,320]" "inputshape2=[1,3,48,960]") ;;
  *) echo "ERROR: unknown kind $kind" >&2; exit 2 ;;
esac
[ -f "$onnx" ] || { echo "ERROR: $onnx missing" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
python3 -m venv "$work/venv"
"$work/venv/bin/pip" install --quiet "pnnx==$pnnx_version"
cp "$onnx" "$work/model.onnx"
(cd "$work" && "$work/venv/bin/pnnx" model.onnx "${shapes[@]}" fp16=0 >convert.log 2>&1) || { tail -n 20 "$work/convert.log" >&2; exit 1; }
cp "$work/model.ncnn.param" "$out/$name.ncnn.param"
cp "$work/model.ncnn.bin" "$out/$name.ncnn.bin"
echo "layers of $name:"
awk 'NF > 3 && $1 !~ /^[0-9]/ { print $1 }' "$out/$name.ncnn.param" | sort -u | tr '\n' ' '
echo
