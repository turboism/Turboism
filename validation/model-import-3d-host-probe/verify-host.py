#!/usr/bin/env python3
"""Independent N2/N3a/N3b stored/history/calculated-geometry verifier. Stage A remains unverified."""
import argparse
import copy
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import sys
import tarfile
import zipfile

import runpy
EVALUATION = runpy.run_path(str(Path(__file__).with_name('verify-evaluation.py')))
ORDER = runpy.run_path(str(Path(__file__).with_name('verify-order.py')))
MORPH = runpy.run_path(str(Path(__file__).with_name('verify-morph.py')))
MORPH_ORDER = runpy.run_path(str(Path(__file__).with_name('verify-morph-order.py')))
IMAGE_BINDING = runpy.run_path(str(Path(__file__).with_name('verify-image-binding.py')))
PIXEL_SAMPLING = runpy.run_path(str(Path(__file__).with_name('verify-pixel-sampling.py')))
SHAPE_IMPORT = runpy.run_path(str(Path(__file__).with_name('verify-shape-import.py')))
WHOLE_IMPORT = runpy.run_path(str(Path(__file__).with_name('verify-whole-import.py')))
CUBE_RENDER = runpy.run_path(str(Path(__file__).with_name('verify-cube-render.py')))
CUBE_GEOMETRY = runpy.run_path(str(Path(__file__).with_name('verify-cube-geometry.py')))
ATLAS_PERSISTENCE = runpy.run_path(str(Path(__file__).with_name('verify-atlas-persistence.py')))
JOINT_POSE = runpy.run_path(str(Path(__file__).with_name('verify-joint-pose.py')))
ROOT = Path(__file__).resolve().parents[2]
POSITIONS = [[16, 24], [272, 24], [272, 152], [16, 152]]
UVS = [[0, 0], [1, 0], [1, 1], [0, 1]]
INDICES = [0, 1, 2, 0, 2, 3]
JAR_SHA = '988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'
# Full six-matrix state/history evidence exceeded first the N2-only 2 MB budget and then the
# N3c 8 MiB budget (observed complete N4b report: 8,405,488 bytes). Keep a fixed byte ceiling,
# enforced before allocation/JSON decoding; never trim assertion evidence.
MAX_NATIVE_REPORT_BYTES = 32 * 1024 * 1024
# Joint r2's complete native report is 77,228,895 bytes (66,226,425 bytes of
# joint-pose evidence including all consumption-wait observations). Admit this
# larger fixed budget only when the caller explicitly requests joint-pose;
# decoded content must still match that mode and pass every semantic check.
MAX_JOINT_REPORT_BYTES = 128 * 1024 * 1024
MAX_CUBE_REPORT_BYTES = 128 * 1024 * 1024
# Principled PNG bounds: a 1024x1024 RGBA raster is ~4.2 MiB raw; per-member and
# aggregate caps are enforced on declared tar sizes before any read.
PIXEL_MEMBER_MAX = 8 * 1024 * 1024
PIXEL_TOTAL_MAX = 32 * 1024 * 1024
CMO3_MAX = 32 * 1024 * 1024
SECTION_PREFIX = 'state/dev.turboism.validation.model-import-3d/'
PERSISTENCE_MEMBER = SECTION_PREFIX + 'persistence/mi3d-t014-model.cmo3'
POST_SAVE_MEMBER = SECTION_PREFIX + 'persistence/post-save-p0.png'
CHECKS = {'newCommand', 'newIdentity', 'blankMesh', 'historyAvailable', 'createSingleUndo',
          'createdGeometry', 'createdReadNoChange', 'createUndo', 'deletedHandle', 'createRedo',
          'gridTuples', 'gridReadNoChange', 'allUndo', 'allRedo', 'restored'}
DEFERRED = {'morphCreation', 'basicIdentity', 'absoluteVsDelta', 'targetWrite',
            'atomicImport', 'dirty', 'saveReopen', 'atlasRepack', 'pluginDisable', 'hostLifecycle'}


def require(value, reason):
    if not value:
        raise ValueError(reason)


def pairs(rows):
    result = {}
    for key, value in rows:
        require(key not in result, 'duplicate JSON key')
        result[key] = value
    return result


