package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.*;
import java.util.*;

/** History-only controls for the neutral-pose priming/return protocol. */
public final class PoseDriveReturnTest {
    public static void main(String[] args) {
        var prefix=List.of(new HistoryEntry(0,"import",true));
        var root=PoseDriveHistoryTest.h(1,1,prefix);
        var primeEntries=new ArrayList<>(prefix);
        primeEntries.add(PoseDriveHistoryTest.entry(1,"prime",PoseDriveHistoryTest.Y,0,1));
        var prime=PoseDriveHistoryTest.h(2,2,primeEntries);
        var returnedEntries=new ArrayList<>(primeEntries);
        returnedEntries.add(PoseDriveHistoryTest.entry(2,"return",PoseDriveHistoryTest.Y,1,0));
        var returned=PoseDriveHistoryTest.h(3,3,returnedEntries);
        var session=new PoseDriveHistory.Session(root);
        PoseDriveHistoryTest.reject(()->session.returned(prime,returned));
        session.driven(root,prime,List.of(new PoseDriveHistory.Write(PoseDriveHistoryTest.Y,0,1),
            new PoseDriveHistory.Write(PoseDriveHistoryTest.P,0,0)));
        session.observed(prime);
        PoseDriveHistoryTest.reject(()->session.returned(root,returned));
        PoseDriveHistoryTest.reject(()->session.returned(prime,prime));
        for(var bad:List.of(
            PoseDriveHistoryTest.entry(2,"return",PoseDriveHistoryTest.P,1,0),
            PoseDriveHistoryTest.entry(2,"return",PoseDriveHistoryTest.Y,2,0),
            PoseDriveHistoryTest.entry(2,"return",PoseDriveHistoryTest.Y,1,2),
            PoseDriveHistoryTest.entry(2,"prime",PoseDriveHistoryTest.Y,1,0))) {
            var entries=new ArrayList<>(primeEntries);entries.add(bad);
            PoseDriveHistoryTest.reject(()->session.returned(prime,PoseDriveHistoryTest.h(3,3,entries)));
        }
        session.returned(prime,returned);
        session.observed(returned);
        PoseDriveHistoryTest.reject(()->session.returned(returned,returned));
        var restored=PoseDriveHistoryTest.h(5,1,returnedEntries);
        session.restored(restored);session.ready(restored);
        System.out.println("PASS priming return + "+PoseDriveHistoryTest.checks+
            " rejection controls; at most two owned entries; native consumption NOT_EVALUATED");
    }
}
