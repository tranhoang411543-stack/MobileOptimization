const assert = require('node:assert/strict');
const { after, before, test } = require('node:test');
require('ts-node/register');
require('reflect-metadata');

const { NestFactory } = require('@nestjs/core');
const { AppModule } = require('../src/app.module');
const { connectionMetadata } = require('../src/connection-metadata');
const { DatabaseService } = require('../src/database.service');
const { BooksService } = require('../src/books/books.service');

let app;
let base;

before(async () => {
  app = await NestFactory.create(AppModule, { logger: false });
  app.use(connectionMetadata());
  app.setGlobalPrefix('api');
  await app.listen(0, '127.0.0.1');
  base = `http://127.0.0.1:${app.getHttpServer().address().port}/api/books`;
});

after(async () => { if (app) await app.close(); });

async function get(path) {
  const response = await fetch(base + path, { headers: { 'Accept-Encoding': 'identity' } });
  const bytes = Buffer.from(await response.arrayBuffer());
  return { status: response.status, headers: response.headers, bytes: bytes.length, body: JSON.parse(bytes) };
}

test('toàn bộ và từng trang trả cùng sách theo thứ tự, trang đầu giảm dữ liệu mạng', async () => {
  const all = await get('/catalog?mode=all');
  const first = await get('/catalog?mode=paged&limit=20');
  assert.equal(all.status, 200);
  assert.ok(all.body.returned > 40, 'Cần dữ liệu mẫu để thử nhiều trang');
  assert.equal(all.body.returned, all.body.items.length);
  assert.equal(all.body.hasMore, false);
  assert.equal(all.body.nextCursor, null);
  assert.equal(first.status, 200);
  assert.equal(first.body.returned, 20);
  assert.deepEqual(first.body.items, all.body.items.slice(0, 20));
  assert.equal(first.body.hasMore, true);
  assert.equal(first.body.nextCursor, first.body.items.at(-1).id);
  assert.equal(first.headers.get('cache-control'), 'no-store');
  assert.ok(first.bytes < all.bytes, 'Dữ liệu trang đầu phải nhỏ hơn tải toàn bộ');
  assert.equal(typeof first.body.timing.databaseMs, 'number');

  const second = await get(`/catalog?mode=paged&limit=20&afterId=${first.body.nextCursor}`);
  assert.deepEqual(second.body.items, all.body.items.slice(20, 40));
  assert.ok(second.body.items[0].id > first.body.items.at(-1).id);

  const cursorBeforeLast = all.body.items.at(-11).id;
  const last = await get(`/catalog?mode=paged&limit=20&afterId=${cursorBeforeLast}`);
  assert.deepEqual(last.body.items, all.body.items.slice(-10));
  assert.equal(last.body.hasMore, false);
  assert.equal(last.body.nextCursor, null);
  const empty = await get(`/catalog?mode=paged&afterId=${all.body.items.at(-1).id}`);
  assert.deepEqual(empty.body.items, []);
  assert.equal(empty.body.hasMore, false);
  assert.equal(empty.body.nextCursor, null);
});

test('chọn trang theo số trả đúng sách, tổng số và trang cuối', async () => {
  const all = await get('/catalog?mode=all');
  const total = all.body.items.length;
  const pageSize = 20;
  const totalPages = Math.ceil(total / pageSize);
  assert.ok(totalPages >= 3, 'Cần ít nhất ba trang để kiểm tra chuyển trang');

  for (const page of [1, 2, 3, totalPages]) {
    const response = await get(`/catalog?mode=paged&limit=${pageSize}&page=${page}`);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.equal(response.body.mode, 'paged');
    assert.equal(response.body.page, page);
    assert.equal(response.body.pageSize, pageSize);
    assert.equal(response.body.total, total);
    assert.equal(response.body.totalPages, totalPages);
    assert.deepEqual(response.body.items, all.body.items.slice((page - 1) * pageSize, page * pageSize));
    assert.equal(response.body.returned, response.body.items.length);
    assert.equal(response.body.hasMore, page < totalPages);
    assert.equal(typeof response.body.timing.databaseMs, 'number');
  }

  const beyondLast = await get('/catalog?mode=paged&limit=20&page=2147483647');
  assert.equal(beyondLast.status, 200);
  assert.equal(beyondLast.body.page, totalPages);
  assert.deepEqual(beyondLast.body.items, all.body.items.slice((totalPages - 1) * pageSize));
});

test('danh sách rỗng và 1003 sách cho số trang chính xác', async () => {
  const database = app.get(DatabaseService);
  await database.transaction(async (client) => {
    await client.query(`CREATE TEMP TABLE books (
      id integer PRIMARY KEY, title text NOT NULL, author text NOT NULL, price_vnd integer NOT NULL
    ) ON COMMIT DROP`);
    const service = new BooksService({
      transaction: async (operation, readOnly) => {
        assert.equal(readOnly, true);
        return operation(client);
      },
    });

    const empty = await service.catalogNumberedPage(1, 20);
    assert.equal(empty.total, 0);
    assert.equal(empty.totalPages, 0);
    assert.equal(empty.page, 1);
    assert.deepEqual(empty.items, []);
    assert.equal(empty.hasMore, false);
    assert.equal(empty.nextCursor, null);

    await client.query(`INSERT INTO books (id, title, author, price_vnd)
      SELECT n, 'Sách ' || n, 'Tác giả', 1000 FROM generate_series(1, 1003) AS n`);
    const first = await service.catalogNumberedPage(1, 20);
    const last = await service.catalogNumberedPage(51, 20);
    const beyondLast = await service.catalogNumberedPage(52, 20);
    assert.equal(first.total, 1003);
    assert.equal(first.totalPages, 51);
    assert.equal(first.returned, 20);
    assert.equal(last.page, 51);
    assert.deepEqual(last.items.map((book) => book.id), [1001, 1002, 1003]);
    assert.equal(last.hasMore, false);
    assert.equal(beyondLast.page, 51);
    assert.deepEqual(beyondLast.items, last.items);
  });
});

test('từ chối chế độ và tham số phân trang sai', async () => {
  for (const path of [
    '/catalog', '/catalog?mode=unknown', '/catalog?mode=all&limit=20',
    '/catalog?mode=paged&limit=0', '/catalog?mode=paged&limit=51',
    '/catalog?mode=paged&limit=1.5', '/catalog?mode=paged&limit=20&limit=10',
    '/catalog?mode=paged&afterId=-1', '/catalog?mode=paged&afterId=2147483648',
    '/catalog?mode=paged&afterId=1&afterId=2',
    '/catalog?mode=all&page=1', '/catalog?mode=paged&page=0',
    '/catalog?mode=paged&page=-1', '/catalog?mode=paged&page=1.5',
    '/catalog?mode=paged&page=2147483648', '/catalog?mode=paged&page=1&page=2',
    '/catalog?mode=paged&page=1&afterId=0',
  ]) {
    assert.equal((await get(path)).status, 400, path);
  }
});

test('API tìm kiếm của App 1 vẫn hoạt động', async () => {
  const response = await get('/search?q=Android&limit=2');
  assert.equal(response.status, 200);
  assert.equal(response.body.returned, 2);
  assert.equal(typeof response.body.timing.databaseMs, 'number');
});
