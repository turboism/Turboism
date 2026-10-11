#!/usr/bin/env python3
"""Bind derived all-face source geometry to standalone official Core evidence."""
import argparse,json,runpy
from pathlib import Path
HERE=Path(__file__).resolve().parent
XYZ=runpy.run_path(str(HERE/'verify-apple-xyz.py'))
JOB=XYZ['JOB'];require,read,sha=(JOB[k] for k in ('require','read','sha'))

def verify(controller,bundle,export_acceptance,recorder,prepared):
 identity,capture,_=JOB['process'](controller,bundle,export_acceptance,recorder,'apple-derived-xyz-140-v1')
 artifacts=export_acceptance.parent/'host-job/state'
 file=controller/'host-job/evidence/standalone-core/capture/positions-f32.bin.gz'
 coords=capture['coordinates']
 require(coords['artifact']==file.name and coords['verticesPerFrame']==80514
         and coords['uncompressedBytes']==16+140*80514*8 and 0<file.stat().st_size==coords['bytes']<=512*1024*1024
         and sha(file)==coords['sha256'],'bounded complete derived XYZ capture')
 result=XYZ['geometry'](capture,read(artifacts/'report.json'),prepared,artifacts,file,derived=True)
 return dict(status='PASS_NATIVE_DERIVED_APPLE_FINITE_XYZ_GEOMETRY',**identity,geometry=result,
  normalExit=True,sourceMatrix='Rz(roll)*Ry(yaw)*Rx(pitch)',officialCoreAccepted=True,
  nativePixelCorrectnessAccepted=False,officialViewerAccepted=False,continuousDomainAccepted=False,
  captureSha256=sha(file.with_name('core-capture.json')),coordinatesSha256=sha(file),goalComplete=False)

if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__)
 for name in ('controller','bundle','export-acceptance','recorder','prepared','out'):
  p.add_argument('--'+name,type=Path,required=True)
 a=p.parse_args();result=verify(a.controller,a.bundle,a.export_acceptance,a.recorder,a.prepared)
 with a.out.open('x') as stream:json.dump(result,stream,indent=2);stream.write('\n')
 print(json.dumps(result,indent=2))
