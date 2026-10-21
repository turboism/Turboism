"""4-language markdown documentation rendering for compatibility matrix."""
from __future__ import annotations

from turboism_dev.versions import cubism_version_key as version_key

from .constants import (
    BOUNDARY_TRIAGE,
    FEATURE_LABELS,
    TASK_GROUP_ALIASES,
)
from .extract import task_base
from .model import compact_ranges, exact_ranges, localized
from .strings import DEV_STRINGS, STRINGS

def _display_fallback(key: str) -> str:
    if "#" in key:
        owner, _, member = key.partition("#")
        return f"{owner.split('.')[-1]}#{member}"
    for prefix in ("dev.turboism.sdk.", "cubism.", "turboism."):
        if key.startswith(prefix):
            return key[len(prefix):]
    return key


def _has_label(key: str) -> bool:
    return (
        key in FEATURE_LABELS or key.rstrip("()") in FEATURE_LABELS
    )


def _format_official_link(off: dict | None, locale: str) -> str | None:
    if not off:
        return None
    ver = off.get("version") or off.get("introducedIn") or ""
    url = off.get("url") or ""
    name = (
        off.get("nameZh")
        if locale == "zh"
        else off.get("nameEn", off.get("nameZh", off.get("feature", "")))
    )
    if not name and "feature" in off:
        name = off["feature"]
    s = STRINGS[locale]
    tmpl = s.get("gap_official_prefix", "Cubism {version} 新增{feature}")
    text = tmpl.format(version=ver, feature=name) if name else f"Cubism {ver}"
    return f"[{text}]({url})" if url else text


def _gap_suffix(
    note: dict,
    locale: str,
    reviewed: list[str],
    *,
    boundary_only: bool = False,
) -> str:
    """Annotate missing versions with their classified nature.

    Missing versions below the earliest supported version are a pure leading
    prefix — the feature did not exist yet — and collapse into a single
    boundary phrase instead of a long version list.
    """
    nature = note.get("missingNature") or {}
    if not nature:
        return ""
    boundary = [v for v, n in nature.items() if n == "boundary"]
    if boundary_only and not boundary:
        return ""
    s = STRINGS[locale]
    versions = set(note.get("versions") or [])
    earliest = min(versions, key=version_key) if versions else ""
    prefix_only = bool(earliest) and bool(boundary) and all(
        version_key(v) < version_key(earliest) for v in boundary
    )
    parts = []
    pending = [v for v, n in nature.items() if n == "pending"]
    unknown = [v for v, n in nature.items() if n == "unknown"]
    interior_boundary = [
        v
        for v in boundary
        if not earliest or version_key(v) >= version_key(earliest)
    ]
    official_src = note.get("officialSource")
    official_link = _format_official_link(official_src, locale)

    if not boundary_only:
        for kind, key, listed in (
            ("pending", "gap_pending", pending),
            ("boundary", "gap_boundary", interior_boundary),
            ("unknown", "gap_unknown", unknown),
        ):
            if listed:
                parts.append(s[key].format(versions=compact_ranges(listed, reviewed)))
    elif interior_boundary:
        parts.append(s["gap_boundary"].format(versions=compact_ranges(interior_boundary, reviewed)))

    if boundary and prefix_only:
        if official_link:
            parts.append(official_link)
        else:
            parts.append(s["gap_boundary_prefix"])
    elif boundary and official_link and not prefix_only:
        parts.append(official_link)

    return "（" + "；".join(parts) + "）" if parts else ""


def _nature_text(nature: dict | None, locale: str) -> str:
    """Summarize the classified nature of a row's missing versions."""
    if not nature:
        return "—"
    s = STRINGS[locale]
    kinds = {n for n in nature.values() if n}
    labels = {
        "boundary": s["nature_boundary"],
        "pending": s["nature_pending"],
        "unknown": s["nature_unknown"],
    }
    return " / ".join(labels[k] for k in ("boundary", "pending", "unknown") if k in kinds) or "—"


def _feature_text(
    note: dict, locale: str, reviewed: list[str], *, user: bool = False
) -> str | None:
    if user and not _has_label(note["key"]):
        return None
    label = localized(note["key"], locale, _display_fallback(note["key"]))
    s = STRINGS[locale]
    versions = note["versions"]
    if not versions:
        return f"{label} —"
    if len(versions) <= len(reviewed) // 2:
        return (
            f"{label} {s['only_versions']} "
            f"{compact_ranges(versions, reviewed)}{_gap_suffix(note, locale, reviewed)}"
        )
    boundary_gap = _gap_suffix(note, locale, reviewed, boundary_only=True)
    return f"{label} {s['not_on']} {compact_ranges(note['missingOn'], reviewed)}{boundary_gap}"


