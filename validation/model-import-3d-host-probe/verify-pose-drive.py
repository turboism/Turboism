"""Verify exact bounded SDK parameter-history effects, independent of Java driver."""
import hashlib
import math
import struct

def require(ok,why):
    if not ok:raise ValueError('POSE_HISTORY: '+why)
def number(value):
    value=float(value)
    require(math.isfinite(value),'finite parameter value')
    return struct.unpack('!f',struct.pack('!f',value))[0]

def verify(report,axes):
    root=report['historyBefore'];end=report['historyAfter'];saved=report['liveBefore'];position=root['position']
    require(root['availability']=='AVAILABLE' and position==len(root['entries']) and not root['canRedo']
            and root['documentBindingId'] and root['managerBindingId'],'isolated history tip')
    require(len(saved)==len(axes)==2 and len(set(axes))==2,'two driver axes')
    hashes=lambda entries:[hashlib.sha256(e.encode()).hexdigest() for e in entries]
    prefix=hashes(root['entries']);ready=None;previous_revision=root['revision']
    def identity(h):
        require(all(h[k]==root[k] for k in ('availability','generation','documentBindingId','managerBindingId')),'history binding')
        require(type(h['revision']) is int and h['revision']>=root['revision'],'history revision')
        require(type(h['position']) is int and 0<=h['position']<=len(h['entries'])<=position+2,'bounded history extent')
        require([e['sha256'] for e in h['entries'][:position]]==prefix,'root entry mutation')
        require([e['entryId'] for e in h['entries'][:position]]==root['entryIds'],'root entry identities')
        require([e['index'] for e in h['entries']]==list(range(len(h['entries']))),'entry indices')
        require(h['canUndo']==(h['position']>0) and h['canRedo']==(h['position']<len(h['entries'])),'history navigation')
    for row in report['samples']:
        before,driven,observed,restored=[row[k] for k in ('historyBeforeDrive','historyDriven','historyAfter','historyRestored')]
        for h in (before,driven,observed,restored):identity(h)
        require(before['position']==position and before['revision']>=previous_revision,'driver start cursor/revision')
        if ready is None:
            require(len(before['entries'])==position and before['revision']==root['revision'],'driver initial history')
        else:require(before==ready,'unowned history between poses')
        changes=[(axis,old,new) for axis,old,new in zip(axes,saved,row['pose']) if number(old)!=number(new)]
        priming=row.get('priming')
        if priming is not None:
            require(report.get('historyProtocol')=='POSE_DRIVER_UNDO_RETURN_V2' and not changes,'priming only for unchanged pose')
            prime=priming['historyDriven'];identity(prime)
            require(prime==priming['historyObserved'],'priming observation mutated history')
            prime_pose=priming['pose']
            require(len(prime_pose)==2 and number(prime_pose[0])!=number(saved[0])
                    and number(prime_pose[1])==number(saved[1]),'single yaw priming pose')
            require(prime['position']==len(prime['entries'])==position+1 and prime['revision']>before['revision'],
                    'one priming entry')
            require(driven['entries'][:position+1]==prime['entries'] and driven['revision']>prime['revision'],
                    'return preserves priming entry')
            changes=[(axes[0],saved[0],prime_pose[0]),(axes[0],prime_pose[0],saved[0])]
        elif report.get('historyProtocol')=='POSE_DRIVER_UNDO_RETURN_V2':
            require(bool(changes),'unchanged pose requires priming')
        if not changes:require(driven==before,'no-op changed history')
        else:
            require(driven['position']==len(driven['entries'])==position+len(changes)
                    and driven['revision']>before['revision'],'driver entry count/revision')
            seen={e['entryId'] for e in before['entries'] if e['entryId']}
            for e,(axis,old,new) in zip(driven['entries'][position:],changes):
                require(e['significant'] is True and e['entryId'] and e['entryId'] not in seen and e['transactionId'],'fresh driver entry')
                seen.add(e['entryId']);a=e['action']
                require(a['kind']=='SET_PARAMETER_VALUE' and a['targetType']=='PARAMETER' and a['targetId']==axis
                        and a['property']=='value' and a['detailLevel']=='FULL'
                        and number(a['before'])==number(old) and number(a['after'])==number(new),'exact parameter operation')
        require(observed==driven,'observation mutated history')
        require(restored['position']==position and restored['entries']==driven['entries']
                and restored['revision']>=driven['revision'],'undo root/owned tail')
        require(row['undoVerified'] is True and row['liveRestored']==saved,'live pose undo')
        ready=restored;previous_revision=restored['revision']
    require(ready is not None,'empty pose history')
    require(all(end[k]==ready[k] for k in ('availability','generation','revision','position','documentBindingId','managerBindingId','canUndo','canRedo'))
            and hashes(end['entries'])==[e['sha256'] for e in ready['entries']]
            and end['entryIds']==[e['entryId'] for e in ready['entries']],'final history differs from last verified undo')
    return dict(samples=len(report['samples']),maxOwnedUndoEntries=2,unrelatedHistoryPreserved=True)


