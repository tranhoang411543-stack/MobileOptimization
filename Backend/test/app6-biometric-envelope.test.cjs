const assert = require('node:assert/strict');
const { randomBytes, randomUUID, createCipheriv, createDecipheriv } = require('node:crypto');
const { test } = require('node:test');
require('ts-node/register');
require('reflect-metadata');
const { App6NotesController } = require('../src/app6-notes/app6-notes.controller');

test('App 6 API chấp nhận bản mã khóa sinh trắc khi tạo và sửa', () => {
  const key = randomBytes(32);
  const iv = randomBytes(12);
  const aad = Buffer.from('vn.edu.mobileoptimization.securestore.note.biometric.v1');
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(aad);
  const clear = 'Ghi chú mẫu';
  const encrypted = Buffer.concat([cipher.update(clear, 'utf8'), cipher.final(), cipher.getAuthTag()]);
  const content = JSON.stringify({ version: 1, algorithm: 'AES-256-GCM', key_scope: 'biometric-wrapped-v1',
    iv: iv.toString('base64'), ciphertext_and_tag: encrypted.toString('base64') });
  const controller = new App6NotesController({
    create: (value) => value,
    update: (id, value) => ({ id, ...value }),
  });

  const created = controller.create({ requestId: randomUUID(), title: 'Mẫu', content, encrypted: true });
  const updated = controller.update('1', { expectedRevision: 'a'.repeat(64), title: 'Mẫu', content, encrypted: true });
  assert.equal(created.content, content);
  assert.equal(updated.id, 1);
  assert.equal(updated.content, content);

  const decipher = createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAAD(aad);
  decipher.setAuthTag(encrypted.subarray(-16));
  assert.equal(Buffer.concat([decipher.update(encrypted.subarray(0, -16)), decipher.final()]).toString('utf8'), clear);
});

test('App 6 API tiếp tục từ chối bản mã thiếu thẻ xác thực', () => {
  const controller = new App6NotesController({ create: (value) => value });
  const content = JSON.stringify({ version: 1, algorithm: 'AES-256-GCM', key_scope: 'biometric-wrapped-v1',
    iv: Buffer.alloc(12).toString('base64'), ciphertext_and_tag: Buffer.alloc(16).toString('base64') });
  assert.throws(() => controller.create({ requestId: randomUUID(), title: 'Mẫu', content, encrypted: true }),
    /Định dạng nội dung mã hóa không hợp lệ/);
});
