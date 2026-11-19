const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { after, before, test } = require('node:test');
require('ts-node/register');
require('reflect-metadata');

const { NestFactory } = require('@nestjs/core');
const { AppModule } = require('../src/app.module');
let app;
let base;

before(async () => {
  app = await NestFactory.create(AppModule, { logger: false });
  app.setGlobalPrefix('api');
  await app.listen(0, '127.0.0.1');
  base = `http://127.0.0.1:${app.getHttpServer().address().port}`;
});
after(async () => { if (app) await app.close(); });

async function digestDownload() {
  const response = await fetch(`${base}/api/file-demo/products.csv`);
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /^text\/csv;\s*charset=utf-8/i);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.match(response.headers.get('content-disposition'), /products-demo\.csv/);
  const hash = createHash('sha256');
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let lines = 0;
  let prefix = '';
  let suffix = '';
  let bytes = 0;
  for await (const chunk of response.body) {
    hash.update(chunk);
    bytes += chunk.length;
    const text = decoder.decode(chunk, { stream: true });
    lines += [...text].filter((char) => char === '\n').length;
    if (prefix.length < 120) prefix += text.slice(0, 120 - prefix.length);
    suffix = (suffix + text).slice(-120);
  }
  decoder.decode();
  assert.equal(lines, 500_001);
  assert.ok(bytes > 10_000_000 && bytes < 64 * 1024 * 1024);
  assert.match(prefix, /^id,ten,danh_muc,gia,so_luong\n1,Sản phẩm mẫu 000001,Điện tử,42000,1\n/);
  assert.match(suffix, /500000,Sản phẩm mẫu 500000,Nhà bếp,25000,20\n$/);
  return { digest: hash.digest('hex'), bytes };
}

test('file CSV mẫu có đủ 500.000 dòng và không đổi giữa các lần tải', async () => {
  const first = await digestDownload();
  assert.deepEqual(await digestDownload(), first);
});
