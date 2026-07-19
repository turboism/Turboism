"""Composition façade for SDK API tier Java fixture sources."""
from __future__ import annotations

from sdk_api_tiers_test_fixture_additions import addition_sources
from sdk_api_tiers_test_fixture_illegal import illegal_sources
from sdk_api_tiers_test_fixture_shared import annotation_source, shared_sources
from sdk_api_tiers_test_fixture_types import initial_sources, source_for_internal, source_for_type


def sources(current: bool, variant: str) -> dict[str, str]:
    result = initial_sources(current, variant)
    result.update(shared_sources(current, variant))
    result.update(addition_sources(current, variant))
    result.update(illegal_sources(current, variant))
    return result
