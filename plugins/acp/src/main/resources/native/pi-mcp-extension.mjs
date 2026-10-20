// Dependency-free MCP bridge for Pi's extension API. Stdout is reserved for RPC JSONL.
import { readFileSync } from 'node:fs';
import { spawn } from 'node:child_process';

export default async function (pi) {
  const clients = [];
  try {
    const config = JSON.parse(readFileSync(process.env.TURBOISM_PI_MCP_CONFIG, 'utf8'));
    for (const [name, server] of Object.entries(config.mcpServers ?? {})) {
      const client = createClient(server);
      clients.push(client);
      await client.call('initialize', { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'turboism-pi', version: '1' } });
      await client.notify('notifications/initialized', {});
      let cursor;
      let count = 0;
      do {
        const result = await client.call('tools/list', cursor ? { cursor } : {});
        for (const tool of result.tools ?? []) {
          if (++count > 512) throw new Error('MCP tool catalog exceeds limit');
          const schema = tool.inputSchema ?? { type: 'object', properties: {} };
          // Older Pi releases validate schemas with TypeBox; newer ones accept plain JSON Schema.
          markSchema(schema);
          pi.registerTool({
            name: `mcp_${name}_${tool.name}`.replace(/[^a-zA-Z0-9_-]/g, '_'),
            label: `${name}: ${tool.name}`,
            description: tool.description || tool.name,
            parameters: schema,
            async execute(_id, args, signal, _update, ctx) {
              if (signal?.aborted) throw new Error('Tool cancelled');
              if (tool.annotations?.readOnlyHint !== true && !await ctx.ui.confirm(`${name}: ${tool.name}`, JSON.stringify(args))) {
                return { content: [{ type: 'text', text: 'Rejected by user' }], isError: true, details: {} };
              }
              const result = await client.call('tools/call', { name: tool.name, arguments: args }, signal);
              return { content: result.content ?? [], isError: !!result.isError, details: result.structuredContent ?? {} };
            },
          });
        }
        cursor = result.nextCursor;
      } while (cursor);
    }
    pi.on('session_shutdown', () => { for (const client of clients) client.close(); });
    pi.registerCommand('turboism-mcp-ready', { description: 'Turboism MCP bridge is ready', handler: async () => {} });
  } catch (error) {
    for (const client of clients) client.close();

    throw error;
  }
}

function markSchema(schema) {
  if (!schema || typeof schema !== 'object') return;
  const kinds = { object: 'Object', array: 'Array', string: 'String', number: 'Number', integer: 'Integer', boolean: 'Boolean', null: 'Null' };
  if (schema.anyOf) { schema[Symbol.for('TypeBox.Kind')] = 'Union'; schema.anyOf.forEach(markSchema); }
  else if (schema.const !== undefined) schema[Symbol.for('TypeBox.Kind')] = 'Literal';
  else if (schema.enum) {
    schema.anyOf = schema.enum.map(value => ({ const: value, [Symbol.for('TypeBox.Kind')]: 'Literal' }));
    schema[Symbol.for('TypeBox.Kind')] = 'Union';
  } else schema[Symbol.for('TypeBox.Kind')] = kinds[schema.type] ?? 'Any';
  for (const [key, value] of Object.entries(schema.properties ?? {})) {
    markSchema(value);
    if (!(schema.required ?? []).includes(key)) value[Symbol.for('TypeBox.Optional')] = 'Optional';
  }
  if (schema.items) markSchema(schema.items);
  if (schema.additionalProperties && typeof schema.additionalProperties === 'object') markSchema(schema.additionalProperties);
}

