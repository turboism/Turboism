package dev.turboism.validation.modelimport3d.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual authorized input and producer on a synthetic SDK; not host acceptance. */
public final class CubeGeometryProducerIntegrationTest {
    public static void main(String[] args) throws Exception {
        SurfaceImportGeometryTest.main(new String[]{args[0]});
        byte[] input=Files.readAllBytes(Path.of(args[0]));
        Path evidence=Path.of(args[1]);Files.createDirectory(evidence);
        var world=new JointPoseProducerIntegrationTest.World(false);
        var data=new LinkedHashMap<String,Object>();data.put("schemaVersion","mi3d-cube-geometry-v1");
        CubeGeometryProbe.run(input,world::model,world::history,world::restore,world.commands(),world.transactions(),data);
        NativeAssertions.require("PASS".equals(data.get("status")) && world.meshes.isEmpty() && world.defs.isEmpty(),
            "cube producer did not restore its synthetic document");
        Path report=evidence.resolve("synthetic-report.json");
        Files.writeString(report,NativeReport.json(Map.of("evidenceKind","OFFLINE_FIXTURE","cubeGeometry",data)));
        var process=new ProcessBuilder("python3","-B","-c",
            "import json,runpy,sys; runpy.run_path(sys.argv[1])['verify_cube_geometry'](json.load(open(sys.argv[2])),sys.argv[3])",
            "validation/model-import-3d-host-probe/verify-cube-geometry.py",report.toString(),args[0])
            .redirectErrorStream(true).redirectOutput(evidence.resolve("oracle.log").toFile()).start();
        int exit=process.waitFor();
        NativeAssertions.require(exit==0,"cube producer/oracle mismatch: "+Files.readString(evidence.resolve("oracle.log")));
        var insetWorld=new JointPoseProducerIntegrationTest.World(false);
        var inset=new LinkedHashMap<String,Object>();inset.put("schemaVersion","mi3d-cube-geometry-v1");
        CubeGeometryProbe.run(input,insetWorld::model,insetWorld::history,insetWorld::restore,
            insetWorld.commands(),insetWorld.transactions(),inset,null,SurfaceImportGeometry.UvMode.FLAT_COLOR_INTERIOR);
        Path insetReport=evidence.resolve("synthetic-report-inset.json");
        Files.writeString(insetReport,NativeReport.json(Map.of("evidenceKind","OFFLINE_FIXTURE",
            "mi3dMode","cube-render","cubeGeometry",inset)));
        var insetProcess=new ProcessBuilder("python3","-B","-c",
            """
            import json,runpy,sys
            m=runpy.run_path(sys.argv[1]); r=json.load(open(sys.argv[2]))
            m['verify_cube_geometry'](r,sys.argv[3])
            r['mi3dMode']='cube-geometry'
            try: m['verify_cube_geometry'](r,sys.argv[3])
            except ValueError: pass
            else: raise AssertionError('undeclared UV policy accepted')
            """,
            "validation/model-import-3d-host-probe/verify-cube-geometry.py",insetReport.toString(),args[0])
            .redirectErrorStream(true).redirectOutput(evidence.resolve("oracle-inset.log").toFile()).start();
        NativeAssertions.require(insetProcess.waitFor()==0,"inset producer/oracle mismatch: "+Files.readString(evidence.resolve("oracle-inset.log")));
        var wrong=new JointPoseProducerIntegrationTest.World(true);var failed=new LinkedHashMap<String,Object>();
        try {
            CubeGeometryProbe.run(input,wrong::model,wrong::history,wrong::restore,wrong.commands(),wrong.transactions(),failed);
            throw new AssertionError("wrong calculated cube geometry accepted");
        } catch(Exception expected) {
            NativeAssertions.require("FAIL".equals(failed.get("status")) && Boolean.TRUE.equals(failed.get("finallyRestored"))
                && wrong.meshes.isEmpty() && wrong.defs.isEmpty(),"cube failure did not restore synthetic state");
        }
        input[0]^=1;var rejected=new JointPoseProducerIntegrationTest.World(false);
        try {
            CubeGeometryProbe.run(input,rejected::model,rejected::history,rejected::restore,rejected.commands(),
                rejected.transactions(),new LinkedHashMap<>());
            throw new AssertionError("wrong input hash accepted");
        } catch(IllegalStateException expected) {
            NativeAssertions.require(rejected.meshes.isEmpty() && rejected.defs.isEmpty() && rejected.position==0,
                "changed source mutated the document before admission");
        }
        System.out.println("CUBE PRODUCER_E2E PASS; synthetic evaluator; hostExecuted=false; artifacts="+evidence);
    }
}
