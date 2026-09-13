"""Public product channel policy; local previews never allocate official identities."""
from __future__ import annotations
import re

BASE = r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)'
STABLE = re.compile(BASE)
BETA = re.compile(BASE + r'-(alpha|beta|rc)\.(0|[1-9]\d*)')
NIGHTLY = re.compile(BASE + r'-0\.nightly\.([1-9]\d*)')
CHANNELS = ('stable', 'beta', 'nightly')


def channel_for(version: str) -> str:
    if not isinstance(version, str) or len(version) > 96:
        raise ValueError('Invalid published product version')
    for name, pattern in [('stable', STABLE), ('beta', BETA), ('nightly', NIGHTLY)]:
        if pattern.fullmatch(version):
            return name
    raise ValueError('Use X.Y.Z, X.Y.Z-beta.N/rc.N/alpha.N, or X.Y.Z-0.nightly.BUILD')


def require_version(version: str, channel: str) -> str:
    if channel not in CHANNELS or channel_for(version) != channel:
        raise ValueError('Version does not belong to the selected channel')
    return version


def resolve_version(declared: str, channel: str, requested: str = '') -> str:
    """Nightly returns a base; only the atomic allocator can append its number."""
    if not STABLE.fullmatch(declared) or channel not in CHANNELS:
        raise ValueError('Invalid declared version or channel')
    if channel == 'nightly':
        if requested:
            raise ValueError('Nightly version is derived from the source and allocated build number')
        return declared
    version = requested or (declared if channel == 'stable' else '')
    require_version(version, channel)
    if version.split('-', 1)[0] != declared:
        raise ValueError('The requested version must use the committed framework base')
    return version
