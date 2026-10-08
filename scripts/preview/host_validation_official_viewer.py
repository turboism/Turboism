"""Queue-only official Viewer observation in an owned display; no Turboism injection."""
from __future__ import annotations
import argparse,json,os,re,select,shutil,subprocess,sys,time
from pathlib import Path
from host_validation_queue import QueueError,atomic_json,file_digest,tree_inventory,runtime_digest,validate_admission
import runpy
_CORE=runpy.run_path(str(Path(__file__).with_name('host_validation_official_core.py')))
path,windows,wait_for_task_exit=(_CORE[k] for k in ('path','windows','wait_for_task_exit'))

EDITOR_SHA='988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'
FLAGS={'--name','--version','--bundle-root','--golden-prefix','--host-root','--run-label','--proton-wrapper','--proton-runner','--transport'}

def require(ok,message):
    if not ok:raise QueueError('official-viewer: '+message)

def options(argv,runtime=False):
    allowed=FLAGS|({'--local-evidence-dir'} if runtime else set());o={}
    require(argv[:2]==['--name','official-viewer'] and len(argv)%2==0,'canonical dedicated dispatch')
    for k,v in zip(argv[::2],argv[1::2]):
        require(k in allowed and k not in o and v and '\0' not in v,'unknown/duplicate option');o[k]=v
    require(set(o)==allowed and o['--version']=='5302' and o['--transport']=='local','exact local task')
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,95}',o['--run-label']),'bounded label')
    for k in ('--bundle-root','--golden-prefix','--host-root','--proton-wrapper','--proton-runner'):path(o[k])
    if runtime:path(o['--local-evidence-dir'])
    return o

def bundle(root):
    root=path(root);m=json.loads((root/'bundle.json').read_text())
    require(m['schemaVersion']=='mi3d-official-viewer-bundle-v1' and m['status']=='FROZEN_NOT_EXECUTED','bundle protocol')
    profile=m.get('captureProfile','cube-3-v1');require(profile in ('cube-3-v1','apple-3-v1','apple-xyz-3-v1','apple-derived-xyz-3-v1'),'explicit Viewer profile')
    descriptor='apple.model3.json' if profile in ('apple-3-v1','apple-xyz-3-v1','apple-derived-xyz-3-v1') else 'cube.model3.json'
    if profile in ('apple-3-v1','apple-xyz-3-v1','apple-derived-xyz-3-v1'):require(m['modelDescriptor']==descriptor,'explicit apple descriptor')
    if profile in ('apple-xyz-3-v1','apple-derived-xyz-3-v1'):
        axes=m['axes'];require(isinstance(axes,list) and len(axes)==3 and all(re.fullmatch('Param3D'+name+'[0-9a-f]{12}',axis) for name,axis in zip(('Yaw','Pitch','Roll'),axes))
            and len({a[-12:] for a in axes})==1,'exact imported XYZ axes')
    derived=profile=='apple-derived-xyz-3-v1'
    textures=['texture-0.png','texture-1.png'] if derived else ['texture-0.png']
    actual={k:v for k,v in tree_inventory(root).items() if v!='directory'};actual.pop('bundle.json',None)
    require(set(actual)==set(m['files']) and len(actual)<=4096,'bundle inventory')
    total=0
    for name,row in m['files'].items():
        p=path(root/name);total+=p.stat().st_size
        require(p.is_file() and row['bytes']==p.stat().st_size and row['sha256']==actual[name],'bundle bytes')
        require(name in ('official-viewer-capture.jar','CubismViewer5.bat','model/'+descriptor,'model/model.moc3','model/texture-0.png') or (derived and name=='model/texture-1.png') or name.startswith(('app/lib/','app/dll64/','app/jre/')),'bundle member role')
    require(total<=1024*1024*1024,'bundle bytes budget')
    require(m['editorSha256']==EDITOR_SHA==actual['app/lib/Live2D_Cubism.jar'],'official Viewer JAR')
    bat=(root/'CubismViewer5.bat').read_text().splitlines()
    def field(key):
        values=[line[len('set '+key+'='):] for line in bat if line.startswith('set '+key+'=')]
        require(len(values)==1,'official BAT field '+key);return values[0]
    cp=[v.replace('\\','/') for v in field('CLASS_PATH').split(';')]+['official-viewer-capture.jar']
    native=[v.replace('\\','/') for v in field('NATIVE_PATH').split(';')]
    require(m['classpath']==cp and len(cp)==len(set(cp)) and all(n in actual for n in cp),'official classpath')
    require(m['nativePaths']==native and native==['app/dll64','app/dll64/windows-amd64'],'official native paths')
    require(json.loads((root/'model'/descriptor).read_text())=={'Version':3,'FileReferences':{'Moc':'model.moc3','Textures':textures}},'fixed model dependencies')
    return m

