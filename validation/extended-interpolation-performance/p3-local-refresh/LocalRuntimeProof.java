package dev.turboism.validation.local;
import com.live2d.cubism.doc.model.ACForm;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.ExtendedInterpolationType;
import dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge;
import java.util.*;
/** Production installer/bridge with an independent observer of the official final notification setter. */
public final class LocalRuntimeProof {
 record Snapshot(int[] state,List<Integer> trace) {}
 static Snapshot refresh(LocalRefreshProof.Fixture fixture,boolean candidate) {
  NativeMeshInterpolationBridge.enabled(candidate);
  LocalRefreshProof.ordinals.clear();List<ACForm> forms=LocalRefreshProof.all(fixture);
  for(int i=0;i<forms.size();i++)LocalRefreshProof.ordinals.put(forms.get(i),i);
  LocalRefreshProof.trace=new ArrayList<>();fixture.extension().updateInterpolatedForms(true);
  Snapshot result=new Snapshot(LocalRefreshProof.snapshot(LocalRefreshProof.all(fixture),true),List.copyOf(LocalRefreshProof.trace));
  LocalRefreshProof.trace=null;LocalRefreshProof.ordinals.clear();return result;
 }
 static List<?>[] slice(LocalRefreshProof.Fixture f)throws Exception {
  var field=f.extension().getClass().getDeclaredField("normalExtendedParameters");field.setAccessible(true);
  Object axis=((List<?>)field.get(f.extension())).get(0);var g=axis.getClass().getMethod("g");g.setAccessible(true);
  Object comb=((List<?>)g.invoke(axis)).get(0);var c=comb.getClass().getMethod("c");c.setAccessible(true);var d=comb.getClass().getMethod("d");d.setAccessible(true);
  var e=axis.getClass().getMethod("e");e.setAccessible(true);return new List<?>[]{(List<?>)d.invoke(comb),(List<?>)c.invoke(comb),(List<?>)e.invoke(axis)};
 }
 @SuppressWarnings({"unchecked","rawtypes"})
 static void guards()throws Exception {
  for(boolean morph:new boolean[]{false,true}) {
   NativeMeshInterpolationBridge.enabled(false);var f=LocalRefreshProof.fixture(1,2,ExtendedInterpolationType.SNS_CURVE,2,16);var slice=slice(f);
   if(morph)f.owner().getKeyformMorphTargets().add(new com.live2d.cubism.doc.model.morphTarget.KeyFormMorphTarget());
   else {
    Object source=slice[1].get(0),target=slice[0].get(1);
    Object color=source.getClass().getMethod("getMultiplyColor").invoke(source);
    target.getClass().getMethod("setMultiplyColor",color.getClass()).invoke(target,color);
   }
   NativeMeshInterpolationBridge.enabled(true);var before=NativeMeshInterpolationBridge.localRefreshStatistics();
   for(int i=0;i<12;i++)f.extension().updateInterpolatedForms_common((List)slice[0],(List)slice[1],(List)slice[2],ExtendedInterpolationType.SNS_CURVE,1f);
   var after=NativeMeshInterpolationBridge.localRefreshStatistics();
   if(after.hits()!=before.hits()||after.misses()!=before.misses())throw new AssertionError("local guard failed morph="+morph);
   System.out.println("LOCAL_GUARD PASS "+(morph?"MORPH":"SHARED_COLOR"));
  }
 }
 static List<java.lang.ref.WeakReference<?>> warmAndDrop()throws Exception {
  NativeMeshInterpolationBridge.enabled(false);var f=LocalRefreshProof.fixture(1,2,ExtendedInterpolationType.SNS_CURVE,2,16);
  long hits=NativeMeshInterpolationBridge.localRefreshStatistics().hits();
  for(int i=0;i<12;i++)refresh(f,true);
  if(NativeMeshInterpolationBridge.localRefreshStatistics().hits()<=hits||NativeMeshInterpolationBridge.localRefreshStatistics().entries()==0)throw new AssertionError("no retained fixed point for collection proof");
  var refs=new ArrayList<java.lang.ref.WeakReference<?>>();refs.add(new java.lang.ref.WeakReference<>(f.owner()));refs.add(new java.lang.ref.WeakReference<>(f.extension()));
  for(var form:LocalRefreshProof.all(f))refs.add(new java.lang.ref.WeakReference<>(form));return refs;
 }
 public static void main(String[] args)throws Exception {
  try {
   int cases=0,checks=0;System.setProperty("turboism.meshInterpolation.localRefresh","true");
   for(int kind=0;kind<3;kind++)for(int axes:new int[]{2,3})for(var type:List.of(ExtendedInterpolationType.ELLIPSE,ExtendedInterpolationType.SNS_CURVE)) {
    NativeMeshInterpolationBridge.enabled(false);
    var official=LocalRefreshProof.fixture(kind,axes,type,2,kind==2?1:16);var candidate=LocalRefreshProof.fixture(kind,axes,type,2,kind==2?1:16);
    List<Snapshot> expected=new ArrayList<>();long accepted=NativeMeshInterpolationBridge.acceptedCalls();
    for(int step=0;step<30;step++){LocalRefreshProof.edit(official,step);expected.add(refresh(official,false));}
    if(accepted!=NativeMeshInterpolationBridge.acceptedCalls())throw new AssertionError("native oracle used bridge");
    long hits=NativeMeshInterpolationBridge.localRefreshStatistics().hits();
    for(int step=0;step<30;step++){
     LocalRefreshProof.edit(candidate,step);long before=NativeMeshInterpolationBridge.localRefreshStatistics().hits();Snapshot actual=refresh(candidate,true);
     if(!Arrays.equals(expected.get(step).state,actual.state)||!expected.get(step).trace.equals(actual.trace))throw new AssertionError("production local mismatch kind="+kind+" axes="+axes+" type="+type+" step="+step+" stats="+NativeMeshInterpolationBridge.localRefreshStatistics());
     System.out.println("LOCAL_INSTALLED_STEP kind="+kind+" axes="+axes+" type="+type.name()+" step="+step+" skipped="+(NativeMeshInterpolationBridge.localRefreshStatistics().hits()-before));checks++;
    }
    if(NativeMeshInterpolationBridge.localRefreshStatistics().hits()==hits)throw new AssertionError("no production local hits kind="+kind+" binding schema?");
    cases++;
   }
   guards();
   List<java.lang.ref.WeakReference<?>> refs=warmAndDrop();
   for(int i=0;i<20&&refs.stream().anyMatch(r->r.get()!=null);i++){System.gc();Thread.sleep(50);}
   if(refs.stream().anyMatch(r->r.get()!=null))throw new AssertionError("local cache retained owner/form");
   System.out.println("LOCAL_COLLECTED count="+refs.size());
   if(LocalRefreshAgent.setterPatched!=1||LocalRefreshAgent.commonPatched!=0)throw new AssertionError("observer scope");
   if(NativeMeshInterpolationBridge.localRefreshStatistics().bytes()>NativeMeshInterpolationBridge.localRefreshStatistics().byteBudget())throw new AssertionError("budget");
   dev.turboism.diagnostic.extended.InterpolationProofAgent.verifyPatched();
   NativeMeshInterpolationBridge.enabled(false);
   if(NativeMeshInterpolationBridge.localRefreshStatistics().bytes()!=0)throw new AssertionError("disable retained local state");
   System.out.println("LOCAL_INSTALLED PASS cases="+cases+" snapshots="+checks+" numericalStateAbsoluteVersionsSetterOrder=true stats="+NativeMeshInterpolationBridge.localRefreshStatistics());System.exit(0);
  }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
 }
}
