#!/usr/bin/env python3
"""Independent stored Warp/3D projection checks. Lifecycle is verified by verify-host.py."""
import math
import struct
SOURCE=[[0,0,.35],[.3,.1,-.1],[-.3,.1,-.1],[0,-.3,-.1]]
LOCAL=[[0,0],[1,0],[0,1],[1,1]]
INDICES=[0,1,2,0,3,1,0,2,3,1,3,2]
KEYS=[-30+3.75*i for i in range(17)]
def require(value,reason):
    if not value:raise ValueError(reason)
def projected(yaw,pitch):
    y,p=math.radians(yaw),math.radians(pitch);result=[]
    for x,y0,z in SOURCE:
        yp=math.cos(p)*y0-math.sin(p)*z;zp=math.sin(p)*y0+math.cos(p)*z
        xp=math.cos(y)*x+math.sin(y)*zp
        result.append([512+200*xp,512-200*yp])
    return result
def error(actual,expected):
    require(len(actual)==len(expected),'control/vertex count')
    return max(math.hypot(a[0]-b[0],a[1]-b[1]) for a,b in zip(actual,expected))
def verify(report,run_id):
    require(report['runId']==run_id and report['mi3dMode']=='warp-authoring' and report['status']=='PASS' and report['failure'] is None,'warp run/result')
    require(report.get('finallyRestored') is True and report['finallyCounts']==report['initialCounts'],'warp final restoration')
    require({c['id'] for c in report['checks']}=={'newCommand','newIdentity'} and all(c['actual'] is True and c['status']=='PASS' for c in report['checks']),'task-new document checks')
    s=report['warpAuthoring'];require(s['status']=='PASS' and s['scope']=='TETRAHEDRON_WARP_STORED_AND_CALCULATED_API_ONLY','explicit Warp API scope')
    require(s['source']==SOURCE and s['local']==LOCAL and s['indices']==INDICES,'frozen tetrahedron/source slots')
    attached=s['attached'];require(attached['uvs']==LOCAL and attached['indices']==INDICES and all(f['positions']==LOCAL for f in attached['forms']),'identity parent attachment provenance')
    require(s['unbound']['rows']==s['unbound']['columns']==1 and s['unbound']['quad'] is True and len(s['unbound']['forms'])==1,'unbound Warp grid')
    require(error(s['unbound']['forms'][0]['positions'],projected(0,0))<.0001,'neutral stored controls')
    before=s['before'];written=s['written'];require(s['undone']==before and s['redone']==written and s['recovered']==written,'complete native Undo/Redo/recovery image')
    require(s['writeOutcome']=='COMMITTED' and s['writeAfter']['position']==s['writeBefore']['position']+1,'single native bake Undo entry')
    require(s['failureOutcome']=='ROLLED_BACK' and s['failureAfter']['position']==s['failureBefore']['position'],'injected root failure history')
    for field in ['id','sourceGuid','rows','columns','quad','axes','cells']:require(written[field]==before[field],'protected Warp '+field)
    axes={a['id']:a for a in written['axes']};require(set(axes)=={'ParamMI3DWarpYaw','ParamMI3DWarpPitch'},'exact axes')
    for a in axes.values():require(a['keys']==KEYS and [a['minimum'],a['default'],a['maximum']]==[-30,0,30],'full 17 keys/default')
    guid_to_id={a['guid']:a['id'] for a in axes.values()};forms={f['guid']:f for f in written['forms']}
    require(len(forms)==289 and len(written['cells'])==289 and len({c['guid'] for c in written['cells']})==289,'independent complete 17x17 grid')
    poses=set()
    for c in written['cells']:
        coords={guid_to_id[g]:v for g,v in c['coordinates']};require(len(c['coordinates'])==len(coords)==2,'complete GUID tuple')
        pose=(coords['ParamMI3DWarpYaw'],coords['ParamMI3DWarpPitch']);poses.add(pose)
        require(error(forms[c['guid']]['positions'],projected(*pose))<.0001,'independent source projection stored form')
    require(poses=={(y,p) for y in KEYS for p in KEYS},'exact joint pose inventory')
    expected={(y,p) for y in [-30,0,30] for p in [-30,0,30]}|{(-13.125,9.375),(16.875,-24.375)}
    require(len(s['samples'])==11 and {tuple(r['pose']) for r in s['samples']}==expected,'native knot/off-knot inventory')
    for row in s['samples']:
        actual=row['actual'];form=actual['calculated'];require(form is not None and form['coordinateType']=='Canvas','calculated Canvas geometry')
        actual_error=error(form['positions'],projected(*row['pose']))
        # Java's SDK projection rounds each reference coordinate to IEEE binary32.
        # Keep the independent double-precision accuracy gate, and reproduce only
        # that documented conversion when checking the reported error statistic.
        float_reference=[[struct.unpack('!f',struct.pack('!f',v))[0] for v in p]
                         for p in projected(*row['pose'])]
        # JSON uses Float.toString shortest decimals; recover their binary32 values.
        float_actual=[[struct.unpack('!f',struct.pack('!f',v))[0] for v in p]
                      for p in form['positions']]
        reported_error=error(float_actual,float_reference)
        require(actual_error<=.1 and abs(reported_error-row['maxErrorPixels'])<1e-6,'independent native projection/error')
        for family in ['live','consumed']:
            values={p['id']:p['value'] for p in actual[family]['parameters']}
            require(values['ParamMI3DWarpYaw']==row['pose'][0] and values['ParamMI3DWarpPitch']==row['pose'][1],'exact native '+family)
    return 'WARP_NATIVE_API_PASS_ONLY_NOT_APPLE_ACCEPTANCE'
