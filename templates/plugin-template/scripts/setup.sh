#!/usr/bin/env bash
# Setup script for a standalone Turboism plugin project created from
# turboism/turboism-plugin-template (or templates/plugin-template/).
#
# Steps:
#   1. Choose the release channel (stable or beta, default stable) or an explicit version.
#   2. Resolve the release via https://api.turboism.dev/v1/releases/<channel>.json,
#      falling back to the GitHub Releases API.
#   3. Download turboism-sdk-<v>.jar and turboism-event-processor-<v>.jar with their
#      .sha256 sidecars from the GitHub release asset URLs.
#   4. Verify SHA-256 digests, place the verified JARs into libs/, and record
#      turboismSdkVersion=<v> in gradle.properties.
#   5. Prompt interactively (or read CLI flags for non-interactive/CI use) for plugin id,
#      display name, Java package, and author; rename the package directory and plugin
#      class accordingly and update plugin.json, i18n catalogs, and Gradle metadata.
#   6. Write META-INF/turboism/cubism-versions.json using the release API's
#      compatibility.cubismEditor.versions (or the default reviewed version set).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CHANNEL=""
EXPLICIT_VERSION=""
PLUGIN_ID=""
PLUGIN_NAME=""
JAVA_PACKAGE=""
CLASS_NAME=""
AUTHOR_NAME=""
REQUIRES_CUBISM=""
CUBISM_VERSIONS_CSV=""
NON_INTERACTIVE=0

RELEASE_API_BASE="${TURBOISM_SETUP_RELEASE_API_URL:-https://api.turboism.dev/v1/releases}"
GITHUB_API_BASE="${TURBOISM_SETUP_GITHUB_API_URL:-https://api.github.com/repos/turboism/Turboism/releases}"
DOWNLOAD_BASE="${TURBOISM_SETUP_DOWNLOAD_BASE_URL:-https://github.com/turboism/Turboism/releases/download}"

DEFAULT_REVIEWED_VERSIONS=(
  "5.0.00" "5.0.01" "5.0.02" "5.0.03" "5.0.04" "5.0.05" "5.0.06"
  "5.1.00" "5.1.01" "5.1.02" "5.1.03"
  "5.2.00" "5.2.01" "5.2.02" "5.2.03"
  "5.3.00" "5.3.01" "5.3.02" "5.3.03" "5.3.04"
)

usage() {
  cat <<'EOF'
Usage: ./scripts/setup.sh [options]

Options:
  --channel <stable|beta>       Release channel to resolve (default: stable)
  --version <version>           Explicit SDK/framework version (e.g. 0.67.0 or 0.67.0-beta.2)
  --id <plugin-id>              Reverse-domain plugin ID (e.g. dev.example.hello)
  --name <display-name>         Human-readable plugin name (e.g. "Hello Plugin")
  --package <java-package>      Java package for the entrypoint class (e.g. dev.example.hello)
  --class-name <ClassName>      Java entrypoint class simple name (e.g. HelloPlugin)
  --author <author-name>        Plugin author name (e.g. "Your Name")
  --requires-cubism <bool>      Set environment.requiresCubism in plugin.json (true or false)
  --cubism-versions <v1,v2,...> Comma-separated supported Cubism Editor versions
  -y, --yes, --non-interactive  Run without interactive prompts, using flags or defaults
  -h, --help                    Show this help message
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --channel)
      [[ $# -ge 2 ]] || die "--channel requires a value (stable or beta)"
      CHANNEL="$2"
      shift 2
      ;;
    --channel=*)
      CHANNEL="${1#*=}"
      shift
      ;;
    --version)
      [[ $# -ge 2 ]] || die "--version requires a value"
      EXPLICIT_VERSION="${2#v}"
      shift 2
      ;;
    --version=*)
      EXPLICIT_VERSION="${1#*=}"
      EXPLICIT_VERSION="${EXPLICIT_VERSION#v}"
      shift
      ;;
    --id)
      [[ $# -ge 2 ]] || die "--id requires a value"
      PLUGIN_ID="$2"
      shift 2
      ;;
    --id=*)
      PLUGIN_ID="${1#*=}"
      shift
      ;;
    --name)
      [[ $# -ge 2 ]] || die "--name requires a value"
      PLUGIN_NAME="$2"
      shift 2
      ;;
    --name=*)
      PLUGIN_NAME="${1#*=}"
      shift
      ;;
    --package)
      [[ $# -ge 2 ]] || die "--package requires a value"
      JAVA_PACKAGE="$2"
      shift 2
      ;;
    --package=*)
      JAVA_PACKAGE="${1#*=}"
      shift
      ;;
    --class-name)
      [[ $# -ge 2 ]] || die "--class-name requires a value"
      CLASS_NAME="$2"
      shift 2
      ;;
    --class-name=*)
      CLASS_NAME="${1#*=}"
      shift
      ;;
    --author)
      [[ $# -ge 2 ]] || die "--author requires a value"
      AUTHOR_NAME="$2"
      shift 2
      ;;
    --author=*)
      AUTHOR_NAME="${1#*=}"
      shift
      ;;
    --requires-cubism)
      [[ $# -ge 2 ]] || die "--requires-cubism requires true or false"
      REQUIRES_CUBISM="$2"
      shift 2
      ;;
    --requires-cubism=*)
      REQUIRES_CUBISM="${1#*=}"
      shift
      ;;
    --cubism-versions)
      [[ $# -ge 2 ]] || die "--cubism-versions requires a comma-separated list"
      CUBISM_VERSIONS_CSV="$2"
      shift 2
      ;;
    --cubism-versions=*)
      CUBISM_VERSIONS_CSV="${1#*=}"
      shift
      ;;
    -y|--yes|--non-interactive)
      NON_INTERACTIVE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1 (run with --help for usage)"
      ;;
  esac