function createClient(config) {
  let nextId = 0;
  let session;
  let child;
  let buffer = '';
  let closed = false;
  const pending = new Map();
  const rejectAll = error => { for (const task of pending.values()) task.reject(error); pending.clear(); };
  if (config.command) {
    child = spawn(config.command, config.args ?? [], { env: { ...process.env, ...config.env }, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', chunk => {
      buffer += chunk;
      if (buffer.length > 8 * 1024 * 1024) { rejectAll(new Error('MCP message exceeds limit')); child.kill(); return; }
      let end;
      while ((end = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, end); buffer = buffer.slice(end + 1);
        if (!line.trim()) continue;
        try {
          const message = JSON.parse(line);
          if (message.method && message.id !== undefined) {
            child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Unsupported MCP server request' } }) + '\n');
          } else if (pending.has(message.id)) {
            const task = pending.get(message.id); pending.delete(message.id);
            message.error ? task.reject(new Error(message.error.message)) : task.resolve(message.result);
          }
        } catch (error) { rejectAll(error); child.kill(); }
      }
    });
    // Drain diagnostics without leaking bridge tokens or corrupting Pi's protocol output.
    child.stderr.resume();
    child.on('error', rejectAll);
    child.stdin.on('error', rejectAll);
    child.on('exit', () => rejectAll(new Error('MCP process exited')));
  }
  async function send(message, signal) {
    if (closed) throw new Error('MCP connection closed');
    if (child) {
      if (message.id === undefined) { child.stdin.write(JSON.stringify(message) + '\n'); return; }
      if (pending.size >= 64) throw new Error('Too many MCP requests');
      return new Promise((resolve, reject) => {
        const abort = () => { const task = pending.get(message.id); pending.delete(message.id); task?.reject(new Error('MCP request cancelled')); };
        const timer = setTimeout(() => { const task = pending.get(message.id); pending.delete(message.id); task?.reject(new Error('MCP request timed out')); }, 15000);
        const finish = fn => value => { clearTimeout(timer); signal?.removeEventListener('abort', abort); fn(value); };
        pending.set(message.id, { resolve: finish(resolve), reject: finish(reject) });
        signal?.addEventListener('abort', abort, { once: true });
        child.stdin.write(JSON.stringify(message) + '\n');
      });
    }
    const controller = new AbortController();
    const abort = () => controller.abort();
    const timer = setTimeout(abort, 15000);
    signal?.addEventListener('abort', abort, { once: true });
    try {
      const headers = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': '2024-11-05', ...config.headers };
      if (session) headers['Mcp-Session-Id'] = session;
      const response = await fetch(config.url, { method: 'POST', headers, body: JSON.stringify(message), signal: controller.signal });
      if (!response.ok) throw new Error(`MCP HTTP ${response.status}`);
      session = response.headers.get('Mcp-Session-Id') ?? session;
      if (message.id === undefined || response.status === 202 || response.status === 204) { await response.body?.cancel(); return; }
      let body = '';
      const decoder = new TextDecoder();
      for await (const chunk of response.body) {
        body += decoder.decode(chunk, { stream: true });
        if (body.length > 8 * 1024 * 1024) throw new Error('MCP message exceeds limit');
        if (response.headers.get('content-type')?.includes('text/event-stream')) {
          let boundary;
          while ((boundary = body.search(/\r?\n\r?\n/)) >= 0) {
            const event = body.slice(0, boundary);
            const separator = body.slice(boundary).match(/^\r?\n\r?\n/)[0];
            body = body.slice(boundary + separator.length);
            const data = event.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
            if (!data) continue;
            const parsed = JSON.parse(data);
            if (parsed.id === message.id) {
              if (parsed.error) throw new Error(parsed.error.message);
              return parsed.result;
            }
          }
        }
      }
      const parsed = JSON.parse(body);
      if (parsed.error) throw new Error(parsed.error.message);
      return parsed.result;
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
  }
  return {
    call(method, params, signal) { return send({ jsonrpc: '2.0', id: ++nextId, method, params }, signal); },
    notify(method, params) { return send({ jsonrpc: '2.0', method, params }); },
    close() { closed = true; rejectAll(new Error('MCP connection closed')); child?.stdin.end(); child?.kill(); },
  };
}
