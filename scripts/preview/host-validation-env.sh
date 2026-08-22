#!/usr/bin/env bash
# Shared local configuration for exact-host validation wrappers.
#
# Developers keep machine-specific paths and SSH placement in the repository
# root `.env` (ignored by Git). Copy `.env.example` to `.env`, edit it locally,
# then invoke any wrapper normally. Existing exported variables take precedence
# so CI and one-off commands can override `.env` without rewriting it.

if [[ -z "${TURBOISM_HOST_VALIDATION_ENV_LOADED:-}" ]]; then
  TURBOISM_HOST_VALIDATION_ENV_LOADED=1
  export TURBOISM_HOST_VALIDATION_ENV_LOADED

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
  : "${TURBOISM_HOST_VALIDATION_SSH_KEY:=}"
  : "${TURBOISM_HOST_VALIDATION_REMOTE_ROOT:=}"
  : "${TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX:=}"
  : "${TURBOISM_HOST_VALIDATION_PROTON_RUNNER:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_5203:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_5203_SHA256:=331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_5302:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_5302_SHA256:=57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_PBT_5302:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_PBT_5302_SHA256:=8b1718d2976eabffc8d85ea10343003aadea784230094e5397a41acbc17a20b8}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_PSD:=}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_PSD_NAME:=clipmask.psd}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_PSD_SHA256:=27c2641e45d9ca55478550b99d1bf69262383af38b0eed39cc172fb96b3b053e}"
  : "${TURBOISM_HOST_VALIDATION_FIXTURE_HISTORY_5302:=}"

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

turboism_select_fixture() {
  local version=$1
  case "$version" in
    5203)
      turboism_require_env TURBOISM_HOST_VALIDATION_FIXTURE_5203 "Cubism 5.2.03 fixture path" || return
      fixture_src="$TURBOISM_HOST_VALIDATION_FIXTURE_5203"
      fixture_sha256="$TURBOISM_HOST_VALIDATION_FIXTURE_5203_SHA256"
      ;;
    5302)
      turboism_require_env TURBOISM_HOST_VALIDATION_FIXTURE_5302 "Cubism 5.3.02 fixture path" || return
      fixture_src="$TURBOISM_HOST_VALIDATION_FIXTURE_5302"
      fixture_sha256="$TURBOISM_HOST_VALIDATION_FIXTURE_5302_SHA256"
      ;;
    *)
      printf 'host validation: unsupported fixture version: %s\n' "$version" >&2
      return 2
      ;;
  esac
}
