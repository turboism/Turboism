package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.Point2;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Versioned binary fingerprint of every authored snapshot field; independently reproducible from JSON. */
final class SurfaceSnapshotFingerprint {
    private SurfaceSnapshotFingerprint() { }
    static String of(ArtMeshAuthoringSnapshot s) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest));
            text(out,"mi3d-snapshot-fingerprint-v1");text(out,s.artMeshId().value());text(out,s.sourceGuid());
            points(out,s.sourceGeometry().positions());points(out,s.sourceGeometry().uvs());
            out.writeInt(s.sourceGeometry().triangleIndices().size());for(int i:s.sourceGeometry().triangleIndices())out.writeInt(i);
            out.writeInt(s.axes().size());
            for(var a:s.axes()) {
                text(out,a.parameterGuid());text(out,a.parameterId().value());
                out.writeFloat(a.minimum());out.writeFloat(a.defaultValue());out.writeFloat(a.maximum());
                out.writeInt(a.keys().size());for(float k:a.keys())out.writeFloat(k);
            }
            out.writeInt(s.cells().size());
            for(var c:s.cells()) {
                text(out,c.formGuid());out.writeInt(c.coordinates().size());
                for(var v:c.coordinates()) {text(out,v.parameterGuid());out.writeFloat(v.value());}
            }
            out.writeInt(s.forms().size());
            for(var f:s.forms()) {text(out,f.formGuid());points(out,f.positions());out.writeInt(f.drawOrder());}
            out.writeInt(s.morphKeys().size());
            for(var m:s.morphKeys()) {
                text(out,m.parameterGuid());text(out,m.formGuid());out.writeBoolean(m.keyValue().isPresent());
                if(m.keyValue().isPresent())out.writeFloat(m.keyValue().orElseThrow());
            }
            out.flush();return HexFormat.of().formatHex(digest.digest());
        } catch(java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("snapshot fingerprint unavailable",failure);
        }
    }
    private static void text(DataOutputStream out,String value) throws java.io.IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    private static void points(DataOutputStream out,List<Point2> points) throws java.io.IOException {
        out.writeInt(points.size());for(var p:points) {out.writeFloat(p.x());out.writeFloat(p.y());}
    }
}
