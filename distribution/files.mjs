/** Single-range downloads only. The registry admits keys, this module never proxies GitHub. */
export function parseRange(header,size){
 if(!header)return null;const m=/^bytes=(\d*)-(\d*)$/.exec(header);if(!m||(!m[1]&&!m[2]))return false;
 let start,end;
 if(!m[1]){const n=Number(m[2]);if(!Number.isSafeInteger(n)||n<=0)return false;start=Math.max(0,size-n);end=size-1;}
 else{start=Number(m[1]);end=m[2]?Math.min(Number(m[2]),size-1):size-1;}
 if(!Number.isSafeInteger(start)||!Number.isSafeInteger(end)||start<0||start>=size||end<start)return false;
 return {offset:start,length:end-start+1};
}
export async function serveFile(request,bucket,asset){
 if(!bucket)return new Response('Official mirror is not configured',{status:503,headers:{'Cache-Control':'no-store','Retry-After':'300'}});
 const head=await bucket.head(asset.key);if(!head||head.size!==asset.size)return new Response('File not mirrored',{status:503,headers:{'Cache-Control':'no-store'}});
 const etag='"'+asset.sha256+'"',headers=new Headers({'Content-Type':asset.mediaType,'Content-Disposition':`attachment; filename="${asset.name}"`,'Content-Length':String(asset.size),'Accept-Ranges':'bytes',ETag:etag,'X-Content-Type-Options':'nosniff','Access-Control-Allow-Origin':'*','Access-Control-Expose-Headers':'ETag, Content-Length, Content-Range, Accept-Ranges','Cache-Control':'public, max-age=0, s-maxage=60, must-revalidate'});
 if((request.headers.get('if-none-match')??'').split(',').some(x=>x.trim().replace(/^W\//,'')===etag||x.trim()==='*'))return new Response(null,{status:304,headers});
 if(request.method==='HEAD')return new Response(null,{headers});
 const ifRange=request.headers.get('if-range');const range=parseRange(!ifRange||ifRange===etag?request.headers.get('range'):null,asset.size);
 if(range===false){headers.set('Content-Range',`bytes */${asset.size}`);headers.delete('Content-Length');return new Response(null,{status:416,headers});}
 const object=await bucket.get(asset.key,range?{range}:undefined);if(!object||object.size!==asset.size)return new Response('File unavailable',{status:503});
 if(range){headers.set('Content-Range',`bytes ${range.offset}-${range.offset+range.length-1}/${asset.size}`);headers.set('Content-Length',String(range.length));}
 return new Response(object.body,{status:range?206:200,headers});
}
