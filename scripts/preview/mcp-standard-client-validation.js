#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const {
  StreamableHTTPClientTransport,
} = require('@modelcontextprotocol/sdk/client/streamableHttp.js');

const EXPECTED_TOOLS = new Set([
  'turboism.model_objects.apply',
  'turboism.parameters.apply',
  'turboism.parameter_bindings.apply',
  'turboism.history.move',
  'turboism.editor_commands.execute',
]);
const EXPECTED_RESOURCES = new Set([
  'turboism://active/document',
  'turboism://active/model/overview',
  'turboism://active/model/hierarchy',
  'turboism://active/model/clip-masks',
  'turboism://active/model/parameters',
  'turboism://active/model/statistics',
  'turboism://active/model/textures',
  'turboism://active/document/history',
  'turboism://environment/cubism-core',
  'turboism://environment/workspace',
  'turboism://environment/workspace/layout',
  'turboism://environment/diagnostics',
  'turboism://host/editor-commands',
]);
const EXPECTED_TEMPLATES = new Set([
  'turboism://active/model/parameters/{parameterId}',
  'turboism://active/model/parameters/{parameterId}/bindings',
]);
const EXPECTED_PROMPTS = new Set([
  'inspect_active_document',
  'edit_model_structure',
  'normalize_parameters',
  'repair_parameter_bindings',
  'recover_document_history',
  'run_editor_command',
  'diagnose_environment',
  'inspect_model_diagnostics',
]);

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function equalSet(actual, expected, label) {
  requireCondition(actual.size === expected.size, `${label} count mismatch`);
  for (const value of expected) {
    requireCondition(actual.has(value), `${label} missing ${value}`);
  }
}

function readConnection(home) {
  const file = path.join(
    home,
    'state',
    'dev.turboism.plugin.mcp',
    'mcp-connection.json',
  );
  const value = JSON.parse(fs.readFileSync(file, 'utf8'));
  requireCondition(
    typeof value.endpoint === 'string'
      && value.endpoint.startsWith('http://127.0.0.1:')
      && value.endpoint.endsWith('/mcp'),
    'connection endpoint is not numeric loopback /mcp',
  );
  requireCondition(
    typeof value.authorization === 'string'
      && value.authorization.startsWith('Bearer '),
    'connection authorization is malformed',
  );
  return value;
}

async function collectPages(fetchPage, key) {
  const values = [];
  let cursor;
  do {
    const page = await fetchPage(cursor);
    values.push(...page[key]);
    cursor = page.nextCursor;
  } while (cursor);
  return values;
}

async function main() {
  requireCondition(
    process.argv.length === 3 || process.argv.length === 4,
    'usage: mcp-standard-client-validation.js <turboism-home> [result-file]',
  );
  const connection = readConnection(path.resolve(process.argv[2]));
  const resultFile = process.argv[3] ? path.resolve(process.argv[3]) : undefined;
  const client = new Client(
    { name: 'turboism-standard-sdk-validation', version: '1' },
    { capabilities: {} },
  );
  const transport = new StreamableHTTPClientTransport(new URL(connection.endpoint), {
    requestInit: { headers: { Authorization: connection.authorization } },
  });

  try {
    await client.connect(transport);
    requireCondition(client.getServerVersion()?.name === 'turboism-mcp', 'server identity mismatch');

    const tools = await collectPages(
      cursor => client.listTools(cursor ? { cursor } : {}),
      'tools',
    );
    equalSet(new Set(tools.map(value => value.name)), EXPECTED_TOOLS, 'tools');
    requireCondition(tools.every(value => value.outputSchema), 'tool outputSchema missing');

    const resources = await collectPages(
      cursor => client.listResources(cursor ? { cursor } : {}),
      'resources',
    );
    equalSet(new Set(resources.map(value => value.uri)), EXPECTED_RESOURCES, 'resources');

    const templates = await collectPages(
      cursor => client.listResourceTemplates(cursor ? { cursor } : {}),
      'resourceTemplates',
    );
    equalSet(
      new Set(templates.map(value => value.uriTemplate)),
      EXPECTED_TEMPLATES,
      'resource templates',
    );

    const prompts = await collectPages(
      cursor => client.listPrompts(cursor ? { cursor } : {}),
      'prompts',
    );
    equalSet(new Set(prompts.map(value => value.name)), EXPECTED_PROMPTS, 'prompts');
    for (const name of [
      'inspect_active_document',
      'diagnose_environment',
      'inspect_model_diagnostics',
    ]) {
      const prompt = await client.getPrompt({ name, arguments: {} });
      requireCondition(prompt.messages.length === 1, `prompt ${name} rendering mismatch`);
    }

    const document = await client.readResource({ uri: 'turboism://active/document' });
    requireCondition(document.contents.length === 1, 'active document content mismatch');
    requireCondition(document.contents[0].mimeType === 'application/json', 'active document MIME mismatch');
    JSON.parse(document.contents[0].text);

    for (const uri of [
      'turboism://environment/cubism-core',
      'turboism://environment/workspace',
      'turboism://environment/workspace/layout',
      'turboism://environment/diagnostics',
      'turboism://active/model/statistics',
      'turboism://active/model/textures',
    ]) {
      const resource = await client.readResource({ uri });
      requireCondition(resource.contents.length === 1, `${uri} content mismatch`);
      requireCondition(resource.contents[0].mimeType === 'application/json', `${uri} MIME mismatch`);
      const payload = JSON.parse(resource.contents[0].text);
      if (uri === 'turboism://environment/diagnostics') {
        requireCondition(Array.isArray(payload.problems), 'diagnostic problems missing');
        requireCondition(typeof payload.truncated === 'boolean', 'diagnostic truncated flag missing');
        for (const problem of payload.problems) {
          requireCondition(
            JSON.stringify(Object.keys(problem).sort())
              === JSON.stringify(['code', 'message', 'severity']),
            'diagnostic problem exposed unexpected fields',
          );
        }
      }
    }

    const history = await client.readResource({ uri: 'turboism://active/document/history' });
    const snapshot = JSON.parse(history.contents[0].text);
    if (snapshot.availability === 'AVAILABLE') {
      const result = await client.callTool({
        name: 'turboism.history.move',
        arguments: {
          operation: 'move_to',
          expectedGeneration: snapshot.generation,
          expectedRevision: snapshot.revision,
          position: snapshot.position,
        },
      });
      requireCondition(result.isError !== true, 'history no-op failed through standard SDK');
      requireCondition(result.structuredContent?.ok === true, 'history no-op output mismatch');
    }

    await transport.terminateSession();
    const result = JSON.stringify({
      status: 'PASS',
      client: '@modelcontextprotocol/sdk',
      sdkVersion: '1.30.0',
      toolCount: tools.length,
      resourceCount: resources.length,
      templateCount: templates.length,
      promptCount: prompts.length,
      authorizationPersisted: false,
    });
    if (resultFile) {
      const temporary = `${resultFile}.tmp`;
      fs.mkdirSync(path.dirname(resultFile), { recursive: true });
      fs.writeFileSync(temporary, `${result}\n`, { encoding: 'utf8', mode: 0o600 });
      fs.renameSync(temporary, resultFile);
    }
    console.log(result);
  } finally {
    await client.close().catch(() => {});
  }
}

main().catch(failure => {
  console.error(`${failure.name}: ${failure.message}`);
  process.exitCode = 1;
});
