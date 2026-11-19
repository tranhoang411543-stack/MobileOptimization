/* Local development certificates only. Do not install the demo CA into the system trust store. */
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const backend = path.resolve(__dirname, '..');
const directory = path.join(backend, '.local-tls', 'app7');
const rawDir = path.resolve(backend, '..', 'T7 - Bảo vệ dữ liệu truyền qua mạng bằng HTTPS', 'app', 'src', 'debug', 'res', 'raw');
const names = ['root.key', 'root-ca.pem', 'server.key', 'server.pem', 'untrusted.key', 'untrusted.pem'];
const required = names.map((name) => path.join(directory, name));
const existing = required.filter((file) => fs.existsSync(file));
const candidates = ['openssl', 'C:\\Program Files\\OpenSSL-Win64\\bin\\openssl.exe', 'C:\\Program Files\\Git\\usr\\bin\\openssl.exe'];
const openssl = candidates.find((candidate) => {
  const result = spawnSync(candidate, ['version'], { stdio: 'ignore', windowsHide: true });
  return !result.error && result.status === 0;
});

function run(args) {
  const result = spawnSync(openssl, args, { stdio: 'ignore', windowsHide: true });
  if (result.error || result.status !== 0) throw new Error('OpenSSL không tạo được chứng chỉ. Các file đã tạo được giữ nguyên để kiểm tra.');
}

try {
  if (existing.length !== 0 && existing.length !== required.length) {
    throw new Error('Bộ chứng chỉ App 7 chưa đầy đủ trong Backend/.local-tls/app7. Không tự ghi đè; hãy kiểm tra thư mục này.');
  }
  if (existing.length === 0) {
    if (!openssl) throw new Error('Không tìm thấy OpenSSL. Hãy cài OpenSSL hoặc Git for Windows để tạo chứng chỉ demo.');
    fs.mkdirSync(directory, { recursive: true });
    const file = (name) => path.join(directory, name);
    const ext = file('server-ext.cnf');
    fs.writeFileSync(ext, '[server_ext]\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost\n', { flag: 'wx' });
    console.log('Đang tạo chứng chỉ HTTPS cục bộ cho App 7...');
    run(['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '3650',
      '-keyout', file('root.key'), '-out', file('root-ca.pem'), '-subj', '/CN=App7 Local Demo CA',
      '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign']);
    run(['req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', file('server.key'),
      '-out', file('server.csr'), '-subj', '/CN=10.0.2.2']);
    run(['x509', '-req', '-in', file('server.csr'), '-CA', file('root-ca.pem'), '-CAkey', file('root.key'),
      '-CAcreateserial', '-out', file('server.pem'), '-days', '825', '-sha256', '-extfile', ext, '-extensions', 'server_ext']);
    run(['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '825',
      '-keyout', file('untrusted.key'), '-out', file('untrusted.pem'), '-subj', '/CN=10.0.2.2',
      '-addext', 'basicConstraints=critical,CA:FALSE', '-addext', 'keyUsage=critical,digitalSignature,keyEncipherment',
      '-addext', 'extendedKeyUsage=serverAuth', '-addext', 'subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost']);
  }
  const publicCa = fs.readFileSync(path.join(directory, 'root-ca.pem'));
  const appCa = path.join(rawDir, 'app7_demo_ca.pem');
  if (!fs.existsSync(appCa) || !fs.readFileSync(appCa).equals(publicCa)) {
    fs.mkdirSync(rawDir, { recursive: true });
    fs.writeFileSync(appCa, publicCa);
    console.log('Đã cập nhật CA công khai của App 7. Hãy build/cài lại bản debug trong Android Studio.');
  }
} catch (error) {
  console.error(`Không chuẩn bị được HTTPS App 7: ${error.message}`);
  process.exitCode = 1;
}
