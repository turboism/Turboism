#!/usr/bin/env python3
"""Execute compiled production angle helper/weave with owned leases; no Editor/admission claim."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-classes', required=True, type=Path)
    parser.add_argument('--jar-5203', required=True, type=Path)
    parser.add_argument('--jar-5302', required=True, type=Path)
    parser.add_argument('--jar-5303', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    root = Path.cwd()
    base = root / 'validation/triangulation-tlindex/diagnostic'
    classes = args.runtime_classes.resolve()
    jars = {'5203': args.jar_5203.resolve(), '5302': args.jar_5302.resolve(), '5303': args.jar_5303.resolve()}
    authority = root / 'runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java'
    text = authority.read_text()
    for profile, jar in jars.items():
        name = {'5203': 'CUBISM_5_2_03', '5302': 'CUBISM_5_3_02', '5303': 'CUBISM_5_3_03'}[profile]
        match = re.search(r'\b' + name + r'\s*=\s*new HostArtifactDigest\(([\d_]+)L,\s*"([a-f0-9]{64})"\)', text)
        if not match or jar.stat().st_size != int(match[1].replace('_', '')) or sha(jar) != match[2]:
            raise ValueError('official artifact mismatch ' + profile)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    generated = output / 'sources'; generated.mkdir()
    destination = output / 'classes'; destination.mkdir()
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1/org.ow2.asm'
    asm = next((cache / 'asm/9.7.1').glob('*/*.jar'))
    tree = next((cache / 'asm-tree/9.7.1').glob('*/*.jar'))
    cp = os.pathsep.join(map(str, (classes, asm, tree)))
    math = generated / 'AngleGuardMath.java'
    math.write_text('''final class AngleGuardMath {
 private AngleGuardMath() {}
 static boolean reject(float cross,float dot) {
  return dev.turboism.adapter.cubism.mesh.TriangulationAngleGuard.reject(cross,dot);
 }
}
''')
    wrapper = generated / 'AngleGuardBytecodePrototype.java'
    wrapper.write_text('''import java.lang.reflect.*;
import java.security.*;
import java.util.*;
import org.objectweb.asm.*;
final class AngleGuardBytecodePrototype {
 static final String P="com/live2d/graphics3d/editableMesh/triangulation/";
 static final String H=P+"h",R=P+"r",V="com/live2d/graphics3d/type/GVector2";
 private AngleGuardBytecodePrototype() {}
 static String sha(byte[] bytes) throws Exception {
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
 }
 static byte[] patchShape(byte[] bytes) throws Exception { return patch(bytes); }
 static byte[] patch(byte[] bytes) throws Exception {
  Class<?> type=Class.forName("dev.turboism.adapter.cubism.mesh.TriangulationAngleGuardPatcher");
  Method patch=type.getDeclaredMethod("patch",byte[].class); patch.setAccessible(true);
  byte[] production;
  try { production=(byte[])patch.invoke(null,(Object)bytes); }
  catch(InvocationTargetException error) {
   if(error.getCause() instanceof RuntimeException cause) throw cause;
   if(error.getCause() instanceof Error cause) throw cause;
   throw error;
  }
  ClassReader reader=new ClassReader(production); ClassWriter out=new ClassWriter(reader,0);
  reader.accept(new ClassVisitor(Opcodes.ASM9,out) {
   @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] errors) {
    MethodVisitor original=super.visitMethod(access,name,desc,sig,errors);
    if(!name.equals("d")||!desc.equals("()V")) return original;
    return new MethodVisitor(Opcodes.ASM9,original) {
     @Override public void visitMethodInsn(int opcode,String owner,String called,String descriptor,boolean itf) {
      if(owner.equals("dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge")) {
       if(called.equals("enter")) {
        super.visitInsn(Opcodes.POP);
        super.visitMethodInsn(opcode,"AngleGuardNativeSelfCheck$Control","enter","()Ljava/lang/AutoCloseable;",false);
       } else super.visitMethodInsn(opcode,"AngleGuardNativeSelfCheck$Control",called,descriptor,itf);
      } else if(owner.equals("dev/turboism/adapter/cubism/mesh/TriangulationAngleGuard")) {
       super.visitMethodInsn(opcode,"AngleGuardNativeSelfCheck$Control",called,descriptor,itf);
      } else super.visitMethodInsn(opcode,owner,called,descriptor,itf);
     }
    };
   }
  },0);
  return out.toByteArray();
 }
}
''')
    # Preserve the original T055 controls; substitute only the compiled scalar predicate.
    original = (base / 'AngleRejectionSelfCheck.java').read_text()
    before = '''        float cross = ax * by - ay * bx;
        float dot = ax * bx + ay * by;
        if (!Float.isFinite(cross) || !Float.isFinite(dot)) return false;
        if (Math.abs(cross) < Float.MIN_NORMAL || Math.abs(dot) < Float.MIN_NORMAL) return false;
        if (dot < 0.0f) return true;
        // A factor-two margin leaves threshold rounding and signed-zero near rays native.
        return Math.abs((double) cross) > (double) dot * 2.0e-6;'''
    if original.count(before) != 1:
        raise ValueError('owned scalar control source drift')
    predicate = generated / 'ProductionAnglePredicateSelfCheck.java'
    predicate.write_text(original.replace(before, '''        return dev.turboism.adapter.cubism.mesh.TriangulationAngleGuard.reject(
                ax * by - ay * bx, ax * bx + ay * by);''').replace(
                    'AngleRejectionSelfCheck', 'ProductionAnglePredicateSelfCheck'))
    sources = [math, wrapper, predicate, base / 'AngleGuardNativeSelfCheck.java', base / 'AngleGuardCostSelfCheck.java']
    env = {key: value for key, value in os.environ.items() if key not in
           ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
    compile_args = ['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', cp, '-d', str(destination)]
    compile_args += list(map(str, sources))
    subprocess.run(compile_args, check=True, env=env)
    runtime_cp = str(destination) + os.pathsep + cp
    commands = {}
    for name, target, inputs in [
        ('native', 'AngleGuardNativeSelfCheck', list(map(str, jars.values()))),
        ('predicate', 'ProductionAnglePredicateSelfCheck', [arg for pair in jars.items() for arg in (pair[0], str(pair[1]))]),
        ('scalar-adaptive', 'AngleGuardCostSelfCheck', ['--adaptive']),
    ]:
        command = ['java', '-ea', '-Xverify:all', '-cp', runtime_cp, target] + inputs
        commands[name] = command
        with (output / (name + '.txt')).open('w') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, env=env)
    if 'FULL_NATIVE_D_FINISHED' not in (output / 'native.txt').read_text():
        raise ValueError('missing complete native controls')
    if 'ANGLE_REJECTION_FINISHED' not in (output / 'predicate.txt').read_text():
        raise ValueError('missing complete scalar controls')
    paths = [authority, Path(__file__).resolve(), base / 'AngleRejectionSelfCheck.java', asm, tree] + sources
    paths += list(jars.values()) + [jar.parent / 'kotlin-stdlib-1.7.21.jar' for jar in jars.values()]
    paths += list(destination.rglob('*.class')) + [output / (name + '.txt') for name in commands]
    for name in ('TriangulationAngleGuard', 'TriangulationAngleGuardPatcher', 'TriangulationAngleGuardPatcher$Guard',
                 'TriangulationDefinitionFingerprint'):
        paths.append(classes / ('dev/turboism/adapter/cubism/mesh/' + name + '.class'))
    production = root / 'runtime/src/main/java/dev/turboism/adapter/cubism/mesh'
    paths += [production / name for name in ('TriangulationAngleGuard.java', 'TriangulationAngleGuardPatcher.java')]
    result = dict(scope='Compiled production helper/weave with owned substitute lease and identical native observer; '
                        'not genuine admission or host performance', commands=commands,
                  pins={str(path): sha(path) for path in paths}, editor_launched=False,
                  limitations=['Scalar cost excludes lease/allocations/getters', 'No actual live dependency gate exercised'])
    (output / 'review.json').write_text(json.dumps(result, indent=2) + '\n')
    print((output / 'native.txt').read_text(), end='')
    print((output / 'predicate.txt').read_text(), end='')


if __name__ == '__main__':
    main()
