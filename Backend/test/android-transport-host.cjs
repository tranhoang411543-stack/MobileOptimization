// Temporary local NestJS listeners used only by App 7 Android instrumentation tests.
require('ts-node/register');
require('reflect-metadata');
const path = require('node:path');
const { NestFactory } = require('@nestjs/core');
const { AppModule } = require('../src/app.module');
const { startDemoHttps } = require('../src/transport-demo/https-listeners');

if (process.env.APP7_ISOLATED_TEST !== '1' || !/^app7_transport_test_[0-9a-f]{16}$/.test(process.env.MOBILE_DEMO_DB_NAME || '')) {
  throw new Error('Host kiểm thử Android chỉ được ghi vào CSDL App 7 cô lập.');
}

async function main() {
  const app = await NestFactory.create(AppModule, { logger: false });
  app.setGlobalPrefix('api');
  await app.listen(13070, '127.0.0.1');
  const tls = await startDemoHttps(app.getHttpAdapter().getInstance(),
    path.join(__dirname, '..', '.local-tls', 'app7'), 13473, 13474);
  console.log('App 7 Android test listeners READY: 13070, 13473, 13474');
  process.on('SIGINT', async () => { await tls.close(); await app.close(); process.exit(0); });
}
main().catch((error) => { console.error(error.message); process.exitCode = 1; });