def official_runtime(o,root):
    m=bundle(root);editor=path(o['--golden-prefix'])/'pfx/drive_c/Program Files/Live2D Cubism 5.3'
    for name,row in m['files'].items():
        if name.startswith('app/') or name=='CubismViewer5.bat':require(file_digest(path(editor/name))==row['sha256'],'official installed dependency '+name)

def task_path(o,run):
    require(re.fullmatch(r'queue-[0-9a-f]{32}',run),'queue run identity')
    return path(Path(o['--host-root'])/'official-viewer'/('5302-'+o['--run-label'])/run)

def launch_script(task,m):
    b=task/'bundle';home=task/'home'
    relative=lambda n:n.replace('/', '\\')
    args=[relative('app/jre/bin/java.exe'),'-Djava.library.path='+';'.join(relative(n) for n in m['nativePaths']),
          '-Djogamp.gluegen.UseTempJarCache=false','-Dsun.java2d.d3d=false','-Duser.language=zh',
          '-Djava.locale.providers=COMPAT,SPI','-Duser.home='+windows(home),'-cp',
          ';'.join(relative(n) for n in m['classpath']),'dev.modelimport.validation.OfficialViewerCapture',
          windows(b/'model'/('apple.model3.json' if m.get('captureProfile') in ('apple-3-v1','apple-xyz-3-v1','apple-derived-xyz-3-v1') else 'cube.model3.json')),windows(task/'capture')]
    if m.get('captureProfile')=='apple-3-v1':args.append('apple-3-v1')
    if m.get('captureProfile') in ('apple-xyz-3-v1','apple-derived-xyz-3-v1'):args.extend(['apple-xyz-3-v1',*m['axes']])
    clear=''.join('set "'+k+'="\r\n' for k in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'))
    local=''.join('set "'+k+'='+windows(home)+'"\r\n' for k in ('USERPROFILE','APPDATA','LOCALAPPDATA'))
    script=('@echo off\r\ncd /d "'+windows(b)+'"\r\n'+clear+local+' '.join('"'+a+'"' for a in args)+'\r\nexit /b %errorlevel%\r\n').encode()
    require(max(map(len,script.splitlines()))<8192,'Windows command line budget')
    return script

def launch_argv(o,task):
    return [o['--proton-wrapper'],'-p',str(task/'prefix'),'--runner',o['--proton-runner'],'--debug',str(task/'prefix/pfx/drive_c/windows/system32/cmd.exe'),'/d','/c',windows(task/'viewer-launch.cmd')]

def run(argv):
    o=options(argv,True);admission=validate_admission();require(admission['cleanupOwner']=='supervisor','contained supervisor required')
    identity={k:admission[k] for k in ('jobId','runId','attemptId','preparedDigest')};task=task_path(o,identity['runId'])
    source=path(o['--bundle-root']);m=bundle(source);official_runtime(o,source)
    task.mkdir(parents=True,exist_ok=False);evidence=path(o['--local-evidence-dir']);evidence.mkdir(parents=True,exist_ok=True)
    preliminary=dict(schemaVersion=1,**identity,normalExit=False,details=dict(cleanupOwner='supervisor',taskDir=str(task)))
    server=None;readfd=writefd=None
    try:
        subprocess.run(['cp','--archive','--reflink=always','--',o['--golden-prefix'],str(task/'prefix')],check=True,timeout=120)
        shutil.copytree(source,task/'bundle');require(bundle(task/'bundle')==m,'staged closure');(task/'home').mkdir()
        (task/'viewer-launch.cmd').write_bytes(launch_script(task,m));command=launch_argv(o,task)
        # A fresh, task-owned X server avoids all interaction with the user's desktop.
        readfd,writefd=os.pipe()
        with (task/'display.log').open('xb') as display_log:
            server=subprocess.Popen(['/usr/bin/Xvfb','-displayfd',str(writefd),'-screen','0','1440x1080x24','-nolisten','tcp','-noreset'],pass_fds=(writefd,),stdout=display_log,stderr=subprocess.STDOUT)
            os.close(writefd);writefd=None
            require(bool(select.select([readfd],[],[],15)[0]),'private display startup timeout')
            raw=os.read(readfd,32);require(re.fullmatch(rb'[0-9]+\n',raw) is not None,'private display number');display=':'+raw.decode().strip()
            os.close(readfd);readfd=None
            atomic_json(task/'launch.json',dict(argv=command,identity=identity,display=display,xvfbPid=server.pid,xvfbSha256=file_digest(Path('/usr/bin/Xvfb'))))
            injected={'CLASSPATH','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','LD_PRELOAD','LD_LIBRARY_PATH','BASH_ENV','ENV','PYTHONPATH','PYTHONHOME','XAUTHORITY'}
            env={k:v for k,v in os.environ.items() if k not in injected and not k.startswith(('TURBOISM_','BASH_FUNC_'))};env['DISPLAY']=display
            for k in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'):env[k]=''
            with (task/'launcher.log').open('xb') as log:
                completed=subprocess.run(command,cwd=task/'bundle',env=env,stdout=log,stderr=subprocess.STDOUT,timeout=240)
            preliminary['details']['launcherExit']=completed.returncode;require(completed.returncode==0,'Viewer launcher failed')
            require((task/'capture/observation.json').is_file(),'missing observation')
            server.terminate();server.wait(timeout=10);preliminary['details']['displayStopped']=True;server=None
        preliminary['details']['gracefulDrain']=wait_for_task_exit(admission['containment'],task/'process-drain.json',identity)
        require(preliminary['details']['gracefulDrain'],'Viewer helpers did not exit naturally');preliminary['normalExit']=True;return 0
    finally:
        for fd in (readfd,writefd):
            if fd is not None:os.close(fd)
        if server is not None:
            # Only our Popen child, never an inherited or discovered process.
            if server.poll() is None:server.terminate()
            try:server.wait(timeout=10)
            except subprocess.TimeoutExpired:pass
        atomic_json(evidence/'lifecycle-result.json',preliminary)

def finalize(job,descriptor,prepared_root,directory,preliminary,proof,runner_exit,requested=None):
    identity={k:job[v] for k,v in [('jobId','job_id'),('runId','run_id'),('attemptId','attempt_id'),('preparedDigest','digest')]}
    require(proof.get('schemaVersion')==1 and proof.get('cleanup')=='safe' and all(proof.get(k)==v for k,v in identity.items()),'matching containment proof')
    o=options([a.replace('@INPUT@',str(prepared_root)) for a in descriptor['argv']]);task=task_path(o,job['run_id']);checks={};passed=False;error=None
    try:
        require(isinstance(preliminary,dict) and all(preliminary.get(k)==v for k,v in identity.items()),'preliminary identity')
        kernel=proof['kernelProof'];final=kernel['finalReading']
        require(kernel['originalCgroupBound'] is True and kernel['errors']==[] and kernel['processTimedOut'] is False
                and kernel['processReturncode']==0 and kernel['cgroupKillWritten'] is False,'kernel exit proof')
        require(final['kind']=='destroyed' or (final['kind']=='same' and final['populated']==0),'kernel scope not empty')
        require(all(kernel[k]==proof[k] for k in ('bootId','cgroupPath','cgroupDevice','cgroupInode')),'kernel scope binding')
        details=preliminary['details'];require(details['cleanupOwner']=='supervisor' and details['taskDir']==str(task),'task owner')
        source=bundle(path(o['--bundle-root']));staged=bundle(task/'bundle');require(source==staged,'post-containment bundle')
        official_runtime(o,task/'bundle')
        require(all(runtime_digest(path(d['path']))==d['sha256'] for d in descriptor['hostDependencies']),'Proton dependencies changed')
        launch=json.loads((task/'launch.json').read_text())
        require(launch['identity']==identity and launch['argv']==launch_argv(o,task),'launch plan')
        require(re.fullmatch(r':[0-9]+',launch['display']) and type(launch['xvfbPid']) is int and launch['xvfbPid']>0
                and launch['xvfbSha256']==file_digest(Path('/usr/bin/Xvfb')),'private display evidence')
        require((task/'viewer-launch.cmd').read_bytes()==launch_script(task,source),'fixed Viewer launch script')
        capture=task/'capture';allowed={'launch.json','observation.json'}|{f'viewer-{i}.png' for i in range(3)}|{f'components-{i}.txt' for i in range(3)}
        require({p.name for p in capture.iterdir()}==allowed,'observation inventory or failure')
        row=json.loads((capture/'launch.json').read_text());observation=json.loads((capture/'observation.json').read_text())
        b=task/'bundle';home=task/'home'
        relative=lambda n:n.replace('/', '\\')
        expected_vm=['-Djava.library.path='+';'.join(relative(n) for n in source['nativePaths']),
                     '-Djogamp.gluegen.UseTempJarCache=false','-Dsun.java2d.d3d=false','-Duser.language=zh',
                     '-Djava.locale.providers=COMPAT,SPI','-Duser.home='+windows(home)]
        require(row['schemaVersion']=='mi3d-official-viewer-observation-v1' and row['status']=='STARTED_NOT_VERIFIED','observer protocol')
        require(type(row['pid']) is int and row['pid']>0 and row['processStart'],'observer process identity')
        require(row['officialJarSha256']==EDITOR_SHA and row['officialJar']==windows(b/'app/lib/Live2D_Cubism.jar'),'actual official JAR')
        require(row['classpath']==';'.join(relative(n) for n in source['classpath']) and row['jvmArguments']==expected_vm,'actual isolated JVM arguments')
        require(row['model3Sha256']==source['files']['model/'+('apple.model3.json' if source.get('captureProfile') in ('apple-3-v1','apple-xyz-3-v1','apple-derived-xyz-3-v1') else 'cube.model3.json')]['sha256'],'actual model descriptor')
        if source.get('captureProfile') in ('apple-xyz-3-v1','apple-derived-xyz-3-v1'):require(observation['axes']==source['axes'],'actual requested XYZ controls')
        require(observation['status']=='CAPTURED_NOT_RENDER_VERIFIED' and observation['poseControl'] in ('NOT_RUN','UI_MOUSE_REQUESTED_NOT_VALUE_VERIFIED')
                and len(observation['frames'])==3,'observation scope')
        import struct
        for i,frame in enumerate(observation['frames']):
            require(frame['path']==f'viewer-{i}.png','frame identity');p=path(capture/frame['path'])
            require(0<p.stat().st_size<=16*1024*1024 and file_digest(p)==frame['sha256'],'frame digest/budget')
            with p.open('rb') as f:header=f.read(24)
            require(header[:8]==b'\x89PNG\r\n\x1a\n' and header[12:16]==b'IHDR','frame PNG')
            width,height=struct.unpack('>II',header[16:24]);require([width,height]==[frame['width'],frame['height']] and 0<width<=4096 and 0<height<=4096,'frame dimensions')
            text=path(capture/f'components-{i}.txt');require(0<text.stat().st_size<=4*1024*1024,'component inventory budget')
        require(preliminary['normalExit'] is True and details['launcherExit']==0 and details['displayStopped'] is True
                and runner_exit==0 and proof['kernelProof']['cgroupKillWritten'] is False,'unforced official Viewer exit')
        drain=json.loads((task/'process-drain.json').read_text())
        require(all(drain[k]==v for k,v in identity.items()) and drain['cgroupPath']==proof['cgroupPath']
                and drain['drained'] is True and drain['signalsSent'] is False and len(drain['samples'])>=2
                and all(s['processes']==[] for s in drain['samples'][-2:]),'natural helper exit')
        checks=dict(bundleManifestSha256=file_digest(task/'bundle/bundle.json'),modelMocSha256=source['files']['model/model.moc3']['sha256'],
                    captureSha256=file_digest(capture/'observation.json'),viewerJarSha256=EDITOR_SHA,officialRenderingVerified=False)
        passed=True
    except (OSError,ValueError,KeyError,TypeError,QueueError) as failure:error=str(failure)
    output=path(directory/'evidence');output.mkdir(parents=True,exist_ok=True)
    names=['launch.json','viewer-launch.cmd','launcher.log','display.log','process-drain.json','capture/launch.json','capture/observation.json','capture/failure.txt']
    names += [f'capture/viewer-{i}.png' for i in range(3)]+[f'capture/components-{i}.txt' for i in range(3)]
    for name in names:
        p=path(task/name)
        if p.is_file():
            require(p.stat().st_size<=16*1024*1024,'evidence budget');dest=output/'standalone-viewer'/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest)
    checks['terminalResult']=dict(passed=passed,scope='OFFICIAL_VIEWER_OBSERVATION_ONLY',error=error)
    terminal=requested or ('succeeded' if passed else 'failed');prefix=task/'prefix'
    if terminal=='succeeded' and prefix.exists():
        require(shutil.rmtree.avoids_symlink_attacks,'safe prefix removal unavailable');shutil.rmtree(prefix)
    result=dict(schemaVersion=1,**identity,cleanup='safe',validationStatus='PASS' if passed else 'FAIL',identityVerified=passed,
                fixtureUnchanged=passed,normalExit=passed,runnerExitCode=runner_exit,containment=proof,terminalState=terminal,
                finalizedBy='contained-supervisor',details=dict(cleanupOwner='supervisor',taskDir=str(task),taskOwnedCleanup=True,
                                                               prefixRetained=prefix.exists(),postContainmentChecks=checks))
    atomic_json(output/'lifecycle-result.json',result);return result

