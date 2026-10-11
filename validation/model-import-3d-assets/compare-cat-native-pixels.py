#!/usr/bin/env python3
"""Compare nine Cat captures with original-face XYZ depth rendering, without a tolerance gate."""
import argparse, hashlib, json, runpy, shutil, struct, subprocess
from pathlib import Path
import numpy as np
from PIL import Image

SOURCE_SHA = '4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7'
POSES = [(0,0,0),(-180,-180,-180),(180,180,180),(90,90,90),(35,-47,61),
         (-127,103,-155),(37.5,-112.5,23),(-150,75,-72),(-179.5,179.5,179.5)]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def require(ok, why):
    if not ok:
        raise ValueError(why)

def run(native, reference, out):
    report = json.loads((native/'state/report.json').read_text())
    require(report['status']=='PASS' and report['schemaVersion'] in ('mi3d-cat-vertex-menu-v1','mi3d-cat-vertex-frames-v1')
            and report['inputSha256']==SOURCE_SHA, 'completed exact Cat capture')
    require(len(report['frames'])==9 and [tuple(f['XYZ']) for f in report['frames']]==POSES,
            'nine requested XYZ poses')
    frozen = json.loads((reference/'report.json').read_text())
    inventory = json.loads((reference/'artifact-manifest.json').read_text())
    require(frozen['sourceSha256']==SOURCE_SHA and frozen['allOriginalFaces']
            and frozen['sourceTriangles']==70576, 'original Cat source domain')
    for name in ('source.bin','reference.cpp','reference-0.rgba'):
        require(sha(reference/name)==inventory[name], 'frozen oracle identity '+name)
    out.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(reference/'source.bin', out/'source.bin')
    cpp = (reference/'reference.cpp').read_text()
    start = cpp.index('    std::vector<std::pair<double,double>> poses=')
    end = cpp.index('        std::vector<double> depth', start)
    poses = ','.join('{'+','.join(map(str,p))+'}' for p in POSES)
    cpp = cpp[:start]+f'''    std::vector<std::array<double,3>> poses={{{poses}}};
    for(size_t pose=0;pose<poses.size();pose++) {{
        double pitch=poses[pose][0]*std::acos(-1.)/180,yaw=poses[pose][1]*std::acos(-1.)/180,roll=poses[pose][2]*std::acos(-1.)/180;
        double cy=std::cos(yaw),sy=std::sin(yaw),cp=std::cos(pitch),sp=std::sin(pitch),cr=std::cos(roll),sr=std::sin(roll);
'''+cpp[end:]
    scale, px, py, pz = frozen['camera']
    # Derive the production framing from the original GLB, never align native
    # pixels to a reference after capture. The old partition experiment used a
    # world-origin camera; production centers the source bounding-sphere pivot.
    source_path = Path(__file__).resolve().parents[2]/'artifacts/cat-textured/source-r2/cat-textured.glb'
    require(sha(source_path)==SOURCE_SHA, 'original GLB framing identity')
    raw = source_path.read_bytes();length = struct.unpack_from('<I',raw,12)[0]
    doc = json.loads(raw[20:20+length]);binary = raw[28+length:]
    primitive = doc['meshes'][0]['primitives'][0]
    ac = doc['accessors'][primitive['attributes']['POSITION']];view = doc['bufferViews'][ac['bufferView']]
    require(ac['componentType']==5126 and ac['count']==36530 and 'byteStride' not in view,
            'exact source framing accessor')
    points = np.frombuffer(binary,dtype='<f4',count=36530*3,
                           offset=view.get('byteOffset',0)+ac.get('byteOffset',0)).reshape(-1,3).astype(float)
    pivot = (points.min(axis=0)+points.max(axis=0))/2
    radius = np.sqrt(((points-pivot)**2).sum(axis=1)).max()
    require(np.allclose([scale,px,py,pz],[400/radius,*pivot],rtol=0,atol=1e-12),
            'source sphere scale/pivot identity')
    old = f's[i]={{512+({scale})*(cy*x+sy*zp+({px})),512-({scale})*(yp+({py})),-sy*x+cy*zp+({pz}),a.u,a.v}};'
    require(cpp.count(old)==1, 'source projection template')
    cpp = cpp.replace(old, f'double xp=cy*x+sy*zp;s[i]={{512+({scale})*(cr*xp-sr*yp),512-({scale})*(sr*xp+cr*yp),-sy*x+cy*zp+({pz}),a.u,a.v}};')
    cpp = cpp.replace('poses[pose].first','poses[pose][1]').replace('poses[pose].second','poses[pose][0]')
    (out/'reference.cpp').write_text(cpp)
    with (out/'reference.log').open('w') as log:
        subprocess.run(['c++','-O2','-std=c++17',str(out/'reference.cpp'),'-o',str(out/'reference')], check=True, stdout=log, stderr=subprocess.STDOUT)
        subprocess.run([str((out/'reference').resolve()),str(out/'source.bin'),str(out/'frame-')], check=True, stdout=log, stderr=subprocess.STDOUT)
    metrics = runpy.run_path(str(Path(__file__).with_name('compare-apple-native-pixels.py')))['metrics']
    rows = []
    for i, frame in enumerate(report['frames']):
        require(frame['artifact']==f'cat-frames/{i}.png', 'capture path')
        path = native/'state'/frame['artifact']
        require(sha(path)==frame['sha256'], 'capture hash')
        with Image.open(path) as image:
            require(image.size==(1024,1024) and image.mode=='RGBA', 'native dimensions')
            actual = np.array(image)
        expected = np.fromfile(out/f'frame-{i}.rgba',dtype='u1').reshape(1024,1024,4)
        Image.fromarray(expected).save(out/f'reference-{i}.png')
        values, error = metrics(actual, expected)
        visible = expected[:,:,3]>0
        interior = visible.copy()
        padded = np.pad(visible,1)
        for y in range(3):
            for x in range(3):
                interior &= padded[y:y+1024,x:x+1024]
        missing = visible & (actual[:,:,3]==0)
        rgba = np.zeros_like(actual);rgba[:,:,0]=error;rgba[:,:,3]=255
        Image.fromarray(rgba).save(out/f'difference-{i}.png')
        rows.append(dict(poseXYZ=frame['XYZ'],nativeSha256=sha(path),
                         referenceSha256=sha(out/f'frame-{i}.rgba'),
                         missingInside3x3SourceCoverage=int((missing & interior).sum()), **values))
    result = dict(status='CAT_XYZ_ALL_PIXEL_DIAGNOSTICS_NOT_ACCEPTANCE',
                  nativeReportSha256=sha(native/'state/report.json'),sourceSha256=SOURCE_SHA,
                  camera=dict(pivot=pivot.tolist(),scale=scale,canvasPivot=[512,512],radiusPixels=400,
                              method='Original GLB bounds midpoint/max radius; no fit to native capture'),
                  allOriginalFaces=True,allPixelsIncluded=True,masking=False,
                  visibilityAccepted=False,antialiasing='Source pixel centers versus native AA; differences retained without automatic seam/occlusion classification',frames=rows)
    (out/'report.json').write_text(json.dumps(result,indent=2)+'\n')
    (out/'artifact-manifest.json').write_text(json.dumps({p.name:sha(p) for p in out.iterdir() if p.is_file()},indent=2)+'\n')
    print(json.dumps([dict(poseXYZ=r['poseXYZ'],missingCoverage=r['missingCoverage'],
                          missingInside3x3SourceCoverage=r['missingInside3x3SourceCoverage'],
                          meanError=r['meanPremultipliedChannelError']) for r in rows],indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser()
    for name in ('native','reference','out'):
        p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();run(a.native,a.reference,a.out)
