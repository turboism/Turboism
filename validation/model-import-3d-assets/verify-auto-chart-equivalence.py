#!/usr/bin/env python3
"""Independent D4/corner/membership comparison; reference never feeds discovery."""
import argparse,hashlib,itertools,json,struct
from pathlib import Path
import numpy as np

def verify(binary,reference,output):
 raw=binary.read_bytes()
 if raw[:8]!=b'CPACK001':raise ValueError('chart inventory header')
 count=struct.unpack_from('>i',raw,8)[0]
 if not 0<count<=200000 or len(raw)!=12+count*56:raise ValueError('bounded full inventory')
 dtype=np.dtype([('chart','>i4'),('parameter','>f8',(3,2))]);records=np.frombuffer(raw,dtype,count=count,offset=12)
 order=np.frombuffer(raw,dtype='>i4',count=count,offset=12+count*52)
 if not np.array_equal(np.sort(order),np.arange(count)):raise ValueError('original order permutation')
 checks=[];offset=0
 for surface in range(2):
  with np.load(reference/f'component-{surface}.npz') as old:
   n=len(old['chart']);new=records[offset:offset+n];offset+=n
   for chart in np.unique(new['chart']):
    mask=new['chart']==chart;prior=np.unique(old['chart'][mask])
    if len(prior)!=1 or not np.array_equal(mask,old['chart']==prior[0]):raise ValueError('changed original triangle membership')
    a=new['parameter'][mask];b=old['parameter'][mask];transforms=[]
    for swap,flipx,flipy in itertools.product([False,True],repeat=3):
     p=a[...,::-1].copy() if swap else a.copy()
     if flipx:p[...,0]=1-p[...,0]
     if flipy:p[...,1]=1-p[...,1]
     if np.array_equal(p,b):transforms.append([swap,flipx,flipy])
    if len(transforms)!=1:raise ValueError('chart parameters differ beyond square symmetry')
    checks.append({'surface':surface,'chart':int(chart),'referenceChart':int(prior[0]),'triangles':int(mask.sum()),'d4Transform':transforms[0]})
 if offset!=count:raise ValueError('reference source inventory mismatch')
 report={'status':'PASS','scope':'complete original triangle chart attribution and parameter equivalence; NOT_NATIVE',
  'triangles':count,'charts':len(checks),'allOriginalTriangleMembershipsExact':True,'allParametersExactUpToD4':True,
  'binarySha256':hashlib.sha256(raw).hexdigest(),'referenceFiles':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(reference.glob('component-*.npz'))},'checks':checks}
 output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k not in ('checks','referenceFiles')}))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--binary',type=Path,required=True);p.add_argument('--reference',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();verify(a.binary,a.reference,a.output)
