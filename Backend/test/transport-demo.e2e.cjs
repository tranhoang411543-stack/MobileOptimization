const assert = require('node:assert/strict');
const http = require('node:http');
const https = require('node:https');
const net = require('node:net');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { before, after, test } = require('node:test');
require('ts-node/register');
require('reflect-metadata');
const { NestFactory } = require('@nestjs/core');
const { AppModule } = require('../src/app.module');
const { DatabaseService } = require('../src/database.service');
const { TransportDemoService } = require('../src/transport-demo/transport-demo.service');
const { startDemoHttps } = require('../src/transport-demo/https-listeners');

const directory = path.join(__dirname, '..', '.local-tls', 'app7');
const sampleInput = { username: `người_dùng'_${randomUUID()}`, password: 'demo_"mật_khẩu"_123' };
const sample = JSON.stringify(sampleInput);
let app, database, tls, httpPort, trustedPort, untrustedPort, arrived = 0, ca;

before(async () => {
  if (process.env.APP7_ISOLATED_TEST !== '1' || !/^app7_transport_test_[0-9a-f]{16}$/.test(process.env.MOBILE_DEMO_DB_NAME || '')) {
    throw new Error('Chỉ chạy kiểm thử ghi App 7 trên CSDL cô lập do test:transport-demo tạo.');
  }
  const previous = new DatabaseService();
  try {
    await previous.query('CREATE TABLE app7_migration_sentinel (value text PRIMARY KEY)');
    await previous.query("INSERT INTO app7_migration_sentinel (value) VALUES ('existing data')");
  } finally {
    await previous.onModuleDestroy();
  }
  ca = fs.readFileSync(path.join(directory, 'root-ca.pem'));
  app = await NestFactory.create(AppModule, { logger: false });
  app.use((req, _res, next) => { if (req.url === '/api/transport-demo/submit') arrived++; next(); });
  app.setGlobalPrefix('api');
  await app.listen(0, '127.0.0.1');
  database = app.get(DatabaseService);
  httpPort = app.getHttpServer().address().port;
  tls = await startDemoHttps(app.getHttpAdapter().getInstance(), directory, 0, 0);
  trustedPort = tls.trusted.address().port;
  untrustedPort = tls.untrusted.address().port;
});
after(async () => { if (tls) await tls.close(); if (app) await app.close(); });

function request(transport, port, body = sample, options = {}) {
  return new Promise((resolve, reject) => {
    const req = transport.request({ host: '127.0.0.1', port, path: '/api/transport-demo/submit', method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), Connection: 'close' },
      ...options }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }));
    });
    req.on('error', reject);
    req.end(body);
  });
}

test('HTTP và HTTPS lưu đúng dữ liệu nhập và mỗi lần tạo một bản ghi', async () => {
  const ids = [];
  for (const result of [await request(http, httpPort), await request(https, trustedPort, sample, { ca })]) {
    assert.equal(result.status, 200);
    const response = JSON.parse(result.body);
    assert.equal(response.ok, true);
    assert.match(response.recordId, /^[1-9][0-9]*$/);
    assert.equal(Object.hasOwn(response, 'username'), false);
    assert.equal(Object.hasOwn(response, 'password'), false);
    assert.ok(!result.body.includes(sampleInput.username));
    assert.ok(!result.body.includes(JSON.stringify(sampleInput.password).slice(1, -1)));
    ids.push(response.recordId);
  }
  assert.notEqual(ids[0], ids[1]);
  const rows = await database.query(
    'SELECT username, password FROM app7_transport_submissions WHERE id = ANY($1::bigint[]) ORDER BY id', [ids],
  );
  assert.deepEqual(rows.rows, [sampleInput, sampleInput]);
});

