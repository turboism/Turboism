package dev.turboism.validation.modelimport3d.nativeprobe;
import java.nio.file.*;import java.util.zip.*;import java.security.*;import org.objectweb.asm.*;
public final class CatShaderUniformTransformerTest {
 public static void main(String[] args)throws Exception {
  byte[] bytes;try(var z=new ZipFile(args[0])){bytes=z.getInputStream(z.getEntry(CatNativeDragAgent.UniformCandidate.OWNER+".class")).readAllBytes();}
  var domain=new ProtectionDomain(new CodeSource(Path.of(args[0]).toUri().toURL(),(java.security.cert.Certificate[])null),null);
  var hook=new CatNativeDragAgent.UniformCandidate.Transformer();byte[] patched=hook.transform(null,CatNativeDragAgent.UniformCandidate.OWNER,null,domain,bytes);if(patched==null)throw new AssertionError("exact shader rejected");
  int[] count=new int[3];new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9){public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){return new MethodVisitor(Opcodes.ASM9){public void visitMethodInsn(int o,String owner,String method,String desc,boolean itf){if(owner.equals(CatNativeDragAgent.UniformCandidate.HELPER)){if(method.equals("lookup"))count[0]++;if(method.equals("invalidate"))count[1]++;}if(method.equals("glGetUniformLocation"))count[2]++;}};}},0);
  if(count[0]!=1 || count[1]!=2 || count[2]!=1)throw new AssertionError(java.util.Arrays.toString(count));
  if(hook.transform(null,CatNativeDragAgent.UniformCandidate.OWNER,String.class,domain,bytes)!=null)throw new AssertionError("redefinition admitted");
  byte[] bad=bytes.clone();bad[bad.length-1]^=1;if(hook.transform(null,CatNativeDragAgent.UniformCandidate.OWNER,null,domain,bad)!=null)throw new AssertionError("changed class admitted");
  if(hook.transform(null,"unrelated/Class",null,domain,bytes)!=null)throw new AssertionError("unrelated owner patched");
  var wrong=new ProtectionDomain(new CodeSource(Path.of(args[1]).toUri().toURL(),(java.security.cert.Certificate[])null),null);
  if(hook.transform(null,CatNativeDragAgent.UniformCandidate.OWNER,null,wrong,bytes)!=null)throw new AssertionError("wrong host artifact admitted");
  System.out.println("PASS exact owner/class/JAR/first definition; one drawing call and two lifecycle hooks; native initialization query retained");
 }
}
