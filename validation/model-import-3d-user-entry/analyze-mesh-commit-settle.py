#!/usr/bin/env python3
"""Classify a single native mesh's source vs editing plane and durable commit."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np


def analyze(report):
    if (report['schemaVersion']!='mi3d-apple-all-mesh-edit-v1' or report['meshStartIndex']!=26
            or report['meshEndIndex']!=27 or report['nativeEditorsCompleted']!=1
            or report['scope']!='SINGLE_CHART_NATIVE_COMMIT_SETTLE_DIAGNOSTIC_NOT_EDIT_ACCEPTANCE'
            or report['sourceUnchanged'] is not True):
        raise ValueError('complete exact single chart diagnostic required')
    before=report['firstNativeCommitBefore'];points=report['nativeEditingPoints'];samples=report['commitSettleSamples']
    ids=[p[0] for p in points]
    if ids!=list(range(len(points))) or len(samples)!=6:raise ValueError('complete ordered native points and settle samples required')
    actual=np.asarray([p[1:] for p in points],dtype=float);source=np.asarray(before['positions'],dtype=float)
    if source.shape!=actual.shape or not np.all(np.isfinite(actual)):raise ValueError('finite complete point layout required')
    vertex_error=np.linalg.norm(actual-source,axis=1)
    source_ix=np.array(before['indices']).reshape(-1,3)
    def areas(xy):
        tri=xy[source_ix];a=tri[:,1]-tri[:,0];b=tri[:,2]-tri[:,0]
        return a[:,0]*b[:,1]-a[:,1]*b[:,0]
    a,b=areas(source),areas(actual)
    inventories=[dict(milliseconds=s['milliseconds'],vertices=s.get('sourceVertices'),triangles=s.get('sourceTriangles'),readError=s.get('readError')) for s in samples]
    return dict(status='NATIVE_COMMIT_SETTLE_DIAGNOSTIC_NOT_FIX',sourceVertices=len(source),
        sourceTriangles=len(source_ix),sourceImagePlaneBounds=[source.min(0).tolist(),source.max(0).tolist()],
        nativeEditingPlaneBounds=[actual.min(0).tolist(),actual.max(0).tolist()],
        maximumSourceVsEditingPlaneError=float(vertex_error.max()),
        editingPlaneEqualsSourcePlane=bool(np.array_equal(actual,source)),
        sourcePlanePositiveTriangles=int((a>0).sum()),sourcePlaneNegativeTriangles=int((a<0).sum()),
        editingPlanePositiveTriangles=int((b>0).sum()),editingPlaneNegativeTriangles=int((b<0).sum()),
        editingPlaneZeroAreaTriangles=int((b==0).sum()),settleSamples=inventories,
        emptyTopologyStillPresentAtTenSeconds=bool(samples[-1].get('sourceTriangles')==0),
        sourceRestored=True,editingAccepted=False,goalComplete=False)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--report',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    result=analyze(json.loads(a.report.read_text()));result['reportSha256']=hashlib.sha256(a.report.read_bytes()).hexdigest()
    with a.out.open('x') as stream:json.dump(result,stream,indent=2);stream.write('\n')
    print(json.dumps(result,indent=2))
