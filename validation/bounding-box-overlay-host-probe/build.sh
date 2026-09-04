#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local bounding-box overlay host validation exerciser plugin
# JAR against the already-built SDK jar, runs the synthetic-image detector
# self-check (plus the offline replay gate when the task-local diagnostic
# screenshot is available), the in-process selection-precondition self-check
# and the bridge-telemetry self-check, and gates the JAR content. The probe is
# validation tooling only; it is never part of the production preview bundle or
# product build, and its self-check classes are never packaged into the plugin
# JAR.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_dir="$repo_root/build/worktree/$worktree_id/sdk/libs"
shopt -s nullglob
sdk_jars=("$sdk_dir"/sdk-*.jar)
if [ "${#sdk_jars[@]}" -ne 1 ] || [ ! -f "${sdk_jars[0]}" ]; then
  echo "error: expected exactly one sdk jar in $sdk_dir; found ${#sdk_jars[@]}" >&2
  exit 1
fi
sdk_jar="${sdk_jars[0]}"

base="validation/bounding-box-overlay-host-probe"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

# 1. Compile the plugin and the detector against the SDK.
javac --release 17 -cp "$sdk_jar" -d "$out/plugin" \
  "$base/src/dev/turboism/validation/boundingbox/BoundingBoxOverlayHostValidationPlugin.java" \
  "$base/src/dev/turboism/validation/boundingbox/BoundingBoxIconDetector.java"

# 2. Generate the two validation-only icon PNGs (solid magenta / solid cyan,
#    16x16) into the JAR staging root and validate them structurally.
python3 - "$out/plugin/icons" <<'PY'
import os
import struct
import sys
import zlib

out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)


def write_png(path, rgb):
    width = height = 16
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(tag, data):
        payload = tag + data
        return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as handle:
        handle.write(png)


def validate_png(path):
    with open(path, "rb") as handle:
        data = handle.read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "bad PNG signature"
    offset = 8
    width = height = None
    while offset < len(data):
        (length,) = struct.unpack(">I", data[offset:offset + 4])
        tag = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        expected_crc = struct.unpack(">I", data[offset + 8 + length:offset + 12 + length])[0]
        assert zlib.crc32(tag + payload) == expected_crc, "bad chunk CRC"
        if tag == b"IHDR":
            width, height = struct.unpack(">II", payload[:8])
        offset += 12 + length
    assert (width, height) == (16, 16), "unexpected dimensions %sx%s" % (width, height)


icon_a = os.path.join(out_dir, "icon-a.png")
icon_b = os.path.join(out_dir, "icon-b.png")
write_png(icon_a, (255, 0, 255))
write_png(icon_b, (0, 255, 255))
validate_png(icon_a)
validate_png(icon_b)
print("[probe] generated icons/icon-a.png icons/icon-b.png")
PY

# 3. Compile and run the synthetic-image, selection-precondition and
#    bridge-telemetry self-checks against the compiled plugin classes. The
#    detector covers success, ambiguity rejection, absence, the bounded DPI
#    band, reversed-order and misalignment rejection, spacing bounds,
#    tolerance, the real icons, the mandatory exact-observed 24x23/40 px
#    regression, and — when the task-local diagnostic screenshot is available
#    — an offline replay gate requiring the observed centers (550,524) and
#    (550,564); the selection check covers the pure reference-coordinate
#    scaling and the unique-main-window rule; the telemetry check covers
#    identity-safe wrap/delegate/count/restore with an unchanged callback
#    result.
javac --release 17 -cp "$sdk_jar:$out/plugin" -d "$out/selfcheck" \
  "$base/selfcheck/dev/turboism/validation/boundingbox/selfcheck/DetectorSelfCheck.java" \
  "$base/selfcheck/dev/turboism/validation/boundingbox/SelectionPreconditionSelfCheck.java" \
  "$base/selfcheck/dev/turboism/validation/boundingbox/BridgeTelemetrySelfCheck.java"
replay_screenshot="/tmp/overlay-d2-ui-tree-doubleclick-probe-r4-after.png"
replay_arg=()
if [ -f "$replay_screenshot" ]; then
  replay_arg=("$replay_screenshot")
  echo "[probe] offline replay gate: $replay_screenshot"
else
  echo "[probe] offline replay gate skipped: screenshot not available"
fi
java -cp "$sdk_jar:$out/plugin:$out/selfcheck" \
  dev.turboism.validation.boundingbox.selfcheck.DetectorSelfCheck \
  "$out/plugin/icons/icon-a.png" "$out/plugin/icons/icon-b.png" "${replay_arg[@]}"
java -cp "$sdk_jar:$out/plugin:$out/selfcheck" \
  dev.turboism.validation.boundingbox.SelectionPreconditionSelfCheck
java -cp "$sdk_jar:$out/plugin:$out/selfcheck" \
  dev.turboism.validation.boundingbox.BridgeTelemetrySelfCheck

# 4. Stage the plugin metadata and assemble the validation-only JAR. Only the
#    plugin classes, metadata and icon resources are included; self-check
#    classes stay out.
cp -r "$base/src/META-INF" "$out/plugin/"
output="$repo_root/build/bounding-box-overlay-host-validation-exerciser.jar"
jar cf "$output" -C "$out/plugin" .

# 5. JAR content contract gate.
missing=0
for entry in \
  META-INF/turboism/plugin.json \
  dev/turboism/validation/boundingbox/BoundingBoxOverlayHostValidationPlugin.class \
  dev/turboism/validation/boundingbox/BoundingBoxIconDetector.class \
  icons/icon-a.png \
  icons/icon-b.png
do
  if ! jar tf "$output" | grep -Fqx "$entry"; then
    echo "error: probe jar is missing $entry" >&2
    missing=1
  fi
done
if jar tf "$output" | grep -Fq 'dev/turboism/validation/boundingbox/selfcheck'; then
  echo "error: probe jar must not contain self-check classes" >&2
  missing=1
fi
if [ "$missing" = 1 ]; then
  exit 1
fi
echo "[probe] $output"
sha256sum "$output"
