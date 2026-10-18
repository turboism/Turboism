"""README compatibility block generation and injection."""
from __future__ import annotations

import re

from .constants import (
    BLOCK_BEGIN,
    BLOCK_END,
    DOC_SUFFIX,
    README_HEADINGS,
    README_LOCALE,
)
from .model import compact_ranges
from .render_markdown import _feature_text, _gap_suffix
from .strings import STRINGS

def readme_block(plugin: dict, locale: str, reviewed: list[str]) -> str:
    s = STRINGS[locale]
    versions = plugin["supportedVersions"]
    version_text = (
        s["all_versions"]
        if versions == reviewed
        else compact_ranges(versions, reviewed)
    )
    gap_suffix = _gap_suffix(
        {
            "missingNature": plugin.get("supportedGaps", {}),
            "versions": versions,
        },
        locale,
        reviewed,
    )
    doc_name = f"plugins{DOC_SUFFIX[locale]}.md"
    parts = [
        BLOCK_BEGIN,
        f"{s['readme_intro']} {version_text}{gap_suffix}.",
    ]
    parts.extend(
        f"- {text}"
        for note in plugin["partialFeatures"][:3]
        if (text := _feature_text(note, locale, reviewed, user=True))
    )
    parts.append(
        f"{s['readme_more']}: [compatibility matrix](../../compatibility/cubism/{doc_name})"
    )
    parts.append(BLOCK_END)
    return "\n".join(parts)


def update_readme(
    text: str, plugin: dict, readme_name: str, reviewed: list[str]
) -> str:
    """Insert or replace the generated compatibility block inside the
    compatibility section of one README variant."""
    locale = README_LOCALE[readme_name]
    heading = README_HEADINGS[readme_name]
    block = readme_block(plugin, locale, reviewed)
    begin = text.find(BLOCK_BEGIN)
    end = text.find(BLOCK_END)
    if begin != -1 and end != -1 and end > begin:
        return text[:begin] + block + text[end + len(BLOCK_END):]
    heading_pos = text.find(heading)
    if heading_pos == -1:
        # Development-only READMEs use a lighter template; locate any heading
        # whose text mentions compatibility in this locale.
        fallback = re.search(
            r"^##\s+.*(?:兼容|互換|compatib|호환)[^\n]*$",
            text,
            re.MULTILINE | re.IGNORECASE,
        )
        if fallback:
            heading_pos = fallback.start()
        else:
            return text.rstrip() + "\n\n" + block + "\n"
    next_heading = text.find("\n## ", heading_pos + 4)
    if next_heading == -1:
        return text.rstrip() + "\n\n" + block + "\n"
    return text[:next_heading].rstrip() + "\n\n" + block + "\n" + text[next_heading:]


