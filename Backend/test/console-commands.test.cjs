const assert = require('node:assert/strict');
const { PassThrough } = require('node:stream');
const { test } = require('node:test');
require('ts-node/register');

const { enableConsoleCommands } = require('../src/console-commands');

test('chỉ xóa khi nhập cls hoặc clear trong terminal tương tác', () => {
  const input = new PassThrough();
  const output = new PassThrough();
  input.isTTY = true;
  output.isTTY = true;
  let text = '';
  let cleared = 0;
  output.on('data', (chunk) => { text += chunk.toString(); });
  const close = enableConsoleCommands({ input, output, clearScreen: () => { cleared++; } });

  assert.equal(typeof close, 'function');
  assert.match(text, /Nhập cls/);
  input.write('wrong command\n');
  assert.equal(cleared, 0);
  assert.match(text, /Lệnh không hợp lệ/);
  input.write('  CLS  \r\nclear\n');
  assert.equal(cleared, 2);
  assert.match(text, /Backend vẫn đang chạy/);
  close();
  input.write('cls\n');
  assert.equal(cleared, 2);
  input.destroy();
  output.destroy();
});

test('không đọc lệnh hoặc xóa khi stdin hoặc stdout không phải terminal', () => {
  const input = new PassThrough();
  const output = new PassThrough();
  let cleared = 0;
  output.isTTY = true;
  assert.equal(enableConsoleCommands({ input, output, clearScreen: () => { cleared++; } }), undefined);
  input.isTTY = true;
  output.isTTY = false;
  assert.equal(enableConsoleCommands({ input, output, clearScreen: () => { cleared++; } }), undefined);
  assert.equal(cleared, 0);
  input.destroy();
  output.destroy();
});
