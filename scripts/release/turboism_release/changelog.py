"""Structured changelog parsing, validation, range queries, and rendering."""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

FORMAT = "turboism.changelog/v1"
SCHEMA_VERSION = 1
REPOSITORY_URL = "https://github.com/turboism/Turboism"
LOCALES = ("en", "zh", "ja", "ko")
DEFAULT_LOCALE = "en"
UNRELEASED = "Unreleased"
GROUP_BY_CATEGORY = "category"
GROUP_BY_VERSION = "version"
VALID_GROUP_BY = (GROUP_BY_CATEGORY, GROUP_BY_VERSION)

CATEGORIES = (
    "added",
    "changed",
    "deprecated",
    "removed",
    "fixed",
    "security",
    "known_limitations",
)

CATEGORY_HEADINGS: dict[str, dict[str, str]] = {
    "en": {
        "added": "Added",
        "changed": "Changed",
        "deprecated": "Deprecated",
        "removed": "Removed",
        "fixed": "Fixed",
        "security": "Security",
        "known_limitations": "Known limitations",
    },
    "zh": {
        "added": "新增",
        "changed": "变更",
        "deprecated": "已弃用",
        "removed": "移除",
        "fixed": "修复",
        "security": "安全",
        "known_limitations": "已知限制",
    },
    "ja": {
        "added": "追加",
        "changed": "変更",
        "deprecated": "非推奨",
        "removed": "削除",
        "fixed": "修正",
        "security": "セキュリティ",
        "known_limitations": "既知の制限",
    },
    "ko": {
        "added": "추가",
        "changed": "변경",
        "deprecated": "폐기 예정",
        "removed": "제거",
        "fixed": "수정",
        "security": "보안",
        "known_limitations": "알려진 제한 사항",
    },
}

CATEGORY_ALIASES: dict[str, str] = {
    "Added": "added",
    "新增": "added",
    "追加": "added",
    "추가": "added",
    "Changed": "changed",
    "变更": "changed",
    "改进": "changed",
    "変更": "changed",
    "변경": "changed",
    "Deprecated": "deprecated",
    "已弃用": "deprecated",
    "非推奨": "deprecated",
    "폐기 예정": "deprecated",
    "Removed": "removed",
    "移除": "removed",
    "削除": "removed",
    "제거": "removed",
    "Fixed": "fixed",
    "修复": "fixed",
    "修正": "fixed",
    "수정": "fixed",
    "Security": "security",
    "安全": "security",
    "セキュリティ": "security",
    "보안": "security",
    "Known limitations": "known_limitations",
    "已知限制": "known_limitations",
    "既知の制限": "known_limitations",
    "알려진 제한 사항": "known_limitations",
}

RANGE_SUMMARY_TEMPLATES: dict[str, str] = {
    "en": "Changes from **{from_version}** to **{to_version}** ({count} releases).",
    "zh": "从 **{from_version}** 到 **{to_version}** 的全部变更（共 {count} 个版本）。",
    "ja": "**{from_version}** から **{to_version}** までの変更（全 {count} リリース）。",
    "ko": "**{from_version}**에서 **{to_version}**까지의 변경 사항(총 {count}개 릴리스).",
}

SINGLE_RANGE_SUMMARY_TEMPLATES: dict[str, str] = {
    "en": "Changes from **{from_version}** to **{to_version}**.",
    "zh": "从 **{from_version}** 到 **{to_version}** 的变更。",
    "ja": "**{from_version}** から **{to_version}** への変更。",
    "ko": "**{from_version}**에서 **{to_version}**으로의 변경 사항.",
}

COMPARE_LINK_TEMPLATES: dict[str, str] = {
    "en": "Full changes since {base}",
    "zh": "查看自 {base} 以来的完整变化",
    "ja": "{base} 以降のすべての変更",
    "ko": "{base} 이후 전체 변경 사항",
}

LOCALE_CHANGELOG_PATHS: dict[str, str] = {
    "en": "docs/en/CHANGELOG.md",
    "zh": "docs/zh/CHANGELOG.md",
    "ja": "docs/ja/CHANGELOG.md",
    "ko": "docs/ko/CHANGELOG.md",
}

