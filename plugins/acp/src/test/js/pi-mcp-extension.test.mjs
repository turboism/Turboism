import extension from '../../main/resources/native/pi-mcp-extension.mjs';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import assert from 'node:assert/strict';
const root = mkdtempSync(join(tmpdir(), 'turboism-pi-mcp-'));
const server = join(root, 'server.mjs');
writeFileSync(server, `import readline from 'node:readline';
for await (const line of readline.createInterface({input:process.stdin})) {
 const r=JSON.parse(line); if(r.id===undefined)continue;
 const result=r.method==='initialize'?{protocolVersion:'2024-11-05',capabilities:{tools:{}}}:r.method==='tools/list'?{tools:[{name:'read',inputSchema:{type:'object',properties:{}},annotations:{readOnlyHint:true}},{name:'write',inputSchema:{type:'object',properties:{}}}]}:{content:[{type:'text',text:'done'}]};
 process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:r.id,result})+'\\n');
}`);
writeFileSync(join(root, 'config.json'), JSON.stringify({mcpServers:{test:{command:process.execPath,args:[server]}}}));
process.env.TURBOISM_PI_MCP_CONFIG=join(root,'config.json');
const tools=[],commands=[],events={};
try {
 await extension({registerTool:t=>tools.push(t),registerCommand:name=>commands.push(name),on:(event,fn)=>events[event]=fn});
 assert.equal(tools.length,2);assert.deepEqual(commands,['turboism-mcp-ready']);
 let confirmed=0;
 const ctx={ui:{confirm:async()=>{confirmed++;return false;}}};
 assert.equal((await tools[0].execute('1',{},undefined,undefined,ctx)).content[0].text,'done');
 assert.equal(confirmed,0);
 assert.equal((await tools[1].execute('2',{},undefined,undefined,ctx)).isError,true);assert.equal(confirmed,1);
 ctx.ui.confirm=async()=>true;
 assert.equal((await tools[1].execute('3',{},undefined,undefined,ctx)).content[0].text,'done');
 console.log('Pi MCP: discovery, readonly, denied mutation, allowed mutation passed');
} finally { events.session_shutdown?.();rmSync(root,{recursive:true,force:true}); }

// Streamable HTTP replies may split SSE events across UTF-8 chunks.
const { createServer } = await import('node:http');
const httpRoot = mkdtempSync(join(tmpdir(), 'turboism-pi-http-'));
const httpTools = [], httpEvents = {};
const httpServer = createServer(async (req, res) => {
  let body = '';
  for await (const chunk of req) body += chunk;
  const request = JSON.parse(body);
  if (!request.id) { res.writeHead(202); res.end(); return; }
  const result = request.method === 'initialize' ? { protocolVersion: '2024-11-05', capabilities: { tools: {} } }
    : request.method === 'tools/list' ? { tools: [{ name: 'read', inputSchema: { type: 'object', properties: {} }, annotations: { readOnlyHint: true } }] }
    : { content: [{ type: 'text', text: '流式成功' }] };
  res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Mcp-Session-Id': 'test-session' });
  const data = Buffer.from('data: ' + JSON.stringify({ jsonrpc: '2.0', id: request.id, result }) + '\n\n');
  res.write(data.subarray(0, 15));
  setTimeout(() => { res.end(data.subarray(15)); }, 5);
});
await new Promise(resolve => httpServer.listen(0, '127.0.0.1', resolve));
try {
  const configPath = join(httpRoot, 'config.json');
  writeFileSync(configPath, JSON.stringify({ mcpServers: { test: { url: `http://127.0.0.1:${httpServer.address().port}/mcp` } } }));
  process.env.TURBOISM_PI_MCP_CONFIG = configPath;
  await extension({ registerTool: tool => httpTools.push(tool), registerCommand: () => {}, on: (name, fn) => httpEvents[name] = fn });
  const result = await httpTools[0].execute('http-1', {}, undefined, undefined, {});
  assert.equal(result.content[0].text, '流式成功');
  console.log('Pi MCP: fragmented HTTP SSE replies passed');
} finally {
  httpEvents.session_shutdown?.();
  httpServer.closeAllConnections();
  await new Promise(resolve => httpServer.close(resolve));
  rmSync(httpRoot, { recursive: true, force: true });
}
