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
 let result;
 if(r.method==='initialize') {if(r.params.protocolVersion!=='2025-11-25')throw new Error('unexpected offered version');result={protocolVersion:'2024-11-05',capabilities:{tools:{},resources:{}}};}
 else if(r.method==='tools/list') {
  if(r.params.cursor && r.params.cursor!=='tools-page-2')throw new Error('unexpected tools cursor');
  result=r.params.cursor?{tools:[
   {name:'turboism.documents.open',inputSchema:{type:'object',properties:{}},annotations:{readOnlyHint:false}},
   {name:'turboism.assets.images.result',inputSchema:{type:'object',properties:{operationId:{type:'string'}}},annotations:{readOnlyHint:true}},
   {name:'turboism.assets.images.cancel',inputSchema:{type:'object',properties:{operationId:{type:'string'}}}},
   ...['read','plan','apply','result','cancel'].map(operation=>({name:'turboism.atlas.'+operation,inputSchema:{type:'object',properties:{operationId:{type:'string'}}},annotations:{readOnlyHint:['read','plan','result'].includes(operation)}})),
   {name:'turboism.rig.transfer_geometry.plan',inputSchema:{type:'object',properties:{}},annotations:{readOnlyHint:true}},
   {name:'turboism.validation.keyform_continuity',inputSchema:{type:'object',properties:{}},annotations:{readOnlyHint:true}},
   ...['sources','relations'].map(kind=>({name:'turboism.assets.'+kind+'.read',inputSchema:{type:'object',properties:{query:{type:'object'}}},annotations:{readOnlyHint:true}})),
   ...['initialize_plan','initialize','initialize_result'].map(operation=>({name:'turboism.authoring.bindings.'+operation,inputSchema:{type:'object',properties:{operationId:{type:'string'}}},annotations:{readOnlyHint:operation!=='initialize'}})),
   ...['read','new_model','new_result'].map(operation=>({name:'turboism.documents.'+operation,inputSchema:{type:'object',properties:{operationId:{type:'string'}}},annotations:{readOnlyHint:operation!=='new_model'}})),
   ...['triangulate','create_plan','create','create_result'].map(operation=>({name:'turboism.authoring.meshes.'+operation,inputSchema:{type:'object',properties:{operationId:{type:'string'}}},annotations:{readOnlyHint:operation!=='create'}})),
   ...['read','plan','apply','result'].map(operation=>({name:'turboism.parameters.structure.'+operation,inputSchema:{type:'object',properties:{operationId:{type:'string'}}},annotations:{readOnlyHint:['read','plan','result'].includes(operation)}})),
   ...['plan','execute','result','cancel'].map(operation=>({name:'turboism.export.moc.'+operation,inputSchema:{type:'object',properties:{operationId:{type:'string'}}},annotations:{readOnlyHint:['plan','result'].includes(operation)}}))]}:
   {tools:[{name:'read',inputSchema:{type:'object',properties:{}},annotations:{readOnlyHint:true}},{name:'write',inputSchema:{type:'object',properties:{}}},
    ...Array.from({length:47},(_,i)=>({name:'extra_'+i,inputSchema:{type:'object',properties:{}},annotations:{readOnlyHint:true}})),
    {name:'turboism.assets.images.register',inputSchema:{type:'object',properties:{operationId:{type:'string'}}}}],nextCursor:'tools-page-2'};
 }
 else if(r.method==='resources/list') result=r.params.cursor?{resources:[{uri:'turboism://second'}]}:{resources:[{uri:'turboism://first'}],nextCursor:'page-2'};
 else if(r.method==='resources/templates/list') result={resourceTemplates:[{uriTemplate:'turboism://objects/{id}'}],nextCursor:r.params.cursor?'end':'template-page-2'};
 else if(r.method==='resources/read') result={contents:[{uri:r.params.uri,mimeType:'application/json',text:'{"object":"head"}'},{uri:r.params.uri,mimeType:'image/png',blob:'cGl4ZWxz'},{uri:r.params.uri,mimeType:'application/octet-stream',blob:'RE9fTk9UX0RVTVA='}]};
 else result={content:[{type:'text',text:'done'},{type:'image',mimeType:'image/png',data:'cGl4ZWxz'}],structuredContent:{revision:7},isError:r.params?.arguments?.simulateError===true};
 process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:r.id,result})+'\\n');
}`);
writeFileSync(join(root, 'config.json'), JSON.stringify({mcpServers:{test:{command:process.execPath,args:[server]}}}));
process.env.TURBOISM_PI_MCP_CONFIG=join(root,'config.json');
const tools=[],commands=[],events={};
try {
 await extension({registerTool:t=>tools.push(t),registerCommand:name=>commands.push(name),on:(event,fn)=>events[event]=fn});
 assert.equal(tools.length,83);assert.deepEqual(commands,['turboism-mcp-ready']);
 const lateResult=tools.find(t=>t.name==='mcp_test_turboism_assets_images_result');
 const lateCancel=tools.find(t=>t.name==='mcp_test_turboism_assets_images_cancel');
 assert.ok(lateResult);assert.ok(lateCancel);
 const atlasResult=tools.find(t=>t.name==='mcp_test_turboism_atlas_result');
 const atlasCancel=tools.find(t=>t.name==='mcp_test_turboism_atlas_cancel');
 assert.ok(atlasResult);assert.ok(atlasCancel);
 const structurePlan=tools.find(t=>t.name==='mcp_test_turboism_parameters_structure_plan');
 const structureApply=tools.find(t=>t.name==='mcp_test_turboism_parameters_structure_apply');
 assert.ok(structurePlan);assert.ok(structureApply);
 assert.equal((await structurePlan.execute('structure-plan',{operationId:'plan'},undefined,undefined,{ui:{confirm:async()=>{throw new Error('preview prompted');}}})).content[0].text,'done');
 let structureApproval=false;
 await structureApply.execute('structure-apply',{operationId:'apply'},undefined,undefined,{ui:{confirm:async()=>{structureApproval=true;return true;}}});
 assert.ok(structureApproval);
 const docRead=tools.find(t=>t.name==='mcp_test_turboism_documents_read');assert.ok(docRead);await docRead.execute('docs-read',{},undefined,undefined,{ui:{confirm:async()=>{throw new Error('read prompted');}}});
 let docApproval=false;await tools.find(t=>t.name==='mcp_test_turboism_documents_new_model').execute('docs-new',{},undefined,undefined,{ui:{confirm:async()=>{docApproval=true;return true;}}});assert.ok(docApproval);
 const docOpen=tools.find(t=>t.name==='mcp_test_turboism_documents_open');assert.ok(docOpen);
 assert.equal((await docOpen.execute('open-denied',{},undefined,undefined,{ui:{confirm:async()=>false}})).isError,true);
 let openApproved=false;await docOpen.execute('open-approved',{},undefined,undefined,{ui:{confirm:async(title)=>{assert.ok(title.includes('documents.open'));openApproved=true;return true;}}});assert.ok(openApproved);
 const meshPlan=tools.find(t=>t.name==='mcp_test_turboism_authoring_meshes_create_plan');
 const meshTri=tools.find(t=>t.name==='mcp_test_turboism_authoring_meshes_triangulate');
 for(const t of [meshPlan,meshTri]){assert.ok(t);await t.execute('mesh-preview',{},undefined,undefined,{ui:{confirm:async()=>{throw new Error('preview prompted');}}});}
 let meshApproval=false;await tools.find(t=>t.name==='mcp_test_turboism_authoring_meshes_create').execute('mesh-create',{},undefined,undefined,{ui:{confirm:async()=>{meshApproval=true;return true;}}});assert.ok(meshApproval);
 for(const kind of ['sources','relations']){const sourceRead=tools.find(t=>t.name==='mcp_test_turboism_assets_'+kind+'_read');assert.ok(sourceRead);await sourceRead.execute('source-read',{query:{artMeshIds:['mesh'],modelImageIds:[]}},undefined,undefined,{ui:{confirm:async()=>{throw new Error('source metadata read prompted');}}});}
 const transfer=tools.find(t=>t.name==='mcp_test_turboism_rig_transfer_geometry_plan');assert.ok(transfer);await transfer.execute('transfer-plan',{},undefined,undefined,{ui:{confirm:async()=>{throw new Error('read-only transfer plan prompted');}}});
 const continuity=tools.find(t=>t.name==='mcp_test_turboism_validation_keyform_continuity');assert.ok(continuity);await continuity.execute('continuity',{},undefined,undefined,{ui:{confirm:async()=>{throw new Error('read-only diagnostics prompted');}}});
 const bindingPlan=tools.find(t=>t.name==='mcp_test_turboism_authoring_bindings_initialize_plan');assert.ok(bindingPlan);await bindingPlan.execute('binding-plan',{},undefined,undefined,{ui:{confirm:async()=>{throw new Error('binding preview prompted');}}});
 const bindingApply=tools.find(t=>t.name==='mcp_test_turboism_authoring_bindings_initialize');assert.ok(bindingApply);let bindingApproved=false;const bindingArgs={planId:'plan',target:{kind:'art_mesh',objectId:'mesh',sourceGuid:'source',axes:[{parameterId:'AngleX',parameterGuid:'axis-guid',keys:[-30,0,30]}]}};await bindingApply.execute('binding-apply',bindingArgs,undefined,undefined,{ui:{confirm:async(title,body)=>{assert.ok(title.includes('authoring.bindings.initialize'));assert.deepEqual(JSON.parse(body).target,bindingArgs.target);bindingApproved=true;return true;}}});assert.ok(bindingApproved);
 const bindingResult=tools.find(t=>t.name==='mcp_test_turboism_authoring_bindings_initialize_result');assert.ok(bindingResult);await bindingResult.execute('binding-result',{},undefined,undefined,{ui:{confirm:async()=>{throw new Error('binding result prompted');}}});
 const mocResult=tools.find(t=>t.name==='mcp_test_turboism_export_moc_result');
 const mocExecute=tools.find(t=>t.name==='mcp_test_turboism_export_moc_execute');
 assert.ok(mocResult);assert.ok(mocExecute);
 assert.equal((await mocResult.execute('moc-result',{operationId:'job'},undefined,undefined,{ui:{confirm:async()=>{throw new Error('read prompted');}}})).content[0].text,'done');
 assert.equal((await mocExecute.execute('moc-execute',{operationId:'job'},undefined,undefined,{ui:{confirm:async()=>false}})).isError,true);
 assert.equal((await atlasResult.execute('atlas-result',{operationId:'job'},undefined,undefined,{ui:{confirm:async()=>{throw new Error('read prompted');}}})).content[0].text,'done');
 assert.equal((await atlasCancel.execute('atlas-cancel',{operationId:'job'},undefined,undefined,{ui:{confirm:async()=>false}})).isError,true);
 assert.ok(tools.find(t=>t.name==='mcp_test_turboism_assets_images_register'));
 assert.equal((await lateResult.execute('late-result',{operationId:'job'},undefined,undefined,{ui:{confirm:async()=>{throw new Error('read prompted');}}})).content[0].text,'done');
 assert.equal((await lateCancel.execute('late-cancel',{operationId:'job'},undefined,undefined,{ui:{confirm:async()=>false}})).isError,true);
 let confirmed=0;
 const ctx={ui:{confirm:async()=>{confirmed++;return false;}}};
 assert.equal((await tools[0].execute('1',{},undefined,undefined,ctx)).content[0].text,'done');
 assert.equal(confirmed,0);
 assert.equal((await tools[1].execute('2',{},undefined,undefined,ctx)).isError,true);assert.equal(confirmed,1);
 ctx.ui.confirm=async()=>true;
 assert.equal((await tools[1].execute('3',{},undefined,undefined,ctx)).content[0].text,'done');
 const list=tools.find(t=>t.name==='mcp_test_resources_list');
 const templates=tools.find(t=>t.name==='mcp_test_resource_templates_list');
 const read=tools.find(t=>t.name==='mcp_test_resource_read');
 const first=await list.execute('list',{},undefined,undefined,{});
 assert.equal(first.details.nextCursor,'page-2');
 const second=await list.execute('list-2',{cursor:first.details.nextCursor},undefined,undefined,{});
 assert.equal(second.details.resources[0].uri,'turboism://second');
 assert.equal((await list.execute('list-again',{},undefined,undefined,{})).details.nextCursor,'page-2');
 assert.equal((await templates.execute('templates',{cursor:'template-page-2'},undefined,undefined,{})).details.nextCursor,'end');
 const resource=await read.execute('resource',{uri:'turboism://first'},undefined,undefined,{});
 assert.equal(resource.content[1].text,'{"object":"head"}');
 assert.deepEqual(resource.content.find(c=>c.type==='image'),{type:'image',mimeType:'image/png',data:'cGl4ZWxz'});
 assert.ok(!JSON.stringify(resource).includes('RE9fTk9UX0RVTVA='));
 assert.equal(resource.details.resources.length,3);
 await assert.rejects(()=>read.execute('invalid',{uri:'x',path:'/tmp/no'},undefined),/Invalid MCP resource arguments/);
 await assert.rejects(()=>read.execute('missing',{},undefined),/Invalid MCP resource arguments/);
 const controller=new AbortController();controller.abort();
 await assert.rejects(()=>list.execute('cancelled',{},controller.signal),/cancelled before dispatch/);
 const imageResult=await tools[0].execute('image',{},undefined,undefined,ctx);
 assert.equal(imageResult.content[1].type,'image');assert.equal(imageResult.details.revision,7);
 assert.ok(imageResult.content.at(-1).text.includes('no verified image input capability'));
 assert.equal(imageResult.isError,false);
 const textModel={model:{input:['text']},ui:{confirm:async()=>true}};
 const textModelResult=await tools[0].execute('text-model',{},undefined,undefined,textModel);
 assert.equal(textModelResult.content[1].type,'image');
 assert.ok(textModelResult.content.at(-1).text.includes('does not support image input'));
 assert.equal(textModelResult.isError,false);assert.equal(textModelResult.details.revision,7);
 const failedImageResult=await tools[0].execute('failed-image',{simulateError:true},undefined,undefined,textModel);
 assert.equal(failedImageResult.isError,true);assert.equal(failedImageResult.details.revision,7);
 const malformedCapability=await tools[0].execute('unknown-capability',{},undefined,undefined,{model:{input:'image'}});
 assert.ok(malformedCapability.content.at(-1).text.includes('no verified image input capability'));
 textModel.model={input:['text','image']};
 const switchedResult=await tools[0].execute('switched-vision',{},undefined,undefined,textModel);
 assert.deepEqual(switchedResult.content,[{type:'text',text:'done'},{type:'image',mimeType:'image/png',data:'cGl4ZWxz'}]);
 const resourceTextModel=await read.execute('resource-text-model',{uri:'turboism://first'},undefined,undefined,{model:{input:['text']}});
 assert.equal(resourceTextModel.content.find(c=>c.type==='image').data,'cGl4ZWxz');
 assert.ok(resourceTextModel.content.at(-1).text.includes('does not support image input'));
 const resourceVisionModel=await read.execute('resource-vision-model',{uri:'turboism://first'},undefined,undefined,{model:{input:['text','image']}});
 assert.ok(!resourceVisionModel.content.some(c=>c.type==='text'&&c.text.includes('model visual inspection')));
 console.log('Pi MCP: 80-tool paginated discovery/late-page execution, tool permissions, image forwarding, resource discovery/pagination/read, binary suppression, validation and cancellation passed');
} finally { events.session_shutdown?.();rmSync(root,{recursive:true,force:true}); }

// Streamable HTTP replies may split SSE events across UTF-8 chunks.
const { createServer } = await import('node:http');
const httpRoot = mkdtempSync(join(tmpdir(), 'turboism-pi-http-'));
const httpTools = [], httpEvents = {};
let negotiatedProtocol = '2025-06-18';
const httpRequests = [];
const httpServer = createServer(async (req, res) => {
  let body = '';
  for await (const chunk of req) body += chunk;
  const request = JSON.parse(body);
  httpRequests.push(request.method);
  if (request.method === 'initialize') {
    assert.equal(request.params.protocolVersion, '2025-11-25');
    assert.equal(req.headers['mcp-protocol-version'], undefined);
  } else if (req.headers['mcp-protocol-version'] !== negotiatedProtocol) {
    res.writeHead(400); res.end(); return;
  }
  if (!request.id) { res.writeHead(202); res.end(); return; }
  const result = request.method === 'initialize' ? { protocolVersion: negotiatedProtocol, capabilities: { tools: {} } }
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
  writeFileSync(configPath, JSON.stringify({ mcpServers: { test: { url: `http://127.0.0.1:${httpServer.address().port}/mcp`, headers: { 'mcp-protocol-version': '2024-11-05' } } } }));
  process.env.TURBOISM_PI_MCP_CONFIG = configPath;
  await extension({ registerTool: tool => httpTools.push(tool), registerCommand: () => {}, on: (name, fn) => httpEvents[name] = fn });
  const result = await httpTools[0].execute('http-1', {}, undefined, undefined, {});
  assert.equal(result.content[0].text, '流式成功');
  console.log('Pi MCP: negotiated protocol headers and fragmented HTTP SSE replies passed');
  const boundary = httpRequests.length;
  negotiatedProtocol = '2099-01-01';
  const unsupportedTools = [];
  await assert.rejects(() => extension({ registerTool: tool => unsupportedTools.push(tool), registerCommand: () => {}, on: () => {} }), /Unsupported MCP protocol version/);
  assert.deepEqual(httpRequests.slice(boundary), ['initialize']);
  assert.equal(unsupportedTools.length, 0);
  console.log('Pi MCP: unsupported negotiated protocol rejected before discovery passed');
} finally {
  httpEvents.session_shutdown?.();
  httpServer.closeAllConnections();
  await new Promise(resolve => httpServer.close(resolve));
  rmSync(httpRoot, { recursive: true, force: true });
}