STRUCTURED_CHANGELOG_PATH = Path("distribution/changelog.json")
RELEASE_NOTES_DIR = Path("distribution/release-notes")

SECTION_HEADING_RE = re.compile(r"^## \[([^\]]+)\](?: - (\d{4}-\d{2}-\d{2}))?$", re.MULTILINE)
SEMVER_RE = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
DIGITS_RE = re.compile(r"^[0-9]+$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
CHANNEL_STABLE = "stable"
CHANNEL_BETA = "beta"
CHANNEL_NIGHTLY = "nightly"
VALID_CHANNELS = (CHANNEL_STABLE, CHANNEL_BETA, CHANNEL_NIGHTLY)
COMPARE_LINK_RE = re.compile(
    r"^\[[^\]]+\]\(https://github\.com/turboism/Turboism/compare/v([0-9A-Za-z.-]+)\.\.\.v([0-9A-Za-z.-]+)\)$"
)
REFERENCE_LINK_RE = re.compile(r"^\[[^\]]+\]:\s+https?://\S+$")


def normalize_version(raw: str) -> str:
    """Strips an optional leading 'v' and normalizes the Unreleased sentinel."""
    cleaned = raw.strip()
    if cleaned.lower() == UNRELEASED.lower():
        return UNRELEASED
    if cleaned.startswith(("v", "V")) and len(cleaned) > 1:
        cleaned = cleaned[1:]
    return cleaned


def channel_for_version(version: str) -> str:
    """Classifies a valid semantic version into stable, beta, or nightly."""
    match = SEMVER_RE.fullmatch(version)
    if not match:
        raise ValueError(f"invalid semantic version: {version!r}")
    prerelease = match.group(4)
    if not prerelease:
        return CHANNEL_STABLE
    tokens = prerelease.split(".")
    if "nightly" in tokens:
        return CHANNEL_NIGHTLY
    return CHANNEL_BETA


def _parse_semver(version: str) -> tuple[tuple[int, int, int], tuple[str, ...]]:
    match = SEMVER_RE.fullmatch(version)
    if not match:
        raise ValueError(f"invalid semantic version: {version!r}")
    core = (int(match.group(1)), int(match.group(2)), int(match.group(3)))
    prerelease = tuple(match.group(4).split(".")) if match.group(4) else ()
    return core, prerelease


def compare_semver(left: str, right: str) -> int:
    """Compares two semantic versions using SemVer 2.0.0 precedence."""
    left_core, left_pre = _parse_semver(left)
    right_core, right_pre = _parse_semver(right)
    if left_core != right_core:
        return -1 if left_core < right_core else 1
    if not left_pre and not right_pre:
        return 0
    if not left_pre:
        return 1
    if not right_pre:
        return -1
    for left_token, right_token in zip(left_pre, right_pre):
        if left_token == right_token:
            continue
        left_num = bool(DIGITS_RE.fullmatch(left_token))
        right_num = bool(DIGITS_RE.fullmatch(right_token))
        if left_num and right_num:
            return -1 if int(left_token) < int(right_token) else 1
        if left_num != right_num:
            return -1 if left_num else 1
        return -1 if left_token < right_token else 1
    if len(left_pre) != len(right_pre):
        return -1 if len(left_pre) < len(right_pre) else 1
    return 0


def _parse_markdown_sections(text: str) -> list[tuple[str, str | None, str]]:
    marks = list(SECTION_HEADING_RE.finditer(text))
    if not marks:
        raise ValueError("changelog markdown has no version sections")
    result: list[tuple[str, str | None, str]] = []
    for index, match in enumerate(marks):
        end = marks[index + 1].start() if index + 1 < len(marks) else len(text)
        result.append((match.group(1), match.group(2), text[match.end() : end].strip()))
    return result


def _parse_release_body(body: str) -> dict[str, Any]:
    summary_lines: list[str] = []
    sections: dict[str, list[str]] = {}
    compare_base: str | None = None
    current_category: str | None = None

    for raw_line in body.splitlines():
        line = raw_line.rstrip()
        if not line.strip():
            continue
        compare_match = COMPARE_LINK_RE.fullmatch(line.strip())
        if compare_match:
            compare_base = compare_match.group(1)
            continue
        if REFERENCE_LINK_RE.fullmatch(line.strip()):
            continue
        if line.startswith("### "):
            heading = line[4:].strip()
            category = CATEGORY_ALIASES.get(heading)
            if category is None:
                raise ValueError(f"unsupported changelog category heading: {heading!r}")
            current_category = category
            sections.setdefault(category, [])
            continue
        if current_category is None:
            summary_lines.append(line.strip())
            continue
        if line.startswith("- "):
            sections[current_category].append(line[2:].strip())
        elif line.startswith("  ") and sections[current_category]:
            sections[current_category][-1] = f"{sections[current_category][-1]} {line.strip()}"
        else:
            raise ValueError(f"unexpected line inside category {current_category!r}: {line!r}")

    return {
        "summary": " ".join(summary_lines) if summary_lines else None,
        "compareBase": compare_base,
        "sections": sections,
    }


def _merge_localized_items(by_locale: dict[str, list[str]]) -> list[dict[str, str]]:
    english_items = by_locale.get(DEFAULT_LOCALE, [])
    entries: list[dict[str, str]] = []
    for index, en_text in enumerate(english_items):
        entry: dict[str, str] = {DEFAULT_LOCALE: en_text}
        for lang in LOCALES:
            if lang == DEFAULT_LOCALE:
                continue
            locale_items = by_locale.get(lang, [])
            if index < len(locale_items):
                entry[lang] = locale_items[index]
        entries.append(entry)
    return entries


def parse_markdown_changelogs(repo_root: Path) -> dict[str, Any]:
    """Parses all committed localized CHANGELOG.md files into the structured changelog document."""
    parsed_by_locale: dict[str, dict[str, tuple[str | None, dict[str, Any]]]] = {}
    english_order: list[str] = []

    for lang in LOCALES:
        path = repo_root / LOCALE_CHANGELOG_PATHS[lang]
        text = path.read_text(encoding="utf-8")
        parsed_sections = _parse_markdown_sections(text)
        locale_map: dict[str, tuple[str | None, dict[str, Any]]] = {}
        for version, release_date, body in parsed_sections:
            locale_map[version] = (release_date, _parse_release_body(body))
            if lang == DEFAULT_LOCALE:
                english_order.append(version)
        parsed_by_locale[lang] = locale_map

    unreleased_sections: dict[str, list[dict[str, str]]] = {}
    if UNRELEASED in parsed_by_locale[DEFAULT_LOCALE]:
        en_unreleased = parsed_by_locale[DEFAULT_LOCALE][UNRELEASED][1]["sections"]
        for category in CATEGORIES:
            if category in en_unreleased and en_unreleased[category]:
                by_locale = {
                    lang: parsed_by_locale[lang].get(UNRELEASED, (None, {"sections": {}}))[1]["sections"].get(
                        category, []
                    )
                    for lang in LOCALES
                }
                unreleased_sections[category] = _merge_localized_items(by_locale)

    release_versions = [version for version in english_order if version != UNRELEASED]
    releases: list[dict[str, Any]] = []
    for index, version in enumerate(release_versions):
        release_date, en_body = parsed_by_locale[DEFAULT_LOCALE][version]
        if release_date is None or not DATE_RE.fullmatch(release_date):
            raise ValueError(f"release {version} is missing a valid YYYY-MM-DD date")
        previous_version = release_versions[index + 1] if index + 1 < len(release_versions) else None

        summary: dict[str, str] = {}
        for lang in LOCALES:
            locale_pair = parsed_by_locale[lang].get(version)
            if locale_pair is not None and locale_pair[1]["summary"]:
                summary[lang] = locale_pair[1]["summary"]

        sections: dict[str, list[dict[str, str]]] = {}
        for category in CATEGORIES:
            if category in en_body["sections"] and en_body["sections"][category]:
                by_locale = {
                    lang: parsed_by_locale[lang].get(version, (None, {"sections": {}}))[1]["sections"].get(category, [])
                    for lang in LOCALES
                }
                sections[category] = _merge_localized_items(by_locale)

        release_record: dict[str, Any] = {
            "version": version,
            "channel": channel_for_version(version),
            "date": release_date,
            "previousVersion": previous_version,
        }
        if en_body["compareBase"]:
            release_record["compareBase"] = en_body["compareBase"]
        if summary:
            release_record["summary"] = summary
        release_record["sections"] = sections
        releases.append(release_record)

    document: dict[str, Any] = {
        "format": FORMAT,
        "schemaVersion": SCHEMA_VERSION,
        "repository": REPOSITORY_URL,
        "locales": list(LOCALES),
        "categories": list(CATEGORIES),
        "unreleased": {"sections": unreleased_sections},
        "releases": releases,
    }
    validate_changelog(document)
    return document


def validate_changelog(document: dict[str, Any]) -> None:
    """Validates schema structure, semver monotonicity, categories, and localized entries."""
    if not isinstance(document, dict):
        raise ValueError("changelog document must be a JSON object")
    if document.get("format") != FORMAT:
        raise ValueError(f"unexpected changelog format: {document.get('format')!r}")
    if document.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError(f"unexpected changelog schemaVersion: {document.get('schemaVersion')!r}")
    if document.get("locales") != list(LOCALES):
        raise ValueError(f"unexpected changelog locales: {document.get('locales')!r}")
    if document.get("categories") != list(CATEGORIES):
        raise ValueError(f"unexpected changelog categories: {document.get('categories')!r}")

    unreleased = document.get("unreleased")
    if not isinstance(unreleased, dict) or not isinstance(unreleased.get("sections"), dict):
        raise ValueError("changelog unreleased section must contain a sections object")
    _validate_sections(unreleased["sections"], UNRELEASED)

    releases = document.get("releases")
    if not isinstance(releases, list) or not releases:
        raise ValueError("changelog must contain at least one release")

    seen_versions: set[str] = set()
    previous_version_seen: str | None = None
    for index, release in enumerate(releases):
        if not isinstance(release, dict):
            raise ValueError(f"release entry at index {index} must be an object")
        version = release.get("version")
        if not isinstance(version, str) or not SEMVER_RE.fullmatch(version):
            raise ValueError(f"invalid release version at index {index}: {version!r}")
        if version in seen_versions:
            raise ValueError(f"duplicate release version: {version}")
        seen_versions.add(version)

        expected_channel = channel_for_version(version)
        channel = release.get("channel")
        if channel is not None and channel != expected_channel:
            raise ValueError(f"release {version} channel {channel!r} != {expected_channel!r}")

        if previous_version_seen is not None and compare_semver(version, previous_version_seen) >= 0:
            raise ValueError(f"releases must be strictly descending by semver: {version}")
        previous_version_seen = version

        date_value = release.get("date")
        if not isinstance(date_value, str) or not DATE_RE.fullmatch(date_value):
            raise ValueError(f"release {version} has invalid date: {date_value!r}")

        expected_prev = releases[index + 1]["version"] if index + 1 < len(releases) else None
        if release.get("previousVersion") != expected_prev:
            raise ValueError(
                f"release {version} previousVersion {release.get('previousVersion')!r} != {expected_prev!r}"
            )

        summary = release.get("summary")
        if summary is not None:
            _validate_localized_map(summary, f"release {version} summary")

        sections = release.get("sections")
        if not isinstance(sections, dict):
            raise ValueError(f"release {version} must contain a sections object")
        _validate_sections(sections, version)


def _validate_sections(sections: dict[str, Any], label: str) -> None:
    for category, items in sections.items():
        if category not in CATEGORIES:
            raise ValueError(f"{label}: unknown category {category!r}")
        if not isinstance(items, list):
            raise ValueError(f"{label}.{category}: items must be a list")
        for item_index, item in enumerate(items):
            _validate_localized_map(item, f"{label}.{category}[{item_index}]")


def _validate_localized_map(value: Any, label: str) -> None:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a localized text object")
    if DEFAULT_LOCALE not in value or not isinstance(value[DEFAULT_LOCALE], str) or not value[DEFAULT_LOCALE].strip():
        raise ValueError(f"{label} must include non-empty '{DEFAULT_LOCALE}' text")
    for lang, text in value.items():
        if lang not in LOCALES:
            raise ValueError(f"{label} has unsupported locale {lang!r}")
        if not isinstance(text, str) or not text.strip():
            raise ValueError(f"{label}.{lang} must be a non-empty string")


def load_changelog(path: Path) -> dict[str, Any]:
    """Reads and validates the structured changelog JSON file."""
    data = json.loads(path.read_text(encoding="utf-8"))
    validate_changelog(data)
    return data


def write_changelog(path: Path, document: dict[str, Any]) -> None:
    """Validates and writes the structured changelog JSON file deterministically."""
    validate_changelog(document)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def localized_text(entry: dict[str, str], lang: str) -> str:
    """Returns localized text for lang, falling back to English when absent."""
    if lang not in LOCALES:
        raise ValueError(f"unsupported locale: {lang!r}")
    return entry.get(lang) or entry[DEFAULT_LOCALE]


def resolve_version_range(
    document: dict[str, Any],
    from_version: str,
    to_version: str,
) -> dict[str, Any]:
    """Computes structured changes strictly after from_version up to and including to_version across channels."""
    validate_changelog(document)
    start = normalize_version(from_version)
    end = normalize_version(to_version)

    if start == UNRELEASED:
        raise ValueError("from_version cannot be Unreleased")
    if not SEMVER_RE.fullmatch(start):
        raise ValueError(f"invalid from_version: {from_version!r}")

    releases: list[dict[str, Any]] = document["releases"]
    known_versions = {release["version"] for release in releases}
    earliest_version = releases[-1]["version"]

    if end != UNRELEASED and end not in known_versions:
        raise ValueError(f"unknown to_version: {to_version!r}")
    if end != UNRELEASED and compare_semver(start, end) >= 0:
        raise ValueError(f"to_version ({end}) must be newer than from_version ({start})")

    from_channel = channel_for_version(start)
    if start not in known_versions:
        if from_channel == CHANNEL_STABLE or compare_semver(start, earliest_version) < 0:
            raise ValueError(f"unknown from_version: {from_version!r}")

    if end == UNRELEASED:
        included: list[dict[str, Any]] = []
        unreleased_sections = document["unreleased"]["sections"]
        if unreleased_sections:
            included.append({"version": UNRELEASED, "channel": None, "date": None, "sections": unreleased_sections})
        included.extend(release for release in releases if compare_semver(start, release["version"]) < 0)
        compare_url = None
        to_channel = None
    else:
        included = [
            release
            for release in releases
            if compare_semver(start, release["version"]) < 0 and compare_semver(release["version"], end) <= 0
        ]
        repo = document.get("repository", REPOSITORY_URL)
        compare_url = f"{repo}/compare/v{start}...v{end}"
        to_channel = channel_for_version(end)

    merged_sections: dict[str, list[dict[str, Any]]] = {}
    for category in CATEGORIES:
        bucket: list[dict[str, Any]] = []
        for release in included:
            for item in release.get("sections", {}).get(category, []):
                bucket.append(
                    {
                        "version": release["version"],
                        "channel": release.get("channel"),
                        "date": release.get("date"),
                        "text": dict(item),
                    }
                )
        if bucket:
            merged_sections[category] = bucket

    return {
        "format": FORMAT,
        "schemaVersion": SCHEMA_VERSION,
        "fromVersion": start,
        "fromChannel": from_channel,
        "toVersion": end,
        "toChannel": to_channel,
        "includedVersions": [release["version"] for release in included],
        "compareUrl": compare_url,
        "mergedSections": merged_sections,
        "releases": included,
    }


def render_range_markdown(
    range_data: dict[str, Any],
    *,
    lang: str = DEFAULT_LOCALE,
    group_by: str = GROUP_BY_CATEGORY,
) -> str:
    """Renders a resolved version range as localized Markdown grouped by category or version."""
    if lang not in LOCALES:
        raise ValueError(f"unsupported locale: {lang!r}")
    if group_by not in VALID_GROUP_BY:
        raise ValueError(f"unsupported group_by mode: {group_by!r}")

    from_version = range_data["fromVersion"]
    to_version = range_data["toVersion"]
    included = range_data["releases"]
    headings = CATEGORY_HEADINGS[lang]

    blocks: list[str] = []
    if len(included) == 1:
        summary_line = SINGLE_RANGE_SUMMARY_TEMPLATES[lang].format(
            from_version=from_version,
            to_version=to_version,
        )
    else:
        summary_line = RANGE_SUMMARY_TEMPLATES[lang].format(
            from_version=from_version,
            to_version=to_version,
            count=len(included),
        )
    blocks.append(summary_line)

    if group_by == GROUP_BY_CATEGORY:
        for category in CATEGORIES:
            items = range_data["mergedSections"].get(category, [])
            if not items:
                continue
            lines = [f"### {headings[category]}", ""]
            for item in items:
                lines.append(f"- {localized_text(item['text'], lang)}")
            blocks.append("\n".join(lines))
    else:
        for release in included:
            heading = (
                f"## [{release['version']}] - {release['date']}"
                if release.get("date")
                else f"## [{release['version']}]"
            )
            release_blocks = [heading]
            summary = release.get("summary")
            if summary:
                release_blocks.append(localized_text(summary, lang))
            for category in CATEGORIES:
                items = release.get("sections", {}).get(category, [])
                if not items:
                    continue
                lines = [f"### {headings[category]}", ""]
                for item in items:
                    lines.append(f"- {localized_text(item, lang)}")
                release_blocks.append("\n".join(lines))
            blocks.append("\n\n".join(release_blocks))

    compare_url = range_data.get("compareUrl")
    if compare_url:
        label = COMPARE_LINK_TEMPLATES[lang].format(base=from_version)
        blocks.append(f"[{label}]({compare_url})")

    return "\n\n".join(blocks) + "\n"


def render_release_markdown(
    release: dict[str, Any],
    *,
    lang: str = DEFAULT_LOCALE,
    repository: str = REPOSITORY_URL,
) -> str:
    """Renders one structured release entry into localized Markdown release notes."""
    if lang not in LOCALES:
        raise ValueError(f"unsupported locale: {lang!r}")
    headings = CATEGORY_HEADINGS[lang]
    blocks: list[str] = []
    summary = release.get("summary")
    if summary and lang in summary:
        blocks.append(summary[lang])
    elif summary and DEFAULT_LOCALE in summary:
        blocks.append(summary[DEFAULT_LOCALE])

    for category in CATEGORIES:
        items = release.get("sections", {}).get(category, [])
        if not items:
            continue
        lines = [f"### {headings[category]}", ""]
        for item in items:
            lines.append(f"- {localized_text(item, lang)}")
        blocks.append("\n".join(lines))

    compare_base = release.get("compareBase")
    if compare_base:
        label = COMPARE_LINK_TEMPLATES[lang].format(base=compare_base)
        blocks.append(f"[{label}]({repository}/compare/v{compare_base}...v{release['version']})")

    return "\n\n".join(blocks) + "\n"


def render_release_notes_json(document: dict[str, Any], version: str) -> dict[str, Any]:
    """Builds a schemaVersion 1 release-notes JSON payload from structured changelog data."""
    validate_changelog(document)
    target = normalize_version(version)
    release = next((item for item in document["releases"] if item["version"] == target), None)
    if release is None:
        raise ValueError(f"unknown release version: {version!r}")
    repository = document.get("repository", REPOSITORY_URL)
    locales = {
        lang: render_release_markdown(release, lang=lang, repository=repository)
        for lang in LOCALES
    }
    return {
        "schemaVersion": 1,
        "version": target,
        "locales": locales,
    }


def check_sync(repo_root: Path) -> bool:
    """Returns True when distribution/changelog.json matches docs/<locale>/CHANGELOG.md."""
    target = repo_root / STRUCTURED_CHANGELOG_PATH
    if not target.is_file():
        return False
    committed = load_changelog(target)
    rebuilt = parse_markdown_changelogs(repo_root)
    return committed == rebuilt
