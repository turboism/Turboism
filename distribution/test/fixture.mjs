export function fixture(version='1.2.3', prerelease=false, developer=true, legacy=null) {
 const isLegacy = legacy === true || (legacy === null && (version === '1.4.0' || version === '0.44.0' || version === '0.67.0-beta.1'));
 const tag='v'+version, root=`https://github.com/turboism/Turboism/releases/download/${tag}/`;
 const names = (legacy === "standard"
  ? [`TurboismInstaller-${version}.exe`,`turboism-${version}.zip`]
  : isLegacy
  ? [`TurboismInstaller-${version}.exe`,`TurboismInstaller-${version}.jar`,`turboism-${version}-full.zip`,`turboism-${version}-lite.zip`]
  : [`TurboismInstaller-${version}-full.exe`,`TurboismInstaller-${version}-thin.exe`,`turboism-${version}-full.zip`,`turboism-${version}-thin.zip`]
 ).flatMap(n=>[n,n+'.sha256']);
 if(developer)names.push(`turboism-sdk-${version}.jar`,`turboism-sdk-${version}.jar.sha256`);
 if(developer===true||developer==='full')names.push(`turboism-event-processor-${version}.jar`,`turboism-event-processor-${version}.jar.sha256`);
 return {id:123,tag_name:tag,draft:false,prerelease,published_at:'2026-09-09T00:00:00Z',body:'Release notes',html_url:`https://github.com/turboism/Turboism/releases/tag/${tag}`,assets:names.map((name,id)=>({id:id+1,name,state:'uploaded',size:20+id,digest:'sha256:'+'a'.repeat(64),content_type:'application/octet-stream',browser_download_url:root+name}))};
}
