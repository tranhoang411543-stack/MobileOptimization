const assert = require('node:assert/strict');
const http = require('node:http');
const { after, before, test } = require('node:test');
require('ts-node/register');
require('reflect-metadata');

const { NestFactory } = require('@nestjs/core');
const { AppModule } = require('../src/app.module');
const { connectionMetadata } = require('../src/connection-metadata');
const { requestTiming } = require('../src/request-timing');
const { DatabaseService } = require('../src/database.service');

let app;
let port;
const timingLogs = [];
const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });

before(async () => {
  app = await NestFactory.create(AppModule, { logger: false });
  app.use(requestTiming((message) => timingLogs.push(message)));
  app.use(connectionMetadata());
  app.setGlobalPrefix('api');
  await app.listen(0, '127.0.0.1');
  port = app.getHttpServer().address().port;
});

after(async () => {
  agent.destroy();
  if (app) await app.close();
});

function get(path, requestAgent) {
  return new Promise((resolve, reject) => {
    const request = http.get({ host: '127.0.0.1', port, path, agent: requestAgent }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => resolve({
        status: response.statusCode,
        headers: response.headers,
        body: chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : null,
      }));
    });
    request.on('error', reject);
  });
}

test('phân biệt request đầu và request tiếp theo trên cùng socket', async () => {
  const first = await get('/api/books/search?q=Kotlin&limit=2', agent);
  const reused = await get('/api/books/search?q=Kotlin&limit=2', agent);
  const invalid = await get('/api/books/search?q=%20', agent);

  assert.equal(first.status, 200);
  assert.equal(first.body.returned, 2);
  assert.equal(first.headers['cache-control'], 'no-store');
  assert.ok(first.headers['x-demo-connection-id']);
  assert.equal(first.headers['x-demo-connection-request'], '1');
  assert.equal(reused.headers['x-demo-connection-id'], first.headers['x-demo-connection-id']);
  assert.equal(reused.headers['x-demo-connection-request'], '2');
  assert.equal(invalid.status, 400);
  assert.equal(invalid.headers['x-demo-connection-id'], first.headers['x-demo-connection-id']);
  assert.equal(invalid.headers['x-demo-connection-request'], '3');

  const connectionId = first.headers['x-demo-connection-id'].slice(0, 8);
  assert.equal(timingLogs.length, 3);
  for (const [index, status] of [200, 200, 400].entries()) {
    assert.ok(timingLogs[index].includes(`HTTP ${status} | Backend:`));
    assert.ok(timingLogs[index].includes(`Kết nối: ${connectionId} · lần ${index + 1}`));
    assert.doesNotMatch(timingLogs[index], /Kotlin|q=/);
  }
});

test('hai socket mới có mã riêng và đều bắt đầu từ request 1', async () => {
  const first = await get('/api/books/search?q=Android&limit=1', false);
  const second = await get('/api/books/search?q=Android&limit=1', false);
  assert.equal(first.status, 200);
  assert.equal(second.status, 200);
  assert.notEqual(first.headers['x-demo-connection-id'], second.headers['x-demo-connection-id']);
  assert.equal(first.headers['x-demo-connection-request'], '1');
  assert.equal(second.headers['x-demo-connection-request'], '1');
});

test('heartbeat giữ socket qua 5 giây và tìm kiếm dùng lại socket, không truy vấn CSDL trong heartbeat', async () => {
  const heartbeatAgent = new http.Agent({ keepAlive: true, maxSockets: 1 });
  const db = app.get(DatabaseService);
  const originalQuery = db.query;
  let queries = 0;
  db.query = function (...args) { queries++; return originalQuery.apply(this, args); };
  try {
    const first = await get('/api/health/keep-alive', heartbeatAgent);
    assert.equal(first.status, 204);
    assert.equal(first.body, null);
    assert.equal(first.headers['cache-control'], 'no-store');
    assert.equal(first.headers['x-demo-connection-request'], '1');
    const id = first.headers['x-demo-connection-id'];
    assert.ok(id);
    for (let count = 2; count <= 4; count++) {
      await new Promise((resolve) => setTimeout(resolve, 2000));
      const ping = await get('/api/health/keep-alive', heartbeatAgent);
      assert.equal(ping.status, 204);
      assert.equal(ping.headers['x-demo-connection-id'], id);
      assert.equal(ping.headers['x-demo-connection-request'], String(count));
    }
    assert.equal(queries, 0, 'Heartbeat không được truy vấn CSDL');
    const search = await get('/api/books/search?q=Kotlin&limit=1', heartbeatAgent);
    assert.equal(search.status, 200);
    assert.equal(search.headers['x-demo-connection-id'], id);
    assert.equal(search.headers['x-demo-connection-request'], '5');
    assert.equal(queries, 1);
  } finally {
    db.query = originalQuery;
    heartbeatAgent.destroy();
  }
});
