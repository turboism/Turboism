#!/usr/bin/env python3
"""Package the Core-verified apple MOC/texture without claiming Viewer execution."""
import argparse
import json
from pathlib import Path
import shutil
import runpy

CHECK = runpy.run_path(str(Path(__file__).with_name('verify-apple-job.py')))


def package(core_acceptance, export_acceptance, out):
    require, read, sha = CHECK['require'], CHECK['read'], CHECK['sha']
    core = read(core_acceptance); export = read(export_acceptance)
    require(core['status'] == 'PASS' and core['scope'] == 'EXACT_APPLE_STANDALONE_OFFICIAL_CORE_GEOMETRY'
            and core['officialCoreAccepted'] is True and core['normalExit'] is True
            and core['cgroupKillWritten'] is False and core['exportAcceptanceSha256'] == sha(export_acceptance),
            'bound native Core acceptance')
    require(export['status'] == 'PASS_EXACT_FROZEN_APPLE_OFFICIAL_EXPORT_CAPTURE_ONLY'
            and export['normalExit'] is True and export['cgroupKillWritten'] is False,
            'bound native export acceptance')
    require(set(export['exportArtifacts']) == {'model.moc3', 'texture-0.png'}, 'exact export resources')
    out = out.absolute()
    require(not any(p.is_symlink() for p in (out, *out.parents)), 'unlinked output')
    sources = {}
    for name, row in export['exportArtifacts'].items():
        source = Path(row['path'])
        require(not any(p.is_symlink() for p in (source, *source.parents)) and source.is_file()
                and 0 < source.stat().st_size == row['bytes'] <= 128*1024*1024
                and sha(source) == row['sha256'], 'exact bounded source export')
        sources[name] = source
    out.mkdir(parents=True, exist_ok=False)
    for name, source in sources.items():
        shutil.copyfile(source, out/name)
        require(sha(out/name) == sha(source) == export['exportArtifacts'][name]['sha256'], 'stable copy')
    model = dict(Version=3, FileReferences=dict(Moc='model.moc3', Textures=['texture-0.png']))
    (out/'apple.model3.json').write_text(json.dumps(model, indent=2)+'\n')
    files = {name: dict(bytes=(out/name).stat().st_size, sha256=sha(out/name))
             for name in ('model.moc3', 'texture-0.png', 'apple.model3.json')}
    receipt = dict(status='PACKAGED_CORE_VERIFIED_VIEWER_NOT_EXECUTED',
                   coreAcceptanceSha256=sha(core_acceptance), exportAcceptanceSha256=sha(export_acceptance),
                   coreJobId=core['jobId'], exportJobId=export['jobId'], files=files,
                   officialCoreAccepted=True, officialRendererAccepted=False,
                   parameters=['Param3DYaw394e4cf740f3', 'Param3DPitch394e4cf740f3'], range=[-30, 30],
                   maximumCanvasErrorPixels=core['geometry']['maximumCanvasErrorPixels'])
    (out/'provenance.json').write_text(json.dumps(receipt, indent=2)+'\n')
    (out/'README.md').write_text(
        '# 苹果运行时模型\n\n'
        '在 Cubism Viewer 中打开 `apple.model3.json`，用 `Param3DYaw394e4cf740f3` / '
        '`Param3DPitch394e4cf740f3` 调整角度（各 −30° 至 30°）。运行时无需 Turboism 插件。\n\n'
        '保留全部 150,784 原面、原 UV 与源模型制作的红皮/棕梗贴图。'
        '几何使用 17×17 联合关键形，层序使用 9×9 联合关键形。\n\n'
        '已通过官方导出及独立官方 Core 的 11 姿态验证，最大源投影误差约 0.404 像素。'
        '官方 Viewer 的实际显示及连续角度域尚未验收。MOC 约 127.5 MiB，加载可能较慢。\n', encoding='utf-8')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ('core-acceptance', 'export-acceptance', 'out'):
        parser.add_argument('--'+name, required=True, type=Path)
    a = parser.parse_args()
    print(json.dumps(package(a.core_acceptance, a.export_acceptance, a.out), indent=2))
