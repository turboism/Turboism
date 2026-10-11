"""V2 producer reports plus independent synthetic rasters; no native pixels."""
from pathlib import Path
import importlib.util
import unittest

path = Path(__file__).with_name('test_pose_drive_frames.py')
spec = importlib.util.spec_from_file_location('return_pose_frames', path)
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
fixtures.EVIDENCE = fixtures.ROOT / 'build/model-import-3d-offline/neutral-pixels-r1-20261001'

if __name__ == '__main__':
    result = unittest.TextTestRunner(verbosity=1).run(unittest.defaultTestLoader.loadTestsFromModule(fixtures))
    raise SystemExit(not result.wasSuccessful())
