"""Java source scanning helpers shared by the development gates."""
from __future__ import annotations

import re

_JAVA_NOISE = re.compile(
    r"//[^\n]*|/\*.*?\*/|\"(?:\\.|[^\"\\])*\"|\'(?:\\.|[^\'\\])*\'",
    re.DOTALL,
)


def strip_java_noise(text: str) -> str:
    """Blank comments and string/char literals while preserving every offset.

    Gates that look for identifiers must not count a name that only appears in a
    comment or a string literal, because such a mention never links the type.
    """
    def repl(match: re.Match) -> str:
        return "".join("\n" if character == "\n" else " " for character in match.group(0))

    return _JAVA_NOISE.sub(repl, text)