done

if [[ ! -t 0 ]] || [[ "${CI:-}" == "true" ]]; then
  NON_INTERACTIVE=1
fi

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"

validate_url() {
  local url="$1"
  if [[ "$url" =~ ^https:// ]]; then
    return 0
  fi
  if [[ "$url" =~ ^file:// ]] || [[ "$url" =~ ^http://(127\.0\.0\.1|localhost)(:[0-9]+)?/ ]]; then
    if [[ -n "${TURBOISM_SETUP_RELEASE_API_URL:-}${TURBOISM_SETUP_GITHUB_API_URL:-}${TURBOISM_SETUP_DOWNLOAD_BASE_URL:-}" ]]; then
      return 0
    fi
  fi
  die "refusing non-HTTPS URL: $url"
}

fetch_url() {
  local url="$1"
  local dest="$2"
  validate_url "$url"
  if [[ "$url" =~ ^https:// ]]; then
    curl -fsSL --proto '=https' --tlsv1.2 --retry 2 --connect-timeout 15 --max-time 120 -o "$dest" "$url"
  else
    curl -fsSL --connect-timeout 15 --max-time 120 -o "$dest" "$url"
  fi
}

# Downloads an asset URL to dest.
# Exit codes:
#   0 - downloaded
#   2 - asset absent (HTTP 404 or missing local file:// target)
#   1 - transport or server error
download_asset() {
  local url="$1"
  local dest="$2"
  validate_url "$url"
  if [[ "$url" =~ ^file:// ]]; then
    local local_path="${url#file://}"
    if [[ ! -e "$local_path" ]]; then
      return 2
    fi
    cp "$local_path" "$dest" || return 1
    return 0
  fi
  local http_code
  local curl_opts=(-sSL -w '%{http_code}' --connect-timeout 15 --max-time 120 -o "$dest")
  if [[ "$url" =~ ^https:// ]]; then
    curl_opts=(--proto '=https' --tlsv1.2 --retry 2 "${curl_opts[@]}")
  fi
  http_code="$(curl "${curl_opts[@]}" "$url")" || {
    rm -f "$dest"
    return 1
  }
  if [[ "$http_code" == "200" ]]; then
    return 0
  elif [[ "$http_code" == "404" ]]; then
    rm -f "$dest"
    return 2
  else
    rm -f "$dest"
    return 1
  fi
}

compute_sha256() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print tolower($1)}'
  else
    python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$file"
  fi
}

verify_sidecar() {
  local jar_file="$1"
  local sha_file="$2"
  local expected_name="$3"
  [[ -s "$jar_file" ]] || die "downloaded artifact is missing or empty: $expected_name"
  [[ -s "$sha_file" ]] || die "downloaded checksum sidecar is missing or empty: ${expected_name}.sha256"
  local expected_hash
  expected_hash="$(awk 'NR==1 {print tolower($1)}' "$sha_file")"
  if [[ ! "$expected_hash" =~ ^[0-9a-f]{64}$ ]]; then
    die "malformed SHA-256 sidecar for $expected_name: $(cat "$sha_file")"
  fi
  local actual_hash
  actual_hash="$(compute_sha256 "$jar_file")"
  if [[ "$actual_hash" != "$expected_hash" ]]; then
    die "SHA-256 mismatch for $expected_name: expected $expected_hash, got $actual_hash"
  fi
}

prompt_value() {
  local prompt_label="$1"
  local default_val="$2"
  local reply=""
  read -r -p "$prompt_label [$default_val]: " reply
  if [[ -z "$reply" ]]; then
    printf '%s' "$default_val"
  else
    printf '%s' "$reply"
  fi
}

# Read current defaults from plugin.json so re-running setup is idempotent.
PLUGIN_JSON="$PROJECT_ROOT/src/main/resources/META-INF/turboism/plugin.json"
[[ -f "$PLUGIN_JSON" ]] || die "missing plugin descriptor: $PLUGIN_JSON"

mapfile -t CURRENT_META < <(python3 - "$PLUGIN_JSON" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
plugin_id = data.get("id", "dev.example.hello")
name = data.get("name", "Hello Plugin")
entrypoints = data.get("entrypoints") or ["dev.example.hello.HelloPlugin"]
ep = entrypoints[0]
pkg, _, cls = ep.rpartition(".")
authors = data.get("authors") or [{"name": "Your Name"}]
author = authors[0].get("name", "Your Name") if isinstance(authors[0], dict) else "Your Name"
req_cubism = "true" if data.get("environment", {}).get("requiresCubism") else "false"
print(plugin_id)
print(name)
print(pkg or "dev.example.hello")
print(cls or "HelloPlugin")
print(author)
print(req_cubism)
PY
)

CUR_ID="${CURRENT_META[0]}"
CUR_NAME="${CURRENT_META[1]}"
CUR_PACKAGE="${CURRENT_META[2]}"
CUR_CLASS="${CURRENT_META[3]}"
CUR_AUTHOR="${CURRENT_META[4]}"
CUR_REQUIRES_CUBISM="${CURRENT_META[5]}"

# Step 1: Choose channel or explicit version.
if [[ -z "$EXPLICIT_VERSION" && -z "$CHANNEL" ]]; then
  if [[ "$NON_INTERACTIVE" -eq 0 ]]; then
    choice="$(prompt_value "Release channel (stable/beta) or explicit version" "stable")"
    if [[ "$choice" == "stable" || "$choice" == "beta" ]]; then
      CHANNEL="$choice"
    else
      EXPLICIT_VERSION="${choice#v}"
    fi
  else
    CHANNEL="stable"
  fi
fi

if [[ -n "$EXPLICIT_VERSION" ]]; then
  if [[ ! "$EXPLICIT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    die "invalid version format: $EXPLICIT_VERSION"
  fi
  if [[ -z "$CHANNEL" ]]; then
    if [[ "$EXPLICIT_VERSION" == *-* ]]; then
      CHANNEL="beta"
    else
      CHANNEL="stable"
    fi
  fi
else
  [[ "$CHANNEL" == "stable" || "$CHANNEL" == "beta" ]] || die "unsupported channel: $CHANNEL (expected stable or beta)"
fi

WORK_TMP="$(mktemp -d "${TMPDIR:-/tmp}/turboism-plugin-setup.XXXXXX")"
cleanup() {
  rm -rf "$WORK_TMP"
}
trap cleanup EXIT

# Step 2: Resolve release through https://api.turboism.dev/v1/releases/<channel>.json,
# falling back to the GitHub Releases API.
RESOLVED_VERSION=""
RESOLVED_COMPAT_VERSIONS=""

resolve_from_release_api() {
  local ch="$1"
  local target_ver="$2"
  local api_file="$WORK_TMP/release-api.json"
  local url="${RELEASE_API_BASE%/}/${ch}.json"
  if ! fetch_url "$url" "$api_file" 2>/dev/null; then
    return 1
  fi
  python3 - "$api_file" "$target_ver" <<'PY'
import json, re, sys
path, target_ver = sys.argv[1], sys.argv[2]
try:
    doc = json.load(open(path, encoding="utf-8"))
except Exception:
    sys.exit(1)
if not isinstance(doc, dict) or doc.get("status") != "ready":
    sys.exit(1)
rel = doc.get("release")
if not isinstance(rel, dict):
    sys.exit(1)
ver = str(rel.get("version") or "").strip()
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", ver):
    sys.exit(1)
if target_ver and ver != target_ver:
    sys.exit(1)
compat = rel.get("compatibility")
versions = []
if isinstance(compat, dict):
    ce = compat.get("cubismEditor")
    if isinstance(ce, dict) and isinstance(ce.get("versions"), list):
        versions = [str(v) for v in ce["versions"] if re.fullmatch(r"5\.\d{1,2}\.\d{2}", str(v))]
print(ver)
print(",".join(versions))
PY
}

resolve_from_github_api() {
  local ch="$1"
  local target_ver="$2"
  local gh_file="$WORK_TMP/github-releases.json"
  local url
  if [[ -n "$target_ver" ]]; then
    url="${GITHUB_API_BASE%/}/tags/v${target_ver}"
  elif [[ "$ch" == "stable" ]]; then
    url="${GITHUB_API_BASE%/}/latest"
  else
    url="${GITHUB_API_BASE%/}?per_page=30"
  fi
  if ! fetch_url "$url" "$gh_file" 2>/dev/null; then
    if [[ -z "$target_ver" && "$ch" == "stable" ]]; then
      url="${GITHUB_API_BASE%/}?per_page=30"
      fetch_url "$url" "$gh_file" 2>/dev/null || return 1
    else
      return 1
    fi
  fi
  python3 - "$gh_file" "$ch" "$target_ver" <<'PY'
import json, re, sys
path, ch, target_ver = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    raw = json.load(open(path, encoding="utf-8"))
except Exception:
    sys.exit(1)
items = [raw] if isinstance(raw, dict) else (raw if isinstance(raw, list) else [])
selected = None
for item in items:
    if not isinstance(item, dict) or item.get("draft") is True:
        continue
    tag = str(item.get("tag_name") or "")
    if not tag.startswith("v"):
        continue
    ver = tag[1:]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", ver):
        continue
    if target_ver:
        if ver == target_ver:
            selected = (ver, item)
            break
        continue
    is_pre = bool(item.get("prerelease"))
    if ch == "stable" and not is_pre and "-" not in ver:
        selected = (ver, item)
        break
    if ch == "beta" and is_pre and "-0.nightly." not in ver:
        selected = (ver, item)
        break
if selected is None:
    sys.exit(1)
ver, rel = selected
body = str(rel.get("body") or "")
versions = []
m = re.search(r"<!-- turboism-compatibility-v1 ([\s\S]*?) -->", body)
if m:
    try:
        compat = json.loads(m.group(1))
        ce = compat.get("cubismEditor", {})
        if isinstance(ce.get("versions"), list):
            versions = [str(v) for v in ce["versions"] if re.fullmatch(r"5\.\d{1,2}\.\d{2}", str(v))]
    except Exception:
        pass
print(ver)
print(",".join(versions))
PY
}

printf 'Resolving Turboism release (%s)...\n' "${EXPLICIT_VERSION:-channel=$CHANNEL}"
if api_out="$(resolve_from_release_api "$CHANNEL" "$EXPLICIT_VERSION")"; then
  RESOLVED_VERSION="$(printf '%s\n' "$api_out" | sed -n '1p')"
  RESOLVED_COMPAT_VERSIONS="$(printf '%s\n' "$api_out" | sed -n '2p')"
  printf 'Resolved version %s via release API.\n' "$RESOLVED_VERSION"
elif gh_out="$(resolve_from_github_api "$CHANNEL" "$EXPLICIT_VERSION")"; then
  RESOLVED_VERSION="$(printf '%s\n' "$gh_out" | sed -n '1p')"
  RESOLVED_COMPAT_VERSIONS="$(printf '%s\n' "$gh_out" | sed -n '2p')"
  printf 'Resolved version %s via GitHub Releases API.\n' "$RESOLVED_VERSION"
elif [[ -n "$EXPLICIT_VERSION" ]]; then
  RESOLVED_VERSION="$EXPLICIT_VERSION"
  printf 'Using explicit version %s.\n' "$RESOLVED_VERSION"
else
  die "unable to resolve release for channel '$CHANNEL' from release API or GitHub Releases API"
fi

[[ -n "$RESOLVED_VERSION" ]] || die "resolved version is empty"

# Step 3 & 4: Download SDK and (when present in the release) event-processor JARs +
# .sha256 sidecars, verify hashes, place in libs/, and update gradle.properties.
TAG="v${RESOLVED_VERSION}"
SDK_JAR_NAME="turboism-sdk-${RESOLVED_VERSION}.jar"
PROC_JAR_NAME="turboism-event-processor-${RESOLVED_VERSION}.jar"

sdk_url="${DOWNLOAD_BASE%/}/${TAG}/${SDK_JAR_NAME}"
printf 'Downloading %s...\n' "$SDK_JAR_NAME"
sdk_rc=0
download_asset "$sdk_url" "$WORK_TMP/$SDK_JAR_NAME" || sdk_rc=$?
if [[ "$sdk_rc" -eq 2 ]]; then
  die "release ${TAG} does not include ${SDK_JAR_NAME} (this release predates standalone SDK distribution); use --channel beta or --version <release with SDK>."
elif [[ "$sdk_rc" -ne 0 ]]; then
  die "failed to download $sdk_url"
fi

sdk_sha_url="${DOWNLOAD_BASE%/}/${TAG}/${SDK_JAR_NAME}.sha256"
printf 'Downloading %s.sha256...\n' "$SDK_JAR_NAME"
download_asset "$sdk_sha_url" "$WORK_TMP/${SDK_JAR_NAME}.sha256" || die "failed to download $sdk_sha_url"
verify_sidecar "$WORK_TMP/$SDK_JAR_NAME" "$WORK_TMP/${SDK_JAR_NAME}.sha256" "$SDK_JAR_NAME"

proc_url="${DOWNLOAD_BASE%/}/${TAG}/${PROC_JAR_NAME}"
printf 'Downloading %s...\n' "$PROC_JAR_NAME"
proc_rc=0
download_asset "$proc_url" "$WORK_TMP/$PROC_JAR_NAME" || proc_rc=$?
HAVE_EVENT_PROCESSOR=0
if [[ "$proc_rc" -eq 0 ]]; then
  HAVE_EVENT_PROCESSOR=1
  proc_sha_url="${DOWNLOAD_BASE%/}/${TAG}/${PROC_JAR_NAME}.sha256"
  printf 'Downloading %s.sha256...\n' "$PROC_JAR_NAME"
  download_asset "$proc_sha_url" "$WORK_TMP/${PROC_JAR_NAME}.sha256" || die "failed to download $proc_sha_url"
  verify_sidecar "$WORK_TMP/$PROC_JAR_NAME" "$WORK_TMP/${PROC_JAR_NAME}.sha256" "$PROC_JAR_NAME"
elif [[ "$proc_rc" -eq 2 ]]; then
  printf 'warning: release %s does not include %s; @SubscribeEvent catalogs will not be generated; use EventBus#subscribe, or rerun setup after a release that ships turboism-event-processor.\n' "$TAG" "$PROC_JAR_NAME" >&2
else
  die "failed to download $proc_url"
fi

# Validate JAR structure before installing into libs/
python3 - "$WORK_TMP/$SDK_JAR_NAME" "$WORK_TMP/$PROC_JAR_NAME" "$HAVE_EVENT_PROCESSOR" <<'PY'
import sys, zipfile
sdk_path, proc_path, have_proc = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
try:
    with zipfile.ZipFile(sdk_path) as z:
        if "dev/turboism/sdk/plugin/TurboismPlugin.class" not in z.namelist():
            raise SystemExit(f"error: {sdk_path} is not a valid Turboism SDK JAR")
    if have_proc:
        with zipfile.ZipFile(proc_path) as z:
            if "dev/turboism/eventprocessor/SubscribeEventProcessor.class" not in z.namelist():
                raise SystemExit(f"error: {proc_path} is not a valid Turboism event-processor JAR")
except zipfile.BadZipFile as exc:
    raise SystemExit(f"error: corrupted JAR archive: {exc}")
PY

mkdir -p "$PROJECT_ROOT/libs"
rm -f "$PROJECT_ROOT"/libs/turboism-sdk-*.jar "$PROJECT_ROOT"/libs/turboism-sdk-*.jar.sha256
rm -f "$PROJECT_ROOT"/libs/turboism-event-processor-*.jar "$PROJECT_ROOT"/libs/turboism-event-processor-*.jar.sha256

cp "$WORK_TMP/$SDK_JAR_NAME" "$PROJECT_ROOT/libs/$SDK_JAR_NAME"
cp "$WORK_TMP/${SDK_JAR_NAME}.sha256" "$PROJECT_ROOT/libs/${SDK_JAR_NAME}.sha256"
if [[ "$HAVE_EVENT_PROCESSOR" -eq 1 ]]; then
  cp "$WORK_TMP/$PROC_JAR_NAME" "$PROJECT_ROOT/libs/$PROC_JAR_NAME"
  cp "$WORK_TMP/${PROC_JAR_NAME}.sha256" "$PROJECT_ROOT/libs/${PROC_JAR_NAME}.sha256"
  printf 'Verified SHA-256 checksums and installed %s and %s into libs/.\n' "$SDK_JAR_NAME" "$PROC_JAR_NAME"
else
  printf 'Verified SHA-256 checksum and installed %s into libs/ (event-processor not in release).\n' "$SDK_JAR_NAME"
fi

# Record turboismSdkVersion in gradle.properties
GRADLE_PROPS="$PROJECT_ROOT/gradle.properties"
python3 - "$GRADLE_PROPS" "$RESOLVED_VERSION" <<'PY'
import pathlib, re, sys
path = pathlib.Path(sys.argv[1])
version = sys.argv[2]
if path.is_file():
    text = path.read_text(encoding="utf-8")
    if re.search(r"(?m)^turboismSdkVersion=", text):
        text = re.sub(r"(?m)^turboismSdkVersion=.*$", f"turboismSdkVersion={version}", text)
    else:
        text = text.rstrip("\n") + f"\nturboismSdkVersion={version}\n"
else:
    text = f"turboismSdkVersion={version}\n"
path.write_text(text, encoding="utf-8")
PY

# Step 5: Gather plugin metadata (interactively or via flags/defaults) and update files.
if [[ -z "$PLUGIN_ID" ]]; then
  if [[ "$NON_INTERACTIVE" -eq 0 ]]; then
    PLUGIN_ID="$(prompt_value "Plugin ID (reverse-domain)" "$CUR_ID")"
  else
    PLUGIN_ID="$CUR_ID"
  fi
fi

if [[ -z "$PLUGIN_NAME" ]]; then
  if [[ "$NON_INTERACTIVE" -eq 0 ]]; then
    PLUGIN_NAME="$(prompt_value "Plugin display name" "$CUR_NAME")"
  else
    PLUGIN_NAME="$CUR_NAME"
  fi
fi

DEFAULT_PKG="$CUR_PACKAGE"
if [[ "$PLUGIN_ID" != "$CUR_ID" ]]; then
  DEFAULT_PKG="$(python3 - "$PLUGIN_ID" <<'PY'
import re, sys
parts = []
for seg in sys.argv[1].lower().split("."):
    clean = re.sub(r"[^a-z0-9_]", "_", seg.replace("-", "_"))
    if not clean or clean[0].isdigit():
        clean = "_" + clean
    parts.append(clean)
print(".".join(parts))
PY
)"
fi

if [[ -z "$JAVA_PACKAGE" ]]; then
  if [[ "$NON_INTERACTIVE" -eq 0 ]]; then
    JAVA_PACKAGE="$(prompt_value "Java package" "$DEFAULT_PKG")"
  else
    JAVA_PACKAGE="$DEFAULT_PKG"
  fi
fi

if [[ -z "$AUTHOR_NAME" ]]; then
  if [[ "$NON_INTERACTIVE" -eq 0 ]]; then
    AUTHOR_NAME="$(prompt_value "Author name" "$CUR_AUTHOR")"
  else
    AUTHOR_NAME="$CUR_AUTHOR"
  fi
fi

if [[ -z "$REQUIRES_CUBISM" ]]; then
  REQUIRES_CUBISM="$CUR_REQUIRES_CUBISM"
fi

if [[ -z "$CUBISM_VERSIONS_CSV" ]]; then
  if [[ -n "$RESOLVED_COMPAT_VERSIONS" ]]; then
    CUBISM_VERSIONS_CSV="$RESOLVED_COMPAT_VERSIONS"
  else
    CUBISM_VERSIONS_CSV="$(IFS=,; printf '%s' "${DEFAULT_REVIEWED_VERSIONS[*]}")"
  fi
fi

# Step 5 & 6: Apply package/class rename, update plugin.json, i18n, build.gradle.kts,
# settings.gradle.kts, and META-INF/turboism/cubism-versions.json via Python.
python3 - "$PROJECT_ROOT" "$CUR_ID" "$CUR_PACKAGE" "$CUR_CLASS" \
  "$PLUGIN_ID" "$PLUGIN_NAME" "$JAVA_PACKAGE" "$CLASS_NAME" "$AUTHOR_NAME" \
  "$REQUIRES_CUBISM" "$CUBISM_VERSIONS_CSV" <<'PY'
import json
import pathlib
import re
import shutil
import sys

(
    project_root_str,
    cur_id,
    cur_pkg,
    cur_cls,
    new_id,
    new_name,
    new_pkg,
    explicit_cls,
    new_author,
    req_cubism_str,
    cubism_versions_csv,
) = sys.argv[1:]

root = pathlib.Path(project_root_str)

if not re.fullmatch(r"[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+", new_id):
    raise SystemExit(
        f"error: invalid plugin id '{new_id}' (must be reverse-domain lowercase, e.g. dev.example.myplugin)"
    )

JAVA_KEYWORDS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while", "true", "false", "null",
    "record", "var", "yield", "sealed", "permits", "non-sealed",
}

pkg_parts = new_pkg.split(".")
if len(pkg_parts) < 2 or not all(
    re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", p) and p not in JAVA_KEYWORDS for p in pkg_parts
):
    raise SystemExit(f"error: invalid Java package '{new_pkg}'")

if not new_name.strip():
    raise SystemExit("error: plugin display name must not be empty")
if not new_author.strip():
    raise SystemExit("error: author name must not be empty")

if req_cubism_str not in ("true", "false"):
    raise SystemExit(f"error: --requires-cubism must be true or false (got '{req_cubism_str}')")
req_cubism = req_cubism_str == "true"

def derive_class_name(display_name: str, plugin_id: str) -> str:
    words = re.findall(r"[A-Za-z0-9]+", display_name)
    if not words:
        words = re.findall(r"[A-Za-z0-9]+", plugin_id.split(".")[-1])
    pascal = "".join(w[:1].upper() + w[1:] for w in words if w)
    if not pascal or pascal[0].isdigit():
        pascal = "Plugin" + pascal
    if not pascal.endswith("Plugin"):
        pascal += "Plugin"
    return pascal

if explicit_cls:
    new_cls = explicit_cls
elif new_name != "Hello Plugin" or new_id != cur_id:
    new_cls = derive_class_name(new_name, new_id)
else:
    new_cls = cur_cls

if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", new_cls) or new_cls in JAVA_KEYWORDS:
    raise SystemExit(f"error: invalid Java class name '{new_cls}'")

versions = [v.strip() for v in cubism_versions_csv.split(",") if v.strip()]
if not versions or not all(re.fullmatch(r"5\.\d{1,2}\.\d{2}", v) for v in versions):
    raise SystemExit(f"error: invalid Cubism version list: {cubism_versions_csv}")
versions = sorted(set(versions), key=lambda v: tuple(int(x) for x in v.split(".")))

java_root = root / "src/main/java"
old_java_file = java_root / cur_pkg.replace(".", "/") / f"{cur_cls}.java"
new_java_file = java_root / new_pkg.replace(".", "/") / f"{new_cls}.java"

if not old_java_file.is_file() and new_java_file.is_file():
    old_java_file = new_java_file

if not old_java_file.is_file():
    raise SystemExit(f"error: entrypoint source file not found: {old_java_file}")

source_text = old_java_file.read_text(encoding="utf-8")
source_text = re.sub(
    r"(?m)^package\s+[A-Za-z0-9_.]+\s*;",
    f"package {new_pkg};",
    source_text,
    count=1,
)
source_text = re.sub(
    rf"\bpublic\s+class\s+{re.escape(cur_cls)}\b",
    f"public class {new_cls}",
    source_text,
    count=1,
)
source_text = source_text.replace(f'"{cur_cls} ', f'"{new_cls} ')

new_java_file.parent.mkdir(parents=True, exist_ok=True)
new_java_file.write_text(source_text, encoding="utf-8")

if old_java_file.resolve() != new_java_file.resolve():
    old_java_file.unlink()
    parent = old_java_file.parent
    while parent != java_root and parent.is_dir() and not any(parent.iterdir()):
        parent.rmdir()
        parent = parent.parent

# Update plugin.json
plugin_json_path = root / "src/main/resources/META-INF/turboism/plugin.json"
plugin_data = json.loads(plugin_json_path.read_text(encoding="utf-8"))
plugin_data["id"] = new_id
plugin_data["name"] = new_name
plugin_data["entrypoints"] = [f"{new_pkg}.{new_cls}"]
plugin_data["authors"] = [{"name": new_author}]
if isinstance(plugin_data.get("environment"), dict):
    plugin_data["environment"]["requiresCubism"] = req_cubism
plugin_json_path.write_text(
    json.dumps(plugin_data, indent=2, ensure_ascii=False) + "\n",
    encoding="utf-8",
)

# Update i18n properties files
i18n_dir = root / "src/main/resources/META-INF/turboism/i18n"
if i18n_dir.is_dir():
    for prop_file in sorted(i18n_dir.glob("messages*.properties")):
        prop_text = prop_file.read_text(encoding="utf-8")
        prop_text = re.sub(
            r"(?m)^plugin\.name=.*$",
            f"plugin.name={new_name}",
            prop_text,
        )
        prop_file.write_text(prop_text, encoding="utf-8")

# Write META-INF/turboism/cubism-versions.json
cubism_versions_path = root / "src/main/resources/META-INF/turboism/cubism-versions.json"
cubism_versions_doc = {
    "format": "turboism.plugin-cubism-versions/v1",
    "plugin": new_id,
    "supportedVersions": versions,
    "fullFeatureVersions": versions,
}
cubism_versions_path.write_text(
    json.dumps(cubism_versions_doc, indent=2, ensure_ascii=False) + "\n",
    encoding="utf-8",
)

# Update build.gradle.kts group and archiveBaseName, and settings.gradle.kts rootProject.name
slug = new_id.split(".")[-1]
archive_base = slug if slug.endswith("-plugin") else f"{slug}-turboism-plugin"
group_id = ".".join(new_pkg.split(".")[:-1]) or new_pkg

build_kts_path = root / "build.gradle.kts"
if build_kts_path.is_file():
    build_kts = build_kts_path.read_text(encoding="utf-8")
    build_kts = re.sub(r'(?m)^group\s*=\s*"[^"]*"', f'group = "{group_id}"', build_kts)
    build_kts = re.sub(
        r'archiveBaseName\.set\("[^"]*"\)',
        f'archiveBaseName.set("{archive_base}")',
        build_kts,
    )
    build_kts_path.write_text(build_kts, encoding="utf-8")

settings_kts_path = root / "settings.gradle.kts"
if settings_kts_path.is_file():
    settings_kts = settings_kts_path.read_text(encoding="utf-8")
    settings_kts = re.sub(
        r'(?m)^rootProject\.name\s*=\s*"[^"]*"',
        f'rootProject.name = "{archive_base}"',
        settings_kts,
    )
    settings_kts_path.write_text(settings_kts, encoding="utf-8")
PY

printf 'Configured plugin:\n'
printf '  ID:              %s\n' "$PLUGIN_ID"
printf '  Name:            %s\n' "$PLUGIN_NAME"
printf '  Package:         %s\n' "$JAVA_PACKAGE"
printf '  Author:          %s\n' "$AUTHOR_NAME"
printf '  SDK Version:     %s\n' "$RESOLVED_VERSION"
printf '  Cubism Versions: %s\n' "$CUBISM_VERSIONS_CSV"
printf 'Setup complete. Run ./gradlew build to compile and package your plugin.\n'
