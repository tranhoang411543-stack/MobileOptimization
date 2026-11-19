const { spawn } = require('node:child_process');
const path = require('node:path');
const { withApp7TestDatabase } = require('./with-app7-test-db.cjs');

withApp7TestDatabase((env) => new Promise((resolve, reject) => {
  const child = spawn(process.execPath, ['--test', 'test/transport-demo.e2e.cjs'], {
    cwd: path.join(__dirname, '..'), env, stdio: 'inherit',
  });
  child.once('error', reject);
  child.once('exit', (code) => resolve(code ?? 1));
})).then((code) => { process.exitCode = code; }).catch((error) => {
  console.error(`Không chạy được kiểm thử App 7 với CSDL cô lập: ${error.message}`);
  process.exitCode = 1;
});