def loads(text):
    return json.loads(text, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite JSON')))


def read(path):
    return loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def path_checked(path):
    require('..' not in path.parts, 'traversal path')
    path = Path(os.path.abspath(path))
    require(not any(p.is_symlink() for p in (path, *path.parents)), 'symlink path')
    return path


def verify_probe_metadata(jar):
    # Independent packaging checks; the complete real runtime contract also runs during build.
    names = [e.filename for e in jar.infolist() if not e.is_dir()]
    require(len(names) == len(set(names)), 'duplicate probe JAR entries')
    meta = loads(jar.read('META-INF/turboism/plugin.json'))
    require(meta['id'] == 'dev.turboism.validation.model-import-3d', 'wrong probe metadata')
    require(meta['entrypoints'] and all(e.replace('.', '/') + '.class' in names for e in meta['entrypoints']),
            'missing plugin entrypoint')
    base = meta['i18n']['baseName']
    expected = {base + '.properties'} | {base + ('' if locale == 'base' else '_' + locale.replace('-', '_'))
                                        + '.properties' for locale in meta['i18n']['locales']}
    require(expected.issubset(names), 'missing declared i18n catalog (base required even with no locales)')
    actual = {n for n in names if n == base + '.properties' or (n.startswith(base + '_') and n.endswith('.properties'))}
    require(actual == expected, 'undeclared i18n catalog')


WARP_AUTHORING_SOURCES_V1 = frozenset((
    'plugins/model-import-3d/src/test/java/dev/turboism/validation/modelimport3d/host/EmbeddedTextureTest.java',
    'plugins/model-import-3d/src/test/java/dev/turboism/validation/modelimport3d/host/PlanarImportTest.java',
    'scripts/preview/host-validation-tasks.json',
    'scripts/preview/run-model-import-3d-host-validation.sh',
    'scripts/test/test_model_import_3d_cube_geometry.py',
    'scripts/test/test_model_import_3d_cube_render.py',
    'scripts/test/test_model_import_3d_evaluation.py',
    'scripts/test/test_model_import_3d_host_validation.py',
    'scripts/test/test_model_import_3d_image_binding.py',
    'scripts/test/test_model_import_3d_joint_pose.py',
    'scripts/test/test_model_import_3d_morph.py',
    'scripts/test/test_model_import_3d_morph_order.py',
    'scripts/test/test_model_import_3d_order.py',
    'scripts/test/test_model_import_3d_persistence.py',
    'scripts/test/test_model_import_3d_pixel_sampling.py',
    'scripts/test/test_model_import_3d_shape_import.py',
    'scripts/test/test_model_import_3d_whole_import.py',
    'validation/model-import-3d-host-probe/README.md',
    'validation/model-import-3d-host-probe/build-host.py',
    'validation/model-import-3d-host-probe/input-src/README.md',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/ClosedConvexSurfaceProof.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/CubeAtlasPersistenceProbe.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/CubeAtlasProbe.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/CubeGeometryProbe.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/CubeMocExportProbe.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/CubePersistenceFile.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/CubePersistenceInput.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/CubePersistenceProbe.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/CubePersistenceRender.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/CubeRenderProbe.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/EmbeddedBaseColorTexture.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/GlbInputMain.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/GlbProjectionMain.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/GlbSurfaceReader.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/ParallelSheetOrderPlan.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/PlanarGeometryProbe.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/PlanarPixelCapture.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/PlanarQuadProof.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/PoseDriveHistory.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SheetGeometryProbe.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SheetPixelCapture.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceAtlasLayout.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceAutoCamera.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceBaseColorPlan.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceBspMerge.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceBspPlan.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceFixedIndexPlan.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceGeometryCapture.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceImport.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceImportAppearance.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceImportGeometry.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceImportPlan.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfacePatchPlan.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceSheetOrderBake.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceSnapshotFingerprint.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/SurfaceSourceSplit.java',
    'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/WholeImportProbe.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/ClosedConvexSurfaceProofTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/CubeGeometryProducerIntegrationTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/CubePersistenceFileTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/CubePersistenceInputTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/GlbSurfaceReaderTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/IntersectingInputTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/PlanarGeometryInputTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/PlanarPixelEncodingTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/PlanarQuadProofTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/PoseDriveHistoryTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/SheetGeometryInputTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/SheetPixelEncodingTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/SurfaceAtlasLayoutTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/SurfaceGeometryCaptureTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/SurfaceImportAppearanceTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/SurfaceImportGeometryTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/SurfaceImportPlanTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/SurfaceImportTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/SurfaceSheetImportTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/SurfaceSnapshotFingerprintTest.java',
    'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/TexturedInterpolationTest.java',
    'validation/model-import-3d-host-probe/native-src/META-INF/turboism/i18n/messages.properties',
    'validation/model-import-3d-host-probe/native-src/META-INF/turboism/plugin.json',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/EvaluationProbe.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/HostProbe.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/ImageBindingProbe.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/JointPoseProbe.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/MorphOrderProbe.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/MorphProbe.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/NativeAssertions.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/NativeReport.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/OrderProbe.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/OrdinaryWriteProbe.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/PixelSamplingProbe.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/ShapeImportProbe.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/SurfaceKeyPlan.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/SurfaceKeyformBake.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/SurfaceProjection.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/SurfaceWarpKeyformBake.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/WarpAuthoringProbe.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/EvaluationAssertionsTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/HostAssertionsTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/ImageBindingAssertionsTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/JointPoseProducerIntegrationTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/MorphAssertionsTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/MorphOrderAssertionsTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/OrderAssertionsTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/PixelSamplingAssertionsTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/PixelSamplingProducerIntegrationTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/ShapeImportAssertionsTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/SurfaceKeyformBakeTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/SurfaceProjectionTest.java',
    'validation/model-import-3d-host-probe/packaging-test/dev/turboism/validation/modelimport3d/packaging/PluginJarCheck.java',
    'validation/model-import-3d-host-probe/test-atlas-persistence.py',
    'validation/model-import-3d-host-probe/verify-atlas-persistence.py',
    'validation/model-import-3d-host-probe/verify-cube-geometry.py',
    'validation/model-import-3d-host-probe/verify-cube-render.py',
    'validation/model-import-3d-host-probe/verify-evaluation.py',
    'validation/model-import-3d-host-probe/verify-host.py',
    'validation/model-import-3d-host-probe/verify-image-binding.py',
    'validation/model-import-3d-host-probe/verify-joint-pose.py',
    'validation/model-import-3d-host-probe/verify-morph-order.py',
    'validation/model-import-3d-host-probe/verify-morph.py',
    'validation/model-import-3d-host-probe/verify-order.py',
    'validation/model-import-3d-host-probe/verify-persistence-pair.py',
    'validation/model-import-3d-host-probe/verify-pixel-sampling.py',
    'validation/model-import-3d-host-probe/verify-shape-import.py',
    'validation/model-import-3d-host-probe/verify-warp-authoring.py',
    'validation/model-import-3d-host-probe/verify-whole-import.py',
))


SOURCE_WARP_CANDIDATE_SOURCES_V1 = WARP_AUTHORING_SOURCES_V1 | frozenset((
    'validation/model-import-3d-host-probe/verify-source-warp-candidate.py',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/SourceWarpFixture.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/SourceWarpAuthoring.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/SourceWarpCandidateProbe.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/SourceWarpFixtureTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/SourceWarpAuthoringTest.java',
))

SOURCE_WARP_CANDIDATE_SOURCES_V2 = SOURCE_WARP_CANDIDATE_SOURCES_V1 | frozenset((
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/SourceWarpAppearance.java',
    'validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/SourceWarpPixelCapture.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/SourceWarpAppearanceTest.java',
    'validation/model-import-3d-host-probe/native-test/dev/turboism/validation/modelimport3d/host/SourceWarpAppearanceBindingTest.java',
))

CANONICAL_WARP_SOURCES_V1 = SOURCE_WARP_CANDIDATE_SOURCES_V2 | frozenset(
    'plugins/model-import-3d/src/main/java/dev/turboism/validation/modelimport3d/host/'+n+'.java'
    for n in ('SurfaceWarpSpatialFit','SurfaceQuadChartDiscovery','SurfaceWarpChartPlan','SurfaceWarpImagePlan','SurfaceWarpAuthoring','SurfaceWarpImportCandidate')) | frozenset(
    'validation/model-import-3d-canonical-warp/src/dev/turboism/validation/modelimport3d/host/'+n+'.java'
    for n in ('CanonicalWarpProbe','CanonicalWarpPreparationTest'))

CANONICAL_WARP_SOURCES_V2 = CANONICAL_WARP_SOURCES_V1 | frozenset((
    'validation/model-import-3d-canonical-warp/verify-canonical-warp.py',
    'validation/model-import-3d-canonical-warp/test-checker.py',
))

CANONICAL_LAYERED_SOURCES_V1 = CANONICAL_WARP_SOURCES_V2 | frozenset(
 'plugins/model-import-3d/src/main/java/dev/turboism/validation/modelimport3d/host/'+n+'.java'
 for n in ('SurfaceWarpLayerPlan','SurfaceWarpLayerAuthoring')) | frozenset(
 'validation/model-import-3d-canonical-warp/src/dev/turboism/validation/modelimport3d/host/'+n+'.java'
 for n in ('CanonicalWarpLayerProbe','CanonicalWarpLayerPreparationTest')) | frozenset(
 'validation/model-import-3d-canonical-warp/'+n for n in ('verify-canonical-warp-layered.py','test-layered-checker.py'))

def verify_build(run):
    run = path_checked(run)
    require(run.parent == ROOT / 'build/model-import-3d-host', 'not a task build run')
    terminal = read(run / 'terminal.json')
    require(terminal['status'] == 'PASS' and terminal['hostExecuted'] is False
            and terminal['inputsUnchanged'] is True, 'build did not complete')
    p = run / 'provenance'; b = read(p / 'build.json'); manifest = read(p / 'source-manifest.json')
    require(b['run'] == run.name and re.fullmatch('[0-9a-f]{40}', b['head']), 'build identity')
    require(sha(p / 'source-manifest.json') == b['sourceManifestSha256'], 'source manifest hash')
    actual_sources = {str(f.relative_to(p / 'source')) for f in (p / 'source').rglob('*') if f.is_file()}
    warp_inventory = b.get('sourceInventoryVersion')
    inventories={'mi3d-warp-authoring-sources-v1':WARP_AUTHORING_SOURCES_V1,
                 'mi3d-source-warp-candidate-sources-v1':SOURCE_WARP_CANDIDATE_SOURCES_V1,
                 'mi3d-source-warp-candidate-sources-v2':SOURCE_WARP_CANDIDATE_SOURCES_V2,
                 'mi3d-canonical-warp-sources-v1':CANONICAL_WARP_SOURCES_V1,
                 'mi3d-canonical-warp-sources-v2':CANONICAL_WARP_SOURCES_V2,
                 'mi3d-canonical-warp-sources-v3':CANONICAL_WARP_SOURCES_V2,
                 'mi3d-canonical-layered-sources-v1':CANONICAL_LAYERED_SOURCES_V1}
    require(warp_inventory is None or warp_inventory in inventories, 'unknown source inventory version')
    if warp_inventory:
        require(set(manifest) == inventories[warp_inventory], 'warp source inventory missing/extra')
    layered = warp_inventory=='mi3d-canonical-layered-sources-v1'
    canonical_warp = warp_inventory in ('mi3d-canonical-warp-sources-v1','mi3d-canonical-warp-sources-v2','mi3d-canonical-warp-sources-v3','mi3d-canonical-layered-sources-v1')
    source_warp = warp_inventory in ('mi3d-source-warp-candidate-sources-v1','mi3d-source-warp-candidate-sources-v2')
    source_texture = b.get('sourceWarpTextureSha256') is not None
    require(not source_texture or warp_inventory in ('mi3d-source-warp-candidate-sources-v2','mi3d-canonical-warp-sources-v1','mi3d-canonical-warp-sources-v2','mi3d-canonical-warp-sources-v3','mi3d-canonical-layered-sources-v1'),'texture requires explicit new inventory')
    stage = b.get('inputStageVersion')
    surface_render = stage in ('mi3d-surface-render-v1','mi3d-surface-render-v2')
    if surface_render:
        require(all('validation/model-import-3d-host-probe/' + name in manifest for name in (
            'verify-planar-pixels.py',
            'input-src/dev/turboism/validation/modelimport3d/host/PlanarPixelCapture.java',
            'input-test/dev/turboism/validation/modelimport3d/host/PlanarPixelEncodingTest.java')), 'planar pixel dependencies missing')
    pose_drive_geometry = surface_render or stage in ('mi3d-pose-drive-geometry-v1','mi3d-pose-drive-geometry-v2')
    if pose_drive_geometry:
        require(all('validation/model-import-3d-host-probe/' + name in manifest for name in (
            'verify-pose-drive.py',
            'input-src/dev/turboism/validation/modelimport3d/host/PoseDriveHistory.java',
            'input-test/dev/turboism/validation/modelimport3d/host/PoseDriveHistoryTest.java')), 'pose drive dependencies missing')
    planar_geometry = pose_drive_geometry or stage == 'mi3d-planar-geometry-v1'
    if planar_geometry:
        require(all('validation/model-import-3d-host-probe/' + name in manifest for name in (
            'input-src/dev/turboism/validation/modelimport3d/host/PlanarGeometryProbe.java',
            'input-test/dev/turboism/validation/modelimport3d/host/PlanarGeometryInputTest.java')), 'planar entry dependencies missing')
    sheet_render = planar_geometry or stage == 'mi3d-sheet-render-v1' 
    sheet_geometry = sheet_render or stage == 'mi3d-sheet-geometry-v1'
    if sheet_render:
        require(all('validation/model-import-3d-host-probe/' + name in manifest for name in (
            'verify-sheet-order.py','verify-sheet-pixels.py',
            'input-src/dev/turboism/validation/modelimport3d/host/SheetPixelCapture.java',
            'input-test/dev/turboism/validation/modelimport3d/host/SheetPixelEncodingTest.java')), 'sheet pixel dependencies missing')
    source_persistence = sheet_geometry or stage == 'mi3d-source-texture-persistence-v1'
    if sheet_geometry:
        prefix = 'validation/model-import-3d-host-probe/'
        require(all(prefix + name in manifest for name in (
            'verify-surface-geometry.py',
            'input-src/dev/turboism/validation/modelimport3d/host/SheetGeometryProbe.java',
            'input-src/dev/turboism/validation/modelimport3d/host/SurfaceGeometryCapture.java',
            'input-src/dev/turboism/validation/modelimport3d/host/ParallelSheetOrderPlan.java',
            'input-src/dev/turboism/validation/modelimport3d/host/SurfaceSheetOrderBake.java',
            'input-test/dev/turboism/validation/modelimport3d/host/SheetGeometryInputTest.java',
            'input-test/dev/turboism/validation/modelimport3d/host/SurfaceSheetImportTest.java',
            'input-test/dev/turboism/validation/modelimport3d/host/SurfaceGeometryCaptureTest.java')), 'sheet dependencies missing')
    planar_quad = source_persistence or stage == 'mi3d-planar-quad-v1'
    if source_persistence:
        require(all(name in manifest for name in (
            'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/CubePersistenceInput.java',
            'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/CubePersistenceInputTest.java',
            'validation/model-import-3d-user-entry/verify-report.py',
            'validation/model-import-3d-user-entry/verify-texture-pixels.py')), 'source persistence dependencies missing')
    embedded_texture = planar_quad or stage == 'mi3d-embedded-base-color-v1'
    if planar_quad:
        require(all(name in manifest for name in (
            'validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/PlanarQuadProof.java',
            'validation/model-import-3d-host-probe/input-test/dev/turboism/validation/modelimport3d/host/PlanarQuadProofTest.java',
            'plugins/model-import-3d/src/test/java/dev/turboism/validation/modelimport3d/host/PlanarImportTest.java')), 'planar quad sources missing')
    atlas_persistence = embedded_texture or stage == 'mi3d-atlas-persistence-v1'
    if embedded_texture:
        require('validation/model-import-3d-host-probe/input-src/dev/turboism/validation/modelimport3d/host/EmbeddedBaseColorTexture.java' in manifest, 'embedded texture source missing')
        require('plugins/model-import-3d/src/test/java/dev/turboism/validation/modelimport3d/host/EmbeddedTextureTest.java' in manifest, 'embedded texture test source missing')
    if atlas_persistence:
        prefix = 'validation/model-import-3d-host-probe/'
        require(all(prefix + name in manifest for name in (
            'input-src/dev/turboism/validation/modelimport3d/host/CubeAtlasPersistenceProbe.java',
            'verify-atlas-persistence.py', 'test-atlas-persistence.py')),
            'atlas persistence sources missing')
        # This stage retains every export-stage build/test contract, adding
        # the persistence probe and its two independent checker sources.
        stage = 'mi3d-official-export-v1'
    require(stage in (None, 'mi3d-cube-input-v1', 'mi3d-base-color-input-v1', 'mi3d-render-input-v1', 'mi3d-import-consumer-v1', 'mi3d-whole-import-v1', 'mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'), 'unknown input stage version')
    require(set(manifest) == actual_sources and len(manifest) == (len(inventories[warp_inventory]) if warp_inventory else 125 if surface_render else 124 if pose_drive_geometry else 119 if planar_geometry else 117 if sheet_render else 113 if sheet_geometry else 105 if source_persistence else 99 if planar_quad else 96 if embedded_texture else 94 if atlas_persistence else 91 if stage == 'mi3d-official-export-v1' else 90 if stage in ('mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1') else 89 if stage == 'mi3d-cube-atlas-plan-v1' else 87 if stage in ('mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1') else 86 if stage == 'mi3d-cube-persistence-v1' else 85 if stage in ('mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1') else 83 if stage == 'mi3d-whole-import-v1' else 80 if stage == 'mi3d-import-consumer-v1' else 78 if stage == 'mi3d-render-input-v1' else 73 if stage == 'mi3d-base-color-input-v1' else 70 if stage else 54), 'source inventory incomplete')
    if stage:
        prefix = 'validation/model-import-3d-host-probe/'
        required_input = {'input-src/README.md', 'verify-cube-geometry.py'}
        required_input.update('input-src/dev/turboism/validation/modelimport3d/host/' + n + '.java' for n in (
            'GlbInputMain', 'GlbProjectionMain', 'GlbSurfaceReader', 'SurfacePatchPlan', 'SurfaceImportPlan',
            'SurfaceImportGeometry', 'ClosedConvexSurfaceProof', 'CubeGeometryProbe'))
        required_input.update('input-test/dev/turboism/validation/modelimport3d/host/' + n + '.java' for n in (
            'GlbSurfaceReaderTest', 'SurfaceImportPlanTest', 'SurfaceImportGeometryTest',
            'ClosedConvexSurfaceProofTest', 'CubeGeometryProducerIntegrationTest'))
        require(all(prefix + n in manifest for n in required_input)
                and 'scripts/test/test_model_import_3d_cube_geometry.py' in manifest, 'cube input source missing')
    if stage in ('mi3d-base-color-input-v1', 'mi3d-render-input-v1', 'mi3d-import-consumer-v1', 'mi3d-whole-import-v1', 'mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
        require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
            'input-src/dev/turboism/validation/modelimport3d/host/SurfaceBaseColorPlan.java',
            'input-src/dev/turboism/validation/modelimport3d/host/SurfaceImportAppearance.java',
            'input-test/dev/turboism/validation/modelimport3d/host/SurfaceImportAppearanceTest.java')),
            'base-color consumer source missing')
    if stage in ('mi3d-render-input-v1', 'mi3d-import-consumer-v1', 'mi3d-whole-import-v1', 'mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
        require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
            'verify-cube-render.py',
            'input-src/dev/turboism/validation/modelimport3d/host/CubeRenderProbe.java',
            'input-src/dev/turboism/validation/modelimport3d/host/SurfaceSnapshotFingerprint.java',
            'input-test/dev/turboism/validation/modelimport3d/host/SurfaceSnapshotFingerprintTest.java'))
            and 'scripts/test/test_model_import_3d_cube_render.py' in manifest, 'render oracle sources missing')
    if stage in ('mi3d-import-consumer-v1', 'mi3d-whole-import-v1', 'mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
        require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
            'input-src/dev/turboism/validation/modelimport3d/host/SurfaceImport.java',
            'input-test/dev/turboism/validation/modelimport3d/host/SurfaceImportTest.java')),
            'whole import consumer sources missing')
    if stage == 'mi3d-official-export-v1':
        require(prefix + 'input-src/dev/turboism/validation/modelimport3d/host/CubeMocExportProbe.java' in manifest, 'export probe source missing')
    if stage in ('mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
        require(prefix + 'input-src/dev/turboism/validation/modelimport3d/host/CubeAtlasProbe.java' in manifest, 'atlas probe source missing')
    if stage in ('mi3d-cube-atlas-plan-v1','mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
        require(all(prefix + p in manifest for p in ('input-src/dev/turboism/validation/modelimport3d/host/SurfaceAtlasLayout.java', 'input-test/dev/turboism/validation/modelimport3d/host/SurfaceAtlasLayoutTest.java')), 'atlas planner sources missing')
    if stage in ('mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
        require(prefix + 'input-src/dev/turboism/validation/modelimport3d/host/CubePersistenceRender.java' in manifest, 'persistence render source missing')
    if stage in ('mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
        require(prefix + 'input-src/dev/turboism/validation/modelimport3d/host/CubePersistenceProbe.java' in manifest, 'cube persistence probe missing')
    if stage in ('mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
        require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
            'input-src/dev/turboism/validation/modelimport3d/host/CubePersistenceFile.java',
            'input-test/dev/turboism/validation/modelimport3d/host/CubePersistenceFileTest.java')),
            'cube save command sources missing')
    if stage in ('mi3d-whole-import-v1', 'mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
        require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
            'verify-whole-import.py', 'input-src/dev/turboism/validation/modelimport3d/host/WholeImportProbe.java'))
            and 'scripts/test/test_model_import_3d_whole_import.py' in manifest, 'whole import gate sources missing')
    require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
        'verify-joint-pose.py',
        'native-src/dev/turboism/validation/modelimport3d/host/SurfaceProjection.java',
        'native-src/dev/turboism/validation/modelimport3d/host/SurfaceKeyPlan.java',
        'native-src/dev/turboism/validation/modelimport3d/host/SurfaceKeyformBake.java',
        'native-src/dev/turboism/validation/modelimport3d/host/JointPoseProbe.java',
        'native-test/dev/turboism/validation/modelimport3d/host/SurfaceProjectionTest.java',
        'native-test/dev/turboism/validation/modelimport3d/host/SurfaceKeyformBakeTest.java',
        'native-test/dev/turboism/validation/modelimport3d/host/JointPoseProducerIntegrationTest.java'))
        and 'scripts/test/test_model_import_3d_joint_pose.py' in manifest, 'joint projection source missing')
    require('validation/model-import-3d-host-probe/native-src/dev/turboism/validation/modelimport3d/host/OrdinaryWriteProbe.java'
            in manifest, 'N3a source missing')
    require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
        'verify-evaluation.py', 'native-src/dev/turboism/validation/modelimport3d/host/EvaluationProbe.java',
        'native-test/dev/turboism/validation/modelimport3d/host/EvaluationAssertionsTest.java')),
        'N3b source missing')
    require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
        'verify-order.py', 'native-src/dev/turboism/validation/modelimport3d/host/OrderProbe.java',
        'native-test/dev/turboism/validation/modelimport3d/host/OrderAssertionsTest.java')),
        'N3c source missing')
    require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
        'verify-morph.py', 'native-src/dev/turboism/validation/modelimport3d/host/MorphProbe.java',
        'native-test/dev/turboism/validation/modelimport3d/host/MorphAssertionsTest.java')),
        'N4a source missing')
    require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
        'verify-morph-order.py', 'native-src/dev/turboism/validation/modelimport3d/host/MorphOrderProbe.java',
        'native-test/dev/turboism/validation/modelimport3d/host/MorphOrderAssertionsTest.java')),
        'N4b source missing')
    require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
        'verify-image-binding.py', 'native-src/dev/turboism/validation/modelimport3d/host/ImageBindingProbe.java',
        'native-test/dev/turboism/validation/modelimport3d/host/ImageBindingAssertionsTest.java'))
        and 'scripts/test/test_model_import_3d_image_binding.py' in manifest, 'N5a source missing')
    require(all('validation/model-import-3d-host-probe/' + n in manifest for n in (
        'verify-pixel-sampling.py',
        'native-src/dev/turboism/validation/modelimport3d/host/PixelSamplingProbe.java',
        'native-test/dev/turboism/validation/modelimport3d/host/PixelSamplingAssertionsTest.java',
        'native-test/dev/turboism/validation/modelimport3d/host/PixelSamplingProducerIntegrationTest.java'))
        and 'scripts/test/test_model_import_3d_pixel_sampling.py' in manifest, 'T008 source missing')
    require('validation/model-import-3d-host-probe/verify-persistence-pair.py' in manifest
            and 'scripts/test/test_model_import_3d_persistence.py' in manifest,
            'T014 source missing')
    for name, digest in manifest.items():
        source = path_checked(p / 'source' / name)
        require(source.is_relative_to(p / 'source') and sha(source) == digest, 'source bytes differ')
    agent = run / 'bundle/turboism-agent.jar'; probe = run / 'bundle/plugins/model-import-3d-host-probe.jar'
    for file, expected in ((p / 'sdk.jar', b['sdkSha256']), (agent, b['agentSha256']), (probe, b['probeSha256'])):
        path_checked(file); require(sha(file) == expected, 'artifact hash mismatch')
    require(terminal['probeSha256'] == b['probeSha256'], 'terminal artifact mismatch')
    with zipfile.ZipFile(p / 'sdk.jar') as sdk, zipfile.ZipFile(agent) as runtime, zipfile.ZipFile(probe) as jar:
        sdk_classes = [n for n in sdk.namelist() if n.startswith('dev/turboism/sdk/') and n.endswith('.class')]
        require(bool(sdk_classes), 'SDK classes missing')
        for name in sdk_classes:
            require(sdk.read(name) == runtime.read(name), 'bundled runtime SDK differs from compile SDK: ' + name)
        classes = {name: hashlib.sha256(jar.read(name)).hexdigest() for name in jar.namelist() if name.endswith('.class')}
        require(classes == b['classes'] and bool(classes), 'probe class inventory differs')
        if atlas_persistence:
            require('dev/turboism/validation/modelimport3d/host/CubeAtlasPersistenceProbe.class' in classes,
                    'atlas persistence class missing')
        require(all(n.startswith('dev/turboism/validation/modelimport3d/host/') for n in classes), 'non-probe classes bundled')
        require('dev/turboism/validation/modelimport3d/host/OrdinaryWriteProbe.class' in classes, 'N3a class missing')
        require('dev/turboism/validation/modelimport3d/host/EvaluationProbe.class' in classes, 'N3b class missing')
        require('dev/turboism/validation/modelimport3d/host/OrderProbe.class' in classes, 'N3c class missing')
        require('dev/turboism/validation/modelimport3d/host/MorphProbe.class' in classes, 'N4a class missing')
        require('dev/turboism/validation/modelimport3d/host/MorphOrderProbe.class' in classes, 'N4b class missing')
        require('dev/turboism/validation/modelimport3d/host/ImageBindingProbe.class' in classes, 'N5a class missing')
        require('dev/turboism/validation/modelimport3d/host/PixelSamplingProbe.class' in classes, 'T008 class missing')
        require(runtime.read('dev/turboism/preview/ValidationFileGrants.class'),
                'T014 grant seam class missing from the bundled runtime')
        if stage:
            require('dev/turboism/validation/modelimport3d/host/CubeGeometryProbe.class' in classes, 'cube producer missing')
        cube_resource = 'dev/turboism/validation/modelimport3d/host/fixtures/cube.glb'
        if b.get('cubeInputSha256') is not None:
            require(stage and b['cubeInputSha256'] == CUBE_GEOMETRY['INPUT_SHA256'], 'unauthorized cube input')
            cube = path_checked(p / 'cube.glb')
            require(cube.stat().st_size == 1936 and sha(cube) == b['cubeInputSha256']
                    and jar.read(cube_resource) == cube.read_bytes(), 'packaged cube differs from frozen input')
            b['verifiedCubeInputPath'] = str(cube)
        else:
            require(cube_resource not in jar.namelist(), 'undeclared cube resource')
        texture_resource = 'dev/turboism/validation/modelimport3d/host/fixtures/cube-textured.glb'
        if source_persistence:
            texture = path_checked(p / 'cube-textured.glb')
            require(b.get('persistenceTextureSha256') == 'dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'
                    and 0 < texture.stat().st_size <= 16*1024*1024 and sha(texture) == b['persistenceTextureSha256']
                    and jar.read(texture_resource) == texture.read_bytes(), 'packaged persistence texture differs')
            require('verifiedCubeInputPath' in b, 'source texture requires original geometry reference')
            b['verifiedPersistenceTexturePath'] = str(texture)
        else:
            require(b.get('persistenceTextureSha256') is None and texture_resource not in jar.namelist(), 'undeclared persistence texture')
        planar_hashes = {'plane.glb':'8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997',
                         'plane-seam.glb':'89d1617c9555250d504136c081abec11b2f3e00fde45237d633bdb2096585f34'}
        if planar_geometry:
            require(b.get('planarInputs') == planar_hashes, 'planar profile inventory')
            for name, expected in planar_hashes.items():
                file = path_checked(p / name)
                require(0 < file.stat().st_size <= 1024*1024 and sha(file) == expected
                        and jar.read('dev/turboism/validation/modelimport3d/host/fixtures/' + name) == file.read_bytes(),
                        'packaged planar fixture differs')
            require('dev/turboism/validation/modelimport3d/host/PlanarGeometryProbe.class' in classes, 'planar native entry missing')
            b['verifiedPlanarInputPaths'] = {name:str(p / name) for name in planar_hashes}
        else:
            require(not b.get('planarInputs') and not any(
                'dev/turboism/validation/modelimport3d/host/fixtures/' + name in jar.namelist()
                for name in planar_hashes), 'undeclared planar resource')
        sheet_resource = 'dev/turboism/validation/modelimport3d/host/fixtures/sheets.glb'
        if sheet_geometry:
            sheet = path_checked(p / 'sheets.glb')
            require(b.get('sheetInputSha256') == '0c0f716da082c9987ba580565a97ec81237c4e0333ae4023042b9c17b41a9984'
                    and 0 < sheet.stat().st_size <= 1024*1024 and sha(sheet) == b['sheetInputSha256']
                    and jar.read(sheet_resource) == sheet.read_bytes(), 'packaged sheets differ')
            require('dev/turboism/validation/modelimport3d/host/SheetGeometryProbe.class' in classes
                    and 'dev/turboism/validation/modelimport3d/host/SurfaceGeometryCapture.class' in classes, 'sheet native entry missing')
            b['verifiedSheetInputPath'] = str(sheet)
        else:
            require(b.get('sheetInputSha256') is None and sheet_resource not in jar.namelist(), 'undeclared sheet resource')
        verify_probe_metadata(jar)
        native = p / 'source/validation/model-import-3d-host-probe/native-src'
        warp_resource='dev/turboism/validation/modelimport3d/host/fixtures/apple-warp.bin'
        if source_warp:
            file=path_checked(p / 'apple-warp.bin')
            require(file.stat().st_size==73575376 and sha(file)==b.get('sourceWarpFixtureSha256')=='9320e7ae7a44f905cfab5827fe84f334b370e7e0f3ea9057fe05c01e4bd69360','source Warp fixture identity')
            require(jar.getinfo(warp_resource).file_size==73575376 and hashlib.sha256(jar.read(warp_resource)).hexdigest()==b['sourceWarpFixtureSha256'],'packaged source Warp differs')
            b['verifiedSourceWarpFixturePath']=str(file)
        else:
            require(b.get('sourceWarpFixtureSha256') is None and warp_resource not in jar.namelist(),'undeclared source Warp fixture')
        texture_resource='dev/turboism/validation/modelimport3d/host/fixtures/apple-textured.glb'
        if source_texture:
            file=path_checked(p / 'apple-textured.glb')
            require((source_warp or canonical_warp) and 0<file.stat().st_size<=16*1024*1024 and sha(file)==b['sourceWarpTextureSha256']=='3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999' and jar.read(texture_resource)==file.read_bytes(),'source Warp texture identity')
            b['verifiedSourceWarpTexturePath']=str(file)
        else:
            require(texture_resource not in jar.namelist(),'undeclared apple texture')
        resources = {str(f.relative_to(native)): f.read_bytes() for f in (native / 'META-INF').rglob('*') if f.is_file()}
        if b.get('cubeInputSha256') is not None or source_warp or canonical_warp:
            descriptor_name = 'META-INF/turboism/plugin.json'
            expected_descriptor = loads(resources[descriptor_name])
            require(expected_descriptor['resources'] == [], 'unexpected source resource declarations')
            expected_descriptor['resources'] = ['dev/turboism/validation/modelimport3d/host/fixtures/']
            packaged_descriptor = jar.read(descriptor_name)
            require(loads(packaged_descriptor) == expected_descriptor
                    and hashlib.sha256(packaged_descriptor).hexdigest() == b['packagedDescriptorSha256'],
                    'cube descriptor transformation differs')
            resources[descriptor_name] = packaged_descriptor
        require(bool(resources) and all(jar.read(n) == data for n, data in resources.items()), 'packaged resources differ from snapshot')
    packaging = p / 'packaging-classes'
    packaging_classes = {str(f.relative_to(packaging)): sha(path_checked(f)) for f in packaging.rglob('*.class')}
    require(packaging_classes == b['packagingClasses'] and set(packaging_classes) == {
        'dev/turboism/validation/modelimport3d/packaging/PluginJarCheck.class'}, 'packaging checker inventory')
    commands = read(run / 'commands.json')
    purposes = ['java-version', 'javac-version', 'compile-probe', 'compile-tests', 'synthetic-tests',
                'package-probe', 'compile-packaging-check', 'packaging-contract']
    if b.get('cubeInputSha256') is not None:
        purposes.insert(5, 'cube-producer-tests')
        if stage in ('mi3d-base-color-input-v1', 'mi3d-render-input-v1', 'mi3d-import-consumer-v1', 'mi3d-whole-import-v1', 'mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
            purposes.insert(6, 'material-consumer-tests')
        if stage in ('mi3d-render-input-v1', 'mi3d-import-consumer-v1', 'mi3d-whole-import-v1', 'mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
            purposes.insert(7, 'render-oracle-tests')
        if stage in ('mi3d-import-consumer-v1', 'mi3d-whole-import-v1', 'mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
            purposes.insert(7, 'whole-import-consumer-tests')
        if stage in ('mi3d-whole-import-v1', 'mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
            purposes.insert(8, 'whole-import-oracle-tests')
    if stage in ('mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
        purposes.append('cube-save-file-tests')
    if source_persistence:
        purposes.append('source-persistence-profile-tests')
    if sheet_geometry:
        purposes.append('sheet-input-tests')
    if sheet_render:
        purposes.append('sheet-pixel-encoding-tests')
    if planar_geometry:
        purposes.append('planar-input-tests')
    if pose_drive_geometry:
        purposes.append('pose-drive-history-tests')
    if surface_render:
        purposes.append('planar-pixel-encoding-tests')
    if source_warp:
        purposes.extend(('source-warp-fixture-tests','source-warp-provenance-tests'))
        if source_texture:purposes.extend(('source-warp-appearance-binding-tests','source-warp-appearance-tests'))
    if canonical_warp:
        purposes.append('canonical-warp-prepare-tests')
        if warp_inventory in ('mi3d-canonical-warp-sources-v2','mi3d-canonical-warp-sources-v3','mi3d-canonical-layered-sources-v1'):purposes.append('canonical-warp-checker-tests')
        prepared=run / 'canonical-prepare'
        expected_names={'packed.bin','controls-xy-f32.bin','attributed.bin'} | ({'orders-i32.bin'} if layered else set())
        require(set(b.get('canonicalPreparedArtifacts',{}))==expected_names and {f.name for f in prepared.iterdir() if f.is_file()}==expected_names,'canonical prepared inventory')
        require(all(sha(path_checked(prepared / n))==b['canonicalPreparedArtifacts'][n] for n in expected_names),'canonical prepared artifact hash')
        require((prepared / 'controls-xy-f32.bin').stat().st_size==66308160 and (prepared / 'attributed.bin').stat().st_size==8443916,'canonical complete sample/triangle inventory')
        require(all('dev/turboism/validation/modelimport3d/host/'+n+'.class' in classes for n in ('CanonicalWarpProbe','SurfaceWarpImportCandidate','SurfaceQuadChartDiscovery','SurfaceWarpAuthoring')),'canonical assembly entry classes missing')
    require([c['purpose'] for c in commands] == purposes and all(c['exitCode'] == 0 for c in commands), 'build commands incomplete')
    logs = []
    for i, c in enumerate(commands):
        require(c['log'] == 'command-%02d.log' % i, 'unexpected build log path')
        log = path_checked(run / c['log'])
        require(sha(log) == c['logSha256'], 'build log changed')
        logs.append(log.read_text(encoding='utf-8'))
    tools = b['toolchain']['executables']
    if warp_inventory in ('mi3d-canonical-warp-sources-v2','mi3d-canonical-warp-sources-v3','mi3d-canonical-layered-sources-v1'):
        index=purposes.index('canonical-warp-checker-tests')
        require(commands[index]['argv'][1:]==['-B',str(p / ('source/validation/model-import-3d-canonical-warp/'+('test-layered-checker.py' if layered else 'test-checker.py'))),
            '--prepared',str(run / 'canonical-prepare'),'--output',str(run / 'canonical-checker-tests')], 'canonical strict checker command')
        checker_report=path_checked(run / 'canonical-checker-tests/checker-tests.json')
        require(sha(checker_report)==b.get('canonicalCheckerReportSha256'),'canonical checker report identity')
        checker_result=read(checker_report)
        expected_cases=46 if layered else 38 if warp_inventory=='mi3d-canonical-warp-sources-v3' else 35
        require(checker_result['status']=='PASS_SYNTHETIC_CHECKER_CASES_NOT_NATIVE'
            and checker_result['negativeCount']==expected_cases and len(checker_result['negativeCases'])==expected_cases
            and all(c['rejected'] is True for c in checker_result['negativeCases'])
            and checker_result['hostExecuted'] is False and checker_result['goalComplete'] is False
            and logs[index].strip()==f'PASS synthetic schema and {expected_cases} rejection cases; hostExecuted=false','canonical strict checker regressions')

    for i, name in enumerate(('java', 'javac')):
        require(commands[i]['argv'] == [tools[name], '-version'] and logs[i].strip()
                and b['toolchain']['versions'][name] == logs[i], 'toolchain version evidence differs')
    if sheet_geometry:
        index = purposes.index('sheet-input-tests')
        require(commands[index]['argv'] == [tools['java'],'-Djava.awt.headless=true','-cp',
            os.pathsep.join(map(str,(p / 'sdk.jar',run / 'classes',run / 'test-classes'))),
            'dev.turboism.validation.modelimport3d.host.SheetGeometryInputTest',str(p / 'sheets.glb')]
            and logs[index].strip() == '8 input/mode checks PASS; hostExecuted=false', 'sheet input test evidence')
    if sheet_render:
        index = purposes.index('sheet-pixel-encoding-tests')
        require(commands[index]['argv'] == [tools['java'],'-Djava.awt.headless=true','-cp',
            os.pathsep.join(map(str,(p / 'sdk.jar',run / 'classes',run / 'test-classes'))),
            'dev.turboism.validation.modelimport3d.host.SheetPixelEncodingTest']
            and logs[index].strip() == 'PASS 1048576 exact ARGB pixels, dimension rejection and nine pose inventory; nativeRenderExecuted=false',
            'sheet pixel encoding test evidence')
    if planar_geometry:
        index = purposes.index('planar-input-tests')
        require(commands[index]['argv'] == [tools['java'],'-Djava.awt.headless=true','-cp',
            os.pathsep.join(map(str,(p / 'sdk.jar',run / 'classes',run / 'test-classes'))),
            'dev.turboism.validation.modelimport3d.host.PlanarGeometryInputTest',str(p)]
            and logs[index].strip() == '22 planar input/mode checks PASS; hostExecuted=false', 'planar input test evidence')
    if pose_drive_geometry:
        index = purposes.index('pose-drive-history-tests')
        require(commands[index]['argv'] == [tools['java'],'-Djava.awt.headless=true','-cp',
            os.pathsep.join(map(str,(p / 'sdk.jar',run / 'classes',run / 'test-classes'))),
            'dev.turboism.validation.modelimport3d.host.PoseDriveHistoryTest']
            and logs[index].strip() == '25 pose-driving history checks PASS; synthetic native-shaped history, hostExecuted=false',
            'pose drive history test evidence')
    if surface_render:
        index = purposes.index('planar-pixel-encoding-tests')
        require(commands[index]['argv'] == [tools['java'],'-Djava.awt.headless=true','-cp',
            os.pathsep.join(map(str,(p / 'sdk.jar',run / 'classes',run / 'test-classes'))),
            'dev.turboism.validation.modelimport3d.host.PlanarPixelEncodingTest']
            and logs[index].strip() == 'PASS 1048576 exact ARGB pixels, dimension rejection and nine pose inventory and source profiles; nativeRenderExecuted=false',
            'planar pixel encoding test evidence')
    packaging_index = purposes.index('packaging-contract')
    if source_persistence:
        index = purposes.index('source-persistence-profile-tests')
        require(commands[index]['argv'] == [tools['java'],'-Xmx768m','-Djava.awt.headless=true','-cp',
                os.pathsep.join(map(str,(p / 'sdk.jar',run / 'classes',run / 'test-classes'))),
                'dev.turboism.validation.modelimport3d.host.CubePersistenceInputTest',str(p / 'cube.glb'),str(p / 'cube-textured.glb')]
                and logs[index].strip() == 'CubePersistenceInputTest: 113 checks PASS; hostExecuted=false',
                'source persistence profile test evidence')
    require(commands[packaging_index]['argv'] == [tools['java'], '-Djava.awt.headless=true', '-cp',
            os.pathsep.join(map(str, (agent, packaging))),
            'dev.turboism.validation.modelimport3d.packaging.PluginJarCheck', str(probe)], 'wrong runtime packaging contract executed')
    require(logs[packaging_index].strip() == 'PACKAGING_CONTRACT PASS: actual JAR + 3 rejection controls; hostExecuted=false',
            'missing runtime packaging contract result')
    if stage in ('mi3d-cube-save-file-v1', 'mi3d-cube-save-file-v2', 'mi3d-cube-persistence-v1', 'mi3d-cube-persistence-render-v1', 'mi3d-cube-persistence-pair-v1', 'mi3d-cube-persistence-pair-v2', 'mi3d-cube-atlas-plan-v1', 'mi3d-cube-atlas-v1', 'mi3d-source-domains-v1', 'mi3d-official-export-v1'):
        index = purposes.index('cube-save-file-tests')
        require(commands[index]['argv'] == [tools['java'], '-Djava.awt.headless=true', '-cp',
                os.pathsep.join(map(str, (p / 'sdk.jar', run / 'classes', run / 'test-classes'))),
                'dev.turboism.validation.modelimport3d.host.CubePersistenceFileTest', str(run / 'cube-save-file-tests')],
                'wrong cube SAVE_AS file test executed')
        require(logs[index].strip() == f'CubePersistenceFileTest: {61 if stage in ("mi3d-cube-persistence-pair-v2","mi3d-cube-atlas-plan-v1","mi3d-cube-atlas-v1","mi3d-source-domains-v1","mi3d-official-export-v1") else 54 if stage in ("mi3d-cube-save-file-v2", "mi3d-cube-persistence-v1", "mi3d-cube-persistence-render-v1", "mi3d-cube-persistence-pair-v1") else 37} checks PASS; native command substituted; hostExecuted=false',
                'missing cube SAVE_AS file test result')
    return b


def snapshot(s, grid=False):
    require(s['positions'] == POSITIONS and s['uvs'] == UVS and s['indices'] == INDICES, 'fixed geometry differs')
    require(isinstance(s['artMeshId'], str) and s['artMeshId'] and isinstance(s['sourceGuid'], str)
            and s['sourceGuid'], 'mesh identity missing')
    forms = s['forms']; guids = {f['guid'] for f in forms}
    require(forms and len(guids) == len(forms) and '' not in guids, 'stored form identity')
    require(all(f['positions'] == POSITIONS and type(f['drawOrder']) is int for f in forms), 'stored form content')
    require(s['morphKeys'] == [], 'N2 unexpectedly contains morph keys')
    for cell in s['cells']:
        require(cell['formGuid'] in guids, 'dangling cell form')
    if not grid:
        require(s['axes'] == [], 'new mesh already bound'); return
    axes = s['axes']; by_id = {a['id']: a['guid'] for a in axes}
    require(len(axes) == 2 and set(by_id) == {'ParamMI3DN2X', 'ParamMI3DN2Y'}
            and len(set(by_id.values())) == 2, 'axis identities')
    for a in axes:
        require(a['minimum'] == -1 and a['default'] == 0 and a['maximum'] == 1
                and sorted(a['keys']) == [-1, 0, 1], 'axis range/keys/default')
    observed = []
    for c in s['cells']:
        coordinates = c['coordinates']; t = {v['guid']: v['value'] for v in coordinates}
        require(len(coordinates) == len(t) == 2 and set(t) == set(by_id.values()), 'tuple dimensions')
        observed.append((t[by_id['ParamMI3DN2X']], t[by_id['ParamMI3DN2Y']]))
    require(len(observed) == 9 and set(observed) == set(itertools.product([-1, 0, 1], repeat=2)), 'missing/duplicate tuple')


def verify_observations(r, run_id):
    require(r['schemaVersion'] == 'mi3d-native-n2-v1' and r['scope'] == 'N2_BOOTSTRAP_CREATE_GRID_READ', 'schema/scope')
    require(r['runId'] == run_id and bool(run_id), 'wrong native run')
    require(r['status'] == 'PASS' and r['failure'] is None and r['stageAStatus'] == 'NOT_EVALUATED', 'not a clean N2 report')
    require(r['hostIdentity'] is None and r['requestedVersion'] == '5302', 'probe must not fabricate host identity')
    rows = r['checks']; require(len(rows) == len(CHECKS) and {c['id'] for c in rows} == CHECKS, 'check inventory')
    for c in rows:
        require(c['status'] == 'PASS' and c['actual'] is True and bool(c['expected'])
                and c['evidenceKind'] == 'EXACT_HOST', 'failed/unobserved assertion')
    deferred = r['deferred']
    require(len(deferred) == len(DEFERRED) and {c['id'] for c in deferred} == DEFERRED
            and all(c['status'] == 'NOT_RUN' and c['reason'] for c in deferred), 'deferred claims missing/inflated')
    for key in ('documentId', 'modelId', 'editorModelId'):
        require(all(isinstance(r[k][key], str) and r[k][key].strip() for k in ('seed', 'document'))
                and r['seed'][key] != r['document'][key], 'seed identity reused/missing')
    for phase, key in (('SEED', 'seed'), ('NEW', 'document')):
        wait = r['documentWait' + phase]
        require(wait['phase'] == phase and wait['reason'] == 'READY'
                and type(wait['attempts']) is int and wait['attempts'] > 0, 'document observation not ready')
        require(wait['documentPresent'] is True and wait['modelPresent'] is True and wait['kind'] == 'MODEL',
                'ready document diagnostic contradicts availability')
        require(wait['documentId'] == r[key]['documentId'] and wait['snapshotModelId'] == r[key]['modelId']
                and wait['editorModelId'] == r[key]['editorModelId'], 'document observation identity differs')
    require(r['seed']['kind'] == r['document']['kind'] == 'MODEL', 'not a model document')
    counts = r['initialCounts']
    require(set(counts) == {'meshes', 'deformers', 'parameters', 'parts'}
            and all(type(v) is int and v >= 0 for v in counts.values())
            and counts['meshes'] == counts['deformers'] == 0, 'not a blank model')
    require(r['finallyRestored'] is True and all(r[k] == counts for k in ('undoCounts', 'restoredCounts', 'finallyCounts')), 'restore failed')
    require(r['createUndoCounts'] == counts, 'creation Undo did not restore blank counts')
    require(r['createdCounts'] == {**counts, 'meshes': 1}, 'unexpected creation counts')
    require(r['gridCounts'] == {**counts, 'meshes': 1, 'parameters': counts['parameters'] + 2}, 'unexpected grid counts')
    require(r['historyAfterCreatedRead'] == r['historyCreated']
            and r['historyAfterGridRead'] == r['historyGrid'], 'read changed native history')
    snapshot(r['created']); snapshot(r['grid'], grid=True)
    require(r['created'] == r['createdRedo'] and r['grid'] == r['gridRedo'], 'Redo changed stored identities/content')
    require(r['created']['artMeshId'] == r['grid']['artMeshId'] and r['created']['sourceGuid'] == r['grid']['sourceGuid'], 'different object tested')
    before = r['historyBefore']
    for key in ('historyBefore', 'historyCreated', 'historyGrid', 'historyRestored'):
        h = r[key]
        require(h['availability'] == 'AVAILABLE' and h['documentBindingId'] == before['documentBindingId']
                and h['managerBindingId'] == before['managerBindingId'] and bool(h['documentBindingId'])
                and bool(h['managerBindingId']) and type(h['position']) is int
                and 0 <= h['position'] <= len(h['entries']), 'history binding/state')
    require(r['historyCreated']['position'] == before['position'] + 1, 'creation not a single Undo')
    require(r['historyGrid']['position'] > r['historyCreated']['position'], 'grid writes have no history')
    require(r['historyRestored']['position'] == before['position'], 'history not restored')


ORDINARY_CHECKS = {'targetIdentity', 'valuesAtDefault', 'writeCommitted', 'singleUndo', 'writeIsolated',
                   'noop', 'writeUndo', 'writeRedo', 'callbackRolledBack', 'rollbackState', 'defaultRejected',
                   'incompleteRejected', 'topologyRejected', 'rejectionsUnchanged', 'restored', 'valuesUnchanged'}
ORDINARY_STATES = {'before', 'written', 'noop', 'undone', 'redone', 'rollbackBefore', 'rollbackAfter',
                   'rejectionsBefore', 'rejectionsAfter', 'restored'}

HISTORY_SHARED = {'availability', 'documentBindingId', 'managerBindingId', 'position', 'entries', 'entryIds'}
HISTORY_LOCAL = {'generation', 'revision'}


def history_shared(h):
    """Cross-provider history payload only; local clocks are retained and checked separately."""
    require(set(h) == HISTORY_SHARED | HISTORY_LOCAL, 'history field inventory')
    require(all(type(h[k]) is int and h[k] >= 0 for k in HISTORY_LOCAL), 'invalid provider-local history counter')
    require(h['availability'] == 'AVAILABLE' and type(h['position']) is int
            and isinstance(h['entries'], list) and isinstance(h['entryIds'], list)
            and 0 <= h['position'] <= len(h['entries']) == len(h['entryIds'])
            and all(isinstance(e, str) and e for e in h['entries'])
            and all(isinstance(e, str) and e for e in h['entryIds'])
            and len(set(h['entryIds'])) == len(h['entryIds'])
            and all(isinstance(h[k], str) and h[k] for k in ('documentBindingId', 'managerBindingId')),
            'invalid shared history payload')
    return {k: h[k] for k in HISTORY_SHARED}


def verify_ordinary(r):
    o = r['ordinaryWrite']
    require(o['schemaVersion'] == 'mi3d-native-n3a-v1' and o['status'] == 'PASS' and o['failure'] is None,
            'N3a missing/not clean')
    require(o['evaluationStatus'] == 'NOT_RUN' and o['coordinateSpace'] == 'EDITOR_STORED_FORM',
            'stored evidence misrepresented as evaluation')
    rows = o['checks']
    require(len(rows) == len(ORDINARY_CHECKS) and {c['id'] for c in rows} == ORDINARY_CHECKS, 'N3a check inventory')
    require(all(c['status'] == 'PASS' and c['actual'] is True and c['evidenceKind'] == 'EXACT_HOST'
                and isinstance(c['expected'], str) and c['expected'] for c in rows), 'N3a failed/unobserved check')
    states = o['states']; require(set(states) == ORDINARY_STATES, 'N3a state inventory')
    baseline = r['gridRedo']; snapshot(baseline, grid=True)
    require(states['before']['snapshot'] == baseline, 'N3a baseline differs from N2')
    require(len(baseline['forms']) == 9 and len({c['formGuid'] for c in baseline['cells']}) == 9,
            'N3a requires nine independent forms')
    by_id = {a['id']: a['guid'] for a in baseline['axes']}
    gx, gy = by_id['ParamMI3DN2X'], by_id['ParamMI3DN2Y']
    wanted = [{gx: 1, gy: 0}, {gx: -1, gy: 0}, {gx: 0, gy: 0}]
    targets = o['targets']; require(len(targets) == 3, 'N3a target inventory')
    guids = []
    for target, coordinates in zip(targets, wanted):
        found = [c['formGuid'] for c in baseline['cells']
                 if {v['guid']: v['value'] for v in c['coordinates']} == coordinates]
        require(len(found) == 1 and target['formGuid'] == found[0] and len(target['coordinates']) == 2
                and {v['guid']: v['value'] for v in target['coordinates']} == coordinates, 'wrong N3a target identity')
        guids.append(found[0])
    require(len(set(guids)) == 3, 'default/target alias')
    # Frozen independent numeric constants, not imported from Java and not derived from its claimed expected values.
    a = [[48, 40], [304, 40], [304, 168], [48, 168]]
    b = [[-4, 64], [252, 64], [252, 192], [-4, 192]]

    def changed(first, second):
        value = copy.deepcopy(baseline)
        for form in value['forms']:
            if form['guid'] == guids[0]: form['positions'] = first
            elif form['guid'] == guids[1]: form['positions'] = second
        return value

    expected = changed(a, b)
    for name, state in states.items():
        require(state['snapshot'] == (baseline if name in {'before', 'undone', 'restored'} else expected),
                'N3a fixed whole-snapshot oracle: ' + name)
        require(state['parameters'] == [0, 0] and all(type(v) in (int, float) for v in state['parameters']),
                'N3a parameter value changed')
        h = state['history']; native = r['historyGrid']; history_shared(h)
        require(h['availability'] == 'AVAILABLE' and h['documentBindingId'] == native['documentBindingId']
                and h['managerBindingId'] == native['managerBindingId'] and h['generation'] == native['generation']
                and type(h['position']) is int and 0 <= h['position'] <= len(h['entries'])
                and len(h['entries']) == len(h['entryIds']), 'N3a history binding/shape')
    h0, h1 = states['before']['history'], states['written']['history']
    # Independently sampled after N2 allRedo, not the pre-Undo historyGrid semantic detail.
    anchor = {**r['historyGridRedo'], 'entryIds': r['historyGridRedoEntryIds']}
    prior = {**r['historyGrid'], 'entryIds': r['historyGridEntryIds']}
    history_shared(anchor); history_shared(prior)
    require(h0 == anchor, 'N3a history differs from post-Redo N2 anchor')
    require(all(anchor[k] == prior[k] for k in
                ('availability', 'documentBindingId', 'managerBindingId', 'generation', 'position', 'entryIds'))
            and anchor['revision'] >= prior['revision'], 'N2 Undo/Redo history identity changed')
    require(h1['position'] == h0['position'] + 1 and len(h1['entries']) == h1['position']
            and h1['entryIds'][:h0['position']] == h0['entryIds'][:h0['position']], 'N3a not one history entry')
    require(states['noop'] == states['written'], 'N3a no-op changed history/state')
    require(states['rollbackAfter'] == states['rollbackBefore'] == states['redone'], 'N3a root recovery differs')
    require(states['rejectionsAfter'] == states['rejectionsBefore'] == states['rollbackAfter'], 'N3a rejection wrote state')
    for name in ('undone', 'restored'):
        require(states[name]['history']['position'] == h0['position'], 'N3a undo position')
    require(states['redone']['history']['position'] == h1['position']
            and all(states[n]['history']['entryIds'] == h1['entryIds'] for n in ('undone', 'redone', 'restored')),
            'N3a Undo/Redo changed entry identities')
    require(o['rollbackMutated'] == changed(b, a) and o['callbackFailure'] == 'MI3D_N3A_EXPECTED_ABORT',
            'N3a callback did not reach deliberate failure after both writes')
    receipt_generation = None
    receipt_revision = -1
    for name, outcome, before, after in (
            ('write', 'COMMITTED', 'before', 'written'), ('noop', 'NO_CHANGE', 'written', 'noop'),
            ('rollback', 'ROLLED_BACK', 'rollbackBefore', 'rollbackAfter')):
        tx = o[name]; receipt = tx['receipt']
        require(tx['outcome'] == outcome and isinstance(receipt['transactionId'], str) and receipt['transactionId']
                and receipt['label'] and history_shared(receipt['historyBefore']) == history_shared(states[before]['history'])
                and history_shared(receipt['historyAfter']) == history_shared(states[after]['history']),
                'N3a typed receipt differs: ' + name)
        for side in ('historyBefore', 'historyAfter'):
            local = receipt[side]
            if receipt_generation is None: receipt_generation = local['generation']
            require(local['generation'] == receipt_generation and local['revision'] >= receipt_revision,
                    'transaction-provider history clock changed identity or moved backwards')
            receipt_revision = local['revision']
        if name != 'write':
            require(receipt['historyBefore'] == receipt['historyAfter'], 'noncommit receipt changed local history')
        else:
            require(receipt['historyAfter']['revision'] > receipt['historyBefore']['revision'],
                    'committed receipt lacks local history revision')
        if name == 'write':
            require(isinstance(receipt['historyEntryId'], str) and receipt['historyEntryId']
                    and receipt['historyEntryId'] == h1['entryIds'][h1['position'] - 1], 'N3a committed entry identity')
        else:
            require(receipt['historyEntryId'] is None, 'N3a noncommit added entry')
        require((isinstance(tx['diagnosticId'], str) and bool(tx['diagnosticId'])) if name == 'rollback'
                else tx['diagnosticId'] is None, 'N3a diagnostic semantics')
    require(len({o[n]['receipt']['transactionId'] for n in ('write', 'noop', 'rollback')}) == 3,
            'N3a transaction identity reused')
    for name, exception in (('defaultRejected', 'java.lang.UnsupportedOperationException'),
                            ('incompleteRejected', 'java.lang.IllegalArgumentException'),
                            ('topologyRejected', 'java.lang.IllegalArgumentException')):
        require(o[name] == exception, 'N3a request not rejected as specified')


def verify_native(r, run_id):
    verify_observations(r, run_id)  # Preserve the historical N2 contract, including observable aliases.
    verify_ordinary(r)  # Mandatory for this build: old N2-only reports are never accepted here.
    EVALUATION['verify_evaluation'](r, snapshot)  # Missing N3b is not silently accepted as historical N3a.
    ORDER['verify_order'](r, snapshot, EVALUATION)  # Missing order evidence is NOT_RUN, never inherited PASS.
    MORPH['verify_morph'](r, snapshot, EVALUATION)  # Missing morph evidence fails; Stage A remains NOT_EVALUATED.
    MORPH_ORDER['verify_morph_order'](r, snapshot, EVALUATION)  # Missing N4b morph-order evidence fails.
    IMAGE_BINDING['verify_image_binding'](r, snapshot, EVALUATION)  # Missing N5a evidence fails closed.
    SHAPE_IMPORT['verify_shape_import'](r, {'text': lambda v: isinstance(v, str) and bool(v.strip())
                                            and v == v.strip() and '\u0000' not in v})  # Missing B0 evidence fails closed.


def verify_lifecycle(life, run_id, build):
    require(life['schemaVersion'] == 1 and life['runId'] == run_id
            and life['finalizedBy'] == 'contained-supervisor' and life['terminalState'] == 'succeeded'
            and life['validationStatus'] == 'PASS' and life['cleanup'] == 'safe', 'no final safe host verdict')
    require(all(life[k] is True for k in ('identityVerified', 'fixtureUnchanged', 'normalExit'))
            and type(life['runnerExitCode']) is int and life['runnerExitCode'] == 0, 'failed host/identity/exit')
    proof = life['containment']
    require(proof['schemaVersion'] == 1 and proof['cleanup'] == 'safe'
            and all(proof[k] == life[k] and bool(life[k]) for k in ('jobId', 'attemptId', 'runId', 'preparedDigest')),
            'containment identity mismatch')
    kernel = proof['kernelProof']; final = kernel['finalReading']
    require(kernel['originalCgroupBound'] is True and kernel['errors'] == []
            and kernel['processTimedOut'] is False and type(kernel['processReturncode']) is int,
            'kernel cleanup proof incomplete')
    require(final['kind'] == 'destroyed' or (final['kind'] == 'same' and final['populated'] == 0),
            'kernel scope is not empty/destroyed')
    require(all(kernel[k] == proof[k] and bool(proof[k]) for k in
                ('cgroupPath', 'cgroupDevice', 'cgroupInode', 'bootId')) and proof['scopeUnit'].endswith('.scope'),
            'kernel scope binding differs')
    details = life['details']; checks = details['postContainmentChecks']
    require(details['taskOwnedCleanup'] is True and details['validationComplete'] is True, 'missing task cleanup')
    require(checks['goldenJarSha256'] == checks['clonedJarSha256'] == JAR_SHA, 'wrong official JAR')
    require(checks['goldenBatSha256'] == details['goldenBatBeforeSha256']
            and checks['clonedBatSha256'] == details['clonedBatBeforeSha256']
            and bool(checks['goldenBatSha256']), 'official BAT drift')
    require(checks['fixtureSourceSha256'] == details['sourceFixtureBeforeSha256']
            and checks['fixtureCopySha256'] == details['fixtureBeforeSha256']
            and bool(checks['fixtureSourceSha256']), 'seed changed')
    artifacts = checks['stagedArtifacts']
    require(artifacts and all(a['sourceSha256'] == a['stagedSha256'] for a in artifacts), 'staged artifact drift')
    require({build['agentSha256'], build['probeSha256']}.issubset({a['stagedSha256'] for a in artifacts}), 'wrong build executed')
    require(checks['terminalResult']['passed'] is True, 'missing native terminal result')


def job_result(evidence, run_id, build, expected_mode=None, atlas_reference=None):
    if build.get('inputStageVersion') in ('mi3d-pose-drive-geometry-v1','mi3d-pose-drive-geometry-v2'):
        require(expected_mode in ('plane-geometry','plane-seam-geometry','sheet-geometry'),
                'pose drive stage admits geometry only')
    """Complete authoritative single-job admission, shared by this CLI and the
    T014 pair verifier: lifecycle/containment/build provenance first, then a
    whole-archive inventory (no duplicates, links or traversal, bounded
    aggregate), the mode-gated native report/artifacts and the official
    console. Returns the extracted evidence; raises on any violation."""
    if build.get('inputStageVersion') in ('mi3d-surface-render-v1','mi3d-surface-render-v2'):
        if expected_mode in ('cube-export','cube-atlas-save','cube-atlas-reopen'):
            # The current complete build also contains the source-textured cube
            # export/persistence probes. Their own atlas checks apply, not the unrelated
            # planar history protocol. Legacy V1 builds retain their old scope.
            require(build['inputStageVersion'] == 'mi3d-surface-render-v2'
                    and build.get('persistenceTextureSha256') == 'dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'
                    and bool(build.get('verifiedPersistenceTexturePath')), 'cube export/persistence requires frozen source texture')
        else:
            require(expected_mode in ('plane-geometry','plane-seam-geometry','sheet-geometry','plane-render','plane-seam-render','sheet-render'),
                    'surface render stage admits only frozen surface modes')
    evidence = path_checked(evidence)
    life = read(evidence / 'lifecycle-result.json')
    verify_lifecycle(life, run_id, build)
    bound = read(path_checked(evidence.parent / 'containment.json'))
    require(all(bound[k] == life['containment'][k] for k in
                ('jobId', 'attemptId', 'runId', 'preparedDigest', 'scopeUnit', 'cgroupPath',
                 'cgroupDevice', 'cgroupInode', 'bootId')), 'job-bound scope does not match final proof')
    archive = evidence / 'final-task/turboism-home-logs-state.tar'
    report_limit = (MAX_CUBE_REPORT_BYTES if expected_mode in ('plane-render', 'plane-seam-render', 'plane-geometry', 'plane-seam-geometry', 'sheet-render', 'sheet-geometry', 'cube-geometry', 'cube-render', 'whole-import', 'cube-persistence-save', 'cube-persistence-reopen', 'cube-atlas', 'cube-export', 'cube-atlas-save', 'cube-atlas-reopen') else
                    MAX_JOINT_REPORT_BYTES if expected_mode == 'joint-pose' else MAX_NATIVE_REPORT_BYTES)
    source_warp_captures = {}
    source_warp_pixels = {}
    pixel_frames = {}
    cube_frames = {}
    persistence_frames = {}
    cmo3 = post_png = None
    export_artifacts = {}
    with tarfile.open(archive, 'r:') as tar:
        # Whole-archive admission BEFORE any member is read: exact unique names,
        # regular files or directories only (links rejected), no traversal,
        # bounded aggregate of the persisted state files.
        members = tar.getmembers()
        require(len({m.name for m in members}) == len(members),
                'duplicate archive member names')
        require(all(m.isreg() or m.isdir() for m in members),
                'archive member not a regular file/dir (link rejected)')
        require(all(m.name and not m.name.startswith('/')
                    and '..' not in m.name.split('/') for m in members),
                'archive member path traversal')
        require(sum(m.size for m in members
                    if m.isreg() and m.name.startswith(SECTION_PREFIX))
                <= PIXEL_TOTAL_MAX + report_limit + CMO3_MAX + (34*1024*1024 if expected_mode == 'cube-export' else 0) + (54 * PIXEL_MEMBER_MAX if expected_mode == 'cube-atlas-save' else 9 * PIXEL_MEMBER_MAX if expected_mode in ('plane-render','plane-seam-render','sheet-render','cube-atlas-reopen') else 36 * PIXEL_MEMBER_MAX if expected_mode in ('cube-atlas','cube-export') else 11 * PIXEL_MEMBER_MAX if expected_mode in ('source-warp-textured','canonical-warp-textured','canonical-warp-layered-textured') else 27 * PIXEL_MEMBER_MAX if expected_mode in ('cube-render','cube-persistence-save','cube-persistence-reopen') else 0),
                'persisted state aggregate out of bound')
        name = SECTION_PREFIX + 'report.json'
        found = [m for m in members if m.name == name]
        require(len(found) == 1 and found[0].isfile(), 'native report archive identity/type')
        member = found[0]
        require(0 < member.size <= report_limit, 'native report archive size budget')
        with tar.extractfile(member) as stream:
            payload = stream.read(report_limit + 1)
        require(len(payload) == member.size, 'native report archive length mismatch')
        report = loads(payload)
        mode = report.get('mi3dMode')
        if expected_mode is not None:
            require(mode == expected_mode, 'executed mode differs from requested acceptance mode')
        sheet_frames = {}
        sheet_prefix = SECTION_PREFIX + 'sheet-render/'
        sheet_members = [m for m in members if m.name.startswith(sheet_prefix) and not m.isdir()]
        expected_sheet = {sheet_prefix + str(i) + '.png' for i in range(9)} if mode == 'sheet-render' else set()
        require({m.name for m in sheet_members} == expected_sheet, 'sheet pixel archive inventory')
        for member in sheet_members:
            require(member.isreg() and 0 < member.size <= PIXEL_MEMBER_MAX, 'sheet PNG file budget')
            with tar.extractfile(member) as stream:
                raw = stream.read(member.size + 1)
            require(len(raw) == member.size, 'sheet PNG archive length')
            sheet_frames[member.name[len(SECTION_PREFIX):]] = raw
        planar_frames = {}
        planar_prefix = SECTION_PREFIX + 'planar-render/'
        planar_members = [m for m in members if m.name.startswith(planar_prefix) and not m.isdir()]
        expected_planar = {planar_prefix + str(i) + '.png' for i in range(9)} if mode in ('plane-render','plane-seam-render') else set()
        require({m.name for m in planar_members} == expected_planar, 'planar pixel archive inventory')
        for member in planar_members:
            require(member.isreg() and 0 < member.size <= PIXEL_MEMBER_MAX, 'planar PNG file budget')
            with tar.extractfile(member) as stream:
                raw = stream.read(member.size + 1)
            require(len(raw) == member.size, 'planar PNG archive length')
            planar_frames[member.name[len(SECTION_PREFIX):]] = raw
        for family, modes in (('source-warp', ('source-warp-candidate','source-warp-textured')),
                              ('canonical-warp', ('canonical-warp-textured','canonical-warp-layered-textured'))):
            capture_prefix=SECTION_PREFIX+family+'-capture/'
            source_members=[m for m in members if m.name.startswith(capture_prefix) and not m.isdir()]
            expected_source=({capture_prefix+f'sample-{i}.xy' for i in range(1078)} | {capture_prefix+f'layer-local-{i}.xy' for i in range(98)}) if mode=='canonical-warp-layered-textured' and family=='canonical-warp' else {capture_prefix+f'sample-{i}.xy' for i in range(22)} if mode in modes else set()
            require({m.name for m in source_members}==expected_source,family+' capture inventory')
            require(sum(m.size for m in source_members)<=101090*8*(12 if mode=='canonical-warp-layered-textured' else 11),family+' capture aggregate')
            for m in source_members:
                require(m.isreg() and 0<m.size<=65535*8,family+' capture member budget')
                with tar.extractfile(m) as stream:raw=stream.read(m.size+1)
                require(len(raw)==m.size,family+' capture exact length')
                source_warp_captures[m.name[len(SECTION_PREFIX):]]=raw
            pixel_prefix=SECTION_PREFIX+family+'-pixels/'
            image_members=[m for m in members if m.name.startswith(pixel_prefix) and not m.isdir()]
            require({m.name for m in image_members}==({pixel_prefix+f'{i}.png' for i in range(11)}
                if mode in modes and mode.endswith('-textured') else set()),family+' PNG inventory')
            require(sum(m.size for m in image_members)<=11*PIXEL_MEMBER_MAX,family+' PNG aggregate')
            for m in image_members:
                require(m.isreg() and 0<m.size<=PIXEL_MEMBER_MAX,family+' PNG budget')
                with tar.extractfile(m) as stream:raw=stream.read(m.size+1)
                require(len(raw)==m.size,family+' PNG exact length')
                source_warp_pixels[m.name[len(SECTION_PREFIX):]]=raw
        # Exact regular-file pixel artifacts: no aliases, duplicates, extras,
        # missing members, links, or unbounded reads. Reopen-mode reports
        # carry exactly the three re-sampled bound poses.
        prefix = SECTION_PREFIX + 'pixel-sampling/'
        poses = (() if mode in ('canonical-warp-layered-textured', 'canonical-warp-textured', 'source-warp-candidate', 'source-warp-textured', 'warp-authoring', 'plane-render','plane-seam-render','plane-geometry','plane-seam-geometry','sheet-render','sheet-geometry','cube-persistence-save','cube-persistence-reopen','cube-atlas', 'cube-export', 'cube-atlas-save', 'cube-atlas-reopen') else ('bound-p0', 'bound-p1', 'bound-m1') if mode == 'persistence-reopen'
                 else ('baseline', 'bound-p0', 'bound-p1', 'bound-m1',
                       'unbound', 'rebound', 'blank'))
        expected = {prefix + pose + '.png' for pose in poses}
        pixel_members = [m for m in members if m.name.startswith(prefix)]
        require({m.name for m in pixel_members} == expected,
                'pixel artifact inventory missing/extra/aliased')
        require(all(m.isreg() for m in pixel_members),
                'pixel artifact is not a regular file (link/other type rejected)')
        require(all(0 < m.size <= PIXEL_MEMBER_MAX for m in pixel_members),
                'pixel artifact member size out of bound')
        require(sum(m.size for m in pixel_members) <= PIXEL_TOTAL_MAX,
                'pixel artifact aggregate size out of bound')
        for m in pixel_members:
            pose = m.name[len(prefix):-4]
            with tar.extractfile(m) as stream:
                data = stream.read(m.size + 1)
            require(len(data) == m.size, 'pixel artifact length mismatch: ' + pose)
            pixel_frames[pose] = data
        cube_prefix = SECTION_PREFIX + 'cube-render/'
        cube_members = [m for m in members if m.name.startswith(cube_prefix)]
        expected_cube = ({cube_prefix + phase + '-' + str(index) + '.png'
                          for phase in CUBE_RENDER['PHASES'] for index in range(9)}
                         if mode == 'cube-render' else set())
        require({m.name for m in cube_members} == expected_cube, 'cube PNG inventory missing/extra')
        for member in cube_members:
            require(member.isreg() and 0 < member.size <= PIXEL_MEMBER_MAX, 'cube PNG member budget/type')
            with tar.extractfile(member) as stream:
                raw = stream.read(member.size + 1)
            require(len(raw) == member.size, 'cube PNG member size')
            cube_frames[member.name[len(SECTION_PREFIX):]] = raw
        persistence_prefix = SECTION_PREFIX + 'cube-persistence-render/'
        persistence_members = [m for m in members if m.name.startswith(persistence_prefix)]
        phases = ('atlas-baseline','atlas-packed','atlas-repacked','atlas-redone','atlas-before-save','atlas-after-save') if mode == 'cube-atlas-save' else ('atlas-reopen',) if mode == 'cube-atlas-reopen' else ('before-save','after-save') if mode == 'cube-persistence-save' else ('reopen',) if mode == 'cube-persistence-reopen' else ('atlas-baseline','atlas-packed','atlas-repacked','atlas-redone') if mode in ('cube-atlas','cube-export') else ()
        expected_persistence = {persistence_prefix + phase + '-' + str(i) + '.png' for phase in phases for i in range(9)}
        require({m.name for m in persistence_members} == expected_persistence, 'cube persistence PNG inventory')
        for member in persistence_members:
            require(member.isreg() and 0 < member.size <= PIXEL_MEMBER_MAX, 'cube persistence PNG type/budget')
            with tar.extractfile(member) as stream:
                raw = stream.read(member.size + 1)
            require(len(raw) == member.size, 'cube persistence PNG length')
            persistence_frames[member.name[len(SECTION_PREFIX):]] = raw
        exports = [m for m in members if m.name.startswith(SECTION_PREFIX + 'official-export/')]
        expected_exports = {SECTION_PREFIX + 'official-export/model.moc3': 32*1024*1024,
                            SECTION_PREFIX + 'official-export/texture-0.png': 2*1024*1024} if mode == 'cube-export' else {}
        require({m.name for m in exports} == set(expected_exports), 'official export archive inventory')
        for member in exports:
            require(member.isreg() and 0 < member.size <= expected_exports[member.name], 'official export artifact type/budget')
            with tar.extractfile(member) as stream:
                raw = stream.read(member.size + 1)
            require(len(raw) == member.size, 'official export artifact length')
            export_artifacts[member.name[len(SECTION_PREFIX):]] = raw
        # T014: the saved CMO3 plus the independently named bounded
        # post-save PNG exist iff this was the save job; nothing else may
        # land under persistence/.
        persistence = [m for m in members
                       if m.name.startswith(SECTION_PREFIX + 'persistence/')]
        if mode in ('cube-persistence-save','cube-atlas-save'):
            require({m.name for m in persistence} == {SECTION_PREFIX + 'persistence/mi3d-cube-model.cmo3'},
                    'cube persistence artifact inventory')
            member=persistence[0]
            require(member.isreg() and 0 < member.size <= CMO3_MAX, 'cube CMO3 type/budget')
            with tar.extractfile(member) as stream:
                cmo3=stream.read(member.size+1)
            require(len(cmo3) == member.size, 'cube CMO3 length')
        elif mode == 'persistence-save':
            require({m.name for m in persistence}
                    == {PERSISTENCE_MEMBER, POST_SAVE_MEMBER},
                    'persistence artifact inventory missing/extra')
            member = [m for m in persistence if m.name == PERSISTENCE_MEMBER][0]
            require(member.isreg() and 0 < member.size <= PIXEL_TOTAL_MAX,
                    'saved CMO3 not a regular file within budget')
            with tar.extractfile(member) as stream:
                cmo3 = stream.read(member.size + 1)
            require(len(cmo3) == member.size, 'saved CMO3 length mismatch')
            post = [m for m in persistence if m.name == POST_SAVE_MEMBER][0]
            require(post.isreg() and 0 < post.size <= PIXEL_MEMBER_MAX,
                    'post-save PNG not a regular file within budget')
            with tar.extractfile(post) as stream:
                post_png = stream.read(post.size + 1)
            require(len(post_png) == post.size, 'post-save PNG length mismatch')
        else:
            require(not persistence, 'unexpected persistence artifact in this mode')
    # Job B reopen reports carry only the reopen leg — the N2 fixture legs
    # belong to Job A's process and must not be duplicated. Normal/save
    # reports keep the full contract plus the mode-gated save evidence.
    if mode not in ('cube-export','cube-atlas-save','cube-atlas-reopen') and build.get('inputStageVersion') in ('mi3d-pose-drive-geometry-v1','mi3d-pose-drive-geometry-v2','mi3d-surface-render-v1','mi3d-surface-render-v2'):
        section = 'sheetGeometry' if mode in ('sheet-geometry','sheet-render') else 'planarGeometry'
        expected_protocol = 'POSE_DRIVER_UNDO_RETURN_V2' if build.get('inputStageVersion') in ('mi3d-surface-render-v2','mi3d-pose-drive-geometry-v2') else 'POSE_DRIVER_UNDO_V1'
        require(report[section]['capture'].get('historyProtocol') == expected_protocol,
                'pose drive stage requires bounded history protocol')
    if mode in ('plane-geometry','plane-seam-geometry','plane-render','plane-seam-render'):
        require(expected_mode == mode and build.get('inputStageVersion') in ('mi3d-planar-geometry-v1','mi3d-pose-drive-geometry-v1','mi3d-pose-drive-geometry-v2','mi3d-surface-render-v1','mi3d-surface-render-v2')
                and 'verifiedPlanarInputPaths' in build, 'planar requires explicit stage/mode/frozen GLB')
        name = 'plane.glb' if mode in ('plane-geometry','plane-render') else 'plane-seam.glb'
        checker = runpy.run_path(str(Path(__file__).with_name('verify-surface-geometry.py')))
        checker['verify_planar_report'](report,run_id,Path(build['verifiedPlanarInputPaths'][name]).read_bytes(),expected_mode=mode)
        if mode in ('plane-render','plane-seam-render'):
            require(build.get('inputStageVersion') in ('mi3d-surface-render-v1','mi3d-surface-render-v2'),'planar render requires pixel-capable build')
            pixels = runpy.run_path(str(Path(__file__).with_name('verify-planar-pixels.py')))
            pixels['verify_frames'](report['planarGeometry']['pixels'],report['planarGeometry']['capture'],
                Path(build['verifiedPlanarInputPaths'][name]).read_bytes(),planar_frames.__getitem__)
    elif mode in ('sheet-geometry','sheet-render'):
        require(expected_mode == mode and 'verifiedSheetInputPath' in build, 'sheet requires explicit mode/frozen GLB')
        checker = runpy.run_path(str(Path(__file__).with_name('verify-surface-geometry.py')))
        checker['verify_sheet_report'](report,run_id,Path(build['verifiedSheetInputPath']).read_bytes(),expected_mode=mode)
        if mode == 'sheet-render':
            require(build.get('inputStageVersion') in ('mi3d-sheet-render-v1','mi3d-surface-render-v1','mi3d-surface-render-v2'), 'sheet render requires explicit pixel-capable build')
            pixels = runpy.run_path(str(Path(__file__).with_name('verify-sheet-pixels.py')))
            pixels['verify_frames'](report['sheetGeometry']['pixels'],report['sheetGeometry']['capture'],
                Path(build['verifiedSheetInputPath']).read_bytes(),sheet_frames.__getitem__)
    elif mode in ('cube-atlas-save','cube-atlas-reopen'):
        require(expected_mode == mode and 'verifiedCubeInputPath' in build, 'atlas persistence requires frozen GLB/mode')
        texture = build.get('verifiedPersistenceTexturePath')
        ATLAS_PERSISTENCE['report_leg'](report,run_id,mode,build['verifiedCubeInputPath'],persistence_frames,cmo3,atlas_reference,
            texture=texture,padded=texture is not None)
    elif mode in ('cube-atlas','cube-export'):
        require(expected_mode == mode and 'verifiedCubeInputPath' in build, 'atlas requires frozen GLB/mode')
        texture = build.get('verifiedPersistenceTexturePath')
        WHOLE_IMPORT['verify_cube_atlas_report'](report,run_id,build['verifiedCubeInputPath'],persistence_frames,expected_mode=mode,
            texture=texture,padded=texture is not None)
        if mode == 'cube-export':
            WHOLE_IMPORT['verify_cube_export'](report,export_artifacts,cube=build['verifiedCubeInputPath'],texture=texture)
    elif mode in ('cube-persistence-save','cube-persistence-reopen'):
        require(expected_mode == mode and 'verifiedCubeInputPath' in build, 'cube persistence requires explicit mode/frozen GLB')
        texture = build.get('verifiedPersistenceTexturePath')
        WHOLE_IMPORT['verify_cube_persistence_report'](report,run_id,mode,build['verifiedCubeInputPath'],persistence_frames,cmo3,
            texture=texture,padded=texture is not None)
    elif mode in ('canonical-warp-textured','canonical-warp-layered-textured'):
        require(expected_mode==mode and build.get('sourceInventoryVersion')==('mi3d-canonical-layered-sources-v1' if mode=='canonical-warp-layered-textured' else 'mi3d-canonical-warp-sources-v3')
            and 'verifiedSourceWarpTexturePath' in build,'canonical native requires strict v3 inventory and frozen GLB')
        import runpy
        prepared=Path(build['verifiedSourceWarpTexturePath']).parent.parent / 'canonical-prepare'
        runpy.run_path(str(Path(__file__).resolve().parents[1] / ('model-import-3d-canonical-warp/'+('verify-canonical-warp-layered.py' if mode=='canonical-warp-layered-textured' else 'verify-canonical-warp.py'))))['verify'](
            report,run_id,prepared,source_warp_captures,source_warp_pixels)
    elif mode in ('source-warp-candidate','source-warp-textured'):
        require(expected_mode==mode and build.get('sourceInventoryVersion') in ('mi3d-source-warp-candidate-sources-v1','mi3d-source-warp-candidate-sources-v2') and 'verifiedSourceWarpFixturePath' in build,'candidate requires explicit mode and frozen source')
        require(mode!='source-warp-textured' or 'verifiedSourceWarpTexturePath' in build,'textured capture requires frozen GLB')
        import runpy
        runpy.run_path(str(Path(__file__).with_name('verify-source-warp-candidate.py')))['verify'](report,run_id,Path(build['verifiedSourceWarpFixturePath']).read_bytes(),source_warp_captures,source_warp_pixels)
    elif mode == 'warp-authoring':
        require(build.get('sourceInventoryVersion') == 'mi3d-warp-authoring-sources-v1', 'warp inventory version missing')
        require(expected_mode == mode, 'Warp API requires explicit mode')
        runpy.run_path(str(Path(__file__).with_name('verify-warp-authoring.py')))['verify'](report,run_id)
    elif mode == 'persistence-reopen':
        require(report['runId'] == run_id and report['status'] == 'PASS'
                and report['failure'] is None, 'reopen run/result invalid')
        PIXEL_SAMPLING['verify_persistence_reopen'](report, pixel_frames)
    else:
        require(mode in (None, 'normal', 'persistence-save', 'joint-pose', 'cube-geometry', 'cube-render', 'whole-import'),
                'unknown mi3d mode: ' + repr(mode))
        verify_native(report, run_id)
        if mode == 'joint-pose':
            JOINT_POSE['verify_joint_pose'](report)
        else:
            require(report.get('jointPose', {}).get('status', 'NOT_RUN') == 'NOT_RUN', 'unexpected joint pose execution')
        if mode in ('cube-geometry', 'cube-render'):
            require(expected_mode == mode and 'verifiedCubeInputPath' in build, 'cube acceptance requires explicit mode and frozen input')
            CUBE_GEOMETRY['verify_cube_geometry'](report, build['verifiedCubeInputPath'])
        else:
            require(report.get('cubeGeometry', {}).get('status', 'NOT_RUN') == 'NOT_RUN', 'unexpected cube execution')
        if mode == 'cube-render':
            CUBE_RENDER['verify_state'](report)
            CUBE_RENDER['verify_pixels'](report['cubeRender'], build['verifiedCubeInputPath'], cube_frames.__getitem__)
        else:
            require(report.get('cubeRender', {}).get('status', 'NOT_RUN') == 'NOT_RUN', 'unexpected cube render execution')
        if mode == 'whole-import':
            require(expected_mode == mode and 'verifiedCubeInputPath' in build, 'whole import requires frozen input and explicit mode')
            WHOLE_IMPORT['verify_whole_import'](report, build['verifiedCubeInputPath'])
        else:
            require(report.get('wholeImport', {}).get('status', 'NOT_RUN') == 'NOT_RUN', 'unexpected whole import execution')
        PIXEL_SAMPLING['verify_pixel_sampling'](report, pixel_frames)  # Missing T008 evidence fails closed.
        if mode == 'persistence-save':
            PIXEL_SAMPLING['verify_persistence_save'](report, cmo3, post_png,
                                                    dict(pixel_frames))  # Job A native SAVE_AS evidence.
    console = path_checked(evidence / 'final-task/cubism-console.txt')
    require(console.is_file() and console.stat().st_size < 16_000_000, 'official console unavailable/oversized')
    EVALUATION['verify_console'](console.read_text(encoding='utf-8', errors='replace'))
    return {'report': report, 'frames': pixel_frames, 'cmo3': cmo3,
            'post': post_png, 'lifecycle': life, 'mode': mode, 'cubePersistenceFrames': persistence_frames, 'exportArtifacts': export_artifacts}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-run', type=Path, required=True)
    parser.add_argument('--build-only', action='store_true')
    parser.add_argument('--evidence', type=Path); parser.add_argument('--run-id')
    parser.add_argument('--expected-mode', choices=('plane-render', 'plane-seam-render', 'plane-geometry', 'plane-seam-geometry', 'sheet-render', 'sheet-geometry', 'normal', 'warp-authoring', 'source-warp-candidate', 'source-warp-textured', 'canonical-warp-textured', 'canonical-warp-layered-textured', 'joint-pose', 'cube-geometry', 'cube-render', 'whole-import', 'persistence-save', 'persistence-reopen', 'cube-persistence-save', 'cube-persistence-reopen', 'cube-atlas', 'cube-export', 'cube-atlas-save'),
                        help='Packed atlas reopen is admitted with its save reference by verify-persistence-pair.py --atlas')
    args = parser.parse_args()
    try:
        build = verify_build(args.build_run)
        if args.build_only:
            require(args.evidence is None and args.run_id is None and args.expected_mode is None, 'ambiguous mode')
            print('BUILD_ONLY PASS; host acceptance NOT_EVALUATED'); return 0
        require(args.evidence is not None and args.run_id is not None, 'final evidence and exact run ID required')
        result = job_result(args.evidence, args.run_id, build, args.expected_mode)
        mode = result['mode']
        if mode in ('canonical-warp-textured','canonical-warp-layered-textured'):
            print('Canonical GLB source/control/Undo/Redo CAPTURE PASS; production visibility/pixels/persistence/export NOT_EVALUATED')
        elif mode == 'source-warp-textured':
            print('Full source Warp geometry/Undo/Redo and native texture CAPTURE PASS; pixel correctness/visibility/persistence/export NOT_EVALUATED')
        elif mode == 'source-warp-candidate':
            print('Full source Warp candidate GEOMETRY/UNDO/REDO EXACT_HOST PASS; production import/visibility/material/persistence/export NOT_COVERED')
        elif mode == 'warp-authoring':
            print('Tetrahedron Warp stored/calculated coordinates and Undo/Redo EXACT_HOST PASS; apple/pixels/persistence/export NOT_COVERED')
        elif mode in ('plane-render','plane-seam-render'):
            print('Planar geometry/source pixels EXACT_HOST PASS; persistence/export NOT_COVERED')
        elif mode in ('plane-geometry','plane-seam-geometry'):
            print('Planar geometry EXACT_HOST PASS; pixels/persistence/export NOT_COVERED')
        elif mode == 'sheet-render':
            print('Sheet geometry/order/pixels EXACT_HOST PASS; persistence/export/history atomicity NOT_COVERED')
        elif mode == 'sheet-geometry':
            print('Sheet geometry EXACT_HOST PASS; visibility/pixels/history atomicity NOT_COVERED')
        elif mode in ('cube-atlas-save','cube-atlas-reopen'):
            print('Atlas persistence single leg EXACT_HOST PASS; pair verification required')
        elif mode == 'cube-export':
            print('Official cube MOC export EXACT_HOST CAPTURE PASS; independent no-plugin Core verification NOT_COVERED')
        elif mode == 'cube-atlas':
            print('Cube atlas EXACT_HOST PASS; export/UI NOT_COVERED')
        elif mode in ('cube-persistence-save','cube-persistence-reopen'):
            print('Cube persistence single leg EXACT_HOST PASS; pair verification still required; atlas/export/UI NOT_COVERED')
        elif mode == 'persistence-reopen':
            print('T014 persistence REOPEN in a fresh process: 3 poses re-sampled '
                  'EXACT_HOST PASS; pair check still requires verify-persistence-pair.py; '
                  'atlas/cube/export NOT_COVERED; stage A NOT_EVALUATED')
        elif mode == 'persistence-save':
            print('N2 + N3a + N3b + N3c ordinary GEOMETRY/ORDER + N4a morph STORED POSITIONS + '
                  'N4b morph STORED/CALCULATED DRAW ORDER + N5a image BINDING/PROJECTION + '
                  'T008 native PIXEL SAMPLING + T014 persistence SAVE_AS EXACT_HOST PASS; '
                  'reopen leg NOT_COVERED here; atlas/cube/export NOT_COVERED; stage A NOT_EVALUATED')
        elif mode == 'whole-import':
            print('Whole cube import: six failure stages and one native Undo/Redo EXACT_HOST PASS; '
                  'persistence/atlas/export/UI NOT_COVERED')
        elif mode == 'cube-render':
            print('Authorized cube geometry/material/visibility EXACT_HOST PASS; '
                  'cube persistence/atlas/export/import UI NOT_COVERED')
        elif mode == 'cube-geometry':
            print('Actual authorized cube six-face dense geometry EXACT_HOST PASS; '
                  'occlusion/material/persistence/export NOT_COVERED; stage A NOT_EVALUATED')
        elif mode == 'joint-pose':
            print('Existing native gates + two-surface dense JOINT POSE geometry EXACT_HOST PASS; '
                  'occlusion/atlas/real-model/export NOT_COVERED; stage A NOT_EVALUATED')
        else:
            print('N2 + N3a + N3b + N3c ordinary GEOMETRY/ORDER + N4a morph STORED POSITIONS + '
                  'N4b morph STORED/CALCULATED DRAW ORDER + N5a image BINDING/PROJECTION + '
                  'T008 native PIXEL SAMPLING EXACT_HOST PASS; '
                  'persistence/atomicity/atlas NOT_COVERED; stage A NOT_EVALUATED')
        return 0
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, tarfile.TarError) as error:
        print('FAIL: ' + str(error), file=sys.stderr); return 1


if __name__ == '__main__':
    sys.exit(main())
