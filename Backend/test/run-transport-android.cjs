const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const { withApp7TestDatabase } = require('./with-app7-test-db.cjs');
const { DatabaseService } = require('../src/database.service');

const backend = path.join(__dirname, '..');
const androidProject = path.join(backend, '..', 'T7 - Bảo vệ dữ liệu truyền qua mạng bằng HTTPS');
const appApk = path.join(androidProject, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
const testApk = path.join(androidProject, 'app', 'build', 'outputs', 'apk', 'androidTest', 'debug', 'app-debug-androidTest.apk');

function findAdb() {
  const properties = path.join(androidProject, 'local.properties');
  const line = fs.existsSync(properties)
    ? fs.readFileSync(properties, 'utf8').split(/\r?\n/).find((item) => item.startsWith('sdk.dir='))
    : null;
  const sdk = line ? line.slice('sdk.dir='.length).replace(/\\\\/g, '\\').replace(/\\:/g, ':')
    : process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) throw new Error('Không tìm thấy Android SDK trong local.properties hoặc môi trường.');
  const adb = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  if (!fs.existsSync(adb)) throw new Error('Không tìm thấy adb trong Android SDK.');
  return adb;
}

function runAdb(adb, args, expectTests = false) {
  return new Promise((resolve, reject) => {
    const device = process.env.ANDROID_SERIAL ? ['-s', process.env.ANDROID_SERIAL] : [];
    const child = spawn(adb, [...device, ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.on('data', (chunk) => { output += chunk.toString(); process.stdout.write(chunk); });
    child.stderr.on('data', (chunk) => { output += chunk.toString(); process.stderr.write(chunk); });
    child.once('error', reject);
    child.once('exit', (code) => {
      if (code !== 0 || (expectTests && !/OK \(\d+ tests?\)/.test(output))) {
        reject(new Error('Lệnh ADB hoặc kiểm thử Android không thành công.'));
      } else resolve();
    });
  });
}

function waitForHost(host) {
  return new Promise((resolve, reject) => {
    let output = '';
    const timer = setTimeout(() => reject(new Error('Host kiểm thử Android chưa sẵn sàng.')), 30000);
    host.stdout.on('data', (chunk) => {
      output += chunk.toString();
      if (output.includes('READY')) { clearTimeout(timer); resolve(); }
    });
    host.stderr.on('data', (chunk) => process.stderr.write(chunk));
    host.once('error', (error) => { clearTimeout(timer); reject(error); });
    host.once('exit', (code) => {
      clearTimeout(timer);
      reject(new Error('Host kiểm thử Android đã dừng: ' + code));
    });
  });
}

async function checkSavedFixtures(env) {
  const original = process.env.MOBILE_DEMO_DB_NAME;
  let database;
  try {
    process.env.MOBILE_DEMO_DB_NAME = env.MOBILE_DEMO_DB_NAME;
    database = new DatabaseService();
  } finally {
    if (original === undefined) delete process.env.MOBILE_DEMO_DB_NAME;
    else process.env.MOBILE_DEMO_DB_NAME = original;
  }
  try {
    const fixtures = [
      ['android_client_demo', 'client_demo_pass_123', 2],
      ['android_unexpected_demo', 'unexpected_demo_pass', 1],
      ['android_ui_demo', 'ui_demo_pass_123', 2],
    ];
    for (const [username, password, expected] of fixtures) {
      const result = await database.query(
        'SELECT count(*)::integer AS total FROM app7_transport_submissions WHERE username = $1 AND password = $2',
        [username, password],
      );
      assert.equal(result.rows[0].total, expected, 'Số bản ghi của dữ liệu Android không khớp.');
    }
    const total = await database.query('SELECT count(*)::integer AS total FROM app7_transport_submissions');
    assert.equal(total.rows[0].total, 5, 'Có request không mong đợi đã được ghi.');
    console.log('Đã xác nhận chính xác 5 bản ghi giả lập trong CSDL kiểm thử cô lập.');
  } finally {
    await database.onModuleDestroy();
  }
}

async function main() {
  const adb = findAdb();
  if (!fs.existsSync(appApk) || !fs.existsSync(testApk)) {
    throw new Error('Cần build :app:assembleDebug và :app:assembleDebugAndroidTest trước.');
  }
  await withApp7TestDatabase(async (env) => {
    const host = spawn(process.execPath, ['test/android-transport-host.cjs'], {
      cwd: backend, env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
    });
    try {
      await waitForHost(host);
      await runAdb(adb, ['install', '-r', appApk]);
      await runAdb(adb, ['install', '-r', '-t', testApk]);
      await runAdb(adb, ['shell', 'am', 'instrument', '-w', '-r',
        'vn.edu.mobileoptimization.transport.test/androidx.test.runner.AndroidJUnitRunner'], true);
      await checkSavedFixtures(env);
    } finally {
      if (host.exitCode === null && host.signalCode === null) {
        const exited = once(host, 'exit');
        host.kill();
        await exited;
      }
    }
  });
}

main().catch((error) => {
  console.error('Không chạy được kiểm thử Android App 7: ' + error.message);
  process.exitCode = 1;
});
