const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { randomBytes, randomUUID, createCipheriv, createDecipheriv } = require('node:crypto');
const { after, before, test } = require('node:test');
const { Client } = require('pg');
require('ts-node/register');
require('reflect-metadata');
const { Module } = require('@nestjs/common');
const { NestFactory } = require('@nestjs/core');
const { DatabaseService } = require('../src/database.service');
const { App6NotesController } = require('../src/app6-notes/app6-notes.controller');
const { App6NotesService } = require('../src/app6-notes/app6-notes.service');

class TestApp {}
Module({ controllers: [App6NotesController], providers: [DatabaseService, App6NotesService] })(TestApp);

let app, base, client, legacyId, legacyCreatedAt, plainId, encryptedId;
const contentAad = Buffer.from('vn.edu.mobileoptimization.securestore.note.v1');
const key = randomBytes(32);
const content = 'Ghi chú giả lập — Android';

before(async () => {
  if (process.env.MOBILE_DEMO_DB_NAME !== 'app6_notes_test' || !process.env.MOBILE_DEMO_DB_PORT) {
    throw new Error('Chỉ chạy kiểm thử ghi trên CSDL cô lập app6_notes_test với MOBILE_DEMO_DB_PORT riêng.');
  }
  client = new Client({
    host: process.env.MOBILE_DEMO_DB_HOST || '127.0.0.1', port: Number(process.env.MOBILE_DEMO_DB_PORT),
    database: 'app6_notes_test', user: process.env.MOBILE_DEMO_DB_USER || 'mobile_demo',
    password: process.env.MOBILE_DEMO_DB_PASSWORD || 'mobile_demo_local',
  });
  await client.connect();
  const existing = await client.query("SELECT to_regclass('public.app6_notes') AS notes");
  if (existing.rows[0].notes) throw new Error('CSDL kiểm thử không trống; không ghi đè.');
  // Exercise the migration on the real previous schema with existing encrypted content.
  await client.query(fs.readFileSync(path.join(__dirname, '..', 'database', '002_app6_notes.sql'), 'utf8'));
  const legacy = await client.query(
    'INSERT INTO app6_notes (request_id, title, content, encrypted) VALUES ($1, $2, $3, true) RETURNING id, created_at',
    [randomUUID(), 'Tên cũ', encrypt(content)],
  );
  legacyId = legacy.rows[0].id;
  legacyCreatedAt = legacy.rows[0].created_at.toISOString();
  app = await NestFactory.create(TestApp, { logger: false });
  app.setGlobalPrefix('api');
  await app.listen(0, '127.0.0.1');
  base = `http://127.0.0.1:${app.getHttpServer().address().port}/api/app6-notes`;
});
after(async () => { if (app) await app.close(); if (client) await client.end(); });