def _host_cell(
    tasks: dict[str, dict], reviewed: list[str], locale: str, *, user: bool
) -> str:
    if not tasks:
        return "—"
    passed: set[str] = set()
    boundary: set[str] = set()
    pending: set[str] = set()
    for versions in tasks.values():
        for version, result in versions.items():
            status = result.get("validationStatus") or ""
            category = result.get("triageCategory") or ""
            if status == "PASS":
                passed.add(version)
            elif category in BOUNDARY_TRIAGE:
                boundary.add(version)
            elif status or category:
                pending.add(version)
            # Jobs with neither verdict nor triage carry no signal at all.
    s = STRINGS[locale]
    parts = []
    if passed:
        parts.append(f"{s['host_pass']} {compact_ranges(passed, reviewed)}")
    if boundary - passed:
        parts.append(
            f"{s['host_boundary']} {compact_ranges(boundary - passed, reviewed)}"
        )
    if not user and pending - passed - boundary:
        parts.append(
            f"{s['host_pending']} "
            f"{compact_ranges(pending - passed - boundary, reviewed)}"
        )
    return "<br>".join(parts) if parts else "—"


def render_plugins_doc(matrix: dict, locale: str) -> str:
    s = STRINGS[locale]
    reviewed = matrix["reviewedVersions"]
    lines = [
        f"# {s['plugins_title']}",
        "",
        f"> {s['generated_notice']}",
        "",
        s["plugins_intro"],
        "",
        f"## {s['license_note_title']}",
        "",
        s["license_note"],
        "",
        "| " + " | ".join(
            [s["col_plugin"], s["col_versions"], s["col_deltas"], s["col_host"]]
        ) + " |",
        "|---|---|---|---|",
    ]
    for plugin in matrix["plugins"]:
        versions = plugin["supportedVersions"]
        if versions == reviewed:
            version_text = s["reviewed_versions"].format(range=exact_ranges(versions), count=len(versions))
        elif versions:
            version_text = compact_ranges(versions, reviewed) + _gap_suffix(
                {
                    "missingNature": plugin.get("supportedGaps", {}),
                    "versions": versions,
                },
                locale,
                reviewed,
            )
        else:
            version_text = "—"
        notes = []
        full = set(plugin["fullFeatureVersions"])
        if full and full != set(versions):
            if full == {v for v in reviewed if version_key(v) >= version_key(min(full, key=version_key))}:
                notes.append(
                    s["full_feature_since"].format(
                        min=min(full, key=version_key)
                    )
                )
            else:
                notes.append(
                    s["full_feature_only"].format(
                        versions=compact_ranges(full, reviewed)
                    )
                )
        for note in plugin["partialFeatures"][:5]:
            text = _feature_text(note, locale, reviewed, user=True)
            if text:
                notes.append(text)
        deltas = "<br>".join(notes) if notes else "—"
        host = _host_cell(
            plugin["hostValidation"], reviewed, locale, user=True
        )
        lines.append(
            f"| `{plugin['directory']}` | {version_text} | {deltas} | {host} |"
        )
    lines += ["", s["validation_legend"], ""]
    return "\n".join(lines)


