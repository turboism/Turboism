#!/usr/bin/env node
/** Protected-publisher helper: stage producer output, request admission, then prove the public
 * API serves the exact bytes. Run only from the dedicated publisher workflow (GitHub OIDC).
 *   node distribution/publish-framework.mjs <producer-output-dir> <prefix> <keyId> [--initialize]
 * The private signing key never reaches this script; it uploads already-signed documents.
 */
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {readFileSync,readdirSync} from 'node:fs';
import {join} from 'node:path';
import {ORIGIN} from './protocol.mjs';
const BUCKET='turboism-downloads',sha=bytes=>createHash('sha256').update(bytes).digest('hex');
/** Every document/object the candidate references must be readable from the official origin, unredirected and byte-exact. */
export async function verifyPublic(candidate,expected,fetcher=fetch,origin=ORIGIN){
 for(const [key,bytes] of expected){
  const url=`${origin}/v1/framework/${key}`,hash=key.startsWith('documents/')?key.slice(10):key.slice(8,72);
  const response=await fetcher(url,{redirect:'manual'});
  if(response.status!==200||response.url&&response.url!==url)throw new Error(`Public ${key}: HTTP ${response.status}`);
  const body=new Uint8Array(await response.arrayBuffer());
  if(response.headers.get('etag')!==`"${hash}"`||Number(response.headers.get('content-length'))!==bytes.length||body.length!==bytes.length||sha(body)!==hash)throw new Error(`Public ${key}: content mismatch`);
  const range=await fetcher(url,{headers:{Range:'bytes=0-0','If-Range':`"${hash}"`},redirect:'manual'});
  if(range.status!==206||range.headers.get('content-range')!==`bytes 0-0/${bytes.length}`)throw new Error(`Public ${key}: Range unsupported`);
  await range.arrayBuffer();
 }
 return candidate.identity;
}
export function collect(directory,prefix){
 const candidate=JSON.parse(readFileSync(join(directory,prefix+'-incremental.json'))),expected=new Map();
 for(const [name,ref] of [['-manifest.json',candidate.manifest],['-manifest.json.sig',candidate.envelope]])expected.set('documents/'+ref.sha256,readFileSync(join(directory,prefix+name)));
 for(const d of readdirSync(join(directory,'objects')))expected.set(`objects/${d}/payload`,readFileSync(join(directory,'objects',d,'payload')));
 return {candidate,expected};
}
/** Withdraw an admitted candidate release so it is no longer advertised by the release API. */
export async function withdrawCandidate({id,token,origin=ORIGIN,fetcher=fetch}){
 const authToken=typeof token==='object'&&token?.value?token.value:token;
 const headers={'Content-Type':'application/json'};
 if(authToken)headers.Authorization=`Bearer ${authToken}`;
 const response=await fetcher(`${origin}/v1/framework/withdraw`,{
  method:'POST',
  headers,
  body:JSON.stringify({id})
 });
 const text=await response.text();let body=null;try{body=JSON.parse(text);}catch{}
 if(!response.ok||!body||body.matched!==true){
  throw new Error(`Withdrawal failed (HTTP ${response.status}): ${text}`);
 }
 return body;
}
/** Request admission for candidate, run public preflight verification, and roll back via withdrawal if verification fails. */
export async function publishCandidate({candidate,expected,keyId,initialize=false,token,origin=ORIGIN,fetcher=fetch,verifier=verifyPublic}){
 const authToken=typeof token==='object'&&token?.value?token.value:token;
 const headers={'Content-Type':'application/json'};
 if(authToken)headers.Authorization=`Bearer ${authToken}`;
 const response=await fetcher(`${origin}/v1/framework/admit`,{
  method:'POST',
  headers,
  body:JSON.stringify({candidate,expected:{identity:candidate.identity,keyId},initialize})
 });
 const result=await response.text();
 if(!response.ok)throw new Error(`Admission failed (HTTP ${response.status}): ${result}`);
 let body=null;try{body=JSON.parse(result);}catch{}
 const id=body?.id||candidate.manifest?.sha256;
 try{
  await verifier(candidate,expected,fetcher,origin);
 }catch(preflightError){
  let withdrawError=null;
  try{
   await withdrawCandidate({id,token:authToken,origin,fetcher});
  }catch(err){
   withdrawError=err;
  }
  if(withdrawError){
   const error=new Error(`Public preflight failed: ${preflightError.message}; rollback withdrawal failed: ${withdrawError.message}`);
   error.cause={preflightError,withdrawError};
   throw error;
  }
  const error=new Error(`Public preflight failed: ${preflightError.message}; candidate ${id} withdrawn`);
  error.cause=preflightError;
  error.withdrawn=true;
  error.id=id;
  throw error;
 }
 return {status:'published',id,identity:candidate.identity,candidate};
}
export const publishFramework=publishCandidate;
async function main([directory,prefix,keyId,flag]){
 const {candidate,expected}=collect(directory,prefix),token=await (await fetch(`${process.env.ACTIONS_ID_TOKEN_REQUEST_URL}&audience=${encodeURIComponent(ORIGIN)}`,{headers:{Authorization:`Bearer ${process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN}`}})).json();
 for(const [key] of expected){ // objects first; the Worker refuses to register anything not matching the signed manifest
  const file=key.startsWith('documents/')?join(directory,prefix+(key.endsWith(candidate.manifest.sha256)?'-manifest.json':'-manifest.json.sig')):join(directory,key);
  execFileSync('npx',['--yes','wrangler@4.127.0','r2','object','put',`${BUCKET}/framework-incoming/${key}`,'--file',file,'--remote'],{stdio:'inherit'});
 }
 const published=await publishCandidate({
  candidate,
  expected,
  keyId,
  initialize:flag==='--initialize',
  token:token.value,
 });
 console.log('admit 200 id:',published.id);
 console.log('public preflight ok; descriptor to publish:',JSON.stringify(published.candidate));
}
if(import.meta.url===`file://${process.argv[1]}`)await main(process.argv.slice(2));
