package dev.turboism.validation.modelimport3d.host;
import java.util.*;
import dev.turboism.sdk.cubism.history.*;
public final class PoseDriveHandoffTest {
    public static void main(String[] args) {
        var prefix=List.of(new HistoryEntry(0,"import",true));
        var root=PoseDriveHistoryTest.h(1,1,prefix);
        var driver=new PoseDriveHistory.Session(root);
        driver.ready(root);
        var entries=new ArrayList<>(prefix);
        entries.add(PoseDriveHistoryTest.entry(1,"geometry-y",PoseDriveHistoryTest.Y,0,30));
        var driven=PoseDriveHistoryTest.h(2,2,entries);
        driver.driven(root,driven,List.of(new PoseDriveHistory.Write(PoseDriveHistoryTest.Y,0,30),new PoseDriveHistory.Write(PoseDriveHistoryTest.P,0,0)));
        PoseDriveHistoryTest.reject(()->driver.ready(driven));
        var restored=PoseDriveHistoryTest.h(3,1,entries);driver.restored(restored);
        // Same typed session crosses geometry/pixel boundary; a fresh session must reject the redo tail.
        driver.ready(restored);
        if(driver.rootSnapshot()!=root)throw new AssertionError("root lost");
        PoseDriveHistoryTest.reject(()->new PoseDriveHistory.Session(restored));
        var foreign=new ArrayList<>(entries);foreign.set(1,PoseDriveHistoryTest.entry(1,"foreign",PoseDriveHistoryTest.Y,0,30));
        PoseDriveHistoryTest.reject(()->driver.ready(PoseDriveHistoryTest.h(3,1,foreign)));
        PoseDriveHistoryTest.reject(()->driver.ready(PoseDriveHistoryTest.h(4,1,entries)));
        driver.ready(restored); // rejections cannot acquire ownership or invalidate the original tail
        var pixelEntries=new ArrayList<>(prefix);pixelEntries.add(PoseDriveHistoryTest.entry(1,"pixel-y",PoseDriveHistoryTest.Y,0,-30));
        var pixelDriven=PoseDriveHistoryTest.h(4,2,pixelEntries);
        driver.driven(restored,pixelDriven,List.of(new PoseDriveHistory.Write(PoseDriveHistoryTest.Y,0,-30),new PoseDriveHistory.Write(PoseDriveHistoryTest.P,0,0)));
        driver.observed(pixelDriven);
        var finalHistory=PoseDriveHistoryTest.h(5,1,pixelEntries);driver.restored(finalHistory);driver.ready(finalHistory);
        System.out.println("PASS geometry-to-pixel owned redo handoff and four pre-write rejection controls; hostExecuted=false");
    }
}
