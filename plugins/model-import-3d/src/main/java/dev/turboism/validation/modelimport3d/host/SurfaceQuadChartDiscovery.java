package dev.turboism.validation.modelimport3d.host;

import java.util.*;

/** Source-preserving chart discovery for closed quad-paired triangle primitives.
 * Exact world-position equivalence establishes topology only; source slots, UVs,
 * indices and triangle order are retained. This is not a visibility/order proof. */
final class SurfaceQuadChartDiscovery {
    private SurfaceQuadChartDiscovery() { }
    record Chart(int surface,int width,int height,int quads) { }
    record Result(List<SurfaceWarpChartPlan.ChartTriangle> attribution,List<Chart> charts) {
        Result { attribution=List.copyOf(attribution);charts=List.copyOf(charts); }
    }
    private record Edge(int a,int b) {
        static Edge of(int a,int b) {return new Edge(Math.min(a,b),Math.max(a,b));}
    }
    private record Link(int face,int from,int to) { }
    private record Step(int face,int from,int to) { }
    private record State(int previous,int current) { }
    private record Grid(int x,int y) {
        Grid add(Grid other) {return new Grid(x+other.x,y+other.y);}
        Grid subtract(Grid other) {return new Grid(x-other.x,y-other.y);}
    }
    static Result discover(List<GlbSurfaceReader.Surface> sources,int maxSide) {
        return discover(sources,maxSide,false);
    }
    /** Optional source-preserving packing; default chart identities stay stable. */
    static Result discoverRectangular(List<GlbSurfaceReader.Surface> sources,int maxSide) {
        return discover(sources,maxSide,true);
    }
    private static Result discover(List<GlbSurfaceReader.Surface> sources,int maxSide,boolean rectangular) {
        Objects.requireNonNull(sources);
        require(!sources.isEmpty() && sources.size()<=1024 && maxSide>=1 && maxSide<=32,"bounded source/chart tile inputs");
        long slots=0,triangles=0;
        for(var source:sources) {
            slots+=source.positions().size();triangles+=source.indices().size()/3;
            require(!source.positions().isEmpty() && source.positions().size()==source.textureUvs().size()
                && !source.indices().isEmpty() && source.indices().size()%6==0,"complete source quad triangle pairs required");
            require(slots<=100000 && triangles<=200000,"source chart discovery budget before topology allocation");
        }
        var charts=new ArrayList<Chart>();var attribution=new ArrayList<SurfaceWarpChartPlan.ChartTriangle>();
        for(int s=0;s<sources.size();s++)discoverSurface(sources.get(s),s,maxSide,rectangular,charts,attribution);
        return new Result(attribution,charts);
    }
    private static void discoverSurface(GlbSurfaceReader.Surface source,int surface,int maxSide,boolean rectangular,
            ArrayList<Chart> charts,ArrayList<SurfaceWarpChartPlan.ChartTriangle> attribution) {
        var topology=new HashMap<SurfaceProjection.Vertex,Integer>();int[] slotTopology=new int[source.positions().size()];
        for(int i=0;i<slotTopology.length;i++) {
            var p=source.positions().get(i);require(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()),"finite world positions required");
            // Normalize signed zero in the topological relation, never in rendering data.
            var key=new SurfaceProjection.Vertex(p.x()==0?0:p.x(),p.y()==0?0:p.y(),p.z()==0?0:p.z());
            slotTopology[i]=topology.computeIfAbsent(key,k->topology.size());
        }
        int count=source.indices().size()/6;int[][] quads=new int[count][4];
        var links=new HashMap<Edge,ArrayList<Link>>();var neighbors=new ArrayList<Set<Integer>>();
        var fans=new ArrayList<Map<Integer,Set<Integer>>>();int[] incident=new int[topology.size()];
        for(int i=0;i<topology.size();i++){neighbors.add(new TreeSet<>());fans.add(new HashMap<>());}
        for(int f=0;f<count;f++) {
            int[] ix=new int[6];for(int c=0;c<6;c++) {ix[c]=source.indices().get(f*6+c);require(ix[c]>=0 && ix[c]<slotTopology.length,"source index outside slot domain");}
            require(ix[0]==ix[3] && ix[2]==ix[4],"source triangles must retain quad 0-2 diagonal");
            int[] q=quads[f];for(int c=0;c<4;c++)q[c]=slotTopology[ix[c==3?5:c]];
            require(Arrays.stream(q).distinct().count()==4,"collapsed source quad");
            for(int c=0;c<4;c++) {
                int v=q[c],previous=q[(c+3)%4],next=q[(c+1)%4];incident[v]++;
                var entries=links.computeIfAbsent(Edge.of(v,next),k->new ArrayList<>());entries.add(new Link(f,v,next));
                require(entries.size()<=2,"nonmanifold source edge");
                neighbors.get(v).add(previous);neighbors.get(v).add(next);
                fans.get(v).computeIfAbsent(previous,k->new HashSet<>()).add(next);
                fans.get(v).computeIfAbsent(next,k->new HashSet<>()).add(previous);
            }
        }
        for(var entries:links.values())require(entries.size()==2 && entries.get(0).from()==entries.get(1).to()
            && entries.get(0).to()==entries.get(1).from(),"closed oriented two-manifold edge required");
        var opposite=new HashMap<State,Integer>();var extraordinary=new ArrayList<Integer>();
        for(int v=0;v<topology.size();v++) {
            var fan=fans.get(v);require(!fan.isEmpty() && fan.size()==incident[v] && fan.values().stream().allMatch(n->n.size()==2),"ambiguous source vertex fan");
            var seen=new HashSet<Integer>();var todo=new ArrayDeque<Integer>();todo.add(fan.keySet().iterator().next());
            while(!todo.isEmpty()) {int n=todo.remove();if(seen.add(n))todo.addAll(fan.get(n));}
            require(seen.size()==fan.size(),"coincident disconnected source vertex fans unsupported");
            if(neighbors.get(v).size()!=4)extraordinary.add(v);
            else for(int incoming:neighbors.get(v)) {
                var remaining=new HashSet<>(neighbors.get(v));remaining.removeAll(fan.get(incoming));remaining.remove(incoming);
                require(remaining.size()==1,"regular quad fan has no unique opposite edge");
                opposite.put(new State(incoming,v),remaining.iterator().next());
            }
        }
        require(!extraordinary.isEmpty(),"closed fully regular periodic surfaces need explicit cuts");
        var cuts=new HashSet<Edge>();
        for(int start:extraordinary)for(int first:neighbors.get(start)) {
            if(cuts.contains(Edge.of(start,first)))continue;
            int previous=start,current=first;var walked=new HashSet<State>();
            while(true) {
                var state=new State(previous,current);require(walked.add(state),"singularity trace entered periodic regular loop");
                cuts.add(Edge.of(previous,current));if(neighbors.get(current).size()!=4)break;
                int next=opposite.get(state);previous=current;current=next;
            }
        }
        var adjacency=new ArrayList<ArrayList<Step>>();for(int f=0;f<count;f++)adjacency.add(new ArrayList<>());
        for(var entry:links.entrySet())if(!cuts.contains(entry.getKey())) {
            var a=entry.getValue().get(0);var b=entry.getValue().get(1);
            adjacency.get(a.face()).add(new Step(b.face(),b.from(),b.to()));adjacency.get(b.face()).add(new Step(a.face(),a.from(),a.to()));
        }
        for(var steps:adjacency)steps.sort(Comparator.comparingInt(Step::face));
        boolean[] visited=new boolean[count];int[] chartIds=new int[count];Arrays.fill(chartIds,-1);
        SurfaceWarpChartPlan.Parameter[][] parameters=new SurfaceWarpChartPlan.Parameter[count][4];
        for(int first=0;first<count;first++) {
            if(visited[first])continue;
            var assigned=new HashMap<Integer,Grid>();Grid[] initial={new Grid(0,0),new Grid(1,0),new Grid(1,1),new Grid(0,1)};
            for(int c=0;c<4;c++)assigned.put(quads[first][c],initial[c]);
            var component=new ArrayList<Integer>();var todo=new ArrayDeque<Integer>();todo.add(first);visited[first]=true;
            while(!todo.isEmpty()) {
                int f=todo.remove();component.add(f);
                for(var step:adjacency.get(f)) {
                    var pu=assigned.get(step.from());var pv=assigned.get(step.to());var delta=pv.subtract(pu);
                    require(Math.abs(delta.x())+Math.abs(delta.y())==1,"transported edge is not a unit grid step");
                    var left=new Grid(-delta.y(),delta.x());int[] q=quads[step.face()];int k=0;while(k<4 && q[k]!=step.from())k++;
                    require(k<4 && q[(k+1)%4]==step.to(),"source winding differs across chart transport");
                    Grid[] points={pu,pv,pv.add(left),pu.add(left)};
                    for(int c=0;c<4;c++) {var old=assigned.putIfAbsent(q[(k+c)%4],points[c]);require(old==null || old.equals(points[c]),"nonrectangular/periodic chart transport conflict");}
                    if(!visited[step.face()]){visited[step.face()]=true;todo.add(step.face());}
                }
            }
            require(new HashSet<>(assigned.values()).size()==assigned.size(),"chart overlaps distinct source vertices");
            int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE;
            for(var p:assigned.values()){minX=Math.min(minX,p.x());minY=Math.min(minY,p.y());maxX=Math.max(maxX,p.x());maxY=Math.max(maxY,p.y());}
            int width=maxX-minX,height=maxY-minY;
            require(width>0 && height>0 && (long)width*height==component.size()
                && (long)(width+1)*(height+1)==assigned.size(),"chart component is not a full rectangular source grid");
            var cells=new HashMap<Grid,Integer>();
            for(int f:component) {
                int lx=Integer.MAX_VALUE,ly=Integer.MAX_VALUE,hx=Integer.MIN_VALUE,hy=Integer.MIN_VALUE;
                for(int v:quads[f]){var p=assigned.get(v);lx=Math.min(lx,p.x());ly=Math.min(ly,p.y());hx=Math.max(hx,p.x());hy=Math.max(hy,p.y());}
                require(hx-lx==1 && hy-ly==1 && cells.put(new Grid(lx-minX,ly-minY),f)==null,"duplicate/non-unit source chart cell");
            }
            // Rectangular source strips need independent axis bounds. Using the
            // shorter side for both axes unnecessarily splits a long thin strip
            // into many square ArtMeshes without adding source geometry.
            int shortSide=Math.min(maxSide,Math.min(width,height));
            int tileWidth=rectangular?Math.min(maxSide,width):shortSide;
            int tileHeight=rectangular?Math.min(maxSide,height):shortSide;
            for(int by=0;by<height;by+=tileHeight)for(int bx=0;bx<width;bx+=tileWidth) {
                int w=Math.min(tileWidth,width-bx),h=Math.min(tileHeight,height-by),chart=charts.size();
                require(chart<1024,"discovered chart budget");charts.add(new Chart(surface,w,h,w*h));
                for(int y=by;y<by+h;y++)for(int x=bx;x<bx+w;x++) {
                    Integer f=cells.get(new Grid(x,y));require(f!=null,"missing rectangular chart cell");chartIds[f]=chart;
                    for(int c=0;c<4;c++){var p=assigned.get(quads[f][c]);parameters[f][c]=new SurfaceWarpChartPlan.Parameter((p.x()-minX-bx)/(double)w,(p.y()-minY-by)/(double)h);}
                }
            }
        }
        for(int f=0;f<count;f++) {
            require(chartIds[f]>=0,"incomplete original face attribution");var p=parameters[f];
            attribution.add(new SurfaceWarpChartPlan.ChartTriangle(new SurfaceWarpChartPlan.Triangle(surface,f*2),chartIds[f],List.of(p[0],p[1],p[2])));
            attribution.add(new SurfaceWarpChartPlan.ChartTriangle(new SurfaceWarpChartPlan.Triangle(surface,f*2+1),chartIds[f],List.of(p[0],p[2],p[3])));
        }
    }
    private static void require(boolean ok,String message) {if(!ok)throw new IllegalArgumentException(message);}
}
