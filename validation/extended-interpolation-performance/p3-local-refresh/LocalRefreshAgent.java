package dev.turboism.validation.local;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;
/** Validation only: instruments official common entry/exit and the actual final setter. */
public final class LocalRefreshAgent {
 public static int commonPatched, setterPatched;
 public static void premain(String ignored,Instrumentation instrumentation) {
  instrumentation.addTransformer(new ClassFileTransformer() {
   public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes) {
    boolean common=!"setter-only".equals(ignored)&&name.equals("com/live2d/cubism/doc/model/interpolator/extendedInterpolation/CExtendedInterpolationExtension");
    boolean setter=name.equals("com/live2d/cubism/doc/model/ACForm");
    if(!common&&!setter)return null;
    ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
    reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
     public MethodVisitor visitMethod(int access,String method,String desc,String sig,String[] exceptions) {
      MethodVisitor mv=super.visitMethod(access,method,desc,sig,exceptions);
      boolean c=common&&method.equals("updateInterpolatedForms_common")&&desc.equals("(Ljava/util/List;Ljava/util/List;Ljava/util/List;Lcom/live2d/cubism/doc/model/interpolator/extendedInterpolation/ExtendedInterpolationType;F)V");
      boolean s=setter&&method.equals("setUpdated")&&desc.equals("()V");
      if(!c&&!s)return mv;
      if(c)commonPatched++;else setterPatched++;
      return new MethodVisitor(Opcodes.ASM9,mv) {
       public void visitCode() {super.visitCode();
        if(s){mv.visitVarInsn(Opcodes.ALOAD,0);mv.visitMethodInsn(Opcodes.INVOKESTATIC,"dev/turboism/validation/local/LocalRefreshProof","updated","(Ljava/lang/Object;)V",false);return;}
        for(int i=0;i<=4;i++)mv.visitVarInsn(Opcodes.ALOAD,i);
        mv.visitVarInsn(Opcodes.FLOAD,5);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,"dev/turboism/validation/local/LocalRefreshProof","enter","(Ljava/lang/Object;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/lang/Object;F)Z",false);
        Label proceed=new Label();mv.visitJumpInsn(Opcodes.IFEQ,proceed);mv.visitInsn(Opcodes.RETURN);mv.visitLabel(proceed);
       }
       public void visitInsn(int opcode) {if(c&&opcode==Opcodes.RETURN)mv.visitMethodInsn(Opcodes.INVOKESTATIC,"dev/turboism/validation/local/LocalRefreshProof","exit","()V",false);super.visitInsn(opcode);}
      };
     }
    },0);return writer.toByteArray();
   }
  });
 }
}
