#!/usr/bin/env python3
"""Lossless empty-border crop of the frozen all98 fixture; no geometry writes."""
import hashlib
import io
import json
import struct
import sys
from pathlib import Path
import numpy as np
from PIL import Image

SOURCE_SHA = 'f93fc84f9a71351977ecd1d85e82d39793278b3683ffb3c8b1b32d5049ef3520'

def crop(source, output, dimension=520):
    assert dimension in (520, 576)
    data = source.read_bytes()
    assert hashlib.sha256(data).hexdigest() == SOURCE_SHA
    assert struct.unpack_from('>iii', data) == (0x4d493345, 2, 98)
    offset = 12
    result = io.BytesIO()
    result.write(data[:12])
    rows = []
    for index in range(98):
        start = offset
        chart, original, slots, corners = struct.unpack_from('>iiii', data, offset)
        assert chart == index
        offset += 16 + original * 8 + 32 + original * 4
        positions = np.frombuffer(data, dtype='>f4', count=slots*2, offset=offset).reshape(-1,2)
        assert positions.min() == 4 and positions.max() == 516
        offset += slots * 8 + corners * 4
        size = struct.unpack_from('>i', data, offset)[0]
        prefix = data[start:offset]
        offset += 4
        encoded = data[offset:offset+size]
        offset += size
        pixels = np.array(Image.open(io.BytesIO(encoded)).convert('RGBA'))
        assert pixels.shape == (1024,1024,4)
        # Keep coordinates and the entire four-pixel extrusion unchanged.
        assert not pixels[520:,:,:].any() and not pixels[:,520:,:].any()
        cropped = pixels[:dimension,:dimension,:]
        png = io.BytesIO()
        Image.fromarray(cropped).save(png, format='PNG')
        raw = png.getvalue()
        assert np.array_equal(np.array(Image.open(io.BytesIO(raw))), cropped)
        result.write(prefix)
        result.write(struct.pack('>i', len(raw)))
        result.write(raw)
        rows.append(dict(chart=index, slots=slots, originalFaces=corners//3,
                         unchangedGeometryAndProvenanceSha256=hashlib.sha256(prefix).hexdigest(),
                         originalPngSha256=hashlib.sha256(encoded).hexdigest(),
                         croppedPngSha256=hashlib.sha256(raw).hexdigest()))
    assert offset == len(data)
    assert sum(r['slots'] for r in rows) == 80514
    assert sum(r['originalFaces'] for r in rows) == 150784
    raw = result.getvalue()
    with output.open('xb') as stream:
        stream.write(raw)
    unique = len({r['croppedPngSha256'] for r in rows})
    report = dict(status='LOSSLESS_CROP_FIXTURE_NOT_HOST_ACCEPTED', sourceSha256=SOURCE_SHA,
                  bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest(), dimensions=[dimension,dimension],
                  charts=rows, distinctImages=unique, uniqueImagePixels=unique*dimension*dimension,
                  originalGeometryUnchanged=True, noDownsampling=True, gutterPixels=4)
    output.with_suffix('.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k!='charts'}, indent=2))

if __name__ == '__main__':
    crop(Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3]) if len(sys.argv)>3 else 520)
