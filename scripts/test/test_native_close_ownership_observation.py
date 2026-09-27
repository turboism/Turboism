"""Synthetic JDK17 tests for the post-close ownership probe. Never loads official Cubism classes."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JDK = Path(os.environ.get('JAVA17_HOME', '/usr/lib/jvm/java-17-openjdk'))
SOURCE = ROOT / 'testing/host-validation/image-archive/src/dev/turboism/validation/NativeCloseOwnershipObservation.java'
MODEL_SOURCE = ('package com.live2d.cubism.doc.model;\n\n'
                '/** Synthetic name/shape stand-in; never the official class. */\n'
                'public final class CModelSource { }\n')
AGENT = ROOT / 'testing/host-validation/image-archive/src/dev/turboism/validation/NativeResourceHostAgent.java'
HARNESS = r'''
package dev.turboism.validation;
import java.io.File;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
public class CloseOwnershipTest {
 static int checks;
 static class Hostile { public int hashCode(){throw new AssertionError("hashCode");}
  public boolean equals(Object x){throw new AssertionError("equals");}
  public String toString(){throw new AssertionError("toString");} }
 static final class Reg extends Hostile { final ArrayList<Object> list=new ArrayList<>(); public ArrayList a(){return list;} }
 static class Entry extends Hostile {
  File file; Object payload; boolean loaded=true; final ArrayList<Object> referrers=new ArrayList<>();
  public File a(){return file;} public Object b(){return payload;} public boolean c(){return loaded;} public List<Object> d(){return referrers;} }
 static final class ModelEntry extends Entry { com.live2d.cubism.doc.model.CModelSource source;
  public com.live2d.cubism.doc.model.CModelSource f(){return source;} }
 static final class OtherEntry extends Entry { public String f(){return "not a source";} }
 static final class Ctrl extends Hostile { Object doc; Object view; final ArrayList<Object> viewContextHistory=new ArrayList<>();
  public Object getCurrentDoc(){return doc;} public Object getCurrentViewContext(){return view;} }
 static final class View extends Hostile { Object doc; public Object getDoc(){return doc;} }
 static final class NoEntries extends Hostile {}
 static final class StaticReg extends Hostile { public static ArrayList a(){return new ArrayList();} }
 static final class NoPayload extends Hostile { public File a(){return null;} public boolean c(){return true;} public List d(){return new ArrayList<>();} }
 static final class NoHistory extends Hostile { public Object getCurrentDoc(){return null;} public Object getCurrentViewContext(){return null;} }
 static final class PrimitiveRegistry extends Hostile { public boolean a(){return true;} }
 static final String PATH="heavy.cmo3";
 static final Object DOC=new Object();
 static final com.live2d.cubism.doc.model.CModelSource SOURCE=new com.live2d.cubism.doc.model.CModelSource();
 static WeakReference<?> weak(Object value){return new WeakReference<Object>(value);}
 static NativeCloseOwnershipObservation.WeakPair pair(){return new NativeCloseOwnershipObservation.WeakPair(weak(DOC),weak(SOURCE),PATH);}
 static NativeCloseOwnershipObservation.WeakPair pair(WeakReference<?> doc,WeakReference<?> src){return new NativeCloseOwnershipObservation.WeakPair(doc,src,PATH);}
 static NativeCloseOwnershipObservation.Layout layout()throws Exception{
  return new NativeCloseOwnershipObservation.Layout(Reg.class,Entry.class,Ctrl.class,View.class);}
 static Entry entry(String path){ModelEntry e=new ModelEntry();e.file=new File(path);e.payload=SOURCE;e.source=SOURCE;return e;}
 static Reg reg(Entry...entries){Reg r=new Reg();r.list.addAll(Arrays.asList(entries));return r;}
 static NativeCloseOwnershipObservation.Snapshot capture(Object reg,Object ctrl,NativeCloseOwnershipObservation.WeakPair w,
   int entries,int views,int referrers,LongSupplier clock)throws Exception{
  return NativeCloseOwnershipObservation.capture(reg,ctrl,layout(),w,
    new NativeCloseOwnershipObservation.Limits(entries,views,referrers,250),clock);}
 static NativeCloseOwnershipObservation.Snapshot run(Reg r,Ctrl c,NativeCloseOwnershipObservation.WeakPair w)throws Exception{
  return capture(r,c,w,4096,4096,16384,()->0);}
 static void eq(Object a,Object b){checks++;if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
 static void prop(NativeCloseOwnershipObservation.Snapshot s,String key,String value){eq(s.values.getProperty(key),value);}
 static Ctrl controller(int history,Object viewDoc){
  Ctrl c=new Ctrl();for(int i=0;i<history;i++){View v=new View();v.doc=viewDoc;c.viewContextHistory.add(v);}return c;}
 public static void main(String[]args)throws Exception{
  // 1. Matched entry, referrers, uncleared weak pair, untouched controller history.
  Entry match=entry(PATH);Entry other=entry("other.cmo3");other.payload=new Object();
  Reg r=reg(other,match);Object referrer=new Object();match.referrers.add(referrer);match.referrers.add(new Object());
  Ctrl c=controller(0,null);
  var s=run(r,c,pair());
  prop(s,"status","COMPLETE");prop(s,"reason","none");
  prop(s,"registryEntries","2");prop(s,"nullEntries","0");prop(s,"fixtureEntryPresent","true");
  prop(s,"fixtureLoadedFlag","true");prop(s,"fixtureWrapperPresent","true");prop(s,"fixtureSourceAccessor","f");
  prop(s,"fixtureCarriedSourcePresent","true");prop(s,"fixtureCarriedSourceIsRecordedSource","true");
  prop(s,"fixtureReferrerCount","2");
  prop(s,"fixtureReferrerClasses",Object.class.getName());
  prop(s,"documentWeakBefore","false");prop(s,"documentWeakAfter","false");
  prop(s,"sourceWeakBefore","false");prop(s,"sourceWeakAfter","false");
  prop(s,"currentDocNull","true");prop(s,"currentViewNull","true");
  prop(s,"historySize","0");prop(s,"historyViewsReferringToDocument","0");prop(s,"historyNullViews","0");
  prop(s,"historyViewsWithDocument","0");
  eq(r.list.size(),2);eq(r.list.get(0)==other,true);eq(r.list.get(1)==match,true);
  eq(c.viewContextHistory.size(),0);
  for(java.lang.reflect.Field f:s.getClass().getDeclaredFields())eq(f.getType()==Properties.class,true);
  for(Object v:s.values.values())eq(v instanceof String,true);
  prop(s,"coverage","post-close-native-ownership-handles");
  // 2. Absent entry: absence is explicit and match-dependent keys are not fabricated.
  var absent=run(reg(entry("other.cmo3")),c,pair());
  prop(absent,"status","COMPLETE");prop(absent,"fixtureEntryPresent","false");
  eq(absent.values.getProperty("fixtureLoadedFlag"),null);
  eq(absent.values.getProperty("fixtureCarriedSourcePresent"),null);
  eq(absent.values.getProperty("fixtureSourceAccessor"),null);
  eq(absent.values.getProperty("fixtureReferrerCount"),null);
  // 3. Cleared weak pair after close, with a foreign payload.
  Entry stale=entry(PATH);stale.payload=new Object();stale.loaded=false;
  ((ModelEntry)stale).source=new com.live2d.cubism.doc.model.CModelSource();
  WeakReference<?> cleared=weak(new Object());cleared.clear();
  var gone=run(reg(stale),controller(0,null),pair(cleared,cleared));
  prop(gone,"documentWeakBefore","true");prop(gone,"documentWeakAfter","true");
  prop(gone,"fixtureEntryPresent","true");prop(gone,"fixtureLoadedFlag","false");prop(gone,"fixtureSourceAccessor","f");
  prop(gone,"fixtureCarriedSourcePresent","true");prop(gone,"fixtureCarriedSourceIsRecordedSource","false");
  // 4. Null entries and a null payload are tolerated and counted.
  Reg withNull=reg(entry(PATH));withNull.list.add(null);Entry empty=entry("none.cmo3");empty.payload=null;
  var nulls=run(withNull,controller(0,null),pair());
  prop(nulls,"registryEntries","2");prop(nulls,"nullEntries","1");prop(nulls,"fixtureWrapperPresent","true");
  var noPayload=run(reg(empty),controller(0,null),pair());
  prop(noPayload,"fixtureEntryPresent","false");
  // 5. History still referring to the closed document, and one that does not.
  View live=new View();live.doc=DOC;
  Ctrl holding=new Ctrl();holding.viewContextHistory.add(new View());holding.viewContextHistory.add(live);holding.doc=DOC;holding.view=live;
  var held=run(reg(entry(PATH)),holding,pair());
  prop(held,"status","COMPLETE");prop(held,"historySize","2");prop(held,"historyViewsReferringToDocument","1");
  prop(held,"historyNullViews","0");prop(held,"historyViewsWithDocument","1");
  prop(held,"currentDocNull","false");prop(held,"currentViewNull","false");
  eq(holding.viewContextHistory.size(),2);eq(holding.viewContextHistory.get(1)==live,true);
  var foreign=run(reg(entry(PATH)),controller(3,new Object()),pair());
  prop(foreign,"historyViewsReferringToDocument","0");prop(foreign,"historySize","3");
  prop(foreign,"historyViewsWithDocument","3");
  // 6. Bounds report PARTIAL with a reason instead of a fabricated total.
  var entryLimit=run(reg(entry(PATH)),c,pair());
  eq(entryLimit.values.getProperty("status"),"COMPLETE");
  eq(capture(r,c,pair(),1,4096,16384,()->0).values.getProperty("reason"),"registry-limit");
  eq(capture(r,controller(2,null),pair(),4096,1,16384,()->0).values.getProperty("reason"),"history-limit");
  Reg wide=reg(entry(PATH));((Entry)wide.list.get(0)).referrers.addAll(Arrays.asList(new Object(),new Object(),new Object()));
  var referrerLimit=capture(wide,c,pair(),4096,4096,2,()->0);
  eq(referrerLimit.values.getProperty("reason"),"referrer-limit");
  eq(referrerLimit.values.getProperty("fixtureReferrerCount"),null);
  eq(referrerLimit.values.getProperty("registryEntries"),null);
  AtomicInteger time=new AtomicInteger();
  eq(capture(r,c,pair(),4096,4096,16384,()->time.getAndIncrement()*300L).values.getProperty("reason"),"time-limit");
  // 7. Shape failures fail closed rather than reporting zero.
  eq(capture(new Object(),c,pair(),4096,4096,16384,()->0).values.getProperty("status"),"UNSUPPORTED");
  eq(capture(r,new Object(),pair(),4096,4096,16384,()->0).values.getProperty("status"),"UNSUPPORTED");
  Reg duplicate=reg(entry(PATH),entry(PATH));
  eq(run(duplicate,c,pair()).values.getProperty("status"),"UNSUPPORTED");
  Reg alien=reg(entry(PATH));alien.list.add(new Object());
  eq(run(alien,c,pair()).values.getProperty("status"),"UNSUPPORTED");
  Ctrl alienView=new Ctrl();alienView.viewContextHistory.add(new Object());
  eq(run(reg(entry(PATH)),alienView,pair()).values.getProperty("status"),"UNSUPPORTED");
  boolean layoutRejected=false;
  try{new NativeCloseOwnershipObservation.Layout(NoEntries.class,Entry.class,Ctrl.class,View.class);}
  catch(ReflectiveOperationException e){layoutRejected=true;}
  eq(layoutRejected,true);
  layoutRejected=false;
  try{new NativeCloseOwnershipObservation.Layout(Reg.class,NoPayload.class,Ctrl.class,View.class);}
  catch(ReflectiveOperationException e){layoutRejected=true;}
  eq(layoutRejected,true);
  layoutRejected=false;
  try{new NativeCloseOwnershipObservation.Layout(Reg.class,Entry.class,NoHistory.class,View.class);}
  catch(ReflectiveOperationException e){layoutRejected=true;}
  eq(layoutRejected,true);
  layoutRejected=false;
  try{new NativeCloseOwnershipObservation.Layout(StaticReg.class,Entry.class,Ctrl.class,View.class);}
  catch(ReflectiveOperationException e){layoutRejected=true;}
  eq(layoutRejected,true);
  layoutRejected=false;
  try{new NativeCloseOwnershipObservation.Layout(PrimitiveRegistry.class,Entry.class,Ctrl.class,View.class);}
  catch(ReflectiveOperationException e){layoutRejected=true;}
  eq(layoutRejected,true);
  boolean incompletePair=false;
  try{new NativeCloseOwnershipObservation.WeakPair(weak(DOC),weak(SOURCE),"");}catch(IllegalArgumentException e){incompletePair=true;}
  eq(incompletePair,true);
  // 8. Detectable same-size replacement during capture must not commit that list.
  int detected=0;
  for(int at=1;at<60;at++){
   Reg changing=reg(entry(PATH));AtomicInteger calls=new AtomicInteger();final int target=at;
   var changed=capture(changing,c,pair(),4096,4096,16384,()->{
    if(calls.incrementAndGet()==target)changing.list.add(new Entry());return 0;});
   if("unstable".equals(changed.values.getProperty("reason"))){detected++;eq(changed.values.getProperty("registryEntries"),null);}
  }
  for(int at=1;at<60;at++){
   Ctrl expiring=controller(0,null);expiring.viewContextHistory.add(new View());AtomicInteger calls=new AtomicInteger();final int target=at;
   var changed=capture(reg(entry(PATH)),expiring,pair(),4096,4096,16384,()->{
    if(calls.incrementAndGet()==target)expiring.viewContextHistory.set(0,new View());return 0;});
   if("unstable".equals(changed.values.getProperty("reason"))){detected++;eq(changed.values.getProperty("historySize"),null);}
  }
  eq(detected>0,true);
  // 11. An f() with the wrong return type is never accepted as the source accessor.
  OtherEntry wrongShape=new OtherEntry();wrongShape.file=new File(PATH);wrongShape.payload=SOURCE;
  var wrong=run(reg(wrongShape),controller(0,null),pair());
  prop(wrong,"fixtureEntryPresent","true");prop(wrong,"fixtureSourceAccessor","absent");
  eq(wrong.values.getProperty("fixtureCarriedSourceIsRecordedSource"),null);
  // 9. Every synthetic deadline point publishes either nothing or a consistent count.
  for(int timeout=0;timeout<160;timeout++){
   AtomicInteger ticks=new AtomicInteger();
   var timed=NativeCloseOwnershipObservation.capture(reg(entry(PATH)),controller(1,DOC),layout(),pair(),
     new NativeCloseOwnershipObservation.Limits(4096,4096,16384,timeout),()->ticks.getAndIncrement());
   String status=timed.values.getProperty("status");
   if("COMPLETE".equals(status)){
    prop(timed,"historyViewsReferringToDocument","1");prop(timed,"registryEntries","1");
   } else {
    eq(timed.values.getProperty("historyViewsReferringToDocument"),null);
    eq(timed.values.getProperty("registryEntries"),null);
   }
  }
  // 10. EDT requirement and the host resolver stay closed for synthetic classes.
  boolean edt=false;
  try{NativeCloseOwnershipObservation.captureHost(new Ctrl(),Ctrl.class,pair());}catch(IllegalStateException e){edt=true;}
  eq(edt,true);
  javax.swing.SwingUtilities.invokeAndWait(()->{
   eq(NativeCloseOwnershipObservation.captureHost(new Ctrl(),Ctrl.class,pair()).values.getProperty("reason"),"layout-or-origin");
   eq(NativeCloseOwnershipObservation.captureHost(new Ctrl(),Object.class,pair()).values.getProperty("reason"),"layout-or-origin");
  });
  System.out.println("PASS synthetic close-ownership checks="+checks+" detectedMutationWindows="+detected);
 }
}
'''


class CloseOwnershipTest(unittest.TestCase):
    def test_synthetic_graphs(self):
        self.assertTrue(SOURCE.is_file(), 'ownership helper not implemented')
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / 'CloseOwnershipTest.java'
            source.write_text(HARNESS)
            model = Path(temp) / 'com/live2d/cubism/doc/model/CModelSource.java'
            model.parent.mkdir(parents=True)
            model.write_text(MODEL_SOURCE)
            compile_result = subprocess.run(
                [str(JDK / 'bin/javac'), '--release', '17', '-d', temp, str(SOURCE), str(source), str(model)],
                capture_output=True, text=True, timeout=60)
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
            result = subprocess.run([str(JDK / 'bin/java'), '-ea', '-Xmx64m', '-cp', temp,
                                     'dev.turboism.validation.CloseOwnershipTest'],
                                    capture_output=True, text=True, timeout=120)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())

    def test_read_only_and_identity_only_call_sites(self):
        text = SOURCE.read_text()
        for forbidden in ('System.gc(', '.get()', '.dispose(', '.remove(', '.clear()', '.releaseResource(',
                          '.retainResource(', '.getFilteredImage(', '.getImage(', '.getAllModelImages(',
                          '.toString()', '.g()', '.hashCode(', '.equals('):
            self.assertNotIn(forbidden, text, forbidden)
        self.assertIn('.refersTo(', text, 'weak state must be read without reacquiring referents')
        self.assertIn('viewContextHistory', text)
        self.assertIn('com.live2d.cubism.doc.a.e', text)

    def test_host_agent_wiring(self):
        text = AGENT.read_text()
        for phase in ('ownership." + name', '"idle"', '"beforeClose"', '"closed120"', '"closedFinal"'):
            self.assertIn(phase, text, phase)
        self.assertIn('ownership.attributionStatus', text)
        for forbidden in ('System.gc(', '.dispose(', 'ClassHistogram', 'dumpHeap', 'jmap'):
            self.assertNotIn(forbidden, text, forbidden)
        self.assertLess(text.index('closed.begin'), text.index('"closed120"'),
                        'closed capture must follow the close')
        self.assertLess(text.index('documentWeakClearedAtSamplerEnd'), text.index('"closedFinal"'),
                        'final capture must follow the sampler window')
        self.assertLess(text.index('"beforeClose"'), text.index('command_close'),
                        'pre-close control must precede the close command')


if __name__ == '__main__':
    unittest.main()
