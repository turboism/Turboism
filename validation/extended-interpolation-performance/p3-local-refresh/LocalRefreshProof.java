package dev.turboism.validation.local;
import com.live2d.cubism.doc.model.*;
import com.live2d.cubism.doc.model.drawable.artMesh.*;
import com.live2d.cubism.doc.model.deformer.warp.*;
import com.live2d.cubism.doc.model.deformer.rotation.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import com.live2d.type.CFloatColor;
import java.lang.reflect.*;
import java.util.*;
/** Experimental per-slice memo: intentionally retains fixtures; NEVER shipped with runtime. */
public final class LocalRefreshProof {
 record Fixture(ACParameterControllableSource owner,CExtendedInterpolationExtension extension) {}
 record Entry(Object[] identities,int[] input,int[] notifications) {}
 static final IdentityHashMap<Object,Entry> memo=new IdentityHashMap<>();
 static final IdentityHashMap<Object,Integer> ordinals=new IdentityHashMap<>();
 static List<Integer> trace; static boolean enabled; static long skipped,computed,localComputed,nativeComputed;
 static Pending pending;
 record Pending(Object key,List<?> targets,Object[] identities,int[] input,List<Integer> notifications) {}
 public static boolean enter(Object ext,List<?> targets,List<?> sources,List<?> keys,Object type,float scale) {
  if(trace==null)return false;
  if(!((CExtendedInterpolationExtension)ext).getOwner().getKeyformMorphTargets().isEmpty() || !((CExtendedInterpolationExtension)ext).getMorphTargetExtendedKeyforms().isEmpty())throw new AssertionError("Morph outside experiment admission");
  if(pending!=null)throw new AssertionError("nested common");
  List<Object> ids=new ArrayList<>();ids.add(ext);ids.add(type);ids.addAll(targets);ids.addAll(sources);ids.addAll(keys);
  for(Object form:targets)references(ids,form);for(Object form:sources)references(ids,form);
  Object[] identities=ids.toArray();int[] input=signature(targets,sources,keys,type,scale);
  Entry entry=memo.get(targets);
  if(enabled&&entry!=null&&same(identities,entry.identities)&&Arrays.equals(input,entry.input)) {
   skipped++;
   if(!Boolean.getBoolean("local.proof.omitNotifications"))for(int index:entry.notifications)((ACForm)targets.get(index)).setUpdated();
   return true;
  }
  computed++;if(enabled)localComputed++;else nativeComputed++;pending=new Pending(targets,targets,identities,input,new ArrayList<>());return false;
 }
 static void references(List<Object> ids,Object form) {
  try {for(Class<?> c=form.getClass();c!=Object.class;c=c.getSuperclass())for(Field f:c.getDeclaredFields()) {
   if(Modifier.isStatic(f.getModifiers()))continue;f.setAccessible(true);Object value=f.get(form);
   if(value instanceof float[] || value instanceof CFloatColor || f.getName().equals("coordType"))ids.add(value);
  }}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
 }
 static boolean same(Object[] a,Object[] b){if(a.length!=b.length)return false;for(int i=0;i<a.length;i++)if(a[i]!=b[i])return false;return true;}
 public static void exit() {
  if(pending==null)return;
  Pending p=pending;pending=null;
  // Admit only if this native invocation left every target value unchanged.
  // This avoids assuming that native common is idempotent or ignores prior targets.
  int[] before=p.input;
  int[] afterTargets=snapshot(p.targets,false);
  if(!Arrays.equals(Arrays.copyOfRange(before,1,1+before[0]),afterTargets)){memo.remove(p.key);return;}
  memo.put(p.key,new Entry(p.identities,before,p.notifications.stream().mapToInt(Integer::intValue).toArray()));
 }
 public static void updated(Object form) {
  if(trace==null)return;
  Integer ordinal=ordinals.get(form);if(ordinal==null)throw new AssertionError("setter outside known bank");trace.add(ordinal);
  if(pending!=null){int index=-1;for(int i=0;i<pending.targets.size();i++)if(pending.targets.get(i)==form){index=i;break;}
   if(index<0)throw new AssertionError("setter outside current targets");pending.notifications.add(index);}
 }
 static int[] signature(List<?> targets,List<?> sources,List<?> keys,Object type,float scale) {
  var words=new ArrayList<Integer>();int[] out=snapshot(targets,false);words.add(out.length);append(words,out);append(words,snapshot(sources,false));
  words.add(Float.floatToRawIntBits(scale));words.add(((Enum<?>)type).ordinal());
  long radians=Double.doubleToRawLongBits(jp.noids.util.ap.c);words.add((int)(radians>>>32));words.add((int)radians);
  for(Object k:keys){var key=(CExtendedInterpolationExtension.b)k;words.add(Float.floatToRawIntBits(key.a()));words.add(key.b()?1:0);words.add(key.c());words.add(Float.floatToRawIntBits(key.d()));}
  return words.stream().mapToInt(Integer::intValue).toArray();
 }
 static void append(List<Integer> words,int[] v){for(int x:v)words.add(x);}
 static int[] snapshot(List<?> forms,boolean versions) {
  try {
   var words=new ArrayList<Integer>();words.add(forms.size());
   for(Object form:forms){
    List<Field> fields=new ArrayList<>();for(Class<?> c=form.getClass();c!=Object.class;c=c.getSuperclass())fields.addAll(List.of(c.getDeclaredFields()));
    fields.sort(Comparator.comparing(f->f.getDeclaringClass().getName()+"."+f.getName()));
    for(Field f:fields){if(Modifier.isStatic(f.getModifiers())||f.getName().equals("instanceNoForDebug")||(!versions&&f.getName().equals("editVersion")))continue;
     f.setAccessible(true);Object v=f.get(form);
     if(v instanceof Float x)words.add(Float.floatToRawIntBits(x));
     else if(v instanceof Integer x)words.add(x);
     else if(v instanceof Boolean x)words.add(x?1:0);
     else if(v instanceof float[] x){words.add(x.length);for(float n:x)words.add(Float.floatToRawIntBits(n));}
     else if(v instanceof CFloatColor x){words.add(Float.floatToRawIntBits(x.getA()));words.add(Float.floatToRawIntBits(x.getR()));words.add(Float.floatToRawIntBits(x.getG()));words.add(Float.floatToRawIntBits(x.getB()));}
     else if(f.getType().isPrimitive())throw new AssertionError("unhandled primitive "+f);
    }
   }
   return words.stream().mapToInt(Integer::intValue).toArray();
  } catch(ReflectiveOperationException e){throw new AssertionError(e);}
 }
 static Fixture fixture(int kind,int axes,ExtendedInterpolationType type,int insert,int vertices)throws Exception {
  Class<?> helper=Class.forName("dev.turboism.validation.p3.P3DependencyProbe");
  Method m=helper.getDeclaredMethod("createFixture",int.class,int.class,ExtendedInterpolationType.class,int.class,int.class,int.class);m.setAccessible(true);
  Object fixture=m.invoke(null,kind,axes,type,insert,vertices,42);
  Field owner=fixture.getClass().getDeclaredField("owner"),extension=fixture.getClass().getDeclaredField("extension");owner.setAccessible(true);extension.setAccessible(true);
  return new Fixture((ACParameterControllableSource)owner.get(fixture),(CExtendedInterpolationExtension)extension.get(fixture));
 }
 static List<ACForm> all(Fixture f){List<ACForm> all=new ArrayList<>((List<ACForm>)(List<?>)f.owner.getKeyforms());all.addAll(f.extension.getNormalExtendedKeyforms());all.addAll(f.extension.getMorphTargetExtendedKeyforms());return all;}
 static List<Integer> refresh(Fixture f,boolean local){
  ordinals.clear();List<ACForm> forms=all(f);for(int i=0;i<forms.size();i++)ordinals.put(forms.get(i),i);
  enabled=local;trace=new ArrayList<>();f.extension.updateInterpolatedForms(true);List<Integer> result=List.copyOf(trace);trace=null;ordinals.clear();return result;
 }
 static void coordinate(ACForm form,float delta){
  if(form instanceof CRotationDeformerForm r)r.setOriginX(r.getOriginX()+delta);
  else if(form instanceof CWarpDeformerForm w)w.getPositions()[0]+=delta;
  else ((CArtMeshForm)form).getPositions()[0]+=delta;
 }
 static void edit(Fixture f,int step)throws Exception {
  List<ACForm> src=(List<ACForm>)(List<?>)f.owner.getKeyforms();ACForm a=src.get(step%src.size());
  switch(step){
   case 3,4,5,6,7,8,9,10 -> {coordinate(a,.125f);a.setUpdated();}
   case 11 -> a.getClass().getMethod("setOpacity",float.class).invoke(a,.375f);
   case 12 -> {Object color=a.getClass().getMethod("getMultiplyColor").invoke(a);((CFloatColor)color).setR(.125f);}
   case 13 -> coordinate(a,-.5f); // no EditVersion, must still invalidate
   case 14 -> coordinate(f.extension.getNormalExtendedKeyforms().get(0),3f);
   case 15 -> {for(var b:f.owner.getKeyformGridSource().getKeyformBindings())b.setExtendedInterpolationScale(-1f);}
   case 16 -> {for(var b:f.owner.getKeyformGridSource().getKeyformBindings())b.setExtendedInterpolationType(b.getExtendedInterpolationType()==ExtendedInterpolationType.ELLIPSE?ExtendedInterpolationType.SNS_CURVE:ExtendedInterpolationType.ELLIPSE);}
   case 17 -> {for(var b:f.owner.getKeyformGridSource().getKeyformBindings())b.setInsertPointCount(1);f.extension.setupFormStructure(true,true);}
   case 18 -> {if(a instanceof CRotationDeformerForm r){r.setAngle(27f);r.setReflectX(!r.isReflectX());}else if(a instanceof CArtMeshForm m)m.setDrawOrder(777);}
  }
 }
 public static void main(String[] args)throws Exception {
  try{
   int cases=0,checks=0;for(int kind=0;kind<3;kind++)for(int axes:new int[]{2,3})for(var type:List.of(ExtendedInterpolationType.ELLIPSE,ExtendedInterpolationType.SNS_CURVE)){
    memo.clear();Fixture nativeBank=fixture(kind,axes,type,2,kind==2?1:16),localBank=fixture(kind,axes,type,2,kind==2?1:16);
    long before=skipped,calc=computed;
    for(int step=0;step<22;step++){
     edit(nativeBank,step);edit(localBank,step);
     long skippedStep=skipped,localStep=localComputed,nativeStep=nativeComputed;
     List<Integer> official=refresh(nativeBank,false),local=refresh(localBank,true);
     System.out.println("LOCAL_STEP kind="+kind+" axes="+axes+" type="+type.name()+" step="+step+" skipped="+(skipped-skippedStep)+" localComputed="+(localComputed-localStep)+" nativeComputed="+(nativeComputed-nativeStep));
     if(!official.equals(local))throw new AssertionError("setter order kind="+kind+" axes="+axes+" type="+type+" step="+step);
     int[] a=snapshot(all(nativeBank),true),b=snapshot(all(localBank),true);
     if(!Arrays.equals(a,b)){int first=0;while(first<Math.min(a.length,b.length)&&a[first]==b[first])first++;throw new AssertionError("state kind="+kind+" axes="+axes+" type="+type+" step="+step+" first="+first+" values="+a[first]+"/"+b[first]);}
     checks++;
    }
    if(skipped==before)throw new AssertionError("no skips");
    System.out.println("LOCAL_CASE kind="+kind+" axes="+axes+" type="+type+" skipped="+(skipped-before)+" computedBothLegs="+(computed-calc));cases++;
   }
   if(LocalRefreshAgent.commonPatched!=1||LocalRefreshAgent.setterPatched!=1)throw new AssertionError("missing instrumentation");
   System.out.println("LOCAL_REFRESH PASS cases="+cases+" snapshots="+checks+" stateRawBitsAbsoluteVersionsSetterOrder=true skipped="+skipped);
   System.exit(0);
  }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
 }
}
