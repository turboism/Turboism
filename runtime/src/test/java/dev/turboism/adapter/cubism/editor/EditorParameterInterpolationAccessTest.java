package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.*;
import dev.turboism.mapping.verification.*;
import dev.turboism.mapping.verification.selector.*;
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.turboism.adapter.cubism.editor.EditorArtMeshAuthoringReadAccessTest.*;

/** Real adapter/decoder/coordinator; native methods are deterministic substitutes. */
class EditorParameterInterpolationAccessTest {
    static final String M="cubism.editor-model.";
    static final ParameterInterpolation ELLIPSE=new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE,1,1);

    @Test void composedNativeUndoRedoRefreshesWholeModelOnceAfterAllChildren() {
        EditorHostThread.dispatch("interpolation root history refresh",()->{
            var f=new Fixture("5.3.02","");var before=f.stored();
            var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("XY ellipse"),()->{
                f.write(0,ELLIPSE);f.write(1,ELLIPSE);return null;
            });
            assertEquals(AuthoringTransactionOutcome.COMMITTED,result.outcome());
            f.modelUpdates=0;
            f.edit.undo();
            assertEquals(1,f.modelUpdates,"root Undo must refresh after every child has restored its cache");
            assertEquals(before,f.stored());assertTrue(f.source.extensions.isEmpty());
            f.edit.redo();
            assertEquals(2,f.modelUpdates,"root Redo must add one whole-model refresh");
            assertEquals(before,f.stored());assertEquals(25,f.source.extension().grid.rows().size());
            return null;
        });
    }

    @Test void composedInterpolationRebuildsLocalCachesButUpdatesModelOnlyAtCommit() {
        EditorHostThread.dispatch("coalesced interpolation refresh",()->{
            var f=new Fixture("5.3.02","");
            var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("XY ellipse"),()->{
                f.write(0,ELLIPSE);
                assertEquals(15,f.source.extension().grid.rows().size());
                assertEquals(0,f.modelUpdates,"source-local cache verification must not refresh the whole model");
                f.write(1,ELLIPSE);
                assertEquals(25,f.source.extension().grid.rows().size());
                assertEquals(0,f.modelUpdates);
                return null;
            });
            assertEquals(AuthoringTransactionOutcome.COMMITTED,result.outcome());
            assertEquals(1,f.modelUpdates);
            var standalone=new Fixture("5.3.02","");standalone.write(0,ELLIPSE);
            assertEquals(1,standalone.modelUpdates,"standalone authoring must still update at its commit");
            return null;
        });
    }

    @Test void commitsInOneRootAndNativeUndoRedoRestoresSettingsAndCache() {
        EditorHostThread.dispatch("interpolation root",()->{
            var f=new Fixture("5.3.02","");var original=f.stored();
            var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("XY ellipse"),()->{
                f.write(0,ELLIPSE);f.write(1,ELLIPSE);return null;
            });
            assertEquals(AuthoringTransactionOutcome.COMMITTED,result.outcome());assertEquals(1,f.begins);
            assertEquals(original,f.stored());assertEquals(25,f.source.extension().grid.rows().size());
            f.edit.undo();assertEquals(original,f.stored());assertTrue(f.source.extensions.isEmpty());
            assertEquals(ParameterInterpolation.Method.LINEAR,f.read(0).method());
            f.edit.redo();assertEquals(ELLIPSE,f.read(0));assertEquals(ELLIPSE,f.read(1));assertEquals(25,f.source.extension().grid.rows().size());
            return null;
        });
    }

    @Test void cacheFailureCollateralMutationAndRejectedUndoNeverCommit() {
        EditorHostThread.dispatch("interpolation failure",()->{
            for(String failure:List.of("cache","source","other-axis","count","illegal","admission","listener","root-listener"))for(boolean nativeAbort:List.of(false,true)) {
                var f=new Fixture("5.3.02","");var before=f.stored();f.failure=failure;f.nativeAbort=nativeAbort;
                var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("failed ellipse"),()->{f.write(0,ELLIPSE);return null;});
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK,result.outcome(),failure);
                assertEquals(before,f.stored());assertEquals(ParameterInterpolation.Method.LINEAR,f.read(0).method());
                assertEquals(ParameterInterpolation.Method.LINEAR,f.read(1).method());assertTrue(f.source.extensions.isEmpty());assertTrue(f.history.isEmpty());
            }
            return null;
        });
    }

    @Test void abortAfterChangingExistingCacheRestoresItsExactPositionsAndGuidBank() {
        EditorHostThread.dispatch("existing cache rollback",()->{
            var f=new Fixture("5.3.02","");f.write(0,ELLIPSE);var ext=f.source.extension();
            var cacheBefore=ext.forms.stream().map(form->Arrays.toString(form.positions())).toList();
            var guids=ext.forms.stream().map(form->form.guid()).toList();
            f.nativeAbort=false;
            var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("abort cache edit"),()->{
                f.write(1,ELLIPSE);throw new IllegalStateException("abort after cache update");
            });
            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK,result.outcome());
            assertEquals(cacheBefore,f.source.extension().forms.stream().map(form->Arrays.toString(form.positions())).toList());
            assertEquals(guids,f.source.extension().forms.stream().map(form->form.guid()).toList());
            assertEquals(ParameterInterpolation.Method.LINEAR,f.read(1).method());return null;
        });
    }

    @Test void rejectsUnverifiedVersionMissingSelectorOversizedCacheAndStaleSourceBeforeEdit() {
        EditorHostThread.dispatch("interpolation admission",()->{
            for(var f:List.of(new Fixture("5.4.00",""),new Fixture("5.3.02",M+"extended-interpolation.update-forms"))) {
                assertThrows(UnsupportedOperationException.class,()->f.write(0,ELLIPSE));assertEquals(0,f.begins);
            }
            var f=new Fixture("5.3.02","");
            f.source.axes.get(1).type=NativeType.ELLIPSE;f.source.axes.get(1).inserts=64;
            assertThrows(IllegalArgumentException.class,()->f.write(0,new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE,64,1)));
            assertEquals(0,f.begins);
            f.source.axes.get(1).type=NativeType.LINEAR;f.current=false;
            assertThrows(IllegalStateException.class,()->f.write(0,ELLIPSE));assertEquals(0,f.begins);return null;
        });
    }

    public enum NativeType { LINEAR, ELLIPSE, SNS_CURVE }
    public static class NativeBinding extends Binding {
        NativeType type=NativeType.LINEAR;int inserts=1;float scale=1;
        public NativeType legacy(){return NativeType.LINEAR;}public boolean illegal(){return CURRENT.failure.equals("illegal")&&type==NativeType.ELLIPSE;}
        NativeBinding(String guid){super(guid);}
        public NativeType type(){return type;}public void type(NativeType value){type=value;}
        public int inserts(){return inserts;}public void inserts(int value){inserts=value;}
        public float scale(){return scale;}public void scale(float value){scale=value;}
    }
    public static class NativeGrid extends Grid {
        final List<Row> allRows=new ArrayList<>();
        public Binding find(Guid guid){return bindings.stream().filter(b->b.guid.equals(guid)).findFirst().orElse(null);}
        public List<Row> rows(){return allRows;}
    }
    public static class NativeSource extends Source {
        final List<NativeBinding> axes=new ArrayList<>();final List<Object> extensions=new ArrayList<>();final NativeGrid nativeGrid=new NativeGrid();
        NativeSource(){
            targets.targets.clear();
            for(var old:grid.bindings){var n=new NativeBinding(old.guid.text());axes.add(n);}
            for(var rows:grid.rows.values())for(var row:rows)for(var c:row.key().coordinates())c.binding=axes.get(grid.bindings.indexOf(c.binding));
            grid.bindings.clear();grid.bindings.addAll(axes);nativeGrid.bindings.clear();nativeGrid.bindings.addAll(axes);nativeGrid.rows.clear();nativeGrid.rows.putAll(grid.rows);
        }
        @Override public NativeGrid grid(){return nativeGrid;}
        public List<Object> extensions(){return extensions;}public void add(Object extension,int position){extensions.add(extension);}
        public NativeSource handler(){return this;}public Undo snapshot(String label){return new Undo(CURRENT);}
        Extension extension(){return extensions.stream().filter(Extension.class::isInstance).map(Extension.class::cast).findFirst().orElse(null);}
        public Binding find(Guid guid){return grid.bindings.stream().filter(b->b.guid.equals(guid)).findFirst().orElse(null);}
    }
    public static class Extension {
        final NativeSource owner;NativeGrid grid;List<StoredForm> forms=new ArrayList<>();boolean enabled;
        public Extension(NativeSource owner){this.owner=owner;}
        public NativeSource owner(){return owner;}public boolean enabled(){return enabled;}public NativeGrid grid(){return grid;}public List<StoredForm> forms(){return forms;}
        public void setup(boolean reset,boolean ignored){
            grid=new NativeGrid();grid.bindings.clear();
            for(var b:owner.axes){var generated=new NativeBinding(b.guid.text());generated.keys.clear();
                for(int i=0;i<b.keys.size()-1;i++){generated.keys.add(b.keys.get(i));
                    if(b.type!=NativeType.LINEAR)for(int j=1;j<=b.inserts;j++)generated.keys.add(b.keys.get(i)+(b.keys.get(i+1)-b.keys.get(i))*j/(b.inserts+1));}
                generated.keys.add(b.keys.get(b.keys.size()-1));grid.bindings.add(generated);}
            for(float x:grid.bindings.get(0).keys)for(float y:grid.bindings.get(1).keys){int index=grid.allRows.size();grid.allRows.add(new Row(new AccessKey(List.of(new Coordinate(grid.bindings.get(0),x),new Coordinate(grid.bindings.get(1),y))),new Guid(index<9?"f0":"cached"+index)));}
            enabled=owner.axes.stream().anyMatch(b->b.type!=NativeType.LINEAR&&b.inserts>0);
            forms=new ArrayList<>();for(int i=9;i<grid.allRows.size();i++)forms.add(new StoredForm("cached"+i,i));
            if(CURRENT.failure.equals("count"))grid.allRows.remove(0);
        }
        public boolean update(boolean force){
            if(CURRENT.failure.equals("cache")){forms.get(0).positions[0]=123;throw new IllegalStateException("partial native cache update");}
            if(CURRENT.failure.equals("source"))owner.forms.get(0).positions[0]=123;
            if(CURRENT.failure.equals("other-axis"))owner.axes.get(1).type=NativeType.ELLIPSE;
            return true;
        }
    }
    public static class Fields {public static final NativeType LINEAR=NativeType.LINEAR,ELLIPSE=NativeType.ELLIPSE,SNS_CURVE=NativeType.SNS_CURVE;}
    public static class FormReads {public float opacity(){return 1;}}
    record Saved(List<ParameterInterpolation> settings,List<Object> extensions,NativeGrid cacheGrid,List<StoredForm> cacheForms,boolean enabled,List<float[]> positions) {
        static Saved capture(Fixture f){var e=f.source.extension();return new Saved(f.source.axes.stream().map(b->new ParameterInterpolation(ParameterInterpolation.Method.valueOf(b.type.name()),b.inserts,b.scale)).toList(),List.copyOf(f.source.extensions),e==null?null:e.grid,e==null?List.of():List.copyOf(e.forms),e!=null&&e.enabled,f.source.forms.stream().map(form->form.positions.clone()).toList());}
        void restore(Fixture f){for(int i=0;i<settings.size();i++){var s=settings.get(i);var b=f.source.axes.get(i);b.type=NativeType.valueOf(s.method().name());b.inserts=s.insertPointCount();b.scale=s.angleScale();}
            // Native ALL_EDIT restores source settings/forms, not extension structure or caches.
            for(int i=0;i<positions.size();i++)f.source.forms.get(i).positions=positions.get(i).clone();}
    }
    public interface Listener{void changed(Object event);}
    public static class Undo {
        final Fixture f;final Saved before;Saved after;final List<Listener> listeners=new ArrayList<>();
        Undo(Fixture f){this.f=f;before=Saved.capture(f);}
        public boolean listen(Listener listener){if(f.failure.equals("listener"))return false;listeners.add(listener);return true;}
        public void undo(){if(after==null)after=Saved.capture(f);before.restore(f);listeners.forEach(l->l.changed(this));}
        public void redo(){after.restore(f);listeners.forEach(l->l.changed(this));}
    }
    public static class StructuralUndo extends Undo {
        final NativeSource source;final Extension extension;
        public StructuralUndo(NativeSource source,Extension extension,int index,boolean add){super(CURRENT);this.source=source;this.extension=extension;}
        public StructuralUndo forceRedo(){redo();return this;}
        @Override public void undo(){source.extensions.remove(extension);}
        @Override public void redo(){source.extensions.add(extension);}
    }
    public static class CompositeUndo extends Undo {
        final List<Undo> children=new ArrayList<>();
        public CompositeUndo(String label){super(CURRENT);}
        public void add(Undo undo){children.add(undo);}
        @Override public void undo(){for(int i=children.size()-1;i>=0;i--)children.get(i).undo();listeners.forEach(l->l.changed(this));}
        @Override public void redo(){children.forEach(Undo::redo);listeners.forEach(l->l.changed(this));}
    }
    public static class Edit extends Undo {
        final List<Undo> undos=new ArrayList<>();
        Edit(){super(CURRENT);}
        @Override public boolean listen(Listener listener){return !f.failure.equals("root-listener") && super.listen(listener);}
        public boolean add(Undo undo,boolean significant){if(CURRENT.failure.equals("admission"))return false;if(!undos.isEmpty())undos.get(undos.size()-1).after=Saved.capture(CURRENT);undos.add(undo);return true;}
        @Override public void undo(){for(int i=undos.size()-1;i>=0;i--)undos.get(i).undo();listeners.forEach(l->l.changed(this));}
        @Override public void redo(){undos.forEach(Undo::redo);listeners.forEach(l->l.changed(this));}
    }
    static Fixture CURRENT;
    public static class Fixture implements EditorAuthoringTransactionCoordinator.Host {
        @Override public void undoEditGroup(final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding expected, final Object edit) {}
        final NativeSource source=new NativeSource();final List<HistoryEntry> history=new ArrayList<>();
        final EditorAuthoringTransactionCoordinator coordinator=new EditorAuthoringTransactionCoordinator(this);
        final EditorAuthoringTransactionCoordinator.Binding binding=new EditorAuthoringTransactionCoordinator.Binding("interpolation-test","document",1,"model",1,Thread.currentThread());
        final VerifiedMemberResolver resolver;final EditorParameterInterpolationAccess access;
        Edit edit;int begins,modelUpdates;boolean current=true,nativeAbort=true;String failure="";
        Fixture(String version,String missing){CURRENT=this;resolver=resolver(version,missing);access=new EditorParameterInterpolationAccess(resolver);}
        void write(int axis,ParameterInterpolation s){CURRENT=this;access.write(source,this,new ArtMeshId("Mesh"),source.axes.get(axis).guid,s,()->{if(!current)throw new IllegalStateException("stale");},coordinator,binding);}
        ParameterInterpolation read(int axis){return access.read(source,source.axes.get(axis).guid);}
        ArtMeshAuthoringSnapshot stored(){return new EditorArtMeshAuthoringReadAccess(resolver).read(new ArtMeshId("Mesh"),source);}
        public static Fixture instance(){return CURRENT;}public Fixture pack(){return this;}public void update(){modelUpdates++;}public void refresh(boolean immediate){}
        public Object unexpected(){throw new AssertionError("unused selector");}
        public boolean isCurrent(EditorAuthoringTransactionCoordinator.Binding b){return current&&b.equals(binding);}
        public HistorySnapshot history(EditorAuthoringTransactionCoordinator.Binding b){return new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE,1,history.size(),history.size(),List.copyOf(history),!history.isEmpty(),false,"document","manager");}
        public Object beginEdit(EditorAuthoringTransactionCoordinator.Binding b,String label){begins++;edit=new Edit();return edit;}
        public void endEdit(EditorAuthoringTransactionCoordinator.Binding b,Object edit,boolean abort){if(abort){if(nativeAbort)this.edit.undo();}else{this.edit.undos.get(this.edit.undos.size()-1).after=Saved.capture(this);history.add(new HistoryEntry(history.size(),"interpolation",true));}}
        public void refresh(EditorAuthoringTransactionCoordinator.Binding b,Set<EditorRefreshRequirement> r){if(r.contains(EditorRefreshRequirement.MODEL_INSTANCES))update();}
        public Optional<String> committedHistoryEntryId(EditorAuthoringTransactionCoordinator.Binding b,HistorySnapshot before,HistorySnapshot after,String id,String label){return Optional.of("interpolation");}
        public String diagnosticId(String code,Throwable failure){return code;}
    }

    static VerifiedMemberResolver resolver(String version,String missing){
        Map<String,StaticSelector> aliases=new LinkedHashMap<>();for(var s:selectors())aliases.put(s.alias(),s);
        for(String alias:EditorParameterInterpolationSelectorContract.REQUIRED_ALIASES)aliases.putIfAbsent(alias,method(alias,Fixture.class,"unexpected"));
        aliases.put(M+"keyform-binding.legacy-type",method(M+"keyform-binding.legacy-type",NativeBinding.class,"legacy"));
        aliases.put(M+"keyform-binding.illegal-extended",method(M+"keyform-binding.illegal-extended",NativeBinding.class,"illegal"));
        for(String[] spec:List.of(new String[]{"extended-type","type"},new String[]{"insert-count","inserts"},new String[]{"extended-scale","scale"})){
            aliases.put(M+"keyform-binding."+spec[0],method(M+"keyform-binding."+spec[0],NativeBinding.class,spec[1]));
            Class<?> arg=spec[1].equals("type")?NativeType.class:spec[1].equals("inserts")?int.class:float.class;
            aliases.put(M+"keyform-binding.set-"+spec[0],method(M+"keyform-binding.set-"+spec[0],NativeBinding.class,spec[1],arg));
        }
        for(String[] spec:List.of(new String[]{"linear","LINEAR"},new String[]{"ellipse","ELLIPSE"},new String[]{"sns-curve","SNS_CURVE"}))
            aliases.put(M+"extended-type."+spec[0],StaticSelector.field(M+"extended-type."+spec[0],Fields.class.getName().replace('.','/'),spec[1],"L"+NativeType.class.getName().replace('.','/')+";",25));
        aliases.put(M+"extended-interpolation.class",StaticSelector.classSelector(M+"extended-interpolation.class",Extension.class.getName().replace('.','/')));
        aliases.put(M+"group-undo.create",StaticSelector.constructor(M+"group-undo.create",CompositeUndo.class.getName().replace('.','/'),"(Ljava/lang/String;)V",1));
        aliases.put("cubism.texture-atlas.group-undo.add",method("cubism.texture-atlas.group-undo.add",CompositeUndo.class,"add",Undo.class));
        aliases.put("cubism.texture-atlas.undo.force-redo",method("cubism.texture-atlas.undo.force-redo",StructuralUndo.class,"forceRedo"));
        aliases.put(M+"undo-add-remove-extension.create",StaticSelector.constructor(M+"undo-add-remove-extension.create",StructuralUndo.class.getName().replace('.','/'),"(L"+NativeSource.class.getName().replace('.','/')+";L"+Extension.class.getName().replace('.','/')+";IZ)V",1));
        aliases.put(M+"extended-interpolation.create",StaticSelector.constructor(M+"extended-interpolation.create",Extension.class.getName().replace('.','/'),"(L"+NativeSource.class.getName().replace('.','/')+";)V",1));
        for(String[] spec:List.of(new String[]{"owner","owner"},new String[]{"enabled","enabled"},new String[]{"grid","grid"},new String[]{"forms","forms"}))aliases.put(M+"extended-interpolation."+spec[0],method(M+"extended-interpolation."+spec[0],Extension.class,spec[1]));
        for(var s:List.of(method(M+"extended-interpolation.setup-forms",Extension.class,"setup",boolean.class,boolean.class),method(M+"extended-interpolation.update-forms",Extension.class,"update",boolean.class),method(M+"parameter-controllable-source.extensions",NativeSource.class,"extensions"),method(M+"parameter-controllable-source.add-extension",NativeSource.class,"add",Object.class,int.class),method(M+"keyform-grid.find-binding",NativeGrid.class,"find",Guid.class)))aliases.put(s.alias(),s);
        return finish(version,missing,aliases);
    }
    static VerifiedMemberResolver finish(String version,String missing,Map<String,StaticSelector> aliases){
        for(var s:List.of(method(M+"keyform-grid.find-binding",NativeGrid.class,"find",Guid.class),method(M+"keyform-grid.rows",NativeGrid.class,"rows"),method(M+"keyform-grid-row.guid",Row.class,"formGuid"),method(M+"drawable-form.opacity",StoredForm.class,"opacity"),method(M+"parameter-controllable-source.handler",NativeSource.class,"handler"),method(M+"parameter-controllable-handler.create-undo-for-all-edit",NativeSource.class,"snapshot",String.class),method(M+"undo.add",Edit.class,"add",Undo.class,boolean.class),method(M+"undo.add-listener",Undo.class,"listen",Listener.class),method(M+"undo.execute-undo",Undo.class,"undo"),method(M+"model-source.update-instances",Fixture.class,"update"),method(M+"app-controller.complete-pack",Fixture.class,"pack"),method(M+"complete-pack.update-parameter",Fixture.class,"refresh",boolean.class),method(M+"complete-pack.repaint-canvas",Fixture.class,"refresh",boolean.class)))aliases.put(s.alias(),s);
        aliases.put(M+"app-controller.instance",StaticSelector.staticMethod(M+"app-controller.instance",Fixture.class.getName().replace('.','/'),"instance","()L"+Fixture.class.getName().replace('.','/')+";",9));
        aliases.put(M+"undo-listener.class",StaticSelector.classSelector(M+"undo-listener.class",Listener.class.getName().replace('.','/')));
        var caps=new HashSet<>(EditorArtMeshAuthoringReadSelectorContract.CAPABILITY_IDS);caps.add(EditorParameterInterpolationSelectorContract.CAPABILITY_ID);
        return TestVerifiedResolvers.create(version,EditorParameterInterpolationSelectorContract.ADAPTER_SLICE_ID,caps,aliases.values().stream().filter(s->!s.alias().equals(missing)).toList(),Fixture.class.getClassLoader());
    }
}
