#!/usr/bin/env python3
"""Independent derived export byte/bank audit; Core and pixel gates stay separate."""
import hashlib
import io
import json
import runpy
import struct
import sys
from pathlib import Path
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
PAIR = runpy.run_path(str(HERE / 'analyze-derived-persistence.py'))

def canonical(bank):
    result=dict(bank)
    for key in ('meshes','meshMetadata'):
        rows=bank[key];mapped={r['id']:r for r in rows}
        assert len(mapped)==len(rows)
        result[key]=mapped
    return result

def verify_atlas_chart(raw,chart,placement,textures):
    _,page,x,y,w,h,rotated=placement
    assert w==h==chart['imageSize'] and not rotated
    count=chart['n']
    # The 4096 page reciprocal is exact in float32; permit at most one ULP
    # from the official affine's addition order, with no positional drift.
    wanted=np.asarray([( (p[0]+x)/4096, (p[1]+y)/4096 ) for p in chart['positions']],dtype='>f4')
    observed=np.frombuffer(raw[count*8:count*16],dtype='>f4').reshape(count,2)
    delta=np.abs(observed.view('>u4').astype(np.int64)-wanted.view('>u4').astype(np.int64))
    assert delta.max()<=1, 'mesh UV does not address its source image placement'
    source=np.array(Image.open(io.BytesIO(chart['png'])).convert('RGBA'))
    atlas= textures[page][y:y+h,x:x+w]
    assert atlas.shape==source.shape
    # RGB of fully transparent texels is immaterial; retain the measurement.
    pixel_delta=np.abs(atlas.astype(np.int16)-source.astype(np.int16))
    assert np.array_equal(atlas[:,:,3],source[:,:,3]), 'atlas alpha differs from source'
    opaque=source[:,:,3]>0
    assert not pixel_delta[opaque].any(), 'atlas visible texture pixels differ from source'
    return dict(chart=chart['chart'],page=page,
        maximumUvUlps=int(delta.max()),visiblePixelDifference=0,
        transparentRgbDifferencePixels=int(np.any(pixel_delta[:,:,:3]>0,axis=2)[~opaque].sum()))