test('migration giữ dữ liệu cũ và có thể chạy lại mà không xóa bản ghi App 7', async () => {
  const beforeRows = await database.query('SELECT count(*)::integer AS total FROM app7_transport_submissions');
  await app.get(TransportDemoService).onModuleInit();
  const afterRows = await database.query('SELECT count(*)::integer AS total FROM app7_transport_submissions');
  const sentinel = await database.query('SELECT value FROM app7_migration_sentinel');
  assert.equal(afterRows.rows[0].total, beforeRows.rows[0].total);
  assert.equal(sentinel.rows[0].value, 'existing data');
});

test('dữ liệu rỗng, sai kiểu, thừa trường hoặc quá dài bị từ chối mà không ghi CSDL', async () => {
  const beforeRows = await database.query('SELECT count(*)::integer AS total FROM app7_transport_submissions');
  const invalid = [
    {}, { username: '', password: 'x' }, { username: '   ', password: 'x' },
    { username: 'demo', password: '' }, { username: 'demo', password: null },
    { username: 'demo', password: 'x', extra: true },
    { username: 'x'.repeat(101), password: 'x' }, { username: 'demo', password: 'x'.repeat(129) },
    { username: 'demo', password: '\u0000' },
  ];
  for (const body of invalid) {
    assert.equal((await request(http, httpPort, JSON.stringify(body))).status, 400);
  }
  const afterRows = await database.query('SELECT count(*)::integer AS total FROM app7_transport_submissions');
  assert.equal(afterRows.rows[0].total, beforeRows.rows[0].total);
});

test('chứng chỉ không tin cậy và sai tên máy chủ đều bị từ chối trước khi gửi request', async () => {
  const before = arrived;
  const beforeRows = await database.query('SELECT count(*)::integer AS total FROM app7_transport_submissions');
  await assert.rejects(request(https, untrustedPort, sample, { ca }), (error) => error.code === 'DEPTH_ZERO_SELF_SIGNED_CERT');
  await assert.rejects(request(https, trustedPort, sample, { ca, servername: 'wrong.demo.invalid' }),
    (error) => error.code === 'ERR_TLS_CERT_ALTNAME_INVALID');
  assert.equal(arrived, before);
  const afterRows = await database.query('SELECT count(*)::integer AS total FROM app7_transport_submissions');
  assert.equal(afterRows.rows[0].total, beforeRows.rows[0].total);
});

test('cổng HTTPS demo không mở các endpoint khác của backend', async () => {
  await new Promise((resolve, reject) => {
    https.get({ host: '127.0.0.1', port: trustedPort, path: '/api/health', ca, agent: false }, (res) => {
      assert.equal(res.statusCode, 404); res.resume(); res.on('end', resolve);
    }).on('error', reject);
  });
});

async function captureClientBytes(targetPort, useTls) {
  const captured = [];
  const sockets = new Set();
  const proxy = net.createServer((client) => {
    const upstream = net.connect(targetPort, '127.0.0.1');
    sockets.add(client);
    sockets.add(upstream);
    client.on('close', () => sockets.delete(client));
    upstream.on('close', () => sockets.delete(upstream));
    client.on('data', (chunk) => captured.push(Buffer.from(chunk)));
    client.on('error', () => upstream.destroy());
    upstream.on('error', () => client.destroy());
    client.pipe(upstream).pipe(client);
  });
  await new Promise((resolve) => proxy.listen(0, '127.0.0.1', resolve));
  try {
    const result = await request(useTls ? https : http, proxy.address().port, sample, useTls ? { ca } : {});
    assert.equal(result.status, 200);
    return Buffer.concat(captured);
  } finally {
    for (const socket of sockets) socket.destroy();
    await new Promise((resolve) => proxy.close(resolve));
  }
}

test('byte thực tế trên socket: HTTP chứa payload mẫu, HTTPS không chứa payload đó', async () => {
  const plain = await captureClientBytes(httpPort, false);
  const encrypted = await captureClientBytes(trustedPort, true);
  const encodedPassword = Buffer.from(JSON.stringify(sampleInput.password));
  assert.ok(plain.includes(encodedPassword));
  assert.ok(!encrypted.includes(encodedPassword));
});
