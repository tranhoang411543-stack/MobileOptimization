const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { after, before, test } = require('node:test');
const { Client } = require('pg');
const { WebSocket } = require('ws');
require('ts-node/register');
require('reflect-metadata');
const { NestFactory } = require('@nestjs/core');
const { AppModule } = require('../src/app.module');
const { App3SyncEvents } = require('../src/app3-sync/app3-sync.events');

let app, base, client, epoch;
const password = process.env.MOBILE_DEMO_DB_PASSWORD || 'mobile_demo_local';

before(async () => {
  if (process.env.MOBILE_DEMO_DB_NAME !== 'app3_sync_test' || !process.env.MOBILE_DEMO_DB_PORT) {
    throw new Error('Chỉ chạy kiểm thử ghi trên PostgreSQL cô lập app3_sync_test với MOBILE_DEMO_DB_PORT riêng.');
  }
  client = new Client({ host: process.env.MOBILE_DEMO_DB_HOST || '127.0.0.1', port: Number(process.env.MOBILE_DEMO_DB_PORT),
    database: 'app3_sync_test', user: process.env.MOBILE_DEMO_DB_USER || 'mobile_demo', password });
  await client.connect();
  const existing = await client.query("SELECT to_regclass('public.books') AS books");
  if (existing.rows[0].books) throw new Error('CSDL kiểm thử không trống; không ghi đè.');
  await client.query(fs.readFileSync(path.join(__dirname, '..', 'database', '001_initial.sql'), 'utf8'));
  app = await NestFactory.create(AppModule, { logger: false });
  app.setGlobalPrefix('api');
  app.get(App3SyncEvents).attach(app.getHttpServer());
  await app.listen(0, '127.0.0.1');
  base = `http://127.0.0.1:${app.getHttpServer().address().port}`;
});
after(async () => { if (app) await app.close(); if (client) await client.end(); });

async function postBook(body = {}) {
  return fetch(`${base}/api/app3-sync/demo-books`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
}
async function changes(after = '0', limit = 50, generation = epoch) {
  return fetch(`${base}/api/app3-sync/changes?epoch=${generation}&after=${after}&limit=${limit}`);
}

test('state, đầu vào và bản danh mục đầu vẫn tương thích', async () => {
  const response = await fetch(`${base}/api/app3-sync/state`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  const head = await response.json();
  epoch = head.epoch;
  assert.equal(head.version, '0');
  const empty = await (await changes()).json();
  assert.deepEqual(empty.changes, []);
  assert.equal(empty.nextCursor, '0');
  assert.equal((await changes('0', 50, '00000000-0000-0000-0000-000000000000')).status, 409);
  assert.equal((await changes('-1')).status, 400);
  assert.equal((await postBook({ priceVnd: -1 })).status, 400);
  const original = await (await fetch(`${base}/api/books/catalog?mode=paged&limit=30`)).json();
  assert.equal(original.items.length, 30);
  assert.equal(original.items[29].id, 30);
  const expandedResponse = await fetch(`${base}/api/app3-sync/catalog`);
  assert.equal(expandedResponse.status, 200);
  assert.equal(expandedResponse.headers.get('cache-control'), 'no-store');
  const expanded = await expandedResponse.json();
  assert.equal(expanded.items.length, 1000);
  assert.equal(expanded.items[0].id, 1);
  assert.equal(expanded.items[999].id, 1000);
  assert.equal(expanded.returned, 1000);
  assert.equal(typeof expanded.timing.databaseMs, 'number');
  assert.deepEqual(expanded.items.slice(0, 30), original.items);
});

test('thêm một sách tạo thay đổi bền vững và báo WebSocket sau khi commit', async () => {
  const ws = new WebSocket(base.replace('http:', 'ws:') + '/api/app3-sync/events');
  try {
    await new Promise((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); });
    const changed = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Thiếu thông báo WebSocket')), 5000);
      ws.on('message', (data) => {
        const event = JSON.parse(data.toString());
        if (event.type === 'catalog_changed') { clearTimeout(timer); resolve(event); }
      });
    });
    const response = await postBook({ title: 'Sách kiểm thử', author: 'Demo', priceVnd: 125000 });
    assert.equal(response.status, 201);
    const added = await response.json();
    const event = await changed;
    assert.equal(added.book.id, 10001);
    assert.equal(added.version, '1');
    assert.equal(event.version, added.version);
    assert.equal(event.epoch, epoch);
    const page = await (await changes()).json();
    assert.equal(page.changes.length, 1);
    assert.equal(page.changes[0].book.id, added.book.id);
    assert.equal(page.nextCursor, '1');
    assert.equal(page.hasMore, false);
    assert.deepEqual((await (await changes('1')).json()).changes, []);
  } finally { ws.terminate(); }
});

test('nhiều ghi đồng thời không trùng ID hoặc bỏ sót mốc, lấy bù qua nhiều trang', async () => {
  const responses = await Promise.all(Array.from({ length: 5 }, () => postBook()));
  assert.ok(responses.every((response) => response.status === 201));
  const books = await Promise.all(responses.map((response) => response.json()));
  assert.equal(new Set(books.map((book) => book.book.id)).size, 5);
  assert.equal(new Set(books.map((book) => book.version)).size, 5);
  let cursor = '1';
  const seen = [];
  let page;
  do {
    page = await (await changes(cursor, 2)).json();
    seen.push(...page.changes.map((item) => item.version));
    cursor = page.nextCursor;
  } while (page.hasMore);
  assert.deepEqual(seen, ['2', '3', '4', '5', '6']);
  assert.equal((await (await fetch(`${base}/api/app3-sync/state`)).json()).version, '6');
});

test('thất bại ghi nhật ký rollback cả sách và phiên bản', async () => {
  await client.query(`CREATE FUNCTION app3_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN IF NEW.title = 'FAIL-SYNC-TEST' THEN RAISE EXCEPTION 'test rollback'; END IF; RETURN NEW; END $$`);
  await client.query('CREATE TRIGGER app3_test_fail BEFORE INSERT ON app3_sync_changes FOR EACH ROW EXECUTE FUNCTION app3_test_fail()');
  const response = await postBook({ title: 'FAIL-SYNC-TEST' });
  assert.equal(response.status, 500);
  assert.equal((await (await fetch(`${base}/api/app3-sync/state`)).json()).version, '6');
  assert.equal((await client.query("SELECT COUNT(*)::int AS n FROM books WHERE title = 'FAIL-SYNC-TEST'")).rows[0].n, 0);
});
