"""Canonical release layouts; previously published full/thin files stay readable."""
from pathlib import Path

PRODUCT_PRIMARY = (
    "TurboismInstaller-{version}.exe",
    "turboism-{version}.zip",
)
LEGACY_PRODUCT_PRIMARY = (
    "turboism-{version}-thin.zip",
    "turboism-{version}-full.zip",
    "TurboismInstaller-{version}-full.exe",
    "TurboismInstaller-{version}-thin.exe",
)
DEVELOPER_PRIMARY = (
    "turboism-sdk-{version}.jar",
    "turboism-event-processor-{version}.jar",
)


def legacy_layout(dist: Path, version: str) -> bool:
    if any((dist / name.format(version=version)).exists() for name in PRODUCT_PRIMARY[1:]):
        return False
    return any((dist / name.format(version=version)).exists() for name in LEGACY_PRODUCT_PRIMARY)


def product_primary(dist: Path, version: str) -> tuple[str, ...]:
    return LEGACY_PRODUCT_PRIMARY if legacy_layout(dist, version) else PRODUCT_PRIMARY


def portable_archives(dist: Path, version: str) -> list[Path]:
    suffixes = ("-full", "-thin") if legacy_layout(dist, version) else ("",)
    return [dist / f"turboism-{version}{suffix}.zip" for suffix in suffixes]
