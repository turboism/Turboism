"""SDK first-release tracking decides the lowest API a plugin can declare."""
import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location("sdk_api_since", ROOT / "scripts/sdk_api_since.py")
SINCE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(SINCE)

OLD = "dev.turboism.sdk.Old"
NEW = "dev.turboism.sdk.New"


class SdkApiSinceTest(unittest.TestCase):
    def table(self):
        return SINCE.build_since([
            ("0.67.0", [OLD, f"{OLD}#run:()V"]),
            ("0.68.0", [OLD, f"{OLD}#run:()V", f"{OLD}#extra:()V", NEW, f"{NEW}#make:()V"]),
        ])

    def test_release_tags_use_latest_snapshot_per_base_and_skip_nightlies(self):
        tags = ["v0.66.0", "v0.67.0-beta.1", "v0.67.0", "v0.68.0-beta.1", "v0.68.0-beta.2",
                "v0.68.0-0.nightly.71", "v0.69.0-beta.1"]
        self.assertEqual(
            SINCE.release_tags(tags),
            [("0.67.0", "v0.67.0"), ("0.68.0", "v0.68.0-beta.2"), ("0.69.0", "v0.69.0-beta.1")],
        )

    def test_members_inherit_their_class_release_and_baseline_is_omitted(self):
        table = self.table()
        self.assertEqual(table["classes"], {NEW: "0.68.0"})
        self.assertEqual(table["members"], {f"{OLD}#extra:()V": "0.68.0"})
        self.assertEqual(SINCE.since_of(table, f"{OLD}#run:()V"), "0.67.0")
        self.assertEqual(SINCE.since_of(table, f"{NEW}#make:()V"), "0.68.0")

    def test_floor_takes_newest_link_but_accepts_older_inherited_declaration(self):
        table = self.table()
        lines = [
            f"toy\t{OLD}\t{OLD} {OLD}#run:()V",
            f"toy\t{OLD}\t{OLD} UNRESOLVED:{OLD}#getClass:()V",
            f"other\t{OLD}\t{OLD} {OLD}#extra:()V",
        ]
        floors = SINCE.plugin_floors(table, lines)
        self.assertEqual(floors["toy"][0], "0.67.0")
        self.assertEqual(floors["other"][0], "0.68.0")
        self.assertEqual(SINCE.plugin_floors(table, [f"x\t{NEW}\t{NEW}"])["x"][0], "0.68.0")


if __name__ == "__main__":
    unittest.main()
