#!/usr/bin/env bash
# Shared local configuration for exact-host validation wrappers.
#
# Developers keep machine-specific local paths in the repository root `.env`
# (ignored by Git). Values are parsed as data; exported values take precedence.

if [[ "${TURBOISM_HOST_VALIDATION_ENV_LOADED:-}" != "$BASHPID" ]]; then
  TURBOISM_HOST_VALIDATION_ENV_LOADED="$BASHPID"
  # This guard is shell-local: child Runners must initialize non-exported defaults.
  export -n TURBOISM_HOST_VALIDATION_ENV_LOADED

  _turboism_env_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  _turboism_env_file="${TURBOISM_ENV_FILE:-$_turboism_env_root/.env}"
  if [[ -f "$_turboism_env_file" ]]; then
    while IFS= read -r _turboism_env_line || [[ -n "$_turboism_env_line" ]]; do
      [[ "$_turboism_env_line" =~ ^[[:space:]]*(#|$) ]] && continue
      [[ "$_turboism_env_line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] \
        || { printf 'host validation: invalid .env assignment (values are not printed)\n' >&2; return 2; }
      _turboism_env_key="${BASH_REMATCH[2]}"
      _turboism_env_value="${BASH_REMATCH[3]}"
      [[ "$_turboism_env_key" == TURBOISM_* ]] \
        || { printf 'host validation: .env key must start with TURBOISM_: %s\n' "$_turboism_env_key" >&2; return 2; }
      # This is a data parser, not `source`: command substitution and shell code
      # remain literal text. Matching single or double quotes are removed only
      # so paths containing spaces are convenient to write.
      _turboism_env_value="${_turboism_env_value#"${_turboism_env_value%%[![:space:]]*}"}"
      _turboism_env_value="${_turboism_env_value%"${_turboism_env_value##*[![:space:]]}"}"
      if [[ "$_turboism_env_value" != \"* && "$_turboism_env_value" != \'* \
        && "$_turboism_env_value" =~ [[:space:]] ]]; then
        printf 'host validation: unquoted whitespace in .env key %s (value is not printed)\n' \
          "$_turboism_env_key" >&2
        return 2
      fi
      if [[ "$_turboism_env_value" == \"* || "$_turboism_env_value" == \'* ]]; then
        _turboism_env_quote="${_turboism_env_value:0:1}"
        if [[ ${#_turboism_env_value} -lt 2 \
          || "${_turboism_env_value: -1}" != "$_turboism_env_quote" ]]; then
          printf 'host validation: unmatched quote in .env key %s (value is not printed)\n' \
            "$_turboism_env_key" >&2
          return 2
        fi
        _turboism_env_value="${_turboism_env_value:1:${#_turboism_env_value}-2}"
      fi
      if [[ ! -v "$_turboism_env_key" ]]; then
        printf -v "$_turboism_env_key" '%s' "$_turboism_env_value"
        export "$_turboism_env_key"
      fi
    done < "$_turboism_env_file"
  fi

  : "${TURBOISM_HOST_VALIDATION_SSH_HOST:=}"
  : "${TURBOISM_HOST_VALIDATION_HOST_ROOT:=}"
  : "${TURBOISM_HOST_VALIDATION_TRANSPORT:=local}"
  : "${TURBOISM_HOST_VALIDATION_SSH_KEY:=}"
  : "${TURBOISM_HOST_VALIDATION_REMOTE_ROOT:=}"
  : "${TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX:=}"
  : "${TURBOISM_HOST_VALIDATION_PROTON_RUNNER:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_5203:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_5203_SHA256:=331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_5302:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_5302_SHA256:=57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_5303:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_5303_SHA256:=57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_PBT_5302:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_PBT_5302_SHA256:=8b1718d2976eabffc8d85ea10343003aadea784230094e5397a41acbc17a20b8}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK_SHA256:=2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_MCP:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_MCP_SHA256:=2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_PSD:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_PSD_NAME:=clipmask.psd}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_PSD_SHA256:=27c2641e45d9ca55478550b99d1bf69262383af38b0eed39cc172fb96b3b053e}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_HISTORY_5302:=}"
  : "${TURBOISM_HOST_VALIDATION_DEFAULT_VERSION:=5302}"

  unset _turboism_env_root _turboism_env_file _turboism_env_line _turboism_env_key _turboism_env_value _turboism_env_quote
fi

turboism_require_env() {
  local name=$1 description=${2:-$1}
  if [[ -z "${!name:-}" ]]; then
    printf 'host validation: %s is required; copy .env.example to .env and set %s\n' \
      "$description" "$name" >&2
    return 2
  fi
}

turboism_dotted_version() {
  local version=$1
  [[ "$version" =~ ^[0-9]{4}$ ]] || return 2
  printf '%s.%s.%s\n' "${version:0:1}" "${version:1:1}" "${version:2:2}"
}

turboism_manifest_path() {
  local preview_dir
  preview_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  if [[ -n "${TURBOISM_HOST_VALIDATION_MANIFEST:-}" && -f "${TURBOISM_HOST_VALIDATION_MANIFEST}" ]]; then
    printf '%s\n' "$TURBOISM_HOST_VALIDATION_MANIFEST"
  elif [[ -f "$preview_dir/host-validation-tasks.json" ]]; then
    printf '%s\n' "$preview_dir/host-validation-tasks.json"
  else
    return 1
  fi
}

turboism_reviewed_versions() {
  local sep=${1:-$'\n'}
  local manifest_file
  if manifest_file="$(turboism_manifest_path)"; then
    python3 - "$manifest_file" "$sep" <<'PY'
import json, sys
data = json.loads(open(sys.argv[1], encoding="utf-8").read())
versions = sorted({v for t in data.get("tasks", {}).values() for v in t.get("versions", [])})
print(sys.argv[2].join(versions))
PY
  else
    return 1
  fi
}

turboism_is_reviewed_version() {
  local version=$1
  [[ "$version" =~ ^5[0-3][0-9]{2}$ ]] || return 1
  local manifest_file
  if manifest_file="$(turboism_manifest_path)"; then
    python3 - "$manifest_file" "$version" <<'PY'
import json, sys
data = json.loads(open(sys.argv[1], encoding="utf-8").read())
versions = {v for t in data.get("tasks", {}).values() for v in t.get("versions", [])}
raise SystemExit(0 if sys.argv[2] in versions else 1)
PY
  else
    return 0
  fi
}

turboism_task_versions() {
  local task=$1 sep=${2:-$'\n'}
  local manifest_file
  if manifest_file="$(turboism_manifest_path)"; then
    python3 - "$manifest_file" "$task" "$sep" <<'PY'
import json, sys
data = json.loads(open(sys.argv[1], encoding="utf-8").read())
task_data = data.get("tasks", {}).get(sys.argv[2])
if not task_data:
    raise SystemExit(2)
print(sys.argv[3].join(task_data.get("versions", [])))
PY
  else
    return 2
  fi
}

turboism_task_versions_usage() {
  local task=$1 versions_bar
  if versions_bar="$(turboism_task_versions "$task" '|')"; then
    printf '<%s>' "$versions_bar"
  else
    printf '<version>'
  fi
}

turboism_task_supports_version() {
  local task=$1 version=$2
  [[ "$version" =~ ^5[0-3][0-9]{2}$ ]] || return 1
  local manifest_file
  if manifest_file="$(turboism_manifest_path)"; then
    python3 - "$manifest_file" "$task" "$version" <<'PY'
import json, sys
data = json.loads(open(sys.argv[1], encoding="utf-8").read())
task_data = data.get("tasks", {}).get(sys.argv[2])
if not task_data:
    raise SystemExit(2)
raise SystemExit(0 if sys.argv[3] in task_data.get("versions", []) else 1)
PY
  else
    return 0
  fi
}

turboism_require_task_version() {
  local task=$1 version=${2:-}
  if ! turboism_task_supports_version "$task" "$version"; then
    local admitted
    admitted="$(turboism_task_versions "$task" ', ' 2>/dev/null || printf 'none')"
    printf 'error: %s host validation: unsupported version: %s (admitted: %s)\n' \
      "$task" "$version" "$admitted" >&2
    return 2
  fi
}

turboism_expected_core_tuple() {
  local version=$1
  turboism_is_reviewed_version "$version" || {
    printf 'error: no observed Core tuple for %s\n' "$version" >&2
    return 2
  }
  case "${version:0:2}" in
    50|51) printf '5.0.0\n' ;;
    52) printf '5.0.256\n' ;;
    53)
      if (( 10#${version:2:2} < 4 )); then
        printf '6.0.257\n'
      else
        printf '6.0.513\n'
      fi
      ;;
    *)
      printf 'error: no observed Core tuple for %s\n' "$version" >&2
      return 2
      ;;
  esac
}

turboism_cubism_install_dir_windows() {
  local version=$1
  local semver
  semver="$(turboism_dotted_version "$version")" || return 2
  local root_dir
  root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  local env_py="$root_dir/scripts/preview/host_validation_evidence.py"
  if [[ -f "$env_py" ]]; then
    local folder
    folder="$(python3 - "$env_py" "$version" <<'PY'
import importlib.util, sys
spec = importlib.util.spec_from_file_location("hv_ev", sys.argv[1])
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
entry = mod.HOSTS.get(sys.argv[2])
print(entry[0] if entry else f"Live2D Cubism {sys.argv[2][0]}.{sys.argv[2][1]}.{sys.argv[2][2:]}")
PY
)"
    printf 'C:/Program Files/%s\n' "$folder"
  else
    printf 'C:/Program Files/Live2D Cubism %s\n' "$semver"
  fi
}

turboism_reviewed_jar_sha256() {
  local version=$1
  local semver
  semver="$(turboism_dotted_version "$version")" || return 2
  local root_dir
  root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  local record="$root_dir/compatibility/cubism/verification/cubism-${semver}-editor-model.json"
  [[ -f "$record" ]] || {
    printf 'host validation: missing editor-model verification record for %s\n' "$semver" >&2
    return 2
  }
  python3 - "$record" <<'PY'
import json, sys
data = json.loads(open(sys.argv[1], encoding="utf-8").read())
print(data["artifact"]["sha256"])
PY
}

turboism_core_acquisition_metadata() {
  local version=$1
  local semver
  semver="$(turboism_dotted_version "$version")" || return 2
  local root_dir
  root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  local core_rec="$root_dir/compatibility/cubism/verification/cubism-${semver}-core-model-read.json"
  local edit_rec="$root_dir/compatibility/cubism/verification/cubism-${semver}-editor-model.json"
  [[ -f "$core_rec" && -f "$edit_rec" ]] || {
    printf 'error: unsupported installed Core acquisition version: %s\n' "$version" >&2
    return 2
  }
  local meta
  meta="$(python3 - "$core_rec" "$edit_rec" <<'PY'
import json, sys
core = json.loads(open(sys.argv[1], encoding="utf-8").read())
edit = json.loads(open(sys.argv[2], encoding="utf-8").read())
print(core["profileId"])
print(edit["artifact"]["sha256"])
print(core["artifact"]["sha256"])
PY
)" || return 2
  {
    IFS= read -r profile
    IFS= read -r editor_jar_sha256
    IFS= read -r core_jar_sha256
  } <<< "$meta"
}

turboism_select_fixture() {
  local version=$1
  turboism_is_reviewed_version "$version" || {
    printf 'host validation: unsupported fixture version: %s\n' "$version" >&2
    return 2
  }
  local semver
  semver="$(turboism_dotted_version "$version")" || return 2
  local pspec="TURBOISM_HOST_VALIDATION_FIXTURE_${version}"
  local sspec="TURBOISM_HOST_VALIDATION_FIXTURE_${version}_SHA256"
  if [ -n "${!pspec:-}" ]; then
    fixture_src="${!pspec}"
    fixture_sha256="${!sspec:-}"
    [ -n "$fixture_sha256" ] || { printf 'host validation: %s set but %s missing\n' "$pspec" "$sspec" >&2; return 2; }
  elif [ -n "${!sspec:-}" ]; then
    turboism_require_env "$pspec" "Cubism $semver fixture path" || return
  else
    local fallback_ver="${TURBOISM_HOST_VALIDATION_DEFAULT_FIXTURE_VERSION:-5303}"
    local fallback_pspec="TURBOISM_HOST_VALIDATION_FIXTURE_${fallback_ver}"
    local fallback_sspec="TURBOISM_HOST_VALIDATION_FIXTURE_${fallback_ver}_SHA256"
    turboism_require_env "$fallback_pspec" "default Cubism fixture path for $semver" || return
    fixture_src="${!fallback_pspec}"
    fixture_sha256="${!fallback_sspec}"
  fi
}