def render_capabilities_doc(matrix: dict, locale: str) -> str:
    s = DEV_STRINGS[locale]
    reviewed = matrix["reviewedVersions"]
    lines = [
        f"# {s['title']}",
        "",
        f"> {STRINGS[locale]['generated_notice']}",
        "",
        s["intro"],
        "",
        f"## {s['matrix_heading']}",
        "",
        s["legend"],
        "",
        "| " + " | ".join([s["col_group"], *reviewed]) + " |",
        "|" + "---|" * (len(reviewed) + 1),
    ]
    cap_by_id = {c["id"]: c for c in matrix["capabilities"]}
    footnotes: list[str] = []
    for group in matrix["capabilityGroups"]:
        group_caps = group["capabilities"]
        cells = []
        for version in reviewed:
            admitted_n = sum(
                1 for c in group_caps if version in cap_by_id[c]["admitted"]
            )
            if admitted_n == len(group_caps):
                cells.append("✓")
            elif admitted_n == 0:
                cells.append("✗")
            else:
                cells.append("◐")
        for cap in group_caps:
            row = cap_by_id[cap]
            verified = set(row["verified"])
            admitted = set(row["admitted"])
            missing = [v for v in reviewed if v not in verified]
            narrowed = [v for v in verified if v not in admitted]
            label = localized(cap, locale, cap)
            display = f"`{cap}`" if label == cap else f"`{cap}` ({label})"
            if missing:
                footnotes.append(
                    f"{display}: {s['fn_missing']} "
                    f"{compact_ranges(missing, reviewed)}"
                )
            if narrowed:
                footnotes.append(
                    f"{display}: {s['fn_narrowed']} "
                    f"{compact_ranges(narrowed, reviewed)}"
                )
        label = localized(group["id"], locale, group["id"])
        lines.append(f"| {label} | " + " | ".join(cells) + " |")
    lines.append("")
    if footnotes:
        lines.extend(f"- {note}" for note in footnotes)
        lines.append("")
    lines += [
        f"## {s['sdk_heading']}",
        "",
        s["sdk_note"],
        "",
        f"| {s['col_api']} | {s['col_kind']} | {s['col_versions']} |",
        "|---|---|---|",
    ]
    for entry in matrix["sdkApiConstraints"]:
        lines.append(
            f"| `{entry['api']}` | {entry['kind']} | "
            f"{compact_ranges(entry['versions'], reviewed)} |"
        )
    lines += [
        "",
        f"## {s['commands_heading']}",
        "",
        s["commands_note"],
        "",
        f"| {s['col_command']} | {s['col_enum']} | {s['col_versions']} | {s['col_nature']} | {s['col_introduced']} |",
        "|---|---|---|---|---|",
    ]
    for command in matrix["commands"]:
        version_text = (
            compact_ranges(command["versions"], reviewed)
            if command["versions"]
            else "—"
        )
        nature_text = _nature_text(command.get("missingNature"), locale)
        off = command.get("officialSource")
        if off and off.get("url"):
            introduced_text = f"[{off.get('version', '')}]({off['url']})"
        elif off and off.get("version"):
            introduced_text = off["version"]
        else:
            introduced_text = "—"
        lines.append(
            f"| `{command['name']}` | {command['enum']} | "
            f"{version_text} | {nature_text} | {introduced_text} |"
        )
    if matrix.get("versionBoundaries"):
        lines += [
            "",
            f"## {s['boundaries_heading']}",
            "",
            s["boundaries_note"],
            "",
            f"| {s['col_surface']} | {s['col_absent']} | {s['col_introduced']} | {s['col_reason']} |",
            "|---|---|---|---|",
        ]
        for b in matrix["versionBoundaries"]:
            absent_text = compact_ranges(b.get("absentOn", []), reviewed)
            off = b.get("officialSource")
            if off and off.get("url"):
                intro_text = f"[{off.get('version', '')}]({off['url']})"
            elif off and off.get("version"):
                intro_text = off["version"]
            else:
                intro_text = "—"
            reason = b.get("reason", "—")
            lines.append(
                f"| `{b['id']}` | {absent_text} | {intro_text} | {reason} |"
            )
    lines += [
        "",
        f"## {s['pins_heading']}",
        "",
        s["pins_note"],
        "",
        f"| {s['col_file']} | {s['col_symbol']} | {s['col_versions']} |",
        "|---|---|---|",
    ]
    for pin in matrix["classPins"]:
        lines.append(
            f"| class-pins/`{pin['name']}.json` | `ClassPinTable` | "
            f"{compact_ranges(pin['versions'], reviewed)} |"
        )
    for gate in matrix["runtimeGates"]:
        lines.append(
            f"| `{gate['file']}` | `{gate['symbol']}` | "
            f"{compact_ranges(gate['versions'], reviewed)} |"
        )
    lines += [
        "",
        f"## {s['monotonicity_heading']}",
        "",
        s["monotonicity"],
        "",
        f"## {s['query_heading']}",
        "",
        s["query"],
        "",
        f"## {s['tasks_heading']}",
        "",
        s["tasks_note"],
        "",
        "| " + " | ".join([s["col_task"], *reviewed]) + " |",
        "|" + "---|" * (len(reviewed) + 1),
    ]
    group_rows: dict[str, dict[str, str]] = {}
    for task, versions in matrix["hostValidation"].items():
        group = TASK_GROUP_ALIASES.get(task_base(task), task_base(task))
        cells = group_rows.setdefault(group, {})
        for version, result in versions.items():
            status = result.get("validationStatus") or ""
            category = result.get("triageCategory") or ""
            if status == "PASS":
                mark = "✓"
            elif category in BOUNDARY_TRIAGE:
                mark = "◇"
            elif status or category:
                mark = "?"
            else:
                continue
            if cells.get(version) != "✓":
                cells[version] = mark
    for group in sorted(group_rows):
        cells = [group_rows[group].get(v, "") for v in reviewed]
        lines.append(f"| `{group}` | " + " | ".join(cells) + " |")
    lines.append("")
    return "\n".join(lines)
