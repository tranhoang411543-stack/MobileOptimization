const assert = require('node:assert/strict');
const { test } = require('node:test');
require('ts-node/register');
require('reflect-metadata');
const { App3SyncService } = require('../src/app3-sync/app3-sync.service');

test('App 3 ghi thời gian CSDL cho danh mục và các request đồng bộ', async () => {
  const head = { epoch: '00000000-0000-0000-0000-000000000001', version: '0' };
  const client = { async query(sql) {
    if (sql.includes('UPDATE app3_sync_state')) return { rows: [{ ...head, version: '1' }] };
    if (sql.includes('MAX(id)')) return { rows: [{ next_id: '10001' }] };
    if (sql.includes('FROM app3_sync_state')) return { rows: [head] };
    return { rows: [] };
  } };
  let committed = false;
  const database = {
    async query() { return { rows: [head] }; },
    async transaction(action) { const value = await action(client); committed = true; return value; },
  };
  const events = { notify() { assert.equal(committed, true); } };
  const books = { async catalogPage(afterId, limit) {
    assert.equal(afterId, 0);
    assert.equal(limit, 1000);
    return { items: [], returned: 0, timing: { databaseMs: 7.25 } };
  } };
  const service = new App3SyncService(database, events, books);
  const logs = [];
  service.logger.log = (message) => logs.push(message);

  await service.catalog();
  await service.state();
  await service.changes(head.epoch, '0', 50);
  committed = false;
  await service.addDemoBook({ title: 'Tên riêng không đưa vào log' });

  assert.equal(logs.length, 4);
  assert.match(logs[0], /GET \/api\/app3-sync\/catalog \| CSDL: 7\.25 ms/);
  assert.match(logs[1], /GET \/api\/app3-sync\/state \| CSDL: \d+\.\d{2} ms/);
  assert.match(logs[2], /GET \/api\/app3-sync\/changes \(transaction\) \| CSDL: \d+\.\d{2} ms/);
  assert.match(logs[3], /POST \/api\/app3-sync\/demo-books \(transaction\) \| CSDL: \d+\.\d{2} ms/);
  assert.ok(logs.every((line) => !line.includes('Tên riêng')));
});

test('App 3 vẫn ghi thời gian CSDL khi truy vấn lỗi và không che lỗi', async () => {
  const service = new App3SyncService({ query: async () => { throw new Error('lỗi CSDL'); } }, {}, {});
  const logs = [];
  service.logger.log = (message) => logs.push(message);
  await assert.rejects(service.state(), /lỗi CSDL/);
  assert.match(logs[0], /GET \/api\/app3-sync\/state \| CSDL: \d+\.\d{2} ms/);
});
