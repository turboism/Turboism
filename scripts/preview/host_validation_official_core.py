"""Queue-only standalone official Core execution; never loads Editor/Agent/plugins."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import select
import shutil
import subprocess
import sys
import time
from host_validation_queue import QueueError, atomic_json, file_digest, tree_inventory, runtime_digest, validate_admission

FLAGS={'--name','--version','--bundle-root','--golden-prefix','--host-root','--run-label',
       '--proton-wrapper','--proton-runner','--transport'}
CORE_SHA='98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4'
EDITOR_SHA='988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'


def require(ok,message):
    if not ok:raise QueueError('official-core: '+message)


def path(value):
    p=Path(value)
    require(p.is_absolute() and '..' not in p.parts and not any(q.is_symlink() for q in (p,*p.parents)), 'unsafe path')
    return p


def options(argv, runtime=False):
    allowed=FLAGS|({'--local-evidence-dir'} if runtime else set());result={}
    require(argv[:2]==['--name','official-core'],'standalone dispatch must be first')
    require(len(argv)%2==0,'canonical value options required')
    for k,v in zip(argv[::2],argv[1::2]):
        require(k in allowed and k not in result and isinstance(v,str) and v and '\0' not in v,'unknown/duplicate option')
        result[k]=v
    require(set(result)==allowed,'exact option inventory required')
    require(result['--name']=='official-core' and result['--version']=='5302' and result['--transport']=='local','exact local standalone task')
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,95}',result['--run-label']),'bounded run label')
    for k in ('--bundle-root','--golden-prefix','--host-root','--proton-wrapper','--proton-runner'):
        path(result[k])
    if runtime:path(result['--local-evidence-dir'])
    return result


def bundle(root):
    root=path(root);m=json.loads((root/'bundle.json').read_text())
    require(m['schemaVersion']=='mi3d-official-core-bundle-v1' and m['status']=='FROZEN_NOT_EXECUTED','bundle protocol')
    actual={k:v for k,v in tree_inventory(root).items() if v!='directory'};actual.pop('bundle.json',None)
    # Queue inventories use bare digests; the bundle also declares bounded sizes.
    declared=m['files'];require(set(actual)==set(declared),'bundle member inventory')
    derived=m.get('captureProfile')=='apple-derived-xyz-140-v1'
    fixed={'Live2DCubismCore.jar','official-core-capture.jar','model.moc3','texture-0.png',
           'native/Live2DCubismCoreJNI.dll','native/vcruntime140.dll'}
    if derived:fixed.add('texture-1.png')
    require(fixed<=set(actual) and all(k in fixed or k.startswith('jre/') for k in actual),'standalone runtime file roles')
    total=0
    for name,row in declared.items():
        p=path(root/name);total+=p.stat().st_size
        require(p.is_file() and p.stat().st_size==row['bytes'] and file_digest(p)==row['sha256'],'bundle member identity')
    require(total<=512*1024*1024 and len(declared)<=4096,'bundle budget')
    require(m['coreSha256']==CORE_SHA==file_digest(root/'Live2DCubismCore.jar'),'official Core identity')
    require(m['mocSha256']==file_digest(root/'model.moc3') and m['recorderSha256']==file_digest(root/'official-core-capture.jar'),'MOC/recorder identity')
    profile=m.get('captureProfile','cube-377-v1');require(profile in ('cube-377-v1','apple-11-v1','apple-domain-353-v1','surface-dense-49-v1','apple-xyz-140-v1','apple-derived-xyz-140-v1'),'explicit supported capture profile')
    if profile in ('apple-11-v1','apple-domain-353-v1','surface-dense-49-v1'):
        axes=m['axes'];require(isinstance(axes,list) and len(axes)==2 and re.fullmatch('Param3DYaw[0-9a-f]{12}',axes[0]) and re.fullmatch('Param3DPitch[0-9a-f]{12}',axes[1]) and axes[0][-12:]==axes[1][-12:],'generated axes from accepted export')
    if profile in ('apple-xyz-140-v1','apple-derived-xyz-140-v1'):
        axes=m['axes'];require(isinstance(axes,list) and len(axes)==3 and len(set(axes))==3
            and all(re.fullmatch('Param3D'+label+'[0-9a-f]{12}',axis) for label,axis in zip(('Yaw','Pitch','Roll'),axes))
            and len({axis[-12:] for axis in axes})==1,'explicit complete XYZ axes')
        require((root/'model.moc3').stat().st_size<=256*1024*1024,'XYZ MOC budget')
    return m


def official_runtime(o,staged):
    app=path(o['--golden-prefix'])/'pfx/drive_c/Program Files/Live2D Cubism 5.3/app'
    require(file_digest(app/'lib/Live2D_Cubism.jar')==EDITOR_SHA,'exact official installation')
    require(tree_inventory(app/'jre')==tree_inventory(staged/'jre'),'official JRE closure')
    for name in ('Live2DCubismCoreJNI.dll','vcruntime140.dll'):
        require(file_digest(app/'dll64'/name)==file_digest(staged/'native'/name),'official native Core dependency')


def task_path(o,run):
    require(re.fullmatch(r'queue-[0-9a-f]{32}',run),'queue-owned run identity')
    return path(Path(o['--host-root'])/'official-core'/('5302-'+o['--run-label'])/run)


def windows(p):
    value=str(path(p));require(not any(c in value for c in '\r\n;"%!^&|<>'),'Windows argument path')
    return 'Z:'+value.replace('/','\\')


def launch_argv(o,task):
    # Direct Unix-path java.exe is remapped by UMU to a drive rooted at bin/,
    # losing the JRE's parent layout. Enter via the clone's cmd.exe and use Z:.
    return [o['--proton-wrapper'],'-p',str(task/'prefix'),'--runner',o['--proton-runner'],'--debug',
        str(task/'prefix/pfx/drive_c/windows/system32/cmd.exe'),'/d','/c',windows(task/'core-launch.cmd')]


def launch_script(task):
    windows(task)
    b=task/'bundle'
    manifest=bundle(b);generated=manifest.get('captureProfile') in ('apple-11-v1','apple-domain-353-v1','surface-dense-49-v1','apple-xyz-140-v1','apple-derived-xyz-140-v1');axes=manifest['axes'] if generated else ['ParamMI3DCubeYaw','ParamMI3DCubePitch']
    java=[windows(b/'jre/bin/java.exe'),'-Djava.library.path='+windows(b/'native'),'-cp',
        windows(b/'official-core-capture.jar')+';'+windows(b/'Live2DCubismCore.jar'),
        'dev.modelimport.validation.OfficialCoreCapture',windows(b/'model.moc3'),windows(task/'capture'),
        *axes]
    if generated:java.append(manifest['captureProfile'])
    return ('@echo off\r\n'+''.join('set "'+k+'="\r\n' for k in
        ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'))+
        ' '.join('"'+a+'"' for a in java)+
        (' > "'+windows(task/'recorder.log')+'" 2>&1' if manifest.get('captureProfile') in ('apple-xyz-140-v1','apple-derived-xyz-140-v1') else '')+
        '\r\nexit /b %errorlevel%\r\n').encode('utf-8')


def launcher_timeout(manifest):
    # The original full XYZ MOC is 197 MB. Keep a finite, profile-specific
    # allowance for its loading and complete 14 million vertex samples.
    return 600 if manifest.get('captureProfile') in ('apple-xyz-140-v1','apple-derived-xyz-140-v1') else 180


def remaining_processes(metadata):
    """Read only this admitted kernel scope, excluding our still-running ancestry.

    This is observation, not cleanup proof. The supervisor still owns final
    cgroup cleanup and must independently prove no forced termination occurred.
    """
    group=metadata['cgroupPath'];base=Path('/sys/fs/cgroup')/group.lstrip('/')
    require(group.startswith('/') and '..' not in Path(group).parts and group!='/','scope path')
    stat=base.stat()
    require((stat.st_dev,stat.st_ino)==(metadata['cgroupDevice'],metadata['cgroupInode']),'scope identity changed')
    require(Path('/proc/self/cgroup').read_text().splitlines()==['0::'+group],'scope membership changed')
    ancestors=set();pid=os.getpid()
    while pid>1:
        require(pid not in ancestors,'process ancestry cycle')
        p=Path('/proc')/str(pid)
        if p.joinpath('cgroup').read_text().splitlines()!=['0::'+group]:break
        ancestors.add(pid);fields=p.joinpath('stat').read_text().rsplit(')',1)[1].split();pid=int(fields[1])
    pids=set()
    for file in base.rglob('cgroup.procs'):
        pids.update(map(int,file.read_text().split()))
    require(len(pids)<=4096,'scope process budget')
    rows=[]
    for pid in sorted(pids-ancestors):
        p=Path('/proc')/str(pid)
        try:
            raw=p.joinpath('stat').read_text();fields=raw.rsplit(')',1)[1].split()
            membership=p.joinpath('cgroup').read_text().splitlines()
        except FileNotFoundError:continue # It exited; the next sample checks again.
        require(len(membership)==1 and (membership[0]=='0::'+group
                or membership[0].startswith('0::'+group+'/')),'observed process escaped scope')
        rows.append(dict(pid=pid,ppid=int(fields[1]),startTicks=fields[19],
                         name=raw.split('(',1)[1].rsplit(')',1)[0],state=fields[0]))
    return rows


def wait_for_task_exit(metadata, destination, identity, timeout=120, poll=.5):
    """Give Proton helpers time to exit naturally; never signal any process."""
    started=time.monotonic();samples=[];empty=0;drained=False
    try:
        while time.monotonic()-started < timeout:
            rows=remaining_processes(metadata)
            samples.append(dict(elapsedSeconds=time.monotonic()-started,processes=rows))
            empty=empty+1 if not rows else 0
            if empty>=2:
                drained=True;return True
            time.sleep(poll)
        return False
    finally:
        atomic_json(destination,dict(schemaVersion=1,**identity,cgroupPath=metadata['cgroupPath'],
            drained=drained,signalsSent=False,samples=samples))


def run(argv):
    o=options(argv,True)
    admission=validate_admission()  # Before even creating the task directory.
    require(admission['cleanupOwner']=='supervisor','contained supervisor required')
    task=task_path(o,admission['runId']);source=path(o['--bundle-root']);m=bundle(source)
    # Snapshot evidence before mutation; golden prefix itself is never launched.
    golden=path(o['--golden-prefix']);golden_jar=golden/'pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/Live2D_Cubism.jar'
    golden_hash=file_digest(golden_jar);official_runtime(o,source)
    task.mkdir(parents=True,exist_ok=False);evidence=path(o['--local-evidence-dir']);evidence.mkdir(parents=True,exist_ok=True)
    identity={k:admission[k] for k in ('jobId','runId','attemptId','preparedDigest')}
    preliminary={'schemaVersion':1,**identity,'normalExit':False,'details':{'cleanupOwner':'supervisor','taskDir':str(task),'goldenJarBefore':golden_hash}}
    server=None
    try:
        subprocess.run(['cp','--archive','--reflink=always','--',str(golden),str(task/'prefix')],check=True,timeout=120)
        shutil.copytree(source,task/'bundle');require(bundle(task/'bundle')==m,'staged closure')
        (task/'core-launch.cmd').write_bytes(launch_script(task))
        command=launch_argv(o,task);atomic_json(task/'launch.json',{'argv':command,'identity':identity})
        injected={'CLASSPATH','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','LD_PRELOAD','LD_LIBRARY_PATH','BASH_ENV','ENV','PYTHONPATH','PYTHONHOME'}
        env={k:v for k,v in os.environ.items() if k not in injected and not k.startswith(('TURBOISM_','BASH_FUNC_'))}
        if m.get('captureProfile') in ('apple-xyz-140-v1','apple-derived-xyz-140-v1'):
            readfd,writefd=os.pipe()
            try:
                with (task/'display.log').open('xb') as display_log:
                    server=subprocess.Popen(['/usr/bin/Xvfb','-displayfd',str(writefd),'-screen','0','1440x1080x24','-nolisten','tcp','-noreset'],
                                            pass_fds=(writefd,),stdout=display_log,stderr=subprocess.STDOUT)
                os.close(writefd);writefd=None
                require(bool(select.select([readfd],[],[],15)[0]),'private display startup timeout')
                raw=os.read(readfd,32);require(re.fullmatch(rb'[0-9]+\n',raw) is not None,'private display number')
                display=':'+raw.decode().strip();env['DISPLAY']=display
                env.pop('WAYLAND_DISPLAY',None)
                atomic_json(task/'launch.json',{'argv':command,'identity':identity,'display':display,
                    'xvfbPid':server.pid,'xvfbSha256':file_digest(Path('/usr/bin/Xvfb'))})
            finally:
                os.close(readfd)
                if writefd is not None:os.close(writefd)
        # Empty Windows inherited agent options as well; recorder validates actual VM arguments.
        for k in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'):env[k]=''
        with (task/'launcher.log').open('xb') as log:
            completed=subprocess.run(command,cwd=task,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=launcher_timeout(m))
        preliminary['details']['javaLauncherExit']=completed.returncode
        require(completed.returncode==0,'launcher failed')
        require((task/'capture/core-capture.json').is_file(),'missing Core output')
        if server is not None:
            server.terminate();server.wait(timeout=10);server=None
            preliminary['details']['displayStopped']=True
        # Launcher exit alone is insufficient: the supervisor immediately cleans
        # a populated scope. Observe transient Proton helpers before returning.
        preliminary['details']['gracefulDrain']=wait_for_task_exit(
            admission['containment'],task/'process-drain.json',identity)
        require(preliminary['details']['gracefulDrain'],'task helpers did not exit naturally')
        preliminary['normalExit']=True
        return 0
    finally:
        if server is not None:
            server.terminate();server.wait(timeout=10)
        atomic_json(evidence/'lifecycle-result.json',preliminary)


def finalize(job,descriptor,prepared_root,directory,preliminary,proof,runner_exit,requested=None):
    identity={k:job[v] for k,v in [('jobId','job_id'),('attemptId','attempt_id'),('runId','run_id'),('preparedDigest','digest')]}
    require(type(proof.get('schemaVersion')) is int and proof['schemaVersion']==1 and proof.get('cleanup')=='safe'
            and all(proof.get(k)==v for k,v in identity.items()),'matching kernel containment proof')
    argv=[a.replace('@INPUT@',str(prepared_root)) for a in descriptor['argv']];o=options(argv)
    task=task_path(o,job['run_id']);checks={};passed=False;error=None;capture_budget=64*1024*1024
    try:
        require(isinstance(preliminary,dict) and preliminary.get('schemaVersion')==1
                and all(preliminary.get(k)==v for k,v in identity.items()),'preliminary identity')
        details=preliminary['details'];require(details['cleanupOwner']=='supervisor' and details['taskDir']==str(task),'preliminary owner/path')
        source=bundle(path(o['--bundle-root']));staged=bundle(task/'bundle');require(source==staged,'post-containment bundle')
        official_runtime(o,task/'bundle')
        require(file_digest(path(o['--golden-prefix'])/'pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/Live2D_Cubism.jar')==details['goldenJarBefore'],'golden identity changed')
        require(all(runtime_digest(path(d['path']))==d['sha256'] for d in descriptor['hostDependencies']),'Proton runtime drift')
        launch=json.loads((task/'launch.json').read_text())
        allowed={'argv','identity'}|({'display','xvfbPid','xvfbSha256'} if source.get('captureProfile') in ('apple-xyz-140-v1','apple-derived-xyz-140-v1') else set())
        require(set(launch)==allowed and launch['argv']==launch_argv(o,task) and launch['identity']==identity,'actual launcher plan differs')
        require((task/'core-launch.cmd').read_bytes()==launch_script(task),'fixed Java launch script differs')
        apple=source.get('captureProfile') in ('apple-11-v1','apple-domain-353-v1')
        domain=source.get('captureProfile')=='apple-domain-353-v1'
        derived=source.get('captureProfile')=='apple-derived-xyz-140-v1'
        xyz=source.get('captureProfile') in ('apple-xyz-140-v1','apple-derived-xyz-140-v1')
        surface=source.get('captureProfile')=='surface-dense-49-v1'
        capture_budget=(256 if apple else 64)*1024*1024
        capture=task/'capture/core-capture.json';require(capture.stat().st_size<=capture_budget,'capture budget')
        row=json.loads(capture.read_text());require(row['schemaVersion']==('mi3d-apple-derived-xyz-official-core-capture-v1' if derived else 'mi3d-apple-xyz-official-core-capture-v1' if xyz else 'mi3d-surface-official-core-capture-v1' if surface else 'mi3d-apple-official-core-domain-capture-v1' if domain else 'mi3d-apple-official-core-capture-v1' if apple else 'mi3d-official-core-capture-v2') and row['status']=='CAPTURED_NOT_VERIFIED','capture protocol')
        require(row['poseProtocol']==('mi3d-apple-derived-xyz-140-v1' if derived else 'mi3d-apple-xyz-140-v1' if xyz else 'mi3d-surface-dense-9921-v1' if surface else 'mi3d-apple-layer-domain-353-v1' if domain else 'mi3d-apple-geometry-11-v1' if apple else 'mi3d-cube-geometry-377-v1') and len(row['frames'])==(140 if xyz else 9921 if surface else 353 if domain else 11 if apple else 377)
                and type(row['pid']) is int and row['pid']>0 and row['processStart'],'captured process/sample inventory')
        if surface:
            require({p.name for p in (task/'capture').iterdir()}=={'core-capture.json'} and row['axes']==source['axes'],'exact surface artifacts and axes')
        if domain or xyz:
            if xyz:
                require(re.fullmatch(r':[0-9]+',launch['display']) and type(launch['xvfbPid']) is int
                        and launch['xvfbPid']>0 and launch['xvfbSha256']==file_digest(Path('/usr/bin/Xvfb'))
                        and details['displayStopped'] is True,'owned display started and stopped')
            require(not xyz or row['axes']==source['axes'],'exact XYZ capture axes')
            coords=row['coordinates'];binary=task/'capture/positions-f32.bin.gz'
            require({p.name for p in (task/'capture').iterdir()}=={'core-capture.json','positions-f32.bin.gz'},'exact domain artifacts')
            vertices=80514 if derived else 100258
            require(coords['artifact']=='positions-f32.bin.gz' and coords['verticesPerFrame']==vertices
                    and coords['uncompressedBytes']==16+(140 if xyz else 353)*vertices*8 and len(row['baselineDrawables'])==98
                    and 0<binary.stat().st_size==coords['bytes']<=512*1024*1024
                    and file_digest(binary)==coords['sha256'],'bounded complete domain coordinates')
        require(row['mocSha256']==source['mocSha256'] and row['coreOrigin']['sha256']==source['coreSha256']
                and row['recorderOrigin']['sha256']==source['recorderSha256'],'capture artifact identities')
        expected_cp=windows(task/'bundle/official-core-capture.jar')+';'+windows(task/'bundle/Live2DCubismCore.jar')
        require(row['classpath']==expected_cp and row['jvmArguments']==['-Djava.library.path='+windows(task/'bundle/native')],'actual standalone JVM arguments')
        require(row['coreOrigin']['path']==windows(task/'bundle/Live2DCubismCore.jar')
                and row['recorderOrigin']['path']==windows(task/'bundle/official-core-capture.jar'),'loaded JAR origins')
        require(preliminary.get('normalExit') is True and details['javaLauncherExit']==0 and runner_exit==0
                and not proof['kernelProof']['cgroupKillWritten'],'normal unforced exit')
        drain=json.loads((task/'process-drain.json').read_text())
        require(drain['schemaVersion']==1 and drain['cgroupPath']==proof['cgroupPath']
                and details['gracefulDrain'] is True and drain['drained'] is True and drain['signalsSent'] is False
                and all(drain[k]==v for k,v in identity.items()) and len(drain['samples'])>=2
                and all(s['processes']==[] for s in drain['samples'][-2:]),'natural helper exit observations')
        checks={'bundleManifestSha256':file_digest(task/'bundle/bundle.json'),'captureSha256':file_digest(capture),
                'mocSha256':source['mocSha256'],'coreSha256':source['coreSha256'],'recorderSha256':source['recorderSha256']}
        passed=True
    except (OSError,ValueError,KeyError,TypeError,QueueError) as failure:error=str(failure)
    output=path(directory/'evidence');output.mkdir(parents=True,exist_ok=True)
    if task.exists():
        for name in ('launch.json','core-launch.cmd','launcher.log','recorder.log','display.log','process-drain.json','capture/core-capture.json','capture/positions-f32.bin.gz'):
            src=path(task/name)
            if src.is_file():
                require(src.stat().st_size<=(512*1024*1024 if name=='capture/positions-f32.bin.gz' else capture_budget if name=='capture/core-capture.json' else 64*1024*1024),'evidence budget');dest=output/'standalone-core'/name
                dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,dest)
    checks['terminalResult']={'passed':passed,'scope':'STANDALONE_CORE_CAPTURE_ONLY','error':error}
    terminal=requested or ('succeeded' if passed else 'failed')
    prefix=path(task/'prefix')
    if terminal=='succeeded' and prefix.exists():
        require(shutil.rmtree.avoids_symlink_attacks,'safe prefix removal unavailable')
        shutil.rmtree(prefix)
    result={'schemaVersion':1,**identity,'cleanup':'safe','validationStatus':'PASS' if passed else 'FAIL',
            'identityVerified':passed,'fixtureUnchanged':passed,'normalExit':passed,'runnerExitCode':runner_exit,
            'containment':proof,'terminalState':terminal,'finalizedBy':'contained-supervisor',
            'details':{'cleanupOwner':'supervisor','taskDir':str(task),'taskOwnedCleanup':True,'prefixRetained':prefix.exists(),
                       'postContainmentChecks':checks}}
    atomic_json(output/'lifecycle-result.json',result);return result


def prepare(argv):
    parser=argparse.ArgumentParser();parser.add_argument('version');parser.add_argument('label')
    parser.add_argument('--prepare-dir',type=Path);parser.add_argument('--dry-run',action='store_true');a=parser.parse_args(argv)
    env={'--bundle-root':'TURBOISM_CORE_BUNDLE','--golden-prefix':'TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX',
         '--host-root':'TURBOISM_HOST_VALIDATION_HOST_ROOT','--proton-wrapper':'TURBOISM_HOST_VALIDATION_PROTON_WRAPPER',
         '--proton-runner':'TURBOISM_HOST_VALIDATION_PROTON_RUNNER'}
    args=['--name','official-core','--version',a.version,'--transport','local','--run-label',a.label]
    for flag,key in env.items():require(os.environ.get(key),'missing '+key);args.extend([flag,os.environ[key]])
    options(args);bundle(path(os.environ['TURBOISM_CORE_BUNDLE']))
    request={'schemaVersion':1,'argv':args,'environment':{}}
    require(a.dry_run or a.prepare_dir is not None,'prepare or dry-run required; direct launch forbidden')
    if a.prepare_dir:atomic_json(a.prepare_dir/'runner-request.json',request)
    else:print(json.dumps(request))


if __name__=='__main__':
    try:
        if sys.argv[1:2]==['prepare']:prepare(sys.argv[2:])
        else:sys.exit(run(sys.argv[1:]))
    except (QueueError,OSError,ValueError,subprocess.SubprocessError) as error:
        print(str(error),file=sys.stderr);sys.exit(1)
