"""Unified SelfTest: meaningful negative cases from all retired gates."""
from scripts.test import (test_reviewed_releases, test_reviewed_releases_r1,
    test_reviewed_releases_r2, test_reviewed_releases_r3,
    test_reviewed_releases_r4, test_reviewed_releases_index, test_reviewed_releases_ranges)


def load_tests(loader, tests, pattern):
    for module in (test_reviewed_releases, test_reviewed_releases_r1,
                   test_reviewed_releases_r2, test_reviewed_releases_r3,
                   test_reviewed_releases_r4, test_reviewed_releases_index, test_reviewed_releases_ranges):
        tests.addTests(loader.loadTestsFromModule(module))
    return tests
