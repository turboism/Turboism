#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCANNER="${SCRIPT_DIR}/scan_migration_docs_safety.py"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

mkdir -p "${tmp}/plans"
printf '%s\n' '# Plan' '不得复制反编译方法体。' 'Do not implement license bypass behavior.' > "${tmp}/plans/allowed.md"
python3 "${SCANNER}" "${tmp}"

assert_rejected() {
  local name="$1"
  local content="$2"
  printf '%s\n' '# Unsafe' "${content}" > "${tmp}/plans/unsafe.md"
  if output="$(python3 "${SCANNER}" "${tmp}" 2>&1)"; then
    echo "FAIL: scanner accepted ${name}" >&2
    exit 1
  fi
  printf '%s\n' "${output}" | grep -Fq "unsafe.md:2:" || {
    echo "FAIL: scanner did not report the real line number for ${name}" >&2
    printf '%s\n' "${output}" >&2
    exit 1
  }
  rm "${tmp}/plans/unsafe.md"
}

assert_rejected "unsafe Chinese clause" '实现反编译方法体迁移。'
assert_rejected "unrelated English negation" 'Do not change the launcher, then implement license bypass.'
assert_rejected "unrelated Chinese negation" '不得修改文档，但实现反编译方法体迁移。'
assert_rejected "mixed-language contrast" '禁止复制文档; however, implement license bypass and remove watermark.'
assert_rejected "multiple prohibited phrases" 'Do not implement license bypass, but enable trial bypass and crack checks.'
# Oracle regression sentences: scope breakers must not inherit a negation from an earlier structure.
assert_rejected "Oracle while breaker" 'Do not change the launcher while implementing license bypass.'
assert_rejected "Oracle and then breaker" 'Never copy private resources and then remove watermark.'
assert_rejected "Oracle Chinese sequence breaker" '不得修改正式目录，同时实现反编译方法体，随后绕过授权。'

printf '%s\n' '# Allowed' \
  'Do not implement license bypass; never remove watermark.' \
  '不得复制反编译方法体，也禁止绕过授权。' > "${tmp}/plans/allowed-multiple.md"
python3 "${SCANNER}" "${tmp}"

echo "PASS: migration docs safety scanner regression"
