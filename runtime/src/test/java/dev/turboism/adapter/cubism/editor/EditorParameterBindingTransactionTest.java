package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.*;
import dev.turboism.mapping.verification.*;
import dev.turboism.mapping.verification.selector.*;
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.turboism.adapter.cubism.editor.EditorArtMeshAuthoringReadAccessTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real binding adapter, snapshot decoder and coordinator; only native members are substitutes. */
class EditorParameterBindingTransactionTest {
    @Test void composedBindingHistoryRefreshesOnceAfterAllChildRestores() {
        EditorHostThread.dispatch("Binding history refresh",()->{
            var f=new Fixture();var before=f.read();
            var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("composed bind"),()->{
                f.access.bind(f.target,points(-1,0,1));f.access.createPoint(f.target,points(.5f).get(0));return null;
            });
            assertEquals(AuthoringTransactionOutcome.COMMITTED,result.outcome());var after=f.read();
            f.modelUpdates=0;f.afterNextUpdate=()->assertEquals(before,f.read(),"refresh must see every child restored");
            f.edit.undo();assertEquals(before,f.read());assertEquals(1,f.modelUpdates);
            f.modelUpdates=0;f.afterNextUpdate=()->assertEquals(after,f.read(),"refresh must see every child reapplied");
            f.edit.redo();assertEquals(after,f.read());assertEquals(1,f.modelUpdates);
            return null;
        });
    }

    @Test void deformerParentConversionJoinsRootAndRestoresAllFormsOnAbortOrRejectedAdmission() {
        EditorHostThread.dispatch("Warp parent root",()->{
            for(boolean abort:List.of(false,true))for(boolean nativeAbort:List.of(false,true))for(boolean reject:List.of(false,true)) {
                var f=new Fixture("",true);f.nativeAbort=nativeAbort;
                var before=f.warpRead();var target=new Object();
                if(reject)f.rejectAt=1;
                var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("XYZ parent"),()->{
                    EditorDeformerParentEditAccess.change(f.resolver,f.coordinator,f.binding,f,f.source,"Warp",
                        target,target,f::warpRead,()->{});
                    if(abort)throw new IllegalStateException("after conversion");return null;
                });
                if(abort || reject) {
                    assertEquals(AuthoringTransactionOutcome.ROLLED_BACK,result.outcome());
                    assertNull(f.source.parent);assertEquals(before,f.warpRead());assertTrue(f.history.isEmpty());
                } else {
                    assertEquals(AuthoringTransactionOutcome.COMMITTED,result.outcome());assertSame(target,f.source.parent);
                    var converted=f.warpRead();assertNotEquals(before,converted);assertEquals(1,f.history.size());
                    f.edit.undo();assertNull(f.source.parent);assertEquals(before,f.warpRead());
                    f.edit.redo();assertSame(target,f.source.parent);assertEquals(converted,f.warpRead());
                }
                assertEquals(1,f.begins);
            }
            return null;
        });
    }

    @Test void rotationBindingsJoinOneRootWithUndoRedoAndAbortRecovery() {
        EditorHostThread.dispatch("Rotation binding root",()->{
            for(boolean abort:List.of(false,true))for(boolean nativeAbort:List.of(false,true)) {
                var f=new Fixture("",false,true);f.nativeAbort=nativeAbort;var before=f.rotationRead();
                var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("Roll bind"),()->{
                    f.access.bind(f.target,points(-1,0,1));
                    f.access.createPoint(f.target,points(.5f).get(0));
                    if(abort)throw new IllegalStateException("after Rotation bind");return null;
                });
                assertEquals(abort?AuthoringTransactionOutcome.ROLLED_BACK:AuthoringTransactionOutcome.COMMITTED,result.outcome());
                assertEquals(1,f.begins);
                if(abort){assertEquals(before,f.rotationRead());assertTrue(f.history.isEmpty());}
                else {
                    assertEquals(List.of(-1f,0f,.5f,1f),f.rotationRead().axes().get(0).keys());
                    var after=f.rotationRead();f.edit.undo();assertEquals(before,f.rotationRead());f.edit.redo();assertEquals(after,f.rotationRead());
                }
            }
            return null;
        });
    }
    @Test void rotationPartialBindingAndProtectedBaseAngleChangeRestoreCompleteSnapshot() {
        EditorHostThread.dispatch("Rotation binding failure",()->{
            for(boolean nativeAbort:List.of(false,true))for(boolean partial:List.of(false,true)) {
                var f=new Fixture("",false,true);f.nativeAbort=nativeAbort;var before=f.rotationRead();
                if(partial)f.grid.failAt=2;else f.afterNextUpdate=()->f.source.baseAngle=15;
                var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("failed Roll bind"),()->{
                    f.access.bind(f.target,points(-1,0,1));return null;
                });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK,result.outcome());assertEquals(before,f.rotationRead());
            }
            return null;
        });
    }
    static final String M = "cubism.editor-model.";
    static final ArtMeshId ID = new ArtMeshId("Mesh");
    static final ParameterId PARAM = new ParameterId("same-id");
    static final ParameterBindingTarget TARGET = ParameterBindingTarget.artMesh(ID);
    static List<ParameterBindingPoint> points(float... values) {
        var result = new ArrayList<ParameterBindingPoint>();
        for (float value : values) result.add(new ParameterBindingPoint(new ParameterBindingPointId("p" + value), value));
        return result;
    }

    @Test void warpBindingsJoinOneRootWithCompleteUndoRedoAndAbortRecovery() {
        EditorHostThread.dispatch("Warp binding root",()->{
            for(boolean abort:List.of(false,true))for(boolean nativeAbort:List.of(false,true)) {
                var f=new Fixture("",true);f.nativeAbort=nativeAbort;var before=f.warpRead();
                var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("Warp bind and densify"),()->{
                    f.access.bind(f.target,points(-1,0,1));
                    f.access.createPoint(f.target,points(.5f).get(0));
                    if(abort)throw new IllegalStateException("after Warp bind");return null;
                });
                assertEquals(abort?AuthoringTransactionOutcome.ROLLED_BACK:AuthoringTransactionOutcome.COMMITTED,result.outcome());
                assertEquals(1,f.begins);
                if(abort){assertEquals(before,f.warpRead());assertTrue(f.history.isEmpty());}
                else {
                    assertEquals(List.of(-1f,0f,.5f,1f),f.warpRead().axes().get(0).keys());
                    var after=f.warpRead();f.edit.undo();assertEquals(before,f.warpRead());f.edit.redo();assertEquals(after,f.warpRead());
                }
            }
            return null;
        });
    }
    @Test void warpBindingPartialMutationAndMissingRecoverySelectorDoNotCommit() {
        EditorHostThread.dispatch("Warp binding failure",()->{
            for(boolean nativeAbort:List.of(false,true)) {
                var f=new Fixture("",true);f.nativeAbort=nativeAbort;f.grid.failAt=2;var before=f.warpRead();
                var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("partial Warp bind"),()->{
                    f.access.bind(f.target,points(-1,0,1));return null;
                });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK,result.outcome());assertEquals(before,f.warpRead());
            }
            var f=new Fixture(M+"undo.execute-undo",true);var before=f.warpRead();
            var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("missing Warp recovery"),()->{
                f.access.bind(f.target,points(-1,0,1));return null;
            });
            assertNotEquals(AuthoringTransactionOutcome.COMMITTED,result.outcome());assertEquals(0,f.begins);assertEquals(before,f.warpRead());
            return null;
        });
    }

    @Test void warpGridCollateralChangeRejectsAndNativeUndoRestoresDimensions() {
        EditorHostThread.dispatch("Warp binding protected dimensions",()->{
            for(boolean nativeAbort:List.of(false,true)) {
                var f=new Fixture("",true);f.nativeAbort=nativeAbort;var before=f.warpRead();
                f.afterNextUpdate=()->f.source.rows=2;
                var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("bad Warp dimensions"),()->{
                    f.access.bind(f.target,points(-1,0,1));return null;
                });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK,result.outcome());assertEquals(before,f.warpRead());
            }
            return null;
        });
    }

    @Test void warpBindingRecoveryNeverTouchesAReplacementSource() {
        EditorHostThread.dispatch("Warp binding source replacement",()->{
            var f=new Fixture("",true);f.nativeAbort=false;var replacement=new NativeSource();
            var foreignBefore=new EditorArtMeshAuthoringReadAccess(f.resolver).read(ID,replacement);
            f.afterNextUpdate=()->f.lookupSource=replacement;
            var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("replaced Warp source"),()->{
                f.access.bind(f.target,points(-1,0,1));return null;
            });
            assertEquals(AuthoringTransactionOutcome.RECOVERY_FAILED,result.outcome());
            assertEquals(foreignBefore,new EditorArtMeshAuthoringReadAccess(f.resolver).read(ID,replacement));
            assertFalse(f.source.grid.bindings().isEmpty()); // Unsafe compensation was not attempted.
            return null;
        });
    }
    @Test void createdWarpBindingRecoverySkipsASourceAlreadyRemovedByNativeAbort() {
        EditorHostThread.dispatch("created Warp binding recovery",()->{
            for(boolean nativeAbort:List.of(false,true)) {
                var f=new Fixture("",true);f.present=false;f.nativeAbort=nativeAbort;
                var result=f.coordinator.execute(f.binding,AuthoringTransactionOptions.of("create and bind Warp"),()->{
                    f.coordinator.mutateCreated(f.binding,f.source,new EditorUndoContribution("cubism.warp-deformer.create","model:warp:Warp","create Warp",
                        (edit,label)->assertTrue(((Edit)edit).add(new Undo(f),true)),()->f.present=true,()->f.present,
                        ()->f.present=false,()->!f.present,Set.of()));
                    f.access.bind(f.target,points(-1,0,1));throw new IllegalStateException("later failure");
                });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK,result.outcome());assertFalse(f.present);assertTrue(f.history.isEmpty());
            }
            return null;
        });
    }

    @Test void bindAndDensifyShareRootAndNativeUndoRedo() {
        EditorHostThread.dispatch("binding transaction", () -> {
            var f = new Fixture(); var before = f.read();
            var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("dense binding"), () -> {
                f.access.bind(TARGET, points(-1, 0, 1));
                f.access.createPoint(TARGET, points(.5F).get(0));
                return null;
            });
            assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
            assertEquals(1, f.begins); assertEquals(1, f.history.size());
            assertEquals(List.of(-1F, 0F, .5F, 1F), f.read().axes().get(0).keys());
            var after = f.read(); f.edit.undo(); assertEquals(before, f.read());
            f.edit.redo(); assertEquals(after, f.read());
            return null;
        });
    }

    @Test void failedBindingAndDensificationRestoreCompleteSnapshot() {
        EditorHostThread.dispatch("binding recovery", () -> {
            for (boolean nativeAbort : List.of(false, true)) for (int failAt : List.of(0, 1, 2, 3)) {
                var f = new Fixture(); f.nativeAbort = nativeAbort; var before = f.read();
                f.rejectAt = failAt < 3 ? failAt : 0;
                f.grid.failAt = failAt == 3 ? 2 : 0;
                var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("failure"), () -> {
                    f.access.bind(TARGET, points(-1, 0, 1));
                    f.access.createPoint(TARGET, points(.5F).get(0));
                    throw new IllegalStateException("after binding");
                });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome(), nativeAbort + ":" + failAt);
                assertEquals(before, f.read()); assertEquals(0, f.history.size()); assertEquals(1, f.begins);
            }
            return null;
        });
    }

    @Test void denseRotationKeysDoNotOverflowHistoryBeforeOrAfterValues() {
        EditorHostThread.dispatch("dense rotation binding", () -> {
            var f = new Fixture(); var before = f.read();
            var dense = new float[45];
            for (int i = 0; i < dense.length; i++) dense[i] = -1F + 2F * i / 44F;
            assertTrue(points(dense).stream().map(ParameterBindingPoint::value).toList().toString().length() > 256);
            var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("dense rotation"), () -> {
                f.access.bind(TARGET, points(dense));
                f.access.createPoint(TARGET, points(.123F).get(0));
                return null;
            });
            assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome(), result.diagnosticId().orElse(""));
            assertEquals(46, f.read().axes().get(0).keys().size());
            var after = f.read(); f.edit.undo(); assertEquals(before, f.read());
            f.edit.redo(); assertEquals(after, f.read());
            return null;
        });
    }

    @Test void moveDeleteAndUnbindJoinTheSameRoot() {
        EditorHostThread.dispatch("binding edits", () -> {
            var f = new Fixture(); var before = f.read();
            var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("edit binding"), () -> {
                f.access.bind(TARGET, points(-1, 0, 1));
                f.access.movePoint(TARGET, new ParameterBindingPointId("p0.0"), .25F);
                assertEquals(List.of(-1F, .25F, 1F), f.read().axes().get(0).keys());
                f.access.deletePoint(TARGET, new ParameterBindingPointId("p0.25"));
                assertEquals(List.of(-1F, 1F), f.read().axes().get(0).keys());
                f.access.unbind(TARGET);
                assertEquals(before, f.read());
                return null;
            });
            assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
            assertEquals(1, f.begins); assertEquals(4, f.edit.undos.size());
            f.edit.undo(); assertEquals(before, f.read());
            return null;
        });
    }

    @Test void rejectsListenerAndIncorrectNativeKeysWithoutFalseCommit() {
        EditorHostThread.dispatch("binding negative controls", () -> {
            for (boolean listenerFailure : List.of(false, true)) {
                var f = new Fixture(); var before = f.read(); f.nativeAbort = false;
                f.rejectListener = listenerFailure; f.grid.ignoreKeys = !listenerFailure;
                var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("bad native result"), () -> {
                    f.access.bind(TARGET, points(-1, 0, 1)); return null;
                });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
                assertEquals(before, f.read()); assertTrue(f.history.isEmpty());
            }
            return null;
        });
    }

    @Test void incompleteNativeRecoveryIsNotReportedAsRolledBack() {
        EditorHostThread.dispatch("binding residue", () -> {
            var f = new Fixture(); f.brokenUndo = true;
            var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("broken undo"), () -> {
                f.access.bind(TARGET, points(-1, 0, 1));
                throw new IllegalStateException("abort");
            });
            assertEquals(AuthoringTransactionOutcome.RECOVERY_FAILED, result.outcome());
            assertFalse(f.read().axes().isEmpty());
            return null;
        });
    }

    @Test void missingUndoSelectorRejectsBeforeRootEdit() {
        EditorHostThread.dispatch("binding preflight", () -> {
            var f = new Fixture(M + "undo.execute-undo"); var before = f.read();
            var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("unverified"), () -> {
                f.access.bind(TARGET, points(-1, 0, 1)); return null;
            });
            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
            assertEquals(0, f.begins); assertEquals(before, f.read());
            return null;
        });
    }

    public interface Listener { void changed(Object event); }
    public static class NativeGrid {
        final Source data;
        int failAt, adds;
        boolean ignoreKeys;
        NativeGrid(Source data) { this.data = data; }
        public List<Binding> bindings() { return data.grid.bindings; }
        public List<Row> rows(Guid guid) { return data.grid.rows(guid); }
        public void add(float value, Guid guid) {
            if (ignoreKeys) return;
            if (bindings().isEmpty()) {
                var axis = new Binding(guid.text()); axis.keys.clear(); bindings().add(axis);
                data.grid.rows.replaceAll((id, rows) -> rows.stream().map(row ->
                    new Row(new AccessKey(List.of(new Coordinate(axis, value))))).toList());
            }
            bindings().get(0).keys.add(value); bindings().get(0).keys.sort(Float::compare);
            if (++adds == failAt) throw new IllegalStateException("partial native key insertion");
        }
        public void remove(float value, Guid guid) { bindings().get(0).keys.remove(Float.valueOf(value)); }
        public void rearrange(Guid guid, List<Float> before, List<Float> after) {
            bindings().get(0).keys.clear(); bindings().get(0).keys.addAll(after);
        }
        public void clear(Object parameterSet, Guid guid) {
            bindings().clear();
            data.grid.rows.replaceAll((id, rows) -> rows.stream().map(row -> new Row(new AccessKey(List.of()))).toList());
        }
    }
    public static class NativeSource {
        final Source data = new Source();
        final NativeGrid grid = new NativeGrid(data);
        int rows=1,columns=1;boolean quad=true;
        float baseAngle;
        Object parent;
        public Object parent(){return parent;}
        public Undo changeParent(Object model,Object target,boolean ignored){
            var undo=new Undo(CURRENT);parent=target;
            for(var form:data.forms)for(int i=0;i<form.positions.length;i++)form.positions[i]-=512;
            return undo;
        }
        public float baseAngle(){return baseAngle;}
        public List<EditorRotationKeyformAccessTest.RotationForm> rotationForms(){return data.forms.stream()
            .map(f->new EditorRotationKeyformAccessTest.RotationForm(f.guid.text(),0)).toList();}
        public int rows(){return rows;}public int columns(){return columns;}public boolean quad(){return quad;}
        NativeSource() {
            data.grid.bindings.clear();
            data.grid.rows.replaceAll((id, rows) -> rows.stream().map(row -> new Row(new AccessKey(List.of()))).toList());
        }
        public Guid guid() { return data.guid(); }
        public float[] positions() { return data.positions(); }
        public float[] uvs() { return data.uvs(); }
        public int[] indices() { return data.indices(); }
        public List<StoredForm> forms() { return data.forms(); }
        public Targets targets() { return data.targets(); }
        public NativeGrid grid() { return grid; }
        public NativeSource handler() { return this; }
        public Undo snapshot(String label) { return new Undo(CURRENT); }
    }
    record State(List<Binding> bindings, List<List<Float>> keys, Map<String,List<Row>> rows,
                 List<float[]> positions, List<Integer> orders,int warpRows,int warpColumns,boolean quad,boolean present,float baseAngle,Object parent) {
        static State capture(Fixture f) {
            return new State(List.copyOf(f.grid.bindings()), f.grid.bindings().stream().map(b -> List.copyOf(b.keys)).toList(),
                new HashMap<>(f.source.data.grid.rows), f.source.data.forms.stream().map(form -> form.positions.clone()).toList(),
                f.source.data.forms.stream().map(form -> form.order).toList(),f.source.rows,f.source.columns,f.source.quad,f.present,f.source.baseAngle,f.source.parent);
        }
        void restore(Fixture f) {
            f.source.rows=warpRows;f.source.columns=warpColumns;f.source.quad=quad;f.present=present;
            f.source.baseAngle=baseAngle;f.source.parent=parent;
            f.grid.bindings().clear(); f.grid.bindings().addAll(bindings);
            for (int i = 0; i < bindings.size(); i++) { bindings.get(i).keys.clear(); bindings.get(i).keys.addAll(keys.get(i)); }
            f.source.data.grid.rows.clear(); f.source.data.grid.rows.putAll(rows);
            for (int i = 0; i < positions.size(); i++) {
                f.source.data.forms.get(i).positions = positions.get(i).clone(); f.source.data.forms.get(i).order = orders.get(i);
            }
        }
    }
    public static class Undo {
        final Fixture f; final State before; State after;
        final List<Listener> listeners = new ArrayList<>();
        Undo(Fixture f) { this.f = f; before = State.capture(f); }
        public boolean listen(Listener listener) {
            if (f.rejectListener) return false;
            listeners.add(listener); return true;
        }
        public void undo() { if (!f.brokenUndo) before.restore(f); listeners.forEach(l -> l.changed(this)); }
        public void redo() { after.restore(f); listeners.forEach(l -> l.changed(this)); }
    }
    public static class Edit extends Undo {
        Edit(){super(CURRENT);}
        final List<Undo> undos = new ArrayList<>();
        public boolean add(Undo undo, boolean significant) {
            if (++CURRENT.admissions == CURRENT.rejectAt) return false;
            if (!undos.isEmpty()) undos.get(undos.size()-1).after = State.capture(CURRENT);
            undos.add(undo); return true;
        }
        @Override public void undo() { for (int i = undos.size()-1; i >= 0; i--) undos.get(i).undo(); listeners.forEach(l->l.changed(this)); }
        @Override public void redo() { undos.forEach(Undo::redo); listeners.forEach(l->l.changed(this)); }
    }
    static Fixture CURRENT;
    public static class Fixture implements EditorAuthoringTransactionCoordinator.Host {
        @Override public void undoEditGroup(final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding expected, final Object edit) {}
        final NativeSource source = new NativeSource(); final NativeGrid grid = source.grid;
        final List<HistoryEntry> history = new ArrayList<>();
        final EditorAuthoringTransactionCoordinator coordinator = new EditorAuthoringTransactionCoordinator(this);
        final EditorAuthoringTransactionCoordinator.Binding binding = new EditorAuthoringTransactionCoordinator.Binding(
            "binding-test", "document", 1, "model", 1, Thread.currentThread());
        final VerifiedMemberResolver resolver;
        final EditorParameterBindingAccess access;
        final ParameterBindingTarget target;
        NativeSource lookupSource=source;boolean present=true;
        Edit edit; int begins, admissions, rejectAt; boolean nativeAbort = true, brokenUndo, rejectListener;
        Fixture() { this(""); }
        Fixture(String excluded) { this(excluded,false); }
        Fixture(String excluded,boolean warp) {
            this(excluded,warp,false);
        }
        Fixture(String excluded,boolean warp,boolean rotation) {
            target=rotation?ParameterBindingTarget.rotationDeformer(new DeformerId("Rotation")):warp?ParameterBindingTarget.warpDeformer(new DeformerId("Warp")):TARGET;
            if(warp || rotation){source.data.targets.targets.clear();source.data.forms.removeIf(form->form.guid.text().startsWith("m"));}
            CURRENT = this; resolver = resolver(excluded,warp,rotation);
            access = new EditorParameterBindingAccess(resolver, "model", this, this, PARAM,
                (id, model) -> assertSame(this, model), (id, src, model, parameter) -> grid.bindings().isEmpty() ? List.of() :
                    List.of(new ParameterBinding(this.target, PARAM, ParameterBindingFamily.KEYFORM_GRID,
                        pointsArray(grid.bindings().get(0).keys))),
                (id, src, model, target) -> present?lookupSource:null, (model, parameter) -> this, coordinator, () -> binding);
        }
        WarpAuthoringSnapshot warpRead() {return new EditorWarpAuthoringReadAccess(resolver).read(new DeformerId("Warp"),source);}
        RotationAuthoringSnapshot rotationRead() {return new EditorRotationAuthoringReadAccess(resolver).read(new DeformerId("Rotation"),source);}
        ArtMeshAuthoringSnapshot read() { return new EditorArtMeshAuthoringReadAccess(resolver).read(ID, source); }
        static List<ParameterBindingPoint> pointsArray(List<Float> keys) {
            return keys.stream().map(v -> new ParameterBindingPoint(new ParameterBindingPointId("p"+v),v)).toList();
        }
        public static Fixture instance() { return CURRENT; }
        public Fixture pack() { return this; }
        public void refresh(boolean immediate) { }
        Runnable afterNextUpdate=()->{};
        int modelUpdates;
        public void update() {modelUpdates++;var callback=afterNextUpdate;afterNextUpdate=()->{};callback.run();}
        public Guid parameterGuid() { return new Guid("new-axis"); }
        public Object unexpected() { throw new AssertionError("nested legacy edit or unused selector invoked"); }
        @Override public boolean isCurrent(EditorAuthoringTransactionCoordinator.Binding b) { return b.equals(binding); }
        @Override public HistorySnapshot history(EditorAuthoringTransactionCoordinator.Binding b) {
            return new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, 1, history.size(), history.size(),
                List.copyOf(history), !history.isEmpty(), false, "document", "manager");
        }
        @Override public Object beginEdit(EditorAuthoringTransactionCoordinator.Binding b, String label) {
            begins++; edit = new Edit(); return edit;
        }
        @Override public void endEdit(EditorAuthoringTransactionCoordinator.Binding b, Object value, boolean abort) {
            if (abort) { if (nativeAbort) edit.undo(); }
            else {
                if (!edit.undos.isEmpty()) edit.undos.get(edit.undos.size()-1).after = State.capture(this);
                history.add(new HistoryEntry(history.size(), "bind", true));
            }
        }
        @Override public void refresh(EditorAuthoringTransactionCoordinator.Binding b, Set<EditorRefreshRequirement> r) { }
        @Override public Optional<String> committedHistoryEntryId(EditorAuthoringTransactionCoordinator.Binding b,
            HistorySnapshot before, HistorySnapshot after, String id, String label) { return Optional.of("bind"); }
        @Override public String diagnosticId(String code, Throwable failure) { return code; }
    }
    static VerifiedMemberResolver resolver(String excluded,boolean warp) {
        return resolver(excluded,warp,false);
    }
    static VerifiedMemberResolver resolver(String excluded,boolean warp,boolean rotation) {
        var aliases = new LinkedHashMap<String, StaticSelector>();
        for (var s : EditorArtMeshAuthoringReadAccessTest.selectors()) {
            Class<?> owner = s.ownerInternalName().equals(Source.class.getName().replace('.','/')) ? NativeSource.class
                : s.ownerInternalName().equals(Grid.class.getName().replace('.','/')) ? NativeGrid.class : null;
            if (owner == NativeSource.class) s = method(s.alias(), owner, s.memberName());
            else if (owner == NativeGrid.class) s = s.memberName().equals("rows") ? method(s.alias(), owner, "rows", Guid.class)
                : method(s.alias(), owner, "bindings");
            aliases.put(s.alias(), s);
        }
        for (String alias : EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES)
            aliases.putIfAbsent(alias, method(alias, Fixture.class, "unexpected"));
        var wired = List.of(
            StaticSelector.staticMethod(M+"app-controller.instance", Fixture.class.getName().replace('.','/'), "instance",
                "()L"+Fixture.class.getName().replace('.','/')+";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
            method(M+"app-controller.complete-pack", Fixture.class,"pack"),
            method(M+"complete-pack.update-parameter", Fixture.class,"refresh",boolean.class),
            method(M+"complete-pack.update-part-palette", Fixture.class,"refresh",boolean.class),
            method(M+"complete-pack.update-deformer-palette", Fixture.class,"refresh",boolean.class),
            method(M+"complete-pack.repaint-canvas", Fixture.class,"refresh",boolean.class),
            method(M+"model-source.update-instances", Fixture.class,"update"),
            method(M+"parameter-source.guid", Fixture.class,"parameterGuid"),
            method(M+"parameter-controllable-source.handler", NativeSource.class,"handler"),
            method(M+"parameter-controllable-handler.create-undo-for-all-edit", NativeSource.class,"snapshot",String.class),
            method(M+"keyform-grid.add-key", NativeGrid.class,"add",float.class,Guid.class),
            method(M+"keyform-grid.remove-key", NativeGrid.class,"remove",float.class,Guid.class),
            method(M+"keyform-grid.rearrange-keys", NativeGrid.class,"rearrange",Guid.class,List.class,List.class),
            method(M+"keyform-grid.remove-all-key", NativeGrid.class,"clear",Object.class,Guid.class),
            method(M+"model.parameter-set", Fixture.class,"pack"),
            method(M+"undo.add", Edit.class,"add",Undo.class,boolean.class),
            method(M+"undo.add-listener", Undo.class,"listen",Listener.class),
            method(M+"undo.execute-undo", Undo.class,"undo"),
            method(M+"parameter-controllable-source.target-deformer-source",NativeSource.class,"parent"),
            method(M+"parameter-controllable-handler.change-target-deformer-guid",NativeSource.class,"changeParent",Object.class,Object.class,boolean.class),
            StaticSelector.classSelector(M+"undo-listener.class",Listener.class.getName().replace('.','/')));
        wired.forEach(s -> aliases.put(s.alias(),s));
        if(warp) {
            for(var selector:List.of(method(M+"deformer-source.guid",NativeSource.class,"guid"),
                method(M+"warp-source.row",NativeSource.class,"rows"),method(M+"warp-source.col",NativeSource.class,"columns"),
                method(M+"warp-source.quad-transform",NativeSource.class,"quad"),method(M+"warp-source.keyforms",NativeSource.class,"forms"),
                method(M+"warp-form.positions",StoredForm.class,"positions")))aliases.put(selector.alias(),selector);
        }
        var capabilities = new HashSet<>(EditorArtMeshAuthoringReadSelectorContract.CAPABILITY_IDS);
        capabilities.add(EditorParameterBindingWriteSelectorContract.ART_MESH_CAPABILITY_ID);
        if(warp){capabilities.addAll(EditorWarpAuthoringSelectorContract.CAPABILITY_IDS);capabilities.add(EditorParameterBindingWriteSelectorContract.WARP_CAPABILITY_ID);}
        if(rotation) {
            aliases.put(M+"deformer-source.guid",method(M+"deformer-source.guid",NativeSource.class,"guid"));
            aliases.put(M+"rotation-source.base-angle",method(M+"rotation-source.base-angle",NativeSource.class,"baseAngle"));
            aliases.put(M+"rotation-source.keyforms",method(M+"rotation-source.keyforms",NativeSource.class,"rotationForms"));
            aliases.put("cubism.editor-history.semantic.form.guid",method("cubism.editor-history.semantic.form.guid",EditorRotationKeyformAccessTest.RotationForm.class,"guid"));
            for(String[] member:List.of(new String[]{"angle","angle"},new String[]{"origin-x","x"},new String[]{"origin-y","y"},new String[]{"scale","scale"},new String[]{"reflect-x","rx"},new String[]{"reflect-y","ry"}))
                aliases.put(M+"rotation-form."+member[0],method(M+"rotation-form."+member[0],EditorRotationKeyformAccessTest.RotationForm.class,member[1]));
            capabilities.addAll(EditorRotationAuthoringSelectorContract.CAPABILITY_IDS);
            capabilities.add(EditorParameterBindingWriteSelectorContract.ROTATION_CAPABILITY_ID);
        }
        return TestVerifiedResolvers.create("5.3.02", EditorParameterBindingWriteSelectorContract.ADAPTER_SLICE_ID,
            capabilities, aliases.values().stream().filter(s -> !s.alias().equals(excluded)).toList(), Fixture.class.getClassLoader());
    }
}
