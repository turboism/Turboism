package dev.turboism.validation.modelimport3d.host;
import java.nio.file.*;
import java.io.*;
import java.util.*;
public final class PlanarGeometryInputTest {
    interface Work {void run()throws Exception;}
    static int checks;
    static void reject(Work work)throws Exception {
        try{work.run();throw new AssertionError("invalid input accepted");}
        catch(IllegalStateException expected){checks++;}
    }
    public static void main(String[] args)throws Exception {
        var profiles=PlanarGeometryProbe.Profile.values();
        for(var profile:profiles) {
            byte[] bytes=Files.readAllBytes(Path.of(args[0],profile.resource));
            if(!Arrays.equals(bytes,PlanarGeometryProbe.readInput(new ByteArrayInputStream(bytes),profile)))throw new AssertionError();checks++;
            reject(()->PlanarGeometryProbe.readInput(null,profile));
            reject(()->PlanarGeometryProbe.readInput(new ByteArrayInputStream(bytes),null));
            reject(()->PlanarGeometryProbe.readInput(new ByteArrayInputStream(new byte[0]),profile));
            var other=profiles[1-profile.ordinal()];
            reject(()->PlanarGeometryProbe.readInput(new ByteArrayInputStream(bytes),other));
            bytes[bytes.length-1]^=1;
            reject(()->PlanarGeometryProbe.readInput(new ByteArrayInputStream(bytes),profile));
            var oversized=new ByteArrayInputStream(new byte[1024*1024+2]);
            reject(()->PlanarGeometryProbe.readInput(oversized,profile));
            if(oversized.available()!=1)throw new AssertionError("unbounded read");checks++;
            HostProbe.requireMode(profile.mode);checks++;
            if(PlanarGeometryProbe.Profile.forMode(profile.mode)!=profile)throw new AssertionError();checks++;
        }
        reject(()->PlanarGeometryProbe.Profile.forMode("plane"));
        reject(()->HostProbe.requireMode("plane-seam-geometry-other"));
        System.out.println(checks+" planar input/mode checks PASS; hostExecuted=false");
    }
}
