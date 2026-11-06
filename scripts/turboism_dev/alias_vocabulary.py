"""Constant naming for the generated ``<Family>Aliases`` host-alias vocabularies.

``scripts/generate_verification_sources.py`` emits one ``<Family>Aliases`` class for each family
that declares an ``aliases`` block in ``scripts/verification-sources/families/*.json``. The host
alias vocabulary check resolves ``<Family>Aliases.<CONSTANT>`` references back to the record alias
the constant stands for, so both readers derive names here instead of each keeping its own copy of
the rule. Derivation is deterministic and injective: a name collision is an error, never a silent
last-one-wins.

The derived name depends only on the alias text, which is why a family must declare the alias
prefix or prefixes its records share: two aliases of one family differing only outside the chosen
prefix would map to the same constant name if the prefix were guessed. A family may span several
namespaces, so ``prefix`` is either one namespace string (the common shape) or a list of them; every
alias must belong to exactly one declared prefix, and two aliases that derive one constant name
across prefixes are an error like a collision inside one.
"""
from __future__ import annotations

import re
from collections.abc import Iterable, Mapping

# ``cubism.clipmask.`` -- dotted lowercase namespace segments, ending in a dot so the constant name
# covers exactly the alias suffix and never part of the owner segment.
PREFIX = re.compile(r"^[a-z0-9][a-z0-9-]*(?:\.[a-z0-9][a-z0-9-]*)*\.$")
# Room for ``EditorModelAliases``, ``ClipmaskAliases``; the suffix keeps vocabulary classes
# recognisable as generated declarations rather than authored ones.
CLASS_NAME = re.compile(r"^[A-Z][A-Za-z0-9]*Aliases$")
CONSTANT_NAME = re.compile(r"^[A-Z][A-Z0-9_]*$")
# ``ALL`` is the generated set of every alias, so no single alias may claim the name.
RESERVED_NAMES = frozenset({"ALL"})
DECLARATION_KEYS = frozenset({"className", "prefix"})


class VocabularyError(ValueError):
    """Raised when an alias vocabulary cannot be derived deterministically."""


def _prefixes(prefix: object) -> tuple[str, ...]:
    """Returns the declared namespaces as a tuple, accepting one string or a list of them."""
    declared = (prefix,) if isinstance(prefix, str) else tuple(prefix) if isinstance(prefix, list) else None
    if not declared:
        raise VocabularyError(
            f"aliases prefix must be a dotted lowercase namespace ending in '.' or a non-empty list of them, got {prefix!r}")
    for namespace in declared:
        if not isinstance(namespace, str) or not PREFIX.match(namespace):
            raise VocabularyError(
                f"aliases prefix must be a dotted lowercase namespace ending in '.', got {namespace!r}")
    if len(set(declared)) != len(declared):
        raise VocabularyError(f"aliases prefix declares a namespace twice: {prefix!r}")
    return declared


def declaration(block: object, label: str) -> tuple[str, tuple[str, ...]]:
    """Returns the ``(class name, alias prefixes)`` of one family's ``aliases`` block.

    The block is family data, so an unexpected shape is an authoring error rather than a reason to
    fall back to a default: a guessed class name would emit a target nothing checks. ``prefix`` is
    one namespace string or a list of them; the normalised form is always a tuple.
    """
    if not isinstance(block, Mapping) or set(block) != DECLARATION_KEYS:
        raise VocabularyError(
            f"{label}: aliases block must declare exactly {sorted(DECLARATION_KEYS)}")
    class_name = block["className"]
    if not isinstance(class_name, str) or not CLASS_NAME.match(class_name):
        raise VocabularyError(f"{label}: aliases className must be a <Family>Aliases name, got {class_name!r}")
    try:
        prefixes = _prefixes(block["prefix"])
    except VocabularyError as failure:
        raise VocabularyError(f"{label}: {failure}") from failure
    return class_name, prefixes


def constant_name(alias: str, prefix: str) -> str:
    """Returns the constant name of one alias: upper snake case of its prefix-relative suffix."""
    if not alias.startswith(prefix) or len(alias) == len(prefix):
        raise VocabularyError(f"alias {alias!r} is not a member of the {prefix!r} namespace")
    suffix = alias[len(prefix) :]
    name = re.sub(r"[^0-9A-Za-z]+", "_", suffix).upper().strip("_")
    if not CONSTANT_NAME.match(name):
        raise VocabularyError(f"alias {alias!r} does not derive a constant name, got {name!r}")
    if name in RESERVED_NAMES:
        raise VocabularyError(f"alias {alias!r} derives the reserved constant name {name!r}")
    return name


def constants(aliases: Iterable[str], prefixes: str | tuple[str, ...]) -> dict[str, str]:
    """Returns ``constant name -> alias`` for every alias, failing closed on a collision.

    ``prefixes`` is one namespace string or the normalised tuple ``declaration`` returns. Every
    alias must belong to exactly one declared prefix: an alias outside all of them, or inside two
    nested ones, would hide which namespace its constant stands for, so it is an authoring error
    naming the alias and the declared prefixes.

    Separator placement is the plausible collision source (``a-b.c`` vs ``a.b-c`` derive one name),
    and a collision would make one alias unreachable from Java, so it is an authoring error instead
    of a deduplicated table. The rule is prefix-relative, so a collision across two prefixes fails
    exactly like one inside a single namespace.
    """
    declared = (prefixes,) if isinstance(prefixes, str) else tuple(prefixes)
    table: dict[str, str] = {}
    for alias in sorted(aliases):
        owners = [p for p in declared if alias.startswith(p) and len(alias) > len(p)]
        if len(owners) != 1:
            raise VocabularyError(
                f"alias {alias!r} must belong to exactly one of the declared prefixes "
                f"{sorted(declared)}, belongs to {sorted(owners)}")
        name = constant_name(alias, owners[0])
        if name in table:
            raise VocabularyError(
                f"aliases {table[name]!r} and {alias!r} both derive the constant name {name!r}")
        table[name] = alias
    if not table:
        raise VocabularyError(f"no alias of the {declared!r} prefixes was declared")
    return table