def analyze(reopen_root, export_root):
    reopen_root, export_root = map(Path, (reopen_root, export_root))
    _, reopened = PAIR['terminal'](reopen_root)
    job, report = PAIR['terminal'](export_root)
    assert report['schemaVersion'] == 'mi3d-apple-derived-export-v1'
    original = reopened['reopenedState']
    assert canonical(report['imported']) == canonical(original)
    section = report['officialExport']
    assert section['status'] == 'CAPTURED_REQUIRES_OFFICIAL_CORE_VERIFICATION'
    assert section['sourceRestored'] and canonical(section['restoredState']) == canonical(original)
    assert canonical(json.loads(section['beforeAtlasStateJson'])) == canonical(original)
    assert hashlib.sha256(section['beforeAtlasStateJson'].encode()).hexdigest() == section['beforeAtlasSha256']
    packed = json.loads(section['packedStateJson'])
    assert packed == report['packed'] == section['exportStateBefore'] == section['exportStateAfter']
    assert section['beforeExportSha256'] == section['afterExportSha256'] == hashlib.sha256(section['packedStateJson'].encode()).hexdigest()
    assert section['historyBeforeExport'] == section['historyAfterExport']
    assert section['settings'] == [512,512,100,False,False,False,False,False]
    for key in ('warps','rotations','parameters','parts','rawImages','imageGroups'):
        assert packed[key] == original[key], key
    meshes = {m['id']:m for m in packed['meshes']}
    before = {m['id']:m for m in original['meshes']}
    assert meshes.keys() == before.keys() and len(meshes) == 98
    fixture = PAIR['FIXTURE'].with_name('derived-bound-all98-aligned-v4.bin')
    charts = PAIR['EDIT']['fixture'](fixture, include_png=True)
    placements = {p[0]:p for p in section['initialAtlasLayout']['placements']}
    metadata = {m['id']:m for m in original['meshMetadata']}
    packed_metadata = {m['id']:m for m in packed['meshMetadata']}
    atlas_ids = [a[0] for a in section['atlases']]
    textures = [np.array(Image.open(PAIR['read_artifact'](export_root,t['artifact'])).convert('RGBA'))
                for t in section['textures']]
    texture_measurements=[]
    rows = section['packedSource']
    assert len(rows) == 98 and {r['id'] for r in rows} == meshes.keys()
    for row in rows:
        mesh = meshes[row['id']]
        for key in ('sourceGuid','vertexCount','triangleCount','axisCount','formCount','cellCount','axes','forms','localSha256'):
            assert mesh[key] == before[row['id']][key], key
        path = PAIR['read_artifact'](export_root,row['artifact'])
        raw = path.read_bytes();count=mesh['vertexCount']
        assert len(raw) == row['bytes'] == count*16+mesh['triangleCount']*12
        assert hashlib.sha256(raw).hexdigest() == row['sha256'] == mesh['geometrySha256']
        chart = charts[int(row['id'].removeprefix('ArtMeshAppleEditableChart'))]
        assert raw[:count*8] == b''.join(struct.pack('>ff',*p) for p in chart['positions'])
        assert raw[count*16:] == b''.join(struct.pack('>i',i) for i in chart['indices'])
        assert np.isfinite(np.frombuffer(raw[:count*16],dtype='>f4')).all()
        placement=placements[metadata[row['id']]['input']['image']]
        _,page,x,y,w,h,rotated=placement
        assert w==h==chart['imageSize'] and not rotated
        assert packed_metadata[row['id']]['input']['atlas']==atlas_ids[page]
        texture_measurements.append(verify_atlas_chart(raw,chart,placement,textures))
    PAIR['frames'](export_root,report,meshes)
    old_frames = {tuple(f['poseXYZ']):f for f in reopened['frames']}
    for frame in report['frames']:
        old = old_frames[tuple(frame['poseXYZ'])]
        assert {m['id']:m for m in frame['evaluated']} == {m['id']:m for m in old['evaluated']}
    pages = section['atlasPageCount'];assert 1 <= pages <= 2
    layout = section['initialAtlasLayout']
    assert layout['pageWidth'] == layout['pageHeight'] == 4096 and layout['pageCount'] == pages
    placements = layout['placements'];assert len({p[0] for p in placements}) == len(placements)
    for p in placements:
        _,page,x,y,w,h,rotated = p
        assert 0 <= page < pages and x>=0 and y>=0 and x+w<=4096 and y+h<=4096 and not rotated
        for q in placements:
            if p[0] != q[0] and page == q[1]:
                assert x+w<=q[2] or q[2]+q[4]<=x or y+h<=q[3] or q[3]+q[5]<=y
    moc = section['moc'];path=PAIR['read_artifact'](export_root,moc['artifact'])
    assert 64 <= path.stat().st_size == moc['bytes'] <= 256*1024*1024
    assert path.read_bytes()[:4] == b'MOC3' and PAIR['digest'](path) == moc['sha256']
    assert len(section['textures']) == pages
    for index,t in enumerate(section['textures']):
        path=PAIR['read_artifact'](export_root,t['artifact'])
        assert t['index']==index and t['width']==t['height']==4096
        assert path.stat().st_size==t['bytes'] and PAIR['digest'](path)==t['sha256']
        with Image.open(path) as image:
            assert image.size==(4096,4096);image.load()
    result=dict(status='PASS_DERIVED_EXPORT_BYTES_BANKS_AND_ORIGINAL_FACES_ONLY',jobId=job['job_id'],
        runId=job['run_id'],prepared=job['prepared_id'],normalExit=True,
        pages=pages,meshes=98,slots=80514,originalFaces=150784,frames=12,
        atlasUvPixelAccepted=True,atlasMeasurements=texture_measurements,
        officialCoreAccepted=False,fullAppleAccepted=False)
    (export_root/'independent-derived-export-review.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))

if __name__ == '__main__':
    analyze(*sys.argv[1:])
