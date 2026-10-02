#!/usr/bin/env python3
"""Execute compiled production borrowed-edge weave with owned eligibility; no live admission claim."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


WRAPPER = r'''import java.lang.reflect.*;
import java.security.*;
import java.util.*;
import org.objectweb.asm.*;
final class BorrowedEdgeBytecodePrototype {
 static final String P="com/live2d/graphics3d/editableMesh/triangulation/";
 static final String H=P+"h",J=P+"j",K=P+"k",TL=P+"TriangleList";
 static final String DESC="(L"+TL+";L"+K+";)L"+TL+";";
 static final String CONTROL="BorrowedEdgeNativeSelfCheck$Control";
 private BorrowedEdgeBytecodePrototype() {}
 static String sha(byte[] bytes)throws Exception {
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
 }
 static byte[] patchShape(byte[] bytes,boolean candidate)throws Exception { return patch(bytes,candidate); }
 static byte[] patch(byte[] bytes,boolean candidate)throws Exception {
  byte[] selected=bytes;
  if(candidate) {
   Class<?> owner=Class.forName("dev.turboism.adapter.cubism.mesh.TriangulationBorrowedEdgePatcher");
   Method patch=owner.getDeclaredMethod("patch",byte[].class);patch.setAccessible(true);
   try { selected=(byte[])patch.invoke(null,(Object)bytes); }
   catch(InvocationTargetException failure) {
    if(failure.getCause() instanceof RuntimeException cause)throw cause;
    if(failure.getCause() instanceof Error cause)throw cause;
    throw failure;
   }
  }
  ClassReader reader=new ClassReader(selected);ClassWriter out=new ClassWriter(reader,0);
  reader.accept(new ClassVisitor(Opcodes.ASM9,out) {
   @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] errors) {
    MethodVisitor original=super.visitMethod(access,name,desc,sig,errors);
    if(!name.equals("a")||!desc.equals(DESC))return original;
    return new MethodVisitor(Opcodes.ASM9,original) {
     int pops;
     @Override public void visitVarInsn(int opcode,int local){pops=0;super.visitVarInsn(opcode,local);}
     @Override public void visitJumpInsn(int opcode,Label label){pops=0;super.visitJumpInsn(opcode,label);}
     @Override public void visitLdcInsn(Object value){pops=0;super.visitLdcInsn(value);}
     @Override public void visitTypeInsn(int opcode,String type){pops=0;super.visitTypeInsn(opcode,type);}
     @Override public void visitInsn(int opcode) {
      if(candidate&&opcode==Opcodes.ICONST_1&&pops==3)
       super.visitMethodInsn(Opcodes.INVOKESTATIC,CONTROL,"bypass","()V",false);
      pops=opcode==Opcodes.POP?pops+1:0;super.visitInsn(opcode);
     }
     @Override public void visitMethodInsn(int opcode,String owner,String called,String descriptor,boolean itf) {
      pops=0;
      if(owner.equals("dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge")) {
       if(called.equals("enter")) {
        super.visitInsn(Opcodes.POP);
        super.visitMethodInsn(opcode,CONTROL,"enter","()Ljava/lang/AutoCloseable;",false);
       } else super.visitMethodInsn(opcode,CONTROL,called,descriptor,itf);
      } else {
       if(opcode==Opcodes.INVOKEVIRTUAL&&owner.equals(K)&&called.equals("a")&&descriptor.equals("(L"+J+";Z)Z"))
        super.visitMethodInsn(Opcodes.INVOKESTATIC,CONTROL,"nativeAttempt","()V",false);
       super.visitMethodInsn(opcode,owner,called,descriptor,itf);
      }
     }
    };
   }
  },0);
  return out.toByteArray();
 }
}
'''


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for profile in ('5203', '5302', '5303'):
        parser.add_argument('--jar-' + profile, required=True, type=Path)
    parser.add_argument('--runtime-classes', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    root = Path.cwd()
    authority = root / 'runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java'
    preparation = root / 'runtime/src/main/java/dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgePreparation.java'
    jars = {profile: getattr(args, 'jar_' + profile).resolve() for profile in ('5203', '5302', '5303')}
    kotlin_pin = re.search(r'KOTLIN_SHA = "([a-f0-9]{64})"', preparation.read_text())[1]
    for profile, jar in jars.items():
        name = {'5203': 'CUBISM_5_2_03', '5302': 'CUBISM_5_3_02', '5303': 'CUBISM_5_3_03'}[profile]
        match = re.search(r'\b' + name + r'\s*=\s*new HostArtifactDigest\(([\d_]+)L,\s*"([a-f0-9]{64})"\)', authority.read_text())
        if not match or jar.stat().st_size != int(match[1].replace('_', '')) or sha(jar) != match[2]:
            raise ValueError('official artifact mismatch ' + profile)
        if sha(jar.parent / 'kotlin-stdlib-1.7.21.jar') != kotlin_pin:
            raise ValueError('Kotlin mismatch ' + profile)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    sources = output / 'sources'
    sources.mkdir()
    classes = output / 'classes'
    classes.mkdir()
    base = Path(__file__).resolve().parent
    for name in ('BorrowedEdgeBytecodePrototype.java', 'BorrowedEdgeNativeSelfCheck.java'):
        shutil.copy2(base / name, sources / name)
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1/org.ow2.asm'
    asm = next((cache / 'asm/9.7.1').glob('*/*.jar'))
    tree = next((cache / 'asm-tree/9.7.1').glob('*/*.jar'))
    runtime = args.runtime_classes.resolve()
    cp = os.pathsep.join(map(str, (runtime, asm, tree)))
    (sources / 'BorrowedEdgeBytecodePrototype.java').write_text(WRAPPER)
    compiled = ['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', cp,
                '-d', str(classes), *map(str, sorted(sources.glob('*.java')))]
    run = subprocess.run(compiled, text=True, capture_output=True, timeout=60)
    (output / 'compile.txt').write_text(run.stdout + run.stderr)
    if run.returncode:
        raise ValueError(run.stdout + run.stderr)
    argv = ['java', '-Djava.awt.headless=true', '-Xverify:all', '-cp', str(classes) + os.pathsep + cp,
            'BorrowedEdgeNativeSelfCheck', *map(str, jars.values())]
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    run = subprocess.run(argv, env=env, text=True, capture_output=True, timeout=180)
    (output / 'native.txt').write_text(run.stdout + run.stderr)
    passed = run.returncode == 0 and 'FULL_BORROWED_NATIVE_FINISHED' in run.stdout
    pins = {}
    for path in [authority, preparation, Path(__file__).resolve(), asm, tree,
                 *[base / name for name in ('BorrowedEdgeBytecodePrototype.java', 'BorrowedEdgeNativeSelfCheck.java')],
                 *sources.glob('*.java'), *classes.rglob('*.class'), *jars.values(),
                 *[p.parent / 'kotlin-stdlib-1.7.21.jar' for p in jars.values()],
                 output / 'compile.txt', output / 'native.txt',
                 *runtime.glob('dev/turboism/adapter/cubism/mesh/TriangulationBorrowedEdgePatcher*.class'),
                 *runtime.glob('dev/turboism/adapter/cubism/mesh/TriangulationDefinitionFingerprint*.class')]:
        pins[str(path)] = sha(path)
    report = {'scope': 'COMPILED_PRODUCTION_PATCHER_OWNED_ELIGIBILITY_NO_ADMISSION_OR_HOST_CLAIM',
              'passed': passed, 'exit': run.returncode, 'compile': compiled, 'argv': argv, 'pins': pins}
    (output / 'review.json').write_text(json.dumps(report, indent=2) + '\n')
    print(run.stdout + run.stderr, end='')
    if not passed:
        raise ValueError('owned complete method controls failed')


if __name__ == '__main__':
    main()
