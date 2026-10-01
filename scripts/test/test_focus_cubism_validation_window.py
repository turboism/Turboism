"""Task binding and compositor coordinate regressions; no real desktop operations."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("window_helper", Path(__file__).resolve().parents[1]
                                           / "preview/focus-cubism-validation-window.py")
helper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(helper)


class WindowHelperTest(unittest.TestCase):
    def test_only_the_unique_task_fixture_window_is_eligible(self):
        windows = [{"id": 1, "title": "other.cmo3 - Editor"},
                   {"id": 2, "title": "queue-task.cmo3 - Editor"}]
        self.assertEqual(2, helper.task_window(windows, "queue-task")["id"])
        self.assertIsNone(helper.task_window(windows, "queue-missing"))
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            helper.task_window(windows + [windows[1]], "queue-task")

    def test_output_position_and_tile_insets_are_included(self):
        window = {"workspace_id": 5, "layout": {"tile_pos_in_workspace_view": [16.0, 30.0],
                  "window_offset_in_tile": [0.0, 1.0], "window_size": [943, 1014]}}
        workspaces = [{"id": 5, "output": "screen"}]
        outputs = {"screen": {"logical": {"x": 1920, "y": 0, "scale": 1, "transform": "Normal"}}}
        self.assertEqual((1936, 31, 943, 1014), helper.window_geometry(window, workspaces, outputs))
        window["layout"]["tile_pos_in_workspace_view"] = None
        with self.assertRaisesRegex(ValueError, "not visible yet"):
            helper.window_geometry(window, workspaces, outputs)
        window["layout"]["tile_pos_in_workspace_view"] = [16.0, 30.0]
        outputs["screen"]["logical"]["scale"] = 1.25
        with self.assertRaisesRegex(ValueError, "unscaled"):
            helper.window_geometry(window, workspaces, outputs)

    def test_other_run_directory_is_rejected_before_any_files_are_created(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "queue-owner"
            with self.assertRaises(ValueError):
                helper.run(path, "queue-other", "/never-executed")
            self.assertFalse(path.exists())

    def test_focus_and_capture_only_use_the_matched_task_window(self):
        with tempfile.TemporaryDirectory() as temporary:
            task = Path(temporary) / "queue-task"
            state = task / "turboism-home/state/dev.turboism.validation.mesh-edit"
            state.mkdir(parents=True)
            (state / "selection-brush-preview.request").write_text("runId=queue-task\n")
            window = {"id": 2, "title": "queue-task.cmo3 - Editor", "is_focused": False,
                      "workspace_id": 5, "layout": {"tile_pos_in_workspace_view": None,
                      "window_offset_in_tile": [0, 0], "window_size": [943, 1014]}}
            clock = [0.0]
            commands = []

            def query(_niri, kind):
                if kind == "windows": return [{"id": 1, "title": "other.cmo3"}, window]
                if kind == "workspaces": return [{"id": 5, "output": "screen"}]
                return {"screen": {"logical": {"x": 0, "y": 0, "scale": 1, "transform": "Normal"}}}

            def command(argv, **_kwargs):
                commands.append(argv)
                self.assertEqual("2", argv[argv.index("--id") + 1])
                if "focus-window" in argv: window["is_focused"] = True
                if "move-window-to-floating" in argv: window["layout"]["tile_pos_in_workspace_view"] = [16, 31]
                if "screenshot-window" in argv:
                    Path(argv[argv.index("--path") + 1]).write_bytes(b"\x89PNG\r\n\x1a\nfixture")

            def sleep(seconds):
                clock[0] += seconds
                if (state / "selection-brush-preview.receipt").exists(): clock[0] = 1201

            with mock.patch.object(helper, "niri_json", side_effect=query), \
                    mock.patch.object(helper.subprocess, "run", side_effect=command), \
                    mock.patch.object(helper.time, "monotonic", side_effect=lambda: clock[0]), \
                    mock.patch.object(helper.time, "sleep", side_effect=sleep):
                helper.run(task, "queue-task", "/fixture-niri")
            receipt = helper.read_properties(state / "selection-brush-preview.receipt")
            self.assertEqual("queue-task", receipt["runId"])
            self.assertEqual("2", receipt["windowId"])
            self.assertEqual("PASS", receipt["status"])
            self.assertEqual(3, len(commands))
            self.assertFalse((state / "editor-window-ready.properties").exists())


if __name__ == "__main__":
    unittest.main()
