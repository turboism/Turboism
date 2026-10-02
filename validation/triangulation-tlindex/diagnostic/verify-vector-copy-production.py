#!/usr/bin/env python3
"""Compiled production vector-copy and angle composition with owned nullable lease; no admission claim."""
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
final class VectorCopyBytecodePrototype {
 static final String P="com/live2d/graphics3d/editableMesh/triangulation/";
 static final String H=P+"h",R=P+"r",V="com/live2d/graphics3d/type/GVector2";
 private static final String CONTROL="VectorCopyNativeSelfCheck$Control";
 private VectorCopyBytecodePrototype() {}
 static String sha(byte[] bytes)throws Exception {
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
 }
 static byte[] patchShape(byte[] bytes)throws Exception { return patch(bytes,true); }
 static byte[] patch(byte[] bytes)throws Exception { return patch(bytes,true); }
 static byte[] invoke(String name,byte[] bytes)throws Exception {
  Class<?> type=Class.forName("dev.turboism.adapter.cubism.mesh."+name);
  Method patch=type.getDeclaredMethod("patch",byte[].class);patch.setAccessible(true);
  try { return (byte[])patch.invoke(null,(Object)bytes); }
  catch(InvocationTargetException error) {
   if(error.getCause() instanceof RuntimeException cause)throw cause;
   if(error.getCause() instanceof Error cause)throw cause;
   throw error;
  }
 }
 static byte[] patch(byte[] bytes,boolean candidate)throws Exception {
  byte[] production=invoke("TriangulationAngleGuardPatcher",bytes);
  if(candidate)production=invoke("TriangulationVectorCopyPatcher",production);
  ClassReader reader=new ClassReader(production);ClassWriter out=new ClassWriter(reader,0);
  reader.accept(new ClassVisitor(Opcodes.ASM9,out) {
   @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] errors) {
    MethodVisitor original=super.visitMethod(access,name,desc,sig,errors);
    if(!name.equals("d")||!desc.equals("()V"))return original;
    return new MethodVisitor(Opcodes.ASM9,original) {
     boolean afterMinus;
     @Override public void visitVarInsn(int opcode,int local) {
      if(candidate&&afterMinus&&opcode==Opcodes.ASTORE&&(local==11||local==12))
       super.visitMethodInsn(Opcodes.INVOKESTATIC,CONTROL,"elidedCopy","()V",false);
      afterMinus=false;super.visitVarInsn(opcode,local);
     }
     @Override public void visitMethodInsn(int opcode,String owner,String called,String descriptor,boolean itf) {
      if(owner.equals(V)&&called.equals("<init>")&&descriptor.equals("(L"+V+";)V"))
       super.visitMethodInsn(Opcodes.INVOKESTATIC,CONTROL,"nativeCopy","()V",false);
      afterMinus=owner.equals(P+"TriPoint")&&called.equals("minus");
      if(owner.equals("dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge")) {
       if(called.equals("enter")) {
        super.visitInsn(Opcodes.POP);
        super.visitMethodInsn(opcode,CONTROL,"enter","()Ljava/lang/AutoCloseable;",false);
       } else super.visitMethodInsn(opcode,CONTROL,called,descriptor,itf);
      } else if(owner.equals("dev/turboism/adapter/cubism/mesh/TriangulationAngleGuard"))
       super.visitMethodInsn(opcode,CONTROL,called,descriptor,itf);
      else super.visitMethodInsn(opcode,owner,called,descriptor,itf);
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
    parser.add_argument('--runtime-source', type=Path,
                        default=Path('runtime/src/main/java/dev/turboism/adapter/cubism/mesh'),
                        help='Mesh source directory; use archived T063 snapshot after withdrawal')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    root = Path.cwd()
    authority = root / 'runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java'
    mesh_source = args.runtime_source.resolve()
    preparation = mesh_source / 'LazyTriangulationEdgePreparation.java'
    if not (mesh_source / 'TriangulationVectorCopyPatcher.java').is_file():
        raise ValueError('T063 integration withdrawn; supply archived --runtime-source and --runtime-classes')
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
    for name in ('AngleGuardBytecodePrototype.java', 'AngleGuardNativeSelfCheck.java', 'AngleGuardMath.java',
                 'VectorCopyBytecodePrototype.java', 'VectorCopyNativeSelfCheck.java', 'VectorCopyIdentitySelfCheck.java'):
        shutil.copy2(base / name, sources / name)
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1/org.ow2.asm'
    asm = next((cache / 'asm/9.7.1').glob('*/*.jar'))
    tree = next((cache / 'asm-tree/9.7.1').glob('*/*.jar'))
    runtime = args.runtime_classes.resolve()
    cp = os.pathsep.join(map(str, (runtime, asm, tree)))
    (sources / 'VectorCopyBytecodePrototype.java').write_text(WRAPPER)
    compiled = ['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', cp,
                '-d', str(classes), *map(str, sorted(sources.glob('*.java')))]
    run = subprocess.run(compiled, text=True, capture_output=True, timeout=60)
    (output / 'compile.txt').write_text(run.stdout + run.stderr)
    if run.returncode:
        raise ValueError(run.stdout + run.stderr)
    argv = ['java', '-Djava.awt.headless=true', '-Xverify:all', '-cp', str(classes) + os.pathsep + cp,
            'VectorCopyNativeSelfCheck', *map(str, jars.values())]
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    run = subprocess.run(argv, env=env, text=True, capture_output=True, timeout=180)
    (output / 'native.txt').write_text(run.stdout + run.stderr)
    passed = run.returncode == 0 and 'FULL_VECTOR_COPY_D_FINISHED' in run.stdout
    identity_argv = argv.copy()
    identity_argv[identity_argv.index('VectorCopyNativeSelfCheck')] = 'VectorCopyIdentitySelfCheck'
    identity = subprocess.run(identity_argv, env=env, text=True, capture_output=True, timeout=180)
    (output / 'identity.txt').write_text(identity.stdout + identity.stderr)
    passed = passed and identity.returncode == 0 and 'VECTOR_COPY_IDENTITY_FINISHED' in identity.stdout
    pins = {}
    for path in [authority, preparation, Path(__file__).resolve(), asm, tree,
                 *[base / name for name in ('AngleGuardBytecodePrototype.java', 'AngleGuardNativeSelfCheck.java', 'AngleGuardMath.java',
                      'VectorCopyBytecodePrototype.java', 'VectorCopyNativeSelfCheck.java', 'VectorCopyIdentitySelfCheck.java')],
                 *sources.glob('*.java'), *classes.rglob('*.class'), *jars.values(),
                 *[p.parent / 'kotlin-stdlib-1.7.21.jar' for p in jars.values()],
                 output / 'compile.txt', output / 'native.txt',
                 output / 'identity.txt']:
        pins[str(path)] = sha(path)
    for path in runtime.glob('dev/turboism/adapter/cubism/mesh/TriangulationVectorCopyPatcher*.class'):
        pins[str(path)] = sha(path)
    for name in ('TriangulationVectorCopyPatcher.java', 'TriangulationAngleGuardPatcher.java', 'TriangulationDefinitionFingerprint.java'):
        path = mesh_source / name
        pins[str(path)] = sha(path)
    report = {'scope': 'COMPILED_PRODUCTION_COMPOSED_D_OWNED_LEASE_NO_ADMISSION_OR_HOST_CLAIM',
              'passed': passed, 'exit': run.returncode, 'compile': compiled, 'argv': argv, 'identityArgv': identity_argv, 'identityExit': identity.returncode, 'pins': pins}
    (output / 'review.json').write_text(json.dumps(report, indent=2) + '\n')
    print(run.stdout + run.stderr + identity.stdout + identity.stderr, end='')
    if not passed:
        raise ValueError('owned complete method controls failed')


if __name__ == '__main__':
    main()
