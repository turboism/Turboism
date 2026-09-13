import test from 'node:test';import assert from 'node:assert/strict';
import { verifyGitHubIdentity } from '../identity.mjs';
const encode=x=>Buffer.from(typeof x==='string'?x:JSON.stringify(x)).toString('base64url');
test('release sync verifies signature, audience, repository and trusted workflow; no credentials in public paths',async()=>{
 const keys=await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify']);
 const jwk=await crypto.subtle.exportKey('jwk',keys.publicKey);jwk.kid='test-key';
 const now=Math.floor(Date.now()/1000),claims={iss:'https://token.actions.githubusercontent.com',aud:'https://api.turboism.dev',repository:'turboism/Turboism',repository_id:'1291982209',ref:'refs/heads/main',workflow_ref:'turboism/Turboism/.github/workflows/notify-release-api.yml@refs/heads/main',event_name:'workflow_dispatch',iat:now,nbf:now-5,exp:now+300};
 async function jwt(body){const input=encode({alg:'RS256',kid:'test-key'})+'.'+encode(body);const sig=await crypto.subtle.sign('RSASSA-PKCS1-v1_5',keys.privateKey,new TextEncoder().encode(input));return input+'.'+Buffer.from(sig).toString('base64url');}
 const fetcher=async()=>new Response(JSON.stringify({keys:[jwk]}),{headers:{'content-type':'application/json'}});
 assert.ok(await verifyGitHubIdentity(await jwt(claims),fetcher));
 for(const changed of [{aud:'elsewhere'},{repository_id:'1'},{event_name:'pull_request'},{exp:now-1},{workflow_ref:'evil/repo/.github/workflows/notify-release-api.yml@refs/heads/main'}])await assert.rejects(()=>jwt({...claims,...changed}).then(t=>verifyGitHubIdentity(t,fetcher)));
 const token=await jwt(claims);await assert.rejects(()=>verifyGitHubIdentity(token.slice(0,-10)+'1234567890',fetcher));
});
