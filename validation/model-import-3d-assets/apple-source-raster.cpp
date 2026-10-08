// Independent source-depth + source-UV oracle. No Cubism, painter order or Warp fitting.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>
struct Vertex { float x,y,z,u,v; };
struct Triangle { std::array<Vertex,3> v; uint32_t material; };
struct Screen { double x,y,z,u,v; };
static constexpr int size=1024,width=256,height=128;
static std::array<uint8_t,4> sample(const std::vector<uint8_t>& tex,double u,double v) {
    double x=std::clamp(u*width-.5,0.,double(width-1)),y=std::clamp(v*height-.5,0.,double(height-1));
    int a=int(std::floor(x)),b=int(std::floor(y)),c=std::min(a+1,width-1),d=std::min(b+1,height-1);double fx=x-a,fy=y-b;
    std::array<uint8_t,4> out{};
    for(int k=0;k<4;k++)out[k]=uint8_t(std::lround((1-fy)*((1-fx)*tex[(b*width+a)*4+k]+fx*tex[(b*width+c)*4+k])+fy*((1-fx)*tex[(d*width+a)*4+k]+fx*tex[(d*width+c)*4+k])));
    return out;
}
int main(int argc,char** argv) {
 try {
    if(argc!=3)throw std::runtime_error("source input and output prefix required");
    std::ifstream in(argv[1],std::ios::binary);char magic[8];in.read(magic,8);uint32_t count;in.read(reinterpret_cast<char*>(&count),4);
    if(!in || std::memcmp(magic,"ASRC0001",8) || count!=150784)throw std::runtime_error("source header");
    if(sizeof(Triangle)!=64)throw std::runtime_error("unexpected triangle layout");
    std::vector<Triangle> triangles(count);in.read(reinterpret_cast<char*>(triangles.data()),count*sizeof(Triangle));
    std::array<std::vector<uint8_t>,2> textures;
    for(auto& tex:textures){tex.resize(width*height*4);in.read(reinterpret_cast<char*>(tex.data()),tex.size());}
    if(!in || in.peek()!=std::char_traits<char>::eof())throw std::runtime_error("source length");
    for(const auto& tri:triangles){if(tri.material>1)throw std::runtime_error("source material");for(auto p:tri.v)if(!std::isfinite(p.x)||!std::isfinite(p.y)||!std::isfinite(p.z)||!std::isfinite(p.u)||!std::isfinite(p.v)||p.u<0||p.u>1||p.v<0||p.v>1)throw std::runtime_error("source coordinate");}
    std::vector<std::pair<double,double>> poses;
    for(double y:{-30.,0.,30.})for(double p:{-30.,0.,30.})poses.emplace_back(y,p);
    poses.emplace_back(-13.125,9.375);poses.emplace_back(16.875,-24.375);
    for(size_t pose=0;pose<poses.size();pose++) {
        double yaw=poses[pose].first*std::acos(-1.)/180,pitch=poses[pose].second*std::acos(-1.)/180;
        double cy=std::cos(yaw),sy=std::sin(yaw),cp=std::cos(pitch),sp=std::sin(pitch);
        std::vector<double> depth(size*size,std::numeric_limits<double>::infinity());std::vector<uint8_t> rgba(size*size*4,0);
        for(const auto& tri:triangles) {
            std::array<Screen,3> s;
            for(int i=0;i<3;i++){auto a=tri.v[i];double y=a.y-130.6125549674034,yp=cp*y-sp*a.z,zp=sp*y+cp*a.z;s[i]={512+2.954462184876577*(cy*a.x+sy*zp),512-2.954462184876577*yp,-sy*a.x+cy*zp,a.u,a.v};}
            auto a=s[0],b=s[1],c=s[2];double den=(b.y-c.y)*(a.x-c.x)+(c.x-b.x)*(a.y-c.y);
            if(den<=1e-12)continue; // Source winding, not centroid depth.
            int x0=std::max(0,int(std::ceil(std::min({a.x,b.x,c.x})-.5))),x1=std::min(size-1,int(std::floor(std::max({a.x,b.x,c.x})-.5)));
            int y0=std::max(0,int(std::ceil(std::min({a.y,b.y,c.y})-.5))),y1=std::min(size-1,int(std::floor(std::max({a.y,b.y,c.y})-.5)));
            for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++) {
                double u=((b.y-c.y)*(x+.5-c.x)+(c.x-b.x)*(y+.5-c.y))/den;
                double v=((c.y-a.y)*(x+.5-c.x)+(a.x-c.x)*(y+.5-c.y))/den,w=1-u-v;
                if(u<-1e-10||v<-1e-10||w<-1e-10)continue;
                double z=u*a.z+v*b.z+w*c.z;size_t offset=y*size+x;
                if(z<depth[offset]){depth[offset]=z;auto pixel=sample(textures[tri.material],u*a.u+v*b.u+w*c.u,u*a.v+v*b.v+w*c.v);std::copy(pixel.begin(),pixel.end(),rgba.begin()+offset*4);}
            }
        }
        std::string prefix=std::string(argv[2])+std::to_string(pose);
        std::ofstream image(prefix+".rgba",std::ios::binary);image.write(reinterpret_cast<char*>(rgba.data()),rgba.size());
        std::ofstream z(prefix+".depth",std::ios::binary);z.write(reinterpret_cast<char*>(depth.data()),depth.size()*sizeof(double));
        if(!image || !z)throw std::runtime_error("output write");
        std::cout<<"SOURCE_REFERENCE pose="<<pose<<" yaw="<<poses[pose].first<<" pitch="<<poses[pose].second<<"\n";
    }
 }catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
}