const post = (body) => fetch(base, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const put = (id, body) => fetch(`${base}/${id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
const get = async (id) => (await fetch(`${base}/${id}`)).json();
function encrypt(text, aad = contentAad) {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(aad);
  const data = Buffer.concat([cipher.update(text, 'utf8'), cipher.final(), cipher.getAuthTag()]);
  return JSON.stringify({ version: 1, algorithm: 'AES-256-GCM', iv: iv.toString('base64'), ciphertext_and_tag: data.toString('base64') });
}
function decrypt(envelope, aad = contentAad) {
  const value = JSON.parse(envelope);
  const bytes = Buffer.from(value.ciphertext_and_tag, 'base64');
  const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(value.iv, 'base64'));
  decipher.setAAD(aad);
  decipher.setAuthTag(bytes.subarray(-16));
  return Buffer.concat([decipher.update(bytes.subarray(0, -16)), decipher.final()]).toString('utf8');
}

test('migration giữ ghi chú cũ và chạy lại an toàn', async () => {
  const old = await get(legacyId);
  assert.equal(old.title, 'Tên cũ');
  assert.equal(old.encrypted, true);
  assert.equal(old.createdAt, legacyCreatedAt);
  assert.equal(old.updatedAt, null);
  assert.equal(decrypt(old.content), content);
  assert.match(old.revision, /^[0-9a-f]{64}$/);
  await app.get(App6NotesService).onModuleInit();
  assert.deepEqual(await get(legacyId), old);
});

test('tạo hai chế độ trong cùng bảng; danh sách chỉ trả thông tin tóm tắt', async () => {
  const plain = await post({ requestId: randomUUID(), title: 'Bản rõ', content, encrypted: false });
  assert.equal(plain.status, 201);
  const plainCreated = await plain.json();
  plainId = plainCreated.id;
  assert.equal(plainCreated.updatedAt, null);
  assert.equal(new Date(plainCreated.createdAt).toISOString(), plainCreated.createdAt);
  const encryptedContent = encrypt(content);
  const secure = await post({ requestId: randomUUID(), title: 'Bản mã', content: encryptedContent, encrypted: true });
  assert.equal(secure.status, 201);
  encryptedId = (await secure.json()).id;
  const response = await fetch(base);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  const list = await response.json();
  assert.equal(list.total, 3);
  assert.equal(list.items[0].title, 'Bản mã');
  assert.equal(list.items[1].title, 'Bản rõ');
  assert.equal(list.items[1].createdAt, plainCreated.createdAt);
  assert.equal(list.items[1].updatedAt, null);
  assert.equal(list.items[2].title, 'Tên cũ');
  assert.ok(list.items.every((item) => !('content' in item)));
  assert.equal((await get(plainId)).content, content);
  const saved = await get(encryptedId);
  assert.equal(saved.title, 'Bản mã');
  assert.equal(saved.content, encryptedContent);
  assert.equal(decrypt(saved.content), content);
  const row = (await client.query('SELECT title, content, encrypted FROM app6_notes WHERE id = $1', [encryptedId])).rows[0];
  assert.equal(row.title, 'Bản mã');
  assert.equal(row.content, encryptedContent);
  assert.equal(row.encrypted, true);
});

test('sửa cùng một bản ghi, đổi chế độ, gửi lại an toàn và từ chối phiên bản cũ', async () => {
  const beforeEdit = await get(plainId);
  assert.equal(beforeEdit.updatedAt, null);
  const secure = { expectedRevision: beforeEdit.revision, title: 'Tên mới',
    content: encrypt('Nội dung mới'), encrypted: true };
  const firstSave = await put(plainId, secure);
  assert.equal(firstSave.status, 200);
  const firstSaved = await firstSave.json();
  assert.ok(firstSaved.updatedAt);
  const retry = await put(plainId, secure);
  assert.equal(retry.status, 200);
  assert.equal((await retry.json()).updatedAt, firstSaved.updatedAt);
  const encrypted = await get(plainId);
  assert.equal(encrypted.id, plainId);
  assert.equal(encrypted.createdAt, beforeEdit.createdAt);
  assert.equal(encrypted.updatedAt, firstSaved.updatedAt);
  assert.equal(encrypted.title, 'Tên mới');
  assert.equal(decrypt(encrypted.content), 'Nội dung mới');
  assert.notEqual(encrypted.revision, beforeEdit.revision);
  assert.equal((await put(plainId, { ...secure, title: 'Xung đột' })).status, 409);
  const clear = { expectedRevision: encrypted.revision, title: 'Tên rõ', content: 'Nội dung rõ', encrypted: false };
  assert.equal((await put(plainId, clear)).status, 200);
  const saved = await get(plainId);
  assert.equal(saved.id, plainId);
  assert.equal(saved.title, 'Tên rõ');
  assert.equal(saved.content, 'Nội dung rõ');
  assert.equal(saved.encrypted, false);
  assert.equal(saved.createdAt, beforeEdit.createdAt);
  assert.ok(Date.parse(saved.updatedAt) >= Date.parse(firstSaved.updatedAt));
  assert.equal((await put(plainId, { ...clear, expectedRevision: saved.revision })).status, 200);
  assert.equal((await get(plainId)).updatedAt, saved.updatedAt);
  assert.equal((await (await fetch(base)).json()).total, 3);
});

test('hai lần sửa đồng thời không ghi đè nhau', async () => {
  const current = await get(legacyId);
  const body = { expectedRevision: current.revision, content: 'Dữ liệu mới', encrypted: false };
  const responses = await Promise.all([put(legacyId, { ...body, title: 'Bản A' }), put(legacyId, { ...body, title: 'Bản B' })]);
  assert.deepEqual(responses.map((response) => response.status).sort(), [200, 409]);
  assert.ok(['Bản A', 'Bản B'].includes((await get(legacyId)).title));
});

test('phân trang, đầu vào, gửi lại yêu cầu và phát hiện bản mã bị sửa', async () => {
  const first = await (await fetch(`${base}?limit=1`)).json();
  assert.equal(first.total, 3);
  assert.equal(first.items[0].id, encryptedId);
  const second = await (await fetch(`${base}?limit=1&afterId=${first.nextCursor}`)).json();
  assert.equal(second.items[0].id, plainId);
  const body = { requestId: randomUUID(), title: 'Gửi lại', content: 'mẫu', encrypted: false };
  const responses = await Promise.all([post(body), post(body)]);
  assert.ok(responses.every((response) => response.status === 201));
  const ids = await Promise.all(responses.map(async (response) => (await response.json()).id));
  assert.equal(ids[0], ids[1]);
  assert.equal((await post({ ...body, content: 'khác' })).status, 409);
  assert.equal((await post({ ...body, requestId: randomUUID(), encrypted: true })).status, 400);
  assert.equal((await post({ ...body, requestId: randomUUID(), titleEncrypted: true })).status, 400);
  assert.equal((await fetch(`${base}?limit=101`)).status, 400);
  assert.equal((await fetch(`${base}/0`)).status, 400);
  assert.equal((await fetch(`${base}/2147483647`)).status, 404);
  const current = await get(encryptedId);
  const modified = JSON.parse(current.content);
  const bytes = Buffer.from(modified.ciphertext_and_tag, 'base64');
  bytes[0] ^= 1;
  modified.ciphertext_and_tag = bytes.toString('base64');
  await client.query('UPDATE app6_notes SET content = $1 WHERE id = $2', [JSON.stringify(modified), encryptedId]);
  const received = await get(encryptedId);
  assert.equal(current.updatedAt, null);
  assert.ok(received.updatedAt);
  assert.notEqual(received.revision, current.revision);
  assert.throws(() => decrypt(received.content));
  assert.equal((await put(encryptedId, { expectedRevision: current.revision, title: 'Cũ', content: 'Cũ',
    encrypted: false })).status, 409);
});