def prepare(argv):
    p=argparse.ArgumentParser();p.add_argument('version');p.add_argument('label');p.add_argument('--prepare-dir',type=Path);p.add_argument('--dry-run',action='store_true');a=p.parse_args(argv)
    env={'--bundle-root':'TURBOISM_VIEWER_BUNDLE','--golden-prefix':'TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX','--host-root':'TURBOISM_HOST_VALIDATION_HOST_ROOT',
         '--proton-wrapper':'TURBOISM_HOST_VALIDATION_PROTON_WRAPPER','--proton-runner':'TURBOISM_HOST_VALIDATION_PROTON_RUNNER'}
    args=['--name','official-viewer','--version',a.version,'--transport','local','--run-label',a.label]
    for flag,key in env.items():require(os.environ.get(key),'missing '+key);args.extend([flag,os.environ[key]])
    options(args);bundle(path(os.environ['TURBOISM_VIEWER_BUNDLE']))
    require(a.dry_run or a.prepare_dir is not None,'direct launch forbidden')
    request=dict(schemaVersion=1,argv=args,environment={})
    if a.prepare_dir:atomic_json(a.prepare_dir/'runner-request.json',request)
    else:print(json.dumps(request))

if __name__=='__main__':
    try:
        if sys.argv[1:2]==['prepare']:prepare(sys.argv[2:])
        else:sys.exit(run(sys.argv[1:]))
    except (QueueError,OSError,ValueError,subprocess.SubprocessError) as error:
        print(str(error),file=sys.stderr);sys.exit(1)
