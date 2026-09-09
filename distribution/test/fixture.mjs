export function fixture(version='1.2.3', prerelease=false) {
 const tag='v'+version, root=`https://github.com/turboism/Turboism/releases/download/${tag}/`;
 const names=[`TurboismInstaller-${version}.exe`,`TurboismInstaller-${version}.jar`,`turboism-${version}-full.zip`,`turboism-${version}-lite.zip`].flatMap(n=>[n,n+'.sha256']);
 return {id:123,tag_name:tag,draft:false,prerelease,published_at:'2026-09-09T00:00:00Z',body:'Release notes',html_url:`https://github.com/turboism/Turboism/releases/tag/${tag}`,assets:names.map((name,id)=>({id:id+1,name,state:'uploaded',size:20+id,digest:'sha256:'+'a'.repeat(64),content_type:'application/octet-stream',browser_download_url:root+name}))};
}
