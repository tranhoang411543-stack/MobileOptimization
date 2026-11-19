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

function jpegDimensions(bytes) {
  assert.equal(bytes.readUInt16BE(0), 0xffd8);
  let pos = 2;
  while (pos + 8 < bytes.length) {
    while (pos < bytes.length && bytes[pos] !== 0xff) pos++;
    while (pos < bytes.length && bytes[pos] === 0xff) pos++;
    const marker = bytes[pos++];
    if (marker === 0xd8 || marker === 0xd9 || (marker >= 0xd0 && marker <= 0xd7)) continue;
    const length = bytes.readUInt16BE(pos);
    if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
      return { width: bytes.readUInt16BE(pos + 5), height: bytes.readUInt16BE(pos + 3) };
    }
    pos += length;
  }
  throw new Error('Không tìm thấy kích thước JPEG');
}

test('cùng 40 sản phẩm và 8 ảnh JPEG local 2000 × 1500 không cache', async () => {
  const response = await fetch(`${base}/api/image-demo/products`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  const data = await response.json();
  assert.equal(data.returned, 40);
  assert.equal(data.items.length, 40);
  assert.equal(data.uniqueImages, 8);
  assert.equal(new Set(data.items.map((item) => item.id)).size, 40);
  const paths = [...new Set(data.items.map((item) => item.imagePath))];
  assert.equal(paths.length, 8);
  for (const imagePath of paths) {
    const image = await fetch(base + imagePath, { headers: { 'Accept-Encoding': 'identity' } });
    assert.equal(image.status, 200, imagePath);
    assert.match(image.headers.get('content-type'), /^image\/jpeg/);
    assert.equal(image.headers.get('cache-control'), 'no-store');
    const bytes = Buffer.from(await image.arrayBuffer());
    assert.deepEqual(jpegDimensions(bytes), { width: 2000, height: 1500 });
    const again = await fetch(base + imagePath, { headers: { 'Accept-Encoding': 'identity' } });
    const repeated = Buffer.from(await again.arrayBuffer());
    assert.equal(createHash('sha256').update(bytes).digest('hex'), createHash('sha256').update(repeated).digest('hex'));
  }
});

test('ảnh ngoài danh sách cho phép trả 404', async () => {
  for (const path of ['/api/image-demo/images/unknown', '/api/image-demo/images/12345678', '/api/image-demo/images/%2e%2e']) {
    assert.equal((await fetch(base + path)).status, 404, path);
  }
});
