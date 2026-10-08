"""Owned SDK differential prototype; no production integration or editor launch."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,os


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    root=Path.cwd();diag=Path(__file__).resolve().parent;out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
    spec=importlib.util.spec_from_file_location('guard',diag/'verify-native-mesh-edge-loop.py');g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
    if g.quiet_jobs():raise RuntimeError('formal performance job present')
    sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
    jars=[];pins={};urls=[]
    for version in ['5203','5302','5303']:
        argv=json.loads((root/f'build/t053-local-builder-r1/metadata-production/on{version}/command.json').read_text())['argv'];jar=next(Path(x) for x in argv[argv.index('-cp')+1].split(os.pathsep) if x.endswith('Live2D_Cubism.jar'));assert sha(jar)==g.PINS[version];jars.append(jar)
        for dependency in sorted(jar.parent.glob('*.jar')):pins[str(dependency)]=sha(dependency)
    cache=Path.home()/'.gradle/caches/modules-2/files-2.1/org.ow2.asm'
    asm=next((cache/'asm/9.7.1').glob('*/*.jar'));tree=next((cache/'asm-tree/9.7.1').glob('*/*.jar'))
    source=diag/'PointTriangleReusePrototype.java'
    for p in [Path(__file__),source,asm,tree,diag/'verify-native-mesh-edge-loop.py']:pins[str(p)]=sha(p)
    classes=out/'classes';classes.mkdir();env=dict(os.environ)
    for k in ['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JDK_JAVAC_OPTIONS','CLASSPATH']:env.pop(k,None)
    cp=os.pathsep.join(map(str,[asm,tree]))
    g.guarded(['javac','--release','17','-Xlint:all','-Werror','-cp',cp,'-d',str(classes),str(source)],out/'compile.log',env)
    g.guarded(['java','-Xverify:all','-XX:+DisableAttachMechanism','-Djava.awt.headless=true','-cp',os.pathsep.join([str(classes),cp]),'PointTriangleReusePrototype',*map(str,jars)],out/'execution.log',env)
    console=(out/'execution.log').read_text();assert console.count('POINT_TRIANGLE_OWNED_REUSE_PASS ')==3 and 'POINT_TRIANGLE_OWNED_REUSE_FINISHED ' in console
    for p,h in pins.items():assert sha(p)==h,p
    report=dict(status='PASS_OWNED_THREE_SDK_DIFFERENTIAL_ONLY',productionChanged=False,editorLaunched=False,hostPerformanceAcceptance='NOT_GRANTED',inputPins=pins,outputPins={str(p.relative_to(root)):sha(p) for p in out.rglob('*') if p.is_file()},limitations=['Owned loader changes only nine temporary construction sites; arithmetic and barycentric calls remain native.','Constructor counter instruments FF constructor in both owned arms only; constructor reduction is not host speed or RSS.','Actual callback/escape closure and reviewed production admission/lifecycle still require independent proof.','General allocation failure timing, concurrent mutation and full UI workflows are not established by deterministic fixtures.'])
    (out/'review.json').write_text(json.dumps(report,indent=2)+'\n');print(report['status']);print('\n'.join(line for line in console.splitlines() if line.startswith('POINT_TRIANGLE_')))


if __name__=='__main__':main()
