"""Synthetic JDK17 tests for the static-root audit. Never loads official Cubism classes."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JDK = Path(os.environ.get('JAVA17_HOME', '/usr/lib/jvm/java-17-openjdk'))
SOURCE = ROOT / 'testing/host-validation/image-archive/src/dev/turboism/validation/NativeStaticRootAudit.java'
OWNERSHIP = ROOT / 'testing/host-validation/image-archive/src/dev/turboism/validation/NativeCloseOwnershipObservation.java'
AGENT = ROOT / 'testing/host-validation/image-archive/src/dev/turboism/validation/NativeResourceHostAgent.java'
SYNTHETIC = {
    'com/live2d/cubism/doc/model/CModelSource.java':
        'package com.live2d.cubism.doc.model;\n\n/** Synthetic stand-in; never the official class. */\npublic final class CModelSource { }\n',
    'com/live2d/cubism/doc/modeling/CModelingDocument.java':
        'package com.live2d.cubism.doc.modeling;\n\n/** Synthetic stand-in; never the official class. */\npublic final class CModelingDocument { }\n',
}

HARNESS = r'''
package dev.turboism.validation;
import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
public class StaticRootAuditTest {
 static int checks;
 /** Hostile: any identity shortcut through equals/hashCode/toString must fail loudly. */
 static class Hostile { public int hashCode(){throw new AssertionError("hashCode");}
  public boolean equals(Object x){throw new AssertionError("equals");}
  public String toString(){throw new AssertionError("toString");} }
 /* Distinct owners so each reviewed entry has exactly one field of interest. */
 static final class A extends Hostile { static CModelSource d; static CModelingDocument doc; static int plain=1; }
 static final class B extends Hostile { static CModelSource i; }
 static final class C extends Hostile { static Object d; }
 static class Singleton extends Hostile { CModelSource held; CModelingDocument document; String other="y"; }
 static final class Holder extends Hostile { static Singleton instance; }
 static final class NullHolder extends Hostile { static Singleton instance; }
 static final class Deep extends Singleton { }
 static final class DeepHolder extends Hostile { static Deep instance; }
 static final String PATH="heavy.cmo3";
 static final CModelSource SOURCE=new CModelSource();
 static final CModelingDocument DOCUMENT=new CModelingDocument();
 static String[][] direct(String... owners){String[][] out=new String[owners.length][2];
  for(int x=0;x<owners.length;x++){String[] parts=owners[x].split("#");out[x]=new String[]{parts[0],parts[1]};}return out;}
 static NativeCloseOwnershipObservation.WeakPair pair(){return new NativeCloseOwnershipObservation.WeakPair(
   new WeakReference<Object>(DOCUMENT),new WeakReference<Object>(SOURCE),PATH);}
 static NativeCloseOwnershipObservation.WeakPair emptyPair(){return new NativeCloseOwnershipObservation.WeakPair(
   new WeakReference<Object>(new Object()),new WeakReference<Object>(new Object()),PATH);}
 static void eq(Object a,Object b){checks++;if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
 static void prop(NativeStaticRootAudit.Result r,String k,String v){eq(r.values.getProperty(k),v);}
 static void reset(){A.d=null;A.doc=null;B.i=null;C.d=null;Holder.instance=null;NullHolder.instance=null;DeepHolder.instance=null;}
 static NativeStaticRootAudit.Result run(String[][] rows,String[] holders,long ns)throws Exception{
  return NativeStaticRootAudit.audit(StaticRootAuditTest.class.getClassLoader(),CModelSource.class,CModelingDocument.class,
    pair(),rows,holders,new NativeStaticRootAudit.Limits(4096,4096,ns),()->0);}
 public static void main(String[]args)throws Exception{
  // 1. A recorded source in a direct static is reported by label, with no host equals call.
  reset();A.d=SOURCE;Holder.instance=null;
  var hit=run(direct("dev.turboism.validation.StaticRootAuditTest$A#d"),new String[0],250_000_000L);
  prop(hit,"status","COMPLETE");prop(hit,"reason","none");prop(hit,"holdersWithRecordedSource","1");
  prop(hit,"holdersWithRecordedDocument","0");
  prop(hit,"recordedSourceHolders","dev.turboism.validation.StaticRootAuditTest$A#d");
  prop(hit,"examined","1");prop(hit,"read","1");prop(hit,"nonNull","1");prop(hit,"unreadable","0");
  // 2. A recorded document in a direct static is reported too.
  reset();A.doc=DOCUMENT;
  var doc=run(direct("dev.turboism.validation.StaticRootAuditTest$A#doc"),new String[0],250_000_000L);
  prop(doc,"holdersWithRecordedDocument","1");prop(doc,"holdersWithRecordedSource","0");
  prop(doc,"recordedDocumentHolders","dev.turboism.validation.StaticRootAuditTest$A#doc");
  // 3. Null, foreign objects and a differently-declared field are all negative, not errors.
  reset();A.d=new CModelSource();Holder.instance=new Singleton();
  var foreign=run(direct("dev.turboism.validation.StaticRootAuditTest$A#d",
    "dev.turboism.validation.StaticRootAuditTest$C#d","dev.turboism.validation.StaticRootAuditTest$Holder#instance"),
    new String[0],250_000_000L);
  prop(foreign,"status","COMPLETE");prop(foreign,"holdersWithRecordedSource","0");
  prop(foreign,"holdersWithRecordedDocument","0");prop(foreign,"examined","3");
  prop(foreign,"nonNull","2");prop(foreign,"unreadable","0");
  // 4. An absent or primitive field is counted as unreadable and never as a zero-valued holder.
  var absent=run(direct("dev.turboism.validation.StaticRootAuditTest$A#missing",
    "dev.turboism.validation.StaticRootAuditTest$A#plain"),new String[0],250_000_000L);
  prop(absent,"status","COMPLETE");prop(absent,"unreadable","2");prop(absent,"read","0");
  eq(absent.values.getProperty("holdersWithRecordedSource"),"0");
  // 5. One-hop scan finds a recorded source and document held by a singleton.
  reset();Holder.instance=new Singleton();Holder.instance.held=SOURCE;Holder.instance.document=DOCUMENT;
  var hop=run(direct(),new String[]{"dev.turboism.validation.StaticRootAuditTest$Holder#instance"},250_000_000L);
  prop(hop,"status","COMPLETE");prop(hop,"holdersWithRecordedSource","1");prop(hop,"holdersWithRecordedDocument","1");
  prop(hop,"recordedSourceHolders","dev.turboism.validation.StaticRootAuditTest$Holder#instance->held");
  prop(hop,"recordedDocumentHolders","dev.turboism.validation.StaticRootAuditTest$Holder#instance->document");
  prop(hop,"scannedFields","2");prop(hop,"examined","1");
  // 6. A null singleton is not scanned and reports no holder.
  reset();
  var empty=run(direct(),new String[]{"dev.turboism.validation.StaticRootAuditTest$NullHolder#instance"},250_000_000L);
  prop(empty,"status","COMPLETE");prop(empty,"nonNull","0");prop(empty,"scannedFields","0");
  eq(empty.values.getProperty("holdersWithRecordedSource"),"0");
  // 7. Inherited single-hop fields are scanned within the superclass bound.
  reset();DeepHolder.instance=new Deep();DeepHolder.instance.held=SOURCE;
  var deep=run(direct(),new String[]{"dev.turboism.validation.StaticRootAuditTest$DeepHolder#instance"},250_000_000L);
  prop(deep,"holdersWithRecordedSource","1");prop(deep,"scannedFields","2");
  // 8. Bounds report PARTIAL with a reason and publish no counts at all.
  reset();Holder.instance=new Singleton();Holder.instance.held=SOURCE;
  var holders=NativeStaticRootAudit.audit(StaticRootAuditTest.class.getClassLoader(),CModelSource.class,CModelingDocument.class,
    pair(),direct("dev.turboism.validation.StaticRootAuditTest$A#d"),
    new String[]{"dev.turboism.validation.StaticRootAuditTest$Holder#instance"},
    new NativeStaticRootAudit.Limits(1,4096,250_000_000L),()->0);
  prop(holders,"status","PARTIAL");prop(holders,"reason","holder-limit");
  eq(holders.values.getProperty("examined"),null);eq(holders.values.getProperty("holdersWithRecordedSource"),null);
  var fields=NativeStaticRootAudit.audit(StaticRootAuditTest.class.getClassLoader(),CModelSource.class,CModelingDocument.class,
    pair(),direct(),new String[]{"dev.turboism.validation.StaticRootAuditTest$Holder#instance"},
    new NativeStaticRootAudit.Limits(4096,1,250_000_000L),()->0);
  prop(fields,"status","PARTIAL");prop(fields,"reason","field-limit");
  eq(fields.values.getProperty("scannedFields"),null);
  for(int timeout=0;timeout<40;timeout++){
   AtomicInteger ticks=new AtomicInteger();
   var timed=NativeStaticRootAudit.audit(StaticRootAuditTest.class.getClassLoader(),CModelSource.class,CModelingDocument.class,
     pair(),direct("dev.turboism.validation.StaticRootAuditTest$A#d"),new String[0],
     new NativeStaticRootAudit.Limits(4096,4096,timeout),()->ticks.getAndIncrement());
   String status=timed.values.getProperty("status");
   if("COMPLETE".equals(status)){prop(timed,"examined","1");}
   else{eq(timed.values.getProperty("examined"),null);eq(timed.values.getProperty("holdersWithRecordedSource"),null);}
  }
  // 9. Every audited static is left exactly as it was found.
  reset();Holder.instance=new Singleton();Holder.instance.held=SOURCE;
  run(direct("dev.turboism.validation.StaticRootAuditTest$A#d"),
    new String[]{"dev.turboism.validation.StaticRootAuditTest$Holder#instance"},250_000_000L);
  eq(Holder.instance.held==SOURCE,true);eq(A.d,null);eq(B.i,null);
  // 10. A pair that matches nothing still audits the same fields.
  var noMatch=NativeStaticRootAudit.audit(StaticRootAuditTest.class.getClassLoader(),CModelSource.class,CModelingDocument.class,
    emptyPair(),direct("dev.turboism.validation.StaticRootAuditTest$A#d"),new String[0],
    new NativeStaticRootAudit.Limits(4096,4096,250_000_000L),()->0);
  prop(noMatch,"status","COMPLETE");prop(noMatch,"examined","1");prop(noMatch,"nonNull","0");
  // 11. Result carries scalars only.
  for(java.lang.reflect.Field f:NativeStaticRootAudit.Result.class.getDeclaredFields()){
   if(java.lang.reflect.Modifier.isStatic(f.getModifiers()))continue;
   eq(f.getType()==Properties.class||java.util.Set.class.isAssignableFrom(f.getType()),true);}
  for(Object v:hit.values.values())eq(v instanceof String,true);
  // 12. EDT is required; the host resolver refuses foreign loaders.
  boolean edt=false;
  try{NativeStaticRootAudit.auditHost(new Object(),StaticRootAuditTest.class,pair());}catch(IllegalStateException e){edt=true;}
  eq(edt,true);
  javax.swing.SwingUtilities.invokeAndWait(()->{
   eq(NativeStaticRootAudit.auditHost(new Object(),StaticRootAuditTest.class,pair()).values.getProperty("reason"),"layout-or-origin");});
  System.out.println("PASS static-root audit checks="+checks);
 }
}
'''


class StaticRootAuditTest(unittest.TestCase):
    def test_synthetic_audit(self):
        self.assertTrue(SOURCE.is_file(), 'static-root audit not implemented')
        with tempfile.TemporaryDirectory() as temp:
            sources = [str(SOURCE), str(OWNERSHIP)]
            for relative, content in SYNTHETIC.items():
                path = Path(temp) / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                sources.append(str(path))
            harness = Path(temp) / 'StaticRootAuditTest.java'
            harness.write_text(HARNESS)
            sources.append(str(harness))
            compiled = subprocess.run([str(JDK / 'bin/javac'), '--release', '17', '-d', temp, *sources],
                                      capture_output=True, text=True, timeout=90)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run([str(JDK / 'bin/java'), '-ea', '-Xmx64m', '-cp', temp,
                                     'dev.turboism.validation.StaticRootAuditTest'],
                                    capture_output=True, text=True, timeout=120)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            print(result.stdout.strip())

    def test_read_only_and_reviewed_calls(self):
        text = SOURCE.read_text()
        for forbidden in ('System.gc(', '.get()', '.dispose(', '.remove(', '.clear()', '.releaseResource(',
                          '.retainResource(', '.getAllModelImages(', '.toString()', '.hashCode(', '.equals('):
            self.assertNotIn(forbidden, text, forbidden)
        self.assertIn('weak.sourceIs(', text, 'identity must be delegated to the weak pair')
        self.assertIn('weak.documentIs(', text, 'identity must be delegated to the weak pair')
        self.assertNotIn('set(null', text, 'the audit must never write a host static')
        self.assertIn('field.get(target)', text, 'static and instance reads share one bounded helper')
        # The reviewed lists must stay exactly as analysed; a silent widening would change coverage.
        for name in ('com.live2d.cubism.appCtrlImpl.ui.a.a', 'com.live2d.cubism.view.palette.parameter.dialog.G',
                     'com.live2d.cubism.view.palette.parameter.dialog.af',
                     'com.live2d.cubism.view.palette.parts.a.a'):
            self.assertIn(name, text, name)
        self.assertIn('com.live2d.cubism.setting.AppSetting#INSTANCE', text)

    def test_host_agent_wiring(self):
        text = AGENT.read_text()
        self.assertIn('NativeStaticRootAudit.auditHost', text)
        self.assertIn('staticRoots.', text)
        # The audit must run after the ownership capture of the same phase, never before it.
        for phase in ('ownership." + name', 'closed120', 'closedFinal'):
            self.assertIn(phase, text, phase)


if __name__ == '__main__':
    unittest.main()
