#!/usr/bin/env python3
"""Package an admitted official MOC export for Cubism Viewer, without executing it."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy

HERE = Path(__file__).resolve().parent
ADMITTED = runpy.run_path(str(HERE / 'export-admission.py'))['admitted']


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def package(acceptance, destination):
    acceptance, destination = Path(acceptance).absolute(), Path(destination).absolute()
    if any(p.is_symlink() for path in (acceptance, destination) for p in (path, *path.parents)):
        raise ValueError('linked package path')
    raw = acceptance.read_bytes()
    receipt = json.loads(raw)
    if not (ADMITTED(receipt) and receipt['scope'] == 'EXACT_HOST_OFFICIAL_MOC_EXPORT_CAPTURE_ONLY'
            and receipt['normalExit'] is True and receipt['cgroupKillWritten'] is False):
        raise ValueError('official export not admitted')
    exports = receipt['exportArtifacts']
    if set(exports) != {'model.moc3', 'texture-0.png'}:
        raise ValueError('unexpected export resources')
    contents = {}
    for name, entry in exports.items():
        source = Path(entry['path']).absolute()
        if any(p.is_symlink() for p in (source, *source.parents)):
            raise ValueError('linked export resource')
        if not source.is_file() or not 0 < source.stat().st_size <= 32 * 1024 * 1024:
            raise ValueError('export resource budget')
        data = source.read_bytes()
        if len(data) != entry['bytes'] or sha(data) != entry['sha256']:
            raise ValueError('export resource changed: ' + name)
        contents[name] = data
    if not contents['model.moc3'].startswith(b'MOC3') or not contents['texture-0.png'].startswith(b'\x89PNG\r\n\x1a\n'):
        raise ValueError('invalid export resource type')
    model = {'Version': 3, 'FileReferences': {'Moc': 'model.moc3', 'Textures': ['texture-0.png']}}
    contents['cube.model3.json'] = (json.dumps(model, indent=2) + '\n').encode()
    destination.mkdir(parents=True, exist_ok=False)
    for name, data in contents.items():
        (destination / name).write_bytes(data)
    evidence = dict(status='PACKAGED_NOT_VIEWER_VERIFIED', exportStatus=receipt['status'],
                    exportJobId=receipt['jobId'], exportRunId=receipt['runId'],
                    acceptanceSha256=sha(raw), officialRendererExecuted=False,
                    files={name: dict(bytes=len(data), sha256=sha(data)) for name, data in contents.items()})
    (destination / 'provenance.json').write_text(json.dumps(evidence, indent=2) + '\n')
    (destination / 'README.txt').write_text(
        '在官方 Cubism Viewer 中打开 cube.model3.json。\n'
        '用 ParamMI3DCubeYaw / ParamMI3DCubePitch 调整水平/垂直角度，范围 -30 到 30。\n'
        '模型使用原生密集参数关键形及 GLB 源贴图，不需要 Turboism 插件。\n'
        '此目录仅完成官方导出打包，Viewer 实际显示验证状态见 provenance.json。\n', encoding='utf-8')
    return evidence


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export-acceptance', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(package(args.export_acceptance, args.out), indent=2))
