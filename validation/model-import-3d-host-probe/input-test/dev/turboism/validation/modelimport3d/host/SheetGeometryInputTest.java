package dev.turboism.validation.modelimport3d.host;
import java.nio.file.*;
import java.io.*;
import java.util.*;
public final class SheetGeometryInputTest {
    interface Work {void run()throws Exception;}
    static int checks;
    static void reject(Work work)throws Exception {
        try{work.run();throw new AssertionError("invalid input accepted");}
        catch(IllegalStateException expected){checks++;}
    }
    public static void main(String[] args)throws Exception {
        byte[] bytes=Files.readAllBytes(Path.of(args[0]));
        if(!Arrays.equals(bytes,SheetGeometryProbe.readInput(new ByteArrayInputStream(bytes))))throw new AssertionError();checks++;
        reject(()->SheetGeometryProbe.readInput(null));
        reject(()->SheetGeometryProbe.readInput(new ByteArrayInputStream(new byte[0])));
        bytes[bytes.length-1]^=1;reject(()->SheetGeometryProbe.readInput(new ByteArrayInputStream(bytes)));
        var oversized=new ByteArrayInputStream(new byte[1024*1024+2]);
        reject(()->SheetGeometryProbe.readInput(oversized));
        if(oversized.available()!=1)throw new AssertionError("unbounded read");checks++;
        HostProbe.requireMode("sheet-geometry");checks++;
        reject(()->HostProbe.requireMode("sheet-geometry-other"));
        System.out.println(checks+" input/mode checks PASS; hostExecuted=false");
    }
}
