package dev.turboism.validation.modelimport3d.host;

import java.nio.file.*;
import java.util.Map;

/** Actual parser/preparation rejection; native menu rejection remains a separate gate. */
public final class IntersectingInputTest {
    public static void main(String[] args)throws Exception {
        byte[] input=Files.readAllBytes(Path.of(args[0]));
        var surfaces=GlbSurfaceReader.read(input);
        if(surfaces.size()!=2 || surfaces.stream().anyMatch(s->s.doubleSided()
                || s.positions().size()!=4 || s.indices().size()!=6 || s.baseColor().get(3)!=1d))
            throw new AssertionError("fixture must parse as two opaque single-sided quads");
        var world=new SurfaceImportTest.World();var before=world.snapshot();
        String reason;
        try {
            var prepared=SurfaceImport.prepare(input,SurfaceImportTest.request());
            // Never apply an unexpectedly accepted counterexample, even in this synthetic test.
            throw new AssertionError("intersecting fixture admitted: "+prepared.inputSha256);
        } catch(IllegalArgumentException expected) {
            reason=expected.getMessage();
            if(!reason.contains("nonconvex or inward-facing triangle"))
                throw new AssertionError("rejected for unrelated parser/material failure",expected);
        }
        if(world.calls!=0 || world.modelReads!=0 || !world.snapshot().equals(before))
            throw new AssertionError("preparation touched authoring");
        System.out.println(NativeReport.json(Map.of("status","PASS","hostExecuted",false,
            "scope","INTERSECTING_GLB_PREPARATION_REJECTION","reason",reason,
            "parsedSurfaces",surfaces.size(),"authoringCalls",world.calls)));
    }
}
