"""Synthetic JDK17 graph tests. Never loads official Cubism classes or launches a host."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JDK = Path(os.environ.get('JAVA17_HOME', '/usr/lib/jvm/java-17-openjdk'))
SOURCE = ROOT / 'testing/host-validation/image-archive/src/dev/turboism/validation/NativeImageRetainObservation.java'
HARNESS = r'''
package dev.turboism.validation;
import java.util.*;
import java.lang.ref.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
public class RetainObservationTest {
 static int checks;
 static class Hostile { public int hashCode(){throw new AssertionError("hashCode");}
  public boolean equals(Object x){throw new AssertionError("equals");}
  public String toString(){throw new AssertionError("toString");} }
 interface User {}
 static final class Doc extends Hostile { Source _modelSource; }
 static final class Source extends Hostile { Manager textureManager = new Manager(); }
 static final class Manager extends Hostile { GroupList _modelImageGroups = new GroupList(); }
 static final class Group extends Hostile { GroupList _modelImages = new GroupList(); }
 static final class GroupList extends ArrayList<Object> {}
 static final class Image extends Hostile implements User { Resource _filteredImage; Source _modelSource;
  Object getFilteredImage(){throw new AssertionError("materialization");} }
 static final class Other extends Hostile implements User {}
 static final class Resource extends Hostile {
  final ArrayList<Record> retainCounter = new ArrayList<>();
  final ArrayList<Record> releasedRetainUserData_forDebug = new ArrayList<>(); }
 static final class Record extends Hostile { final User a; Record(User u){a=u;} }
 static final class BadDoc { Object _modelSource; }
 static NativeImageRetainObservation.Layout layout() throws Exception {
  return new NativeImageRetainObservation.Layout(Doc.class, Source.class, Manager.class,
    Group.class, Image.class, Resource.class, Record.class, User.class, GroupList.class);
 }
 static Doc graph() {
  Doc d=new Doc();d._modelSource=new Source();Group g=new Group();
  d._modelSource.textureManager._modelImageGroups.add(g);
  Image active=new Image();active._modelSource=d._modelSource;active._filteredImage=new Resource();
  Image old=new Image();old._modelSource=new Source();
  Image same=new Image();same._modelSource=d._modelSource;
  Image detached=new Image();Other other=new Other();Resource r=active._filteredImage;
  r.retainCounter.add(new Record(active));
  for(User u: new User[]{old,old,same,detached,other,active})r.releasedRetainUserData_forDebug.add(new Record(u));
  Image shared=new Image();shared._filteredImage=r;
  g._modelImages.add(active);g._modelImages.add(shared);g._modelImages.add(new Image());return d;
 }
 static Group group(Doc d){return (Group)d._modelSource.textureManager._modelImageGroups.get(0);}
 static Resource resource(Doc d){return ((Image)group(d)._modelImages.get(0))._filteredImage;}
 static void eq(Object a,Object b){checks++;if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
 static void count(NativeImageRetainObservation.Snapshot s,String key,long n){eq(s.values.getProperty(key),Long.toString(n));}
 static NativeImageRetainObservation.Snapshot capture(Doc d,int groups,int images,int records,LongSupplier clock)throws Exception{
  return NativeImageRetainObservation.capture(d,layout(),new NativeImageRetainObservation.Limits(groups,images,records,250),clock);
 }
 public static void main(String[] args)throws Exception{
  Doc d=graph();Resource r=resource(d);Object first=r.releasedRetainUserData_forDebug.get(0);
  var s=capture(d,4096,4096,16384,()->0);
  eq(s.values.getProperty("status"),"COMPLETE");count(s,"groups",1);count(s,"images",3);
  count(s,"missingImages",1);count(s,"resources",1);count(s,"activeRecords",1);
  count(s,"releasedRecords",6);count(s,"releasedRecordsAbsentActive",5);count(s,"releasedUsers",5);
  count(s,"modelUsersSameSource",2);count(s,"modelUsersDifferentSource",1);count(s,"modelUsersNullSource",1);
  eq(r.releasedRetainUserData_forDebug.get(0)==first,true);eq(r.retainCounter.size(),1);
  eq(((Image)group(d)._modelImages.get(2))._filteredImage==null,true);
  // Result shape has no callback, reflection layout or host graph. Explicit clearing is deterministic, not forced GC.
  for(Field f:s.getClass().getDeclaredFields())eq(f.getType()==Properties.class||f.getType()==List.class,true);
  for(Object v:s.values.values())eq(v instanceof String,true);
  eq(s.resources.size(),1);eq(s.users.size(),5);
  Properties p=new Properties();s.writeWeak(p,"w");eq(p.getProperty("w.resources.notCleared"),"1");
  for(WeakReference<?> w:s.resources)w.clear();for(WeakReference<?> w:s.users)w.clear();
  s.writeWeak(p,"w");eq(p.getProperty("w.resources.cleared"),"1");eq(p.getProperty("w.users.cleared"),"5");
  eq(capture(d,0,4096,16384,()->0).values.getProperty("reason"),"group-limit");
  eq(capture(d,4096,0,16384,()->0).values.getProperty("reason"),"image-limit");
  var limited=capture(d,4096,4096,6,()->0);
  eq(limited.values.getProperty("reason"),"record-limit");count(limited,"releasedRecordsAbsentActive",0);
  eq(limited.resources.size(),0);eq(limited.users.size(),0);
  AtomicInteger time=new AtomicInteger();
  eq(capture(d,4096,4096,16384,()->time.getAndIncrement()*300L).values.getProperty("reason"),"time-limit");
  boolean bad=false;try{new NativeImageRetainObservation.Layout(BadDoc.class,Source.class,Manager.class,
   Group.class,Image.class,Resource.class,Record.class,User.class,GroupList.class);}catch(ReflectiveOperationException e){bad=true;}
  eq(bad,true);
  // Missing record user is unsupported rather than a complete zero.
  Doc broken=graph();resource(broken).releasedRetainUserData_forDebug.add(new Record(null));
  eq(capture(broken,4096,4096,16384,()->0).values.getProperty("status"),"UNSUPPORTED");
  // Inject same-size replacement at different read boundaries; detectable races must not commit that resource.
  int detected=0;
  for(int at=1;at<100;at++){
   Doc changing=graph();Resource cr=resource(changing);AtomicInteger calls=new AtomicInteger();final int target=at;
   var changed=capture(changing,4096,4096,16384,()->{
    if(calls.incrementAndGet()==target)cr.releasedRetainUserData_forDebug.set(0,new Record(new Other()));return 0;});
   if("unstable".equals(changed.values.getProperty("reason"))){detected++;count(changed,"resources",0);eq(changed.users.size(),0);}
  }
  eq(detected>0,true);
  Doc empty=new Doc();empty._modelSource=new Source();
  var zero=capture(empty,4096,4096,16384,()->0);eq(zero.values.getProperty("status"),"COMPLETE");count(zero,"resources",0);
  Doc duplicate=graph();duplicate._modelSource.textureManager._modelImageGroups.add(group(duplicate));
  var dup=capture(duplicate,4096,4096,16384,()->0);count(dup,"resources",1);count(dup,"releasedUsers",5);
  Doc inactive=graph();resource(inactive).retainCounter.clear();
  count(capture(inactive,4096,4096,16384,()->0),"releasedRecordsAbsentActive",6);
  Doc multiple=graph();Image extra=new Image();extra._filteredImage=new Resource();
  extra._filteredImage.releasedRetainUserData_forDebug.add(new Record(new Other()));group(multiple)._modelImages.add(extra);
  var global=capture(multiple,4096,4096,7,()->0);eq(global.values.getProperty("reason"),"record-limit");
  count(global,"resources",1);count(global,"releasedRecords",6);eq(global.resources.size(),1);
  eq(NativeImageRetainObservation.capture(new Object(),layout(),
   new NativeImageRetainObservation.Limits(10,10,10,250),()->0).values.getProperty("status"),"UNSUPPORTED");
  boolean edt=false;try{NativeImageRetainObservation.captureHost(d,Doc.class);}catch(IllegalStateException e){edt=true;}eq(edt,true);
  javax.swing.SwingUtilities.invokeAndWait(()->{
   eq(NativeImageRetainObservation.captureHost(d,Doc.class).values.getProperty("status"),"UNSUPPORTED");
   eq(NativeImageRetainObservation.captureHost(d,Object.class).values.getProperty("reason"),"layout-or-origin");
  });
  // Capturing every synthetic deadline point cannot publish part of a resource's user cohort.
  for(int timeout=0;timeout<180;timeout++){
   AtomicInteger ticks=new AtomicInteger();
   var timed=NativeImageRetainObservation.capture(graph(),layout(),
    new NativeImageRetainObservation.Limits(4096,4096,16384,timeout),()->ticks.getAndIncrement());
   long total=Long.parseLong(timed.values.getProperty("resources"));eq(total==0||total==1,true);
   eq(timed.users.size(),total==0?0:5);
  }
  System.out.println("PASS synthetic retain observation checks="+checks+" detectedMutationWindows="+detected);
 }
}
'''


class RetainObservationTest(unittest.TestCase):
    def test_synthetic_graphs(self):
        self.assertTrue(SOURCE.is_file(), 'observation helper not implemented')
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / 'RetainObservationTest.java'
            source.write_text(HARNESS)
            compile_result = subprocess.run(
                [str(JDK / 'bin/javac'), '--release', '17', '-d', temp, str(SOURCE), str(source)],
                capture_output=True, text=True, timeout=30)
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
            result = subprocess.run([str(JDK / 'bin/java'), '-ea', '-Xmx64m', '-cp', temp,
                                     'dev.turboism.validation.RetainObservationTest'],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())

    def test_no_mutating_or_materializing_calls(self):
        text = SOURCE.read_text()
        for forbidden in ('System.gc(', '.getFilteredImage(', '.getImage(', '.releaseResource(',
                          '.retainResource(', '.dispose(', '.flush(', '.toString()', '.getAllModelImages('):
            self.assertNotIn(forbidden, text)
        self.assertNotIn('.get()', text, 'weak lifecycle must not reacquire referents')


if __name__ == '__main__':
    unittest.main()
