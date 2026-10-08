package dev.turboism.validation.modelimport3d.host;
import dev.turboism.sdk.cubism.history.*;
import java.util.*;
public final class PoseDriveHistoryTest {
    static int checks;
    static final String Y="ParamMI3DCubeYaw",P="ParamMI3DCubePitch";
    static HistoryEntry entry(int i,String id,String axis,float from,float to) {
        return new HistoryEntry(i,"Turboism: Set Parameter Value",true,Optional.of(new HistoryAction(
            HistoryAction.Kind.SET_PARAMETER_VALUE,"PARAMETER",axis,"value",Optional.of(Float.toString(from)),
            Optional.of(Float.toString(to)),HistoryAction.DetailLevel.FULL)),Optional.of(new HistoryEntryId(id)),Optional.of("tx-"+id));
    }
    static HistorySnapshot h(long revision,int position,List<HistoryEntry> entries) {
        return new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE,1,revision,position,entries,position>0,position<entries.size(),"doc","manager");
    }
    static void reject(Runnable r) {
        try{r.run();throw new AssertionError("invalid history accepted");}catch(IllegalStateException expected){checks++;}
    }
    public static void main(String[] args) {
        var prefix=List.of(new HistoryEntry(0,"canvas",true),new HistoryEntry(1,"import",true));
        var root=h(3,2,prefix);var entries=new ArrayList<>(prefix);
        entries.add(entry(2,"e3",Y,0,-30));entries.add(entry(3,"e4",P,0,-30));
        var driven=h(5,4,entries);var restored=h(7,2,entries);
        var writes=List.of(new PoseDriveHistory.Write(Y,0,-30),new PoseDriveHistory.Write(P,0,-30));
        PoseDriveHistory.driven(root,root,driven,writes);checks++;
        // Mirrors native failure: SDK adds two parameter actions; observation must preserve them.
        PoseDriveHistory.observed(driven,driven);checks++;
        PoseDriveHistory.restored(root,driven,restored);checks++;
        reject(()->PoseDriveHistory.observed(root,driven));
        reject(()->PoseDriveHistory.observed(driven,h(6,4,entries)));
        reject(()->PoseDriveHistory.driven(root,root,root,writes)); // old history-free mock must fail
        reject(()->PoseDriveHistory.driven(root,root,driven,List.of(writes.get(1),writes.get(0))));
        var wrong=new ArrayList<>(entries);wrong.set(2,entry(2,"e3",Y,1,-30));
        reject(()->PoseDriveHistory.driven(root,root,h(5,4,wrong),writes));
        wrong.set(2,entry(2,"e3","other",0,-30));
        reject(()->PoseDriveHistory.driven(root,root,h(5,4,wrong),writes));
        var changedPrefix=new ArrayList<>(entries);changedPrefix.set(0,new HistoryEntry(0,"unrelated mutation",true));
        reject(()->PoseDriveHistory.driven(root,root,h(5,4,changedPrefix),writes));
        reject(()->PoseDriveHistory.restored(root,driven,h(7,3,entries)));
        reject(()->PoseDriveHistory.restored(root,driven,h(7,2,changedPrefix)));
        reject(()->PoseDriveHistory.root(restored)); // preexisting redo is not an admitted root
        var next=new ArrayList<>(prefix);next.add(entry(2,"e5",Y,0,30));
        var nextDriven=h(8,3,next);
        PoseDriveHistory.driven(root,restored,nextDriven,List.of(new PoseDriveHistory.Write(Y,0,30),new PoseDriveHistory.Write(P,0,0)));checks++;
        var reused=new ArrayList<>(prefix);reused.add(entry(2,"e3",Y,0,30));
        reject(()->PoseDriveHistory.driven(root,restored,h(8,3,reused),List.of(new PoseDriveHistory.Write(Y,0,30),new PoseDriveHistory.Write(P,0,0))));
        PoseDriveHistory.driven(root,restored,restored,List.of(new PoseDriveHistory.Write(Y,0,0),new PoseDriveHistory.Write(P,0,0)));checks++;
        reject(()->PoseDriveHistory.driven(root,restored,h(8,2,entries),List.of(new PoseDriveHistory.Write(Y,0,0),new PoseDriveHistory.Write(P,0,0))));
        var session=new PoseDriveHistory.Session(root);
        session.driven(root,driven,writes);session.observed(driven);checks++;
        reject(()->session.driven(root,driven,writes));
        reject(()->session.observed(h(6,4,entries)));
        session.restored(restored);checks++;
        reject(()->session.observed(restored));
        var unauthorized=new ArrayList<>(entries);unauthorized.set(3,entry(3,"foreign",P,0,-30));
        reject(()->session.driven(h(7,2,unauthorized),nextDriven,List.of(new PoseDriveHistory.Write(Y,0,30),new PoseDriveHistory.Write(P,0,0))));
        session.driven(restored,nextDriven,List.of(new PoseDriveHistory.Write(Y,0,30),new PoseDriveHistory.Write(P,0,0)));checks++;
        session.restored(h(9,2,next));checks++;
        System.out.println(checks+" pose-driving history checks PASS; synthetic native-shaped history, hostExecuted=false");
    }
}