def verify_pixels(section,geometry,axes):
    """Verify one unbroken owned history chain across geometry and raster capture."""
    require(section.get('historyProtocol')==geometry.get('historyProtocol') and geometry.get('historyProtocol') in ('POSE_DRIVER_UNDO_V1','POSE_DRIVER_UNDO_RETURN_V2'),'pixel/geometry protocol')
    verify(geometry,axes)
    summary_keys=('availability','generation','revision','position','documentBindingId','managerBindingId','entryIds')
    summary=lambda full:{k:full[k] for k in summary_keys}
    require(section['entryHistory']==summary(geometry['historyAfter']),'pixel entry history is not geometry exit')
    require(section['driverRoot']==geometry['samples'][0]['historyBeforeDrive'],'pixel root ownership')
    require(section['liveBefore']==section['liveAfter']==geometry['liveBefore']==geometry['liveAfter']==[0,0],
            'pixel/geometry neutral live binding')
    require(section['finallyRestored'] is True and section['restoredHistory']==summary(section['historyFinal']),
            'pixel final history summary')
    rows=[]
    for frame in section['frames']:
        compact=frame['historyObserved']
        observed={k:compact[k] for k in summary_keys if k!='entryIds'}
        observed['entryIds']=[e['entryId'] for e in compact['entries']]
        require(frame['historyBefore']==frame['historyAfter']==observed,'pixel render observation binding')
        rows.append(dict(pose=frame['pose'],historyBeforeDrive=frame['historyBeforeDrive'],historyDriven=frame['historyDriven'],
            historyAfter=compact,historyRestored=frame['historyRestored'],undoVerified=frame['undoVerified'],liveRestored=frame['liveRestored']))
        if 'priming' in frame:
            rows[-1]['priming']=frame['priming']
            verify_priming(frame['priming'],frame['consumption'],axes)
    require(len(rows)==9,'pixel history frame inventory')
    combined=dict(geometry,samples=geometry['samples']+rows,historyAfter=section['historyFinal'])
    result=verify(combined,axes)
    return dict(result,frames=len(rows),geometryToPixelOwnershipVerified=True)


def verify_priming(prime,returned,axes):
    """Pixel-stage priming must be independently witnessed, not just an Undo action."""
    require(len(prime['meshes'])==len(returned)>0,'priming mesh inventory')
    def params(e,section):
        rows=e[section]['parameters']
        require(len({p['guid'] for p in rows})==len(rows),'priming parameter identities')
        return {p['guid']:(p['id'],number(p['value'])) for p in rows}
    for detail,back in zip(prime['meshes'],returned):
        requested,actual=detail['requested'],detail['actual']
        require(detail['waitReason']=='CONSUMED' and detail['lastObservation']==actual
                and type(detail['attempts']) is int and detail['attempts']>=1
                and 0<=detail['elapsedMillis']<15000,'priming consumption witness')
        require(all((e['artMeshId'],e['sourceGuid'])==(back['requested']['artMeshId'],back['requested']['sourceGuid'])
                    for e in (requested,actual)),'priming mesh identity')
        require(params(requested,'live')==params(actual,'live')==params(actual,'consumed'),'priming consumed values')
        for axis,value in zip(axes,prime['pose']):
            require([v for name,v in params(actual,'live').values() if name==axis]==[number(value)],'priming pose applied')
        require(actual['passEntryTimeMillis']>=0 and actual['consumed']['updateVersion']!=requested['consumed']['updateVersion'],
                'priming fresh pass')
        require(params(back['requested'],'consumed')==params(actual,'consumed'),'return begins from consumed priming pose')
