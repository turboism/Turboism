#!/usr/bin/env node
/** Protected-publisher helper: stage producer output, request admission, then prove the public
 * API serves the exact bytes. Run only from the dedicated publisher workflow (GitHub OIDC).
 *   node distribution/publish-framework.mjs <producer-output-dir> <prefix> <keyId> [--initialize] [--reactivate]
 * The private signing key never reaches this script; it uploads already-signed documents.
 */
import {createHash} from 'node:crypto';
import {execFile} from 'node:child_process';
import {readFileSync,readdirSync,appendFileSync} from 'node:fs';
import {join} from 'node:path';
import {promisify} from 'node:util';
import {ORIGIN} from './protocol.mjs';
const execFileAsync=promisify(execFile);
const BUCKET='turboism-downloads',WRANGLER_SPEC='wrangler@4.127.0',R2_API_ORIGIN='https://api.cloudflare.com/client/v4';
const UPLOAD_CONCURRENCY=8,PUBLIC_VERIFY_CONCURRENCY=8,TRANSIENT_STATUSES=new Set([429,500,502,503,504]),MAX_UPLOAD_ATTEMPTS=3;
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
async function mapPool(items,limit,worker){
 const list=[...items];if(!list.length)return [];
 const results=new Array(list.length);let next=0,firstError=null;
 const count=Math.min(limit,list.length);
 await Promise.all(Array.from({length:count},async()=>{
  while(!firstError){
   const index=next++;if(index>=list.length)return;
   try{results[index]=await worker(list[index],index);}
   catch(err){if(!firstError)firstError=err;}
  }
 }));
 if(firstError)throw firstError;
 return results;
}
/** Every document/object the candidate references must be readable from the official origin, unredirected and byte-exact. */
export async function verifyPublic(candidate,expected,fetcher=fetch,origin=ORIGIN){
 await mapPool(expected.entries(),PUBLIC_VERIFY_CONCURRENCY,async([key,bytes])=>{
  const url=`${origin}/v1/framework/${key}`,hash=key.startsWith('documents/')?key.slice(10):key.slice(8,72);
  const response=await fetcher(url,{redirect:'manual'});
  if(response.status!==200||response.url&&response.url!==url)throw new Error(`Public ${key}: HTTP ${response.status}`);
  const body=new Uint8Array(await response.arrayBuffer());
  if(response.headers.get('etag')!==`"${hash}"`||Number(response.headers.get('content-length'))!==bytes.length||body.length!==bytes.length||sha(body)!==hash)throw new Error(`Public ${key}: content mismatch`);
  const range=await fetcher(url,{headers:{Range:'bytes=0-0','If-Range':`"${hash}"`},redirect:'manual'});
  if(range.status!==206||range.headers.get('content-range')!==`bytes 0-0/${bytes.length}`)throw new Error(`Public ${key}: Range unsupported`);
  await range.arrayBuffer();
 });
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
export async function publishCandidate({candidate,expected,keyId,initialize=false,reactivate=false,token,origin=ORIGIN,fetcher=fetch,verifier=verifyPublic}){
 const authToken=typeof token==='object'&&token?.value?token.value:token;
 const headers={'Content-Type':'application/json'};
 if(authToken)headers.Authorization=`Bearer ${authToken}`;
 const response=await fetcher(`${origin}/v1/framework/admit`,{
  method:'POST',
  headers,
  body:JSON.stringify({candidate,expected:{identity:candidate.identity,keyId},initialize,reactivate})
 });
 const result=await response.text();
 if(!response.ok)throw new Error(`Admission failed (HTTP ${response.status}): ${result}`);
 let body=null;try{body=JSON.parse(result);}catch{}
 const expectedId=candidate?.manifest?.sha256,identity=candidate?.identity;
 if(!body||typeof body!=='object'||body.status!=='admitted'||body.id!==expectedId||!body.identity||
    body.identity.version!==identity?.version||
    body.identity.buildNumber!==identity?.buildNumber||
    body.identity.channel!==identity?.channel||
    body.identity.sourceRevision!==identity?.sourceRevision){
  throw new Error(`Invalid admission receipt: expected candidate ${expectedId} (${identity?.version}#${identity?.buildNumber}) on channel ${identity?.channel}`);
 }
 try{
  await verifier(candidate,expected,fetcher,origin);
 }catch(preflightError){
  let withdrawError=null;
  try{
   await withdrawCandidate({id:expectedId,token:authToken,origin,fetcher});
  }catch(err){
   withdrawError=err;
  }
  if(withdrawError){
   const error=new Error(`Public preflight failed: ${preflightError.message}; rollback withdrawal failed: ${withdrawError.message}`);
   error.cause={preflightError,withdrawError};
   throw error;
  }
  const error=new Error(`Public preflight failed: ${preflightError.message}; candidate ${expectedId} withdrawn`);
  error.cause=preflightError;
  error.withdrawn=true;
  error.id=expectedId;
  throw error;
 }
 return {status:'published',id:expectedId,identity:candidate.identity,candidate};
}
export const publishFramework=publishCandidate;
async function putR2ObjectRest(accountId,token,remoteKey,bytes,fetcher=fetch){
 // Cloudflare R2 REST requires literal '/' separators while percent-encoding path segments.
 const encodedKey=remoteKey.split('/').map(encodeURIComponent).join('/');
 const url=`${R2_API_ORIGIN}/accounts/${encodeURIComponent(accountId)}/r2/buckets/${encodeURIComponent(BUCKET)}/objects/${encodedKey}`;
 for(let attempt=1;attempt<=MAX_UPLOAD_ATTEMPTS;attempt++){
  let response;
  try{
   response=await fetcher(url,{
    method:'PUT',
    headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/octet-stream','Content-Length':String(bytes.length)},
    body:bytes,
   });
  }catch(err){
   if(attempt<MAX_UPLOAD_ATTEMPTS){
    await new Promise(resolve=>setTimeout(resolve,attempt*1000));
    continue;
   }
   throw new Error(`R2 upload failed for ${remoteKey}: ${err.message}`);
  }
  const text=await response.text();
  if(response.ok){
   let body=null;try{body=JSON.parse(text);}catch{}
   if(body?.success===true)return;
  }
  if(TRANSIENT_STATUSES.has(response.status)&&attempt<MAX_UPLOAD_ATTEMPTS){
   await new Promise(resolve=>setTimeout(resolve,attempt*1000));
   continue;
  }
  throw new Error(`R2 upload failed for ${remoteKey} (HTTP ${response.status}): ${text.slice(0,200)}`);
 }
}
export async function stageObjects(directory,prefix,candidate,expected,{fetcher=fetch,env=process.env,uploader=null}={}){
 const accountId=env.CLOUDFLARE_ACCOUNT_ID,apiToken=env.CLOUDFLARE_API_TOKEN;
 const items=[...expected].map(([key,bytes])=>({
  key,
  bytes,
  file:key.startsWith('documents/')?join(directory,prefix+(key.endsWith(candidate.manifest.sha256)?'-manifest.json':'-manifest.json.sig')):join(directory,key),
 }));
 await mapPool(items,UPLOAD_CONCURRENCY,async({key,bytes,file})=>{
  const remoteKey=`framework-incoming/${key}`;
  if(uploader){
   await uploader(remoteKey,bytes,file);
   return;
  }
  if(accountId&&apiToken){
   await putR2ObjectRest(accountId,apiToken,remoteKey,bytes,fetcher);
   return;
  }
  await execFileAsync('npx',['--yes',WRANGLER_SPEC,'r2','object','put',`${BUCKET}/${remoteKey}`,'--file',file,'--remote'],{stdio:'inherit'});
 });
}
async function main(args){
 const [directory,prefix,keyId]=args,flags=new Set(args.slice(3));
 const {candidate,expected}=collect(directory,prefix),token=await (await fetch(`${process.env.ACTIONS_ID_TOKEN_REQUEST_URL}&audience=${encodeURIComponent(ORIGIN)}`,{headers:{Authorization:`Bearer ${process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN}`}})).json();
 await stageObjects(directory,prefix,candidate,expected); // objects first; the Worker refuses to register anything not matching the signed manifest
 const published=await publishCandidate({
  candidate,
  expected,
  keyId,
  initialize:flags.has('--initialize'),
  reactivate:flags.has('--reactivate'),
  token:token.value,
 });
 console.log('admit 200 id:',published.id);
 if(process.env.GITHUB_OUTPUT){
  appendFileSync(process.env.GITHUB_OUTPUT,`admitted_id=${published.id}\n`);
 }
 console.log('public preflight ok; descriptor to publish:',JSON.stringify(published.candidate));
}
if(import.meta.url===`file://${process.argv[1]}`)await main(process.argv.slice(2));
