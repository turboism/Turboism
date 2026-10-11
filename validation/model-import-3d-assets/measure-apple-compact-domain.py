#!/usr/bin/env python3
"""All-pixel source depth diagnostic of the actual frozen compact layer bank."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import numpy as np


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(reference, report, attributed, out, core_capture=None):
    manifest = json.loads((reference/'artifact-manifest.json').read_text())
    source = reference/'source.bin'
    if sha(source) != manifest['source.bin']:
        raise ValueError('frozen source identity')
    if sha(attributed) != 'e104cdf12c789927d1b23bd36fe851c612c398e21af005c22d03e87ec4cb47bb':
        raise ValueError('frozen attribution identity')
    r = json.loads(report.read_text()); state = json.loads(r['officialExport']['packedStateJson'])
    if r['schemaVersion'] != 'mi3d-apple-menu-export-v1' or len(state['meshes']) != 98:
        raise ValueError('explicit accepted apple export inventory')
    raw = source.read_bytes()
    if raw[:8] != b'ASRC0001' or struct.unpack_from('<I', raw, 8)[0] != 150784:
        raise ValueError('complete source triangle inventory')
    tri = np.frombuffer(raw, dtype=np.dtype([('v', '<f4', (3, 5)), ('material', '<u4')]), count=150784, offset=12)
    chart = np.frombuffer(attributed.read_bytes(), dtype=np.dtype([('chart', '>i4'), ('parameter', '>f8', (3, 2))]), count=150784, offset=12)['chart'].astype(int)
    if set(chart) != set(range(98)):
        raise ValueError('complete chart inventory')
    keys = np.linspace(-30, 30, 9); bank = np.empty((9, 9, 98), dtype=float)
    axes = r['sourceTextureRender']['axes']
    for i, mesh in enumerate(state['meshes']):
        by_guid = {a['guid']: a['id'] for a in mesh['axes']}; seen = set()
        if any(a['keys'] != keys.tolist() for a in mesh['axes']) or len(mesh['forms']) != 81:
            raise ValueError('complete nine-key ordinary bank')
        for form in mesh['forms']:
            coords = {by_guid[g]: v for g, v in form['coordinates']}
            y, p = (list(keys).index(coords[a]) for a in axes)
            if (y, p) in seen or not 0 <= form['drawOrder'] <= 1000:
                raise ValueError('duplicate/out-of-range stored order')
            bank[y, p, i] = form['drawOrder']; seen.add((y, p))
    grid = np.linspace(-30, 30, 17)
    # 81 keys + 64 cell centers + 144 edge midpoints; includes the entire boundary.
    poses = [(float(y), float(p)) for y in grid for p in grid]
    random = np.random.default_rng(4012027)
    poses += [tuple(map(float, pair)) for pair in random.uniform(-30, 30, (64, 2)).astype(np.float32)]
    if len(set(poses)) != 353:
        raise ValueError('fixed pose inventory')
    native = None
    if core_capture is not None:
        native = json.loads(core_capture.read_text())
        if (native['schemaVersion'] != 'mi3d-apple-official-core-domain-capture-v1'
                or native['poseProtocol'] != 'mi3d-apple-layer-domain-353-v1'
                or [f['pose'] for f in native['frames']] != [list(p) for p in poses]
                or native['mocSha256'] != r['officialExport']['moc']['sha256']):
            raise ValueError('same native MOC and complete domain protocol')
    out.mkdir(parents=True, exist_ok=False); (out/'orders').mkdir()
    (out/'poses.json').write_text(json.dumps(poses, indent=2)+'\n')
    neutral_depth = tri['v'][:, :, 2].astype(float).mean(axis=1)
    for i, (y, p) in enumerate(poses):
        yi = min(7, max(0, int(np.searchsorted(keys, y, side='right')-1)))
        pi = min(7, max(0, int(np.searchsorted(keys, p, side='right')-1)))
        fy, fp = (y-keys[yi])/7.5, (p-keys[pi])/7.5
        score = np.rint((1-fy)*((1-fp)*bank[yi, pi]+fp*bank[yi, pi+1])
                        +fy*((1-fp)*bank[yi+1, pi]+fp*bank[yi+1, pi+1]))
        if native is not None:
            rows = native['frames'][i]['drawables']; by_id = {row['id']: row for row in rows}
            if len(rows) != len(by_id) or set(by_id) != {m['id'] for m in state['meshes']}:
                raise ValueError('complete actual native drawable identity')
            # Core has already resolved integer ties into an actual render-order permutation.
            score = np.array([by_id[m['id']]['renderOrder'] for m in state['meshes']])
            if sorted(score) != list(range(98)):
                raise ValueError('actual native render order permutation')
        np.lexsort((-neutral_depth, score[chart])).astype('<u4').tofile(out/'orders'/f'{i}.bin')
    template = Path(__file__).with_name('apple-source-raster.cpp').read_text()
    s = template.replace('if(argc!=3)', 'if(argc!=4)')
    start = s.index('    std::vector<std::pair<double,double>> poses;')
    end = s.index('    for(size_t pose=', start)
    literals = ','.join('{'+format(y, '.17g')+','+format(p, '.17g')+'}' for y, p in poses)
    s = s[:start]+'    std::vector<std::pair<double,double>> poses={'+literals+'};\n'+s[end:]
    s = s.replace('std::vector<uint8_t> rgba(size*size*4,0);', 'std::vector<double> painted(size*size,std::numeric_limits<double>::infinity());')
    s = s.replace('        for(const auto& tri:triangles) {\n', '''        std::ifstream orderFile(std::string(argv[3])+"/"+std::to_string(pose)+".bin",std::ios::binary);
        std::vector<uint32_t> order(count);orderFile.read(reinterpret_cast<char*>(order.data()),count*4);
        if(!orderFile || orderFile.peek()!=std::char_traits<char>::eof())throw std::runtime_error("order length");
        auto sorted=order;std::sort(sorted.begin(),sorted.end());for(uint32_t i=0;i<count;i++)if(sorted[i]!=i)throw std::runtime_error("order permutation");
        for(uint32_t id:order) {const auto& tri=triangles[id];
''')
    start = s.index('                if(z<depth[offset])')
    end = s.index('\n', start)
    s = s[:start]+'                depth[offset]=std::min(depth[offset],z);painted[offset]=z;'+s[end:]
    start = s.index('        std::string prefix=')
    end = s.index('\n    }\n }catch', start)
    s = s[:start]+'''        uint64_t visible=0,wrong=0;double maximum=0;
        for(size_t pixel=0;pixel<depth.size();pixel++)if(std::isfinite(depth[pixel])){
            visible++;double gap=painted[pixel]-depth[pixel];if(gap>1e-5)wrong++;maximum=std::max(maximum,gap);
        }
        std::cout<<"{\\"index\\":"<<pose<<",\\"visiblePixels\\":"<<visible<<",\\"wrongDepthPixels\\":"<<wrong<<",\\"maximumDepthGap\\":"<<maximum<<"}\\n"<<std::flush;
''' +s[end:]
    if 'rgba' in s or 'orderFile' not in s:
        raise ValueError('renderer template drift')
    cpp = out/'compact-domain.cpp'; cpp.write_text(s)
    commands = []
    for name, argv in [('compile', ['c++', '-O2', '-std=c++17', str(cpp), '-o', str(out/'compact-domain')]),
                       ('measure', [str((out/'compact-domain').resolve()), str(source.resolve()), str(out.resolve()), str((out/'orders').resolve())])]:
        with (out/(name+'.log')).open('xb') as log:
            result = subprocess.run(argv, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        commands.append(dict(argv=argv, exitCode=result.returncode))
        if result.returncode:
            raise RuntimeError(name+' failed')
    rows = [json.loads(line) for line in (out/'measure.log').read_text().splitlines()]
    if [r['index'] for r in rows] != list(range(353)):
        raise ValueError('complete actual raster inventory')
    for row, pose in zip(rows, poses):
        row['pose'] = pose
    result = dict(status='SOURCE_DEPTH_DOMAIN_DIAGNOSTIC_NOT_NATIVE_ACCEPTANCE',
                  poses=353, keyPoses=81, cellCenters=64, edgeMidpoints=144, randomPoses=64,
                  canvas=[1024, 1024], allOriginalTriangles=150784, boundaryMask=False,
                  sourceSha256=sha(source), exportReportSha256=sha(report), attributionSha256=sha(attributed),
                  maximumWrongDepthPixels=max(r['wrongDepthPixels'] for r in rows),
                  posesWithWrongDepth=sum(r['wrongDepthPixels'] > 0 for r in rows),
                  layerKeysPerAxis=9, hostExecuted=False, nativeVisibilityAccepted=False,
                  orderingInput='ACTUAL_NATIVE_CORE_RENDER_ORDER' if native is not None else 'ROUNDED_SOURCE_LAYER_INTERPOLATION_CANDIDATE',
                  coreCaptureSha256=sha(core_capture) if native is not None else None,
                  warning='Source geometry only. Native Warp interpolation and actual render must be checked separately.',
                  rows=rows, commands=commands)
    (out/'report.json').write_text(json.dumps(result, indent=2)+'\n')
    (out/'artifact-manifest.json').write_text(json.dumps({str(p.relative_to(out)): sha(p) for p in out.rglob('*') if p.is_file()}, indent=2)+'\n')
    print(json.dumps({k: v for k, v in result.items() if k not in ('rows', 'commands')}))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ('reference', 'report', 'attributed', 'out'):
        p.add_argument('--'+name, required=True, type=Path)
    p.add_argument('--core-capture', type=Path)
    a = p.parse_args(); run(a.reference, a.report, a.attributed, a.out, a.core_capture)
