package dev.turboism.validation.modelimport3d.host;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Entirely source-derived experimental Warp preparation. Fixed neutral centroid
 * order is a candidate, not a visibility proof; the production menu must not use
 * this path until native geometry, pixels and source occlusion are reviewed. */
final class SurfaceWarpImportCandidate {
    private SurfaceWarpImportCandidate() { }
    record Config(int tileSide,int cells,int keysPerAxis,double maximumIdealErrorPixels) {
        Config {
            if(tileSide<1 || tileSide>32 || cells<1 || cells>32 || keysPerAxis<3 || keysPerAxis>63
                || keysPerAxis%2!=1 || (long)keysPerAxis*keysPerAxis>4096
                || !Double.isFinite(maximumIdealErrorPixels) || maximumIdealErrorPixels<=0)
                throw new IllegalArgumentException("bounded source Warp configuration required");
        }
    }
    static final Config DEFAULT=new Config(32,16,17,1);
    static final Config FULL_CIRCLE=new Config(32,8,49,5);
    record Prepared(String inputSha256,SurfaceQuadChartDiscovery.Result discovery,
            List<SurfaceWarpChartPlan.Triangle> candidateOrder,SurfaceWarpAuthoring.Prepared authoring) {
        Prepared {candidateOrder=List.copyOf(candidateOrder);}
    }
    static Prepared prepareFramed(byte[] glb,SurfaceImport.Request request,Config config) throws IOException {
        Objects.requireNonNull(glb);Objects.requireNonNull(request);Objects.requireNonNull(config);
        if(glb.length>GlbSurfaceReader.MAX_BYTES)throw new IllegalArgumentException("GLB container byte budget before copy");
        byte[] frozen=glb.clone();var sources=GlbSurfaceReader.read(frozen);
        if(request.roll().isPresent() && sources.stream().anyMatch(GlbSurfaceReader.Surface::doubleSided)) {
            // An opaque closed convex shell has no visible interior back faces. This
            // proof admits source double-sided cube materials without changing geometry.
            ClosedConvexSurfaceProof.verify(sources);
            sources=sources.stream().map(v->new GlbSurfaceReader.Surface(v.node(),v.mesh(),v.primitive(),v.material(),v.positions(),
                v.sourceNormals(),v.textureUvs(),v.indices(),v.baseColor(),false,v.reflected(),v.texture())).toList();
        }
        var camera=SurfaceAutoCamera.fit(sources,request.camera().centerX(),request.camera().centerY(),400);
        var framed=new SurfaceImport.Request(request.name(),request.yaw(),request.pitch(),camera,
            request.yawLimit(),request.pitchLimit(),request.maxProjectedVertices(),request.roll());
        var prepared=prepareDecoded(sources,framed,config);
        try {
            return new Prepared(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frozen)),
                prepared.discovery(),prepared.candidateOrder(),prepared.authoring());
        } catch(NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
    static Prepared prepareDecoded(List<GlbSurfaceReader.Surface> sources,SurfaceImport.Request request,Config config) throws IOException {
        Objects.requireNonNull(sources);Objects.requireNonNull(request);Objects.requireNonNull(config);
        if(sources.stream().anyMatch(GlbSurfaceReader.Surface::doubleSided))throw new IllegalArgumentException("source Warp candidate requires single-sided primitives");
        var discovery=SurfaceQuadChartDiscovery.discover(sources,config.tileSide());
        var order=new ArrayList<SurfaceWarpChartPlan.Triangle>();var depths=new HashMap<SurfaceWarpChartPlan.Triangle,Double>();
        for(var attributed:discovery.attribution()) {
            var triangle=attributed.source();var source=sources.get(triangle.surface());double z=0;
            for(int c=0;c<3;c++)z+=source.positions().get(source.indices().get(triangle.triangle()*3+c)).z()/3;
            if(!Double.isFinite(z))throw new IllegalArgumentException("nonfinite source centroid depth");
            order.add(triangle);depths.put(triangle,z);
        }
        // Stable ties retain source primitive/triangle order. No position/UV welding.
        order.sort(Comparator.comparingDouble((SurfaceWarpChartPlan.Triangle t)->depths.get(t)).reversed());
        int chartCount=discovery.charts().size(),columns=Math.min(chartCount,(int)Math.ceil(Math.sqrt(2d*chartCount)));
        var geometry=SurfaceWarpChartPlan.compile(sources,discovery.attribution(),order,
            new SurfaceWarpChartPlan.Layout(chartCount,config.cells(),columns,config.keysPerAxis(),request.maxProjectedVertices(),65535));
        var yaw=keys(request.yawLimit(),config.keysPerAxis());var pitch=keys(request.pitchLimit(),config.keysPerAxis());
        var authoring=SurfaceWarpAuthoring.prepare(geometry,request,new SurfaceKeyPlan.Grid(yaw,pitch,0),config.maximumIdealErrorPixels());
        return new Prepared("",discovery,order,authoring);
    }
    private static List<Double> keys(double limit,int count) {
        var result=new ArrayList<Double>();int half=count/2;
        for(int i=-half;i<=half;i++)result.add(i==0?0d:limit*(i/(double)half));
        return List.copyOf(result);
    }
}
