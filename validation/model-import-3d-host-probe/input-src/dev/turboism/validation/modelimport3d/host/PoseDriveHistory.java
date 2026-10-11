package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.*;
import java.util.*;

/** Strict history contract for isolated SDK pose-driving writes, never a history bypass.
 * Each pose starts at the imported root and is undone before the next pose.
 * Only a previously verified driver-owned redo tail may be replaced.
 */
final class PoseDriveHistory {
    record Write(String parameter,float before,float after) {
        Write {
            NativeAssertions.require(parameter!=null && !parameter.isBlank()
                && Float.isFinite(before) && Float.isFinite(after),"pose write finite identity");
        }
    }
    private PoseDriveHistory() {}
    /** Stateful ownership prevents accepting/truncating an unrelated redo tail between poses. */
    static final class Session {
        private final HistorySnapshot root;
        private HistorySnapshot ready;
        private HistorySnapshot pending;
        Session(HistorySnapshot root) {root(root);this.root=root;this.ready=root;}
        HistorySnapshot rootSnapshot() {return root;}
        /** Must run before setValue so unrelated redo is never truncated by the driver. */
        void ready(HistorySnapshot current) {
            NativeAssertions.require(pending==null,"previous pose not restored");
            PoseDriveHistory.observed(ready,current);
        }
        void driven(HistorySnapshot before,HistorySnapshot after,List<Write> writes) {
            ready(before);
            PoseDriveHistory.driven(root,before,after,writes);
            pending=after;
        }
        void observed(HistorySnapshot current) {
            NativeAssertions.require(pending!=null,"no admitted pose");
            PoseDriveHistory.observed(pending,current);
        }
        /** Append the exact inverse of a single admitted priming write.
         * The caller must observe native consumption at the priming pose before
         * returning. History ownership alone does not prove native evaluation.
         */
        void returned(HistorySnapshot before,HistorySnapshot after) {
            observed(before);
            identity(before,after);
            int position=root.position();
            NativeAssertions.require(before.position()==position+1
                && before.entries().size()==position+1,"return requires one priming action");
            NativeAssertions.require(after.position()==position+2 && after.entries().size()==position+2
                && after.revision()>before.revision() && after.canUndo() && !after.canRedo()
                && after.entries().subList(0,position+1).equals(before.entries()),"return changed priming prefix/extent");
            var first=before.entries().get(position).action().orElseThrow();
            var last=after.entries().get(position+1);
            var inverse=new HistoryAction(HistoryAction.Kind.SET_PARAMETER_VALUE,"PARAMETER",first.targetId(),"value",
                first.after(),first.before(),HistoryAction.DetailLevel.FULL);
            NativeAssertions.require(last.significant() && last.entryId().isPresent() && last.transactionId().isPresent()
                && before.entries().stream().noneMatch(e->e.entryId().equals(last.entryId()))
                && last.action().filter(inverse::equals).isPresent(),"return must be fresh exact inverse");
            pending=after;
        }
        void restored(HistorySnapshot current) {
            NativeAssertions.require(pending!=null,"no admitted pose to restore");
            PoseDriveHistory.restored(root,pending,current);
            pending=null;ready=current;
        }
    }
    static void root(HistorySnapshot root) {
        NativeAssertions.require(root.availability()==HistorySnapshot.Availability.AVAILABLE
            && root.position()==root.entries().size() && !root.canRedo()
            && !root.documentBindingId().isEmpty() && !root.managerBindingId().isEmpty(),"pose driver requires isolated history tip");
    }
    private static void identity(HistorySnapshot before,HistorySnapshot after) {
        NativeAssertions.require(after.availability()==HistorySnapshot.Availability.AVAILABLE
            && before.generation()==after.generation() && before.documentBindingId().equals(after.documentBindingId())
            && before.managerBindingId().equals(after.managerBindingId()) && after.revision()>=before.revision(),
            "pose history identity/revision");
    }
    static void driven(HistorySnapshot root,HistorySnapshot before,HistorySnapshot after,List<Write> requested) {
        root(root);identity(root,before);identity(before,after);
        NativeAssertions.require(requested.size()==2 && !requested.get(0).parameter().equals(requested.get(1).parameter()),
            "two distinct pose axes");
        NativeAssertions.require(before.position()==root.position()
            && before.entries().subList(0,root.position()).equals(root.entries()),"pose driver changed root prefix");
        var changes=requested.stream().filter(w->Float.compare(w.before(),w.after())!=0).toList();
        if(changes.isEmpty()) {
            NativeAssertions.require(after.equals(before),"same pose changed history");return;
        }
        NativeAssertions.require(after.position()==root.position()+changes.size()
            && after.entries().size()==after.position() && after.canUndo() && !after.canRedo()
            && after.revision()>before.revision()
            && after.entries().subList(0,root.position()).equals(root.entries()),"pose driver history extent/prefix");
        var identities=new HashSet<HistoryEntryId>();
        for(var entry:before.entries())entry.entryId().ifPresent(identities::add);
        for(int i=0;i<changes.size();i++) {
            var entry=after.entries().get(root.position()+i);var w=changes.get(i);
            NativeAssertions.require(entry.significant() && entry.entryId().isPresent()
                && identities.add(entry.entryId().orElseThrow()) && entry.transactionId().isPresent(),"pose write stable fresh entry identity");
            var expected=new HistoryAction(HistoryAction.Kind.SET_PARAMETER_VALUE,"PARAMETER",w.parameter(),"value",
                Optional.of(Float.toString(w.before())),Optional.of(Float.toString(w.after())),HistoryAction.DetailLevel.FULL);
            NativeAssertions.require(entry.action().filter(expected::equals).isPresent(),"pose write exact action");
        }
    }
    static void observed(HistorySnapshot driven,HistorySnapshot observed) {
        NativeAssertions.require(driven.equals(observed),"pose observation changed history");
    }
    static void restored(HistorySnapshot root,HistorySnapshot driven,HistorySnapshot restored) {
        root(root);identity(driven,restored);
        NativeAssertions.require(restored.generation()==root.generation()
            && restored.documentBindingId().equals(root.documentBindingId())
            && restored.managerBindingId().equals(root.managerBindingId())
            && restored.position()==root.position() && restored.entries().equals(driven.entries())
            && restored.entries().subList(0,root.position()).equals(root.entries())
            && restored.canUndo()==root.canUndo()
            && restored.canRedo()==(restored.entries().size()>root.position()),"pose undo changed root or owned redo tail");
    }
}
