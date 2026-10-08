#!/usr/bin/env python3
"""Package the actual full-XYZ MOC for Viewer. Does not execute or admit rendering."""
import argparse
import json
from pathlib import Path
import runpy
import shutil

HERE=Path(__file__).resolve().parent
JOB=runpy.run_path(str(HERE/'verify-apple-job.py'))
require,read,sha=(JOB[k] for k in ('require','read','sha'))


def package(controller,bundle,export_acceptance,recorder,core_review,out):
    review=read(core_review);derived=review['status']=='PASS_NATIVE_DERIVED_APPLE_FINITE_XYZ_GEOMETRY'
    identity,capture,export=JOB['process'](controller,bundle,export_acceptance,recorder,'apple-derived-xyz-140-v1' if derived else 'apple-xyz-140-v1')
    review=read(core_review)
    require(review['status']==('PASS_NATIVE_DERIVED_APPLE_FINITE_XYZ_GEOMETRY' if derived else 'PASS_NATIVE_APPLE_FINITE_XYZ_GEOMETRY') and review['officialCoreAccepted'] is True
            and review['normalExit'] is True and review['goalComplete'] is False
            and all(review[k]==v for k,v in identity.items()), 'exact succeeded finite XYZ Core review')
    evidence=controller/'host-job/evidence/standalone-core/capture'
    require(review['captureSha256']==sha(evidence/'core-capture.json')
            and review['coordinatesSha256']==sha(evidence/'positions-f32.bin.gz'), 'review binds exact native coordinates')
    geometry=review['geometry']
    require(geometry['meshes']==98 and geometry['triangles']==150784 and geometry['poses']==140
            and geometry['checkedVertexSamples']==(11271960 if derived else 14036120) and geometry['topologyExact'] is True
            and geometry['packedUVVerified'] is True and geometry['layerIntegerEnclosureVerified'] is True
            and geometry['toleranceCanvasPixels']==5 and 0<=geometry['maximumCanvasErrorPixels']<=5, 'full original XYZ gate')
    if not derived:require(set(export['exportArtifacts'])=={'model.moc3','texture-0.png'}, 'complete official resources')
    textures=['texture-0.png','texture-1.png'] if derived else ['texture-0.png']
    state=export_acceptance.parent/('host-job/state' if derived else 'native-result/state')
    report=read(state/'report.json');section=report['officialExport'];source={}
    for row in [section['moc'],*section['textures']]:
        name=Path(row['artifact']).name;p=state/row['artifact']
        require(p.is_file() and not any(q.is_symlink() for q in (p,*p.parents))
                and 0<p.stat().st_size==row['bytes']<=256*1024*1024 and sha(p)==row['sha256'], 'bounded unchanged export')
        source[name]=p
    require(set(source)=={'model.moc3',*textures} and sha(source['model.moc3'])==capture['mocSha256'], 'same Core MOC')
    out=out.absolute();require(not out.exists() and not any(p.is_symlink() for p in (out,*out.parents)), 'new unlinked package directory')
    out.mkdir(parents=True)
    for name,p in source.items():
        shutil.copyfile(p,out/name);require(sha(out/name)==sha(p), 'exact resource copy')
    descriptor=dict(Version=3,FileReferences=dict(Moc='model.moc3',Textures=textures))
    (out/'apple.model3.json').write_text(json.dumps(descriptor,indent=2)+'\n')
    axes=capture['axes'];receipt=dict(status='PACKAGED_FINITE_XYZ_CORE_VERIFIED_VIEWER_NOT_EXECUTED',
        coreAcceptanceSha256=sha(core_review),exportAcceptanceSha256=sha(export_acceptance),**identity,
        exportJobId=export['jobId'],parameters=axes,range=[-180,180],meshes=98,triangles=150784,
        maximumCanvasErrorPixels=geometry['maximumCanvasErrorPixels'],toleranceCanvasPixels=5,
        officialCoreAccepted=True,officialViewerAccepted=False,pixelCorrectnessAccepted=False,goalComplete=False,
        files={name:dict(bytes=(out/name).stat().st_size,sha256=sha(out/name)) for name in [*source,'apple.model3.json']})
    (out/'provenance.json').write_text(json.dumps(receipt,indent=2)+'\n')
    (out/'README.md').write_text('# 苹果 XYZ 运行时模型\n\n在 Cubism Viewer 中打开 `apple.model3.json`。\n\n'
        +'参数分别为 Yaw/Y、Pitch/X、Roll/Z，各 −180° 至 180°：\n\n'
        +'\n'.join('- `'+a+'`' for a in axes)+'\n\n'
        +'模型保留 150784 个原面与源贴图，运行时不需要 Turboism 插件。\n\n'
        +'官方导出和独立 Core 的 140 个 XYZ 姿态已通过；最大源投影误差 '
        +f'{geometry["maximumCanvasErrorPixels"]:.3f} 像素（全 XYZ 标准为 5 像素）。'
        +'Viewer 实际渲染、连续旋转遮挡及缝隙尚未验收。此文件不用于 Cubism Editor 网格编辑。\n')
    return receipt


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('controller','bundle','export-acceptance','recorder','core-review','out'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();print(json.dumps(package(a.controller,a.bundle,a.export_acceptance,a.recorder,a.core_review,a.out),indent=2))
