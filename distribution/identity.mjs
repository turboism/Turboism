/** Secretless GitHub Actions -> sync notification. Only refreshes public release data. */
const ISSUER='https://token.actions.githubusercontent.com';
let cache=null;
function require(ok){if(!ok)throw new Error('Unauthorized sync notification');}
const bytes=s=>Uint8Array.from(atob(s.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
export async function verifyGitHubIdentity(token,fetcher=fetch){
 require(typeof token==='string'&&token.length<12000);const parts=token.split('.');require(parts.length===3);
 const header=JSON.parse(new TextDecoder().decode(bytes(parts[0]))),c=JSON.parse(new TextDecoder().decode(bytes(parts[1]))),now=Math.floor(Date.now()/1000);
 require(header.alg==='RS256'&&typeof header.kid==='string'&&header.kid.length<200);
 require(c.iss===ISSUER&&c.aud==='https://api.turboism.dev'&&c.repository==='turboism/Turboism'&&c.repository_id==='1291982209');
 require(Number.isInteger(c.exp)&&c.exp>now&&Number.isInteger(c.iat)&&c.iat<=now+30&&c.exp-c.iat<=3600&&(!c.nbf||c.nbf<=now+30));
 require(['workflow_run','workflow_dispatch','release'].includes(c.event_name));
 require(c.ref==='refs/heads/main'||/^refs\/tags\/v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(c.ref??''));
 require(c.workflow_ref===`turboism/Turboism/.github/workflows/notify-release-api.yml@${c.ref}`);
 if(!cache||cache.until<now||fetcher!==fetch){
  const r=await fetcher(ISSUER+'/.well-known/jwks',{signal:AbortSignal.timeout(10000),redirect:'error'});require(r.ok);
  const text=await r.text();require(text.length<64000);const data=JSON.parse(text);require(Array.isArray(data.keys));cache={keys:data.keys,until:now+600};
 }
 const jwk=cache.keys.find(k=>k.kid===header.kid&&k.kty==='RSA');require(jwk);
 const key=await crypto.subtle.importKey('jwk',jwk,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['verify']);
 require(await crypto.subtle.verify('RSASSA-PKCS1-v1_5',key,bytes(parts[2]),new TextEncoder().encode(parts[0]+'.'+parts[1])));return true;
}
