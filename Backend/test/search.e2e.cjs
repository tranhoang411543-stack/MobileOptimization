const assert = require('node:assert/strict');
const test = require('node:test');

// Chạy khi backend và PostgreSQL của dự án đã được Start.ps1 khởi động.
const base = 'http://127.0.0.1:3000/api';

async function get(path) {
  const response = await fetch(base + path);
  return { status: response.status, body: await response.json() };
}

test('health xác nhận PostgreSQL đang sẵn sàng', async () => {
  const result = await get('/health');
  assert.equal(result.status, 200);
  assert.deepEqual(result.body, { status: 'ok', database: 'ok' });
});

test('tìm kiếm không phân biệt hoa thường và có giới hạn kết quả', async () => {
  const first = await get('/books/search?q=Android&limit=5');
  const second = await get('/books/search?q=android&limit=5');
  assert.equal(first.status, 200);
  assert.equal(first.body.returned, 5);
  assert.deepEqual(first.body.items, second.body.items);
  assert.ok(first.body.items.every((book) => /android/i.test(book.title)));
  assert.equal(typeof first.body.timing.databaseMs, 'number');
});

test('không có kết quả và ký tự đặc biệt được xem là chuỗi tìm kiếm', async () => {
  for (const query of ['khong-co-sach-nay-xyz', '%', "' OR 1=1 --"]) {
    const result = await get('/books/search?q=' + encodeURIComponent(query));
    assert.equal(result.status, 200);
    assert.deepEqual(result.body.items, []);
  }
});

test('từ khóa và giới hạn không hợp lệ trả về 400', async () => {
  for (const path of ['/books/search', '/books/search?q=%20', '/books/search?q=' + 'a'.repeat(101),
    '/books/search?q=a&limit=0', '/books/search?q=a&limit=51', '/books/search?q=a&limit=1.5',
    '/books/search?q=a&q=b']) {
    assert.equal((await get(path)).status, 400, path);
  }
});
