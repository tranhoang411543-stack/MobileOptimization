const assert = require('node:assert/strict');
const http = require('node:http');
const { test } = require('node:test');
require('ts-node/register');

const { connectionMetadata } = require('../src/connection-metadata');
const { requestTiming } = require('../src/request-timing');

test('log từng request hoàn tất và gián đoạn trên kết nối HTTP thật', { timeout: 5000 }, async (t) => {
  const lines = [];
  let abortReceived;
  let abortLogged;
  const requestReceived = new Promise((resolve) => { abortReceived = resolve; });
  const interruptedLogged = new Promise((resolve) => { abortLogged = resolve; });
  const timing = requestTiming((line) => {
    lines.push(line);
    if (line.includes('"/api/abort"')) abortLogged();
  });
  const metadata = connectionMetadata();
  const server = http.createServer((request, response) => {
    timing(request, response, () => metadata(request, response, () => {
      if (request.url === '/api/abort') {
        abortReceived();
      } else if (request.url === '/api/invalid') {
        response.writeHead(400, { 'Content-Type': 'application/json' });
        response.end('{"error":"bad request"}');
      } else {
        setTimeout(() => {
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end('{"ok":true}');
        }, 35);
      }
    }));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  const agent = new http.Agent({ keepAlive: true, maxSockets: 1 });
  t.after(async () => {
    agent.destroy();
    await new Promise((resolve) => server.close(resolve));
  });

  function get(path) {
    return new Promise((resolve, reject) => {
      const request = http.get({ host: '127.0.0.1', port, path, agent }, (response) => {
        let body = '';
        response.setEncoding('utf8');
        response.on('data', (chunk) => { body += chunk; });
        response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body }));
      });
      request.on('error', reject);
    });
  }

  const success = await get('/api/books/search?q=Android&token=secret');
  assert.equal(success.status, 200);
  assert.equal(success.body, '{"ok":true}');
  assert.equal(lines.length, 1);
  assert.match(lines[0], /Nhận lúc \d{2}:\d{2}:\d{2}\.\d{3} \| GET "\/api\/books\/search" \| HTTP 200/);
  const elapsed = lines[0].match(/Backend: (\d+\.\d{2}) ms/);
  assert.ok(elapsed && Number(elapsed[1]) >= 20, lines[0]);
  const connectionId = success.headers['x-demo-connection-id'].slice(0, 8);
  assert.ok(lines[0].includes(`Kết nối: ${connectionId} · lần 1`));
  assert.doesNotMatch(lines[0], /Android|token|secret/);

  const invalid = await get('/api/invalid');
  assert.equal(invalid.status, 400);
  assert.equal(invalid.headers['x-demo-connection-id'], success.headers['x-demo-connection-id']);
  assert.equal(lines.length, 2);
  assert.ok(lines[1].includes(`HTTP 400 | Backend:`));
  assert.ok(lines[1].includes(`Kết nối: ${connectionId} · lần 2`));

  const interrupted = http.get({ host: '127.0.0.1', port, path: '/api/abort' });
  interrupted.on('error', () => {});
  await requestReceived;
  interrupted.destroy();
  await interruptedLogged;
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.equal(lines.length, 3);
  assert.match(lines[2], /GET "\/api\/abort" \| Gián đoạn \(chưa gửi HTTP\)/);
});
