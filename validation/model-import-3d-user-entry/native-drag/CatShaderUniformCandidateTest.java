package dev.turboism.validation.modelimport3d.nativeprobe;
import java.util.*;
public final class CatShaderUniformCandidateTest {
 public static final class Context {
  static Context current;long handle=1;boolean created=true;
  public long getHandle(){return handle;}public boolean isCreated(){return created;}public static Context getCurrent(){return current;}
 }
 public static final class GL {
  Context context=new Context();int calls,epoch;RuntimeException failure;
  public Context getContext(){return context;}
  public int glGetUniformLocation(int program,String name){calls++;if(failure!=null)throw failure;return name==null || name.equals("missing")?-1:program*100+epoch+name.length();}
 }
 public static final class Shader {
  int program=3;HashMap<String,Object> uniforms=new HashMap<>(Map.of("active",1));
  public int getGlProgramID(){return program;}public HashMap<String,Object> getUniformMap(){return uniforms;}
 }
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 public static void main(String[] args)throws Exception {
  var cache=CatNativeDragAgent.UniformCandidate.class;GL gl=new GL();Shader shader=new Shader();Context.current=gl.context;
  CatNativeDragAgent.UniformCandidate.enabled=true;
  for(String name:List.of("active","missing","array[0]","array[3]","array")){
   int expected=gl.glGetUniformLocation(3,name),before=gl.calls;
   for(int i=0;i<20;i++)check(CatNativeDragAgent.UniformCandidate.lookup(gl,3,name,shader)==expected);
   check(gl.calls==before+1);
  }
  gl.epoch=1000;CatNativeDragAgent.UniformCandidate.invalidate(shader);check(CatNativeDragAgent.UniformCandidate.lookup(gl,3,"active",shader)==1306);
  gl.epoch=2000;gl.context.handle=2;check(CatNativeDragAgent.UniformCandidate.lookup(gl,3,"active",shader)==2306);
  gl.context=new Context();Context.current=gl.context;gl.epoch=3000;check(CatNativeDragAgent.UniformCandidate.lookup(gl,3,"active",shader)==3306);
  shader.program=4;check(CatNativeDragAgent.UniformCandidate.lookup(gl,4,"active",shader)==3406);
  int before=gl.calls;shader.uniforms.clear();for(int i=0;i<2;i++)CatNativeDragAgent.UniformCandidate.lookup(gl,4,"active",shader);check(gl.calls==before+2);
  shader.uniforms.put("active",1);Context.current=null;before=gl.calls;for(int i=0;i<2;i++)CatNativeDragAgent.UniformCandidate.lookup(gl,4,"active",shader);check(gl.calls==before+2);
  Context.current=gl.context;gl.context.created=false;before=gl.calls;for(int i=0;i<2;i++)CatNativeDragAgent.UniformCandidate.lookup(gl,4,"active",shader);check(gl.calls==before+2);gl.context.created=true;
  CatNativeDragAgent.UniformCandidate.enabled=false;before=gl.calls;for(int i=0;i<2;i++)CatNativeDragAgent.UniformCandidate.lookup(gl,4,"active",shader);check(gl.calls==before+2);
  var failure=new IllegalStateException("original failure");gl.failure=failure;before=gl.calls;
  try{CatNativeDragAgent.UniformCandidate.lookup(gl,4,"active",shader);throw new AssertionError("lost original failure");}catch(IllegalStateException e){check(e==failure);}check(gl.calls==before+1);
  gl.failure=null;CatNativeDragAgent.UniformCandidate.enabled=true;before=gl.calls;for(int i=0;i<2;i++)CatNativeDragAgent.UniformCandidate.lookup(gl,0,null,shader);check(gl.calls==before+2);
  System.out.println("PASS "+checks+" active/missing/array uniform, reinit/context/handle/program invalidation, inactive/uncreated/empty/disabled fallback and exact exception checks");
 }
}
