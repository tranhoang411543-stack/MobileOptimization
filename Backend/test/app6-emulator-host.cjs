// Optional emulator integration fixture. It refuses to run against the normal demo DB.
const { Client } = require('pg');
require('ts-node/register');
require('reflect-metadata');
const { Module } = require('@nestjs/common');
const { NestFactory } = require('@nestjs/core');
const { DatabaseService } = require('../src/database.service');
const { App6NotesController } = require('../src/app6-notes/app6-notes.controller');
const { App6NotesService } = require('../src/app6-notes/app6-notes.service');

if (process.env.MOBILE_DEMO_DB_NAME !== 'app6_notes_test' || !process.env.MOBILE_DEMO_DB_PORT ||
    !/^[1-9]\d{0,4}$/.test(process.env.APP6_EMULATOR_TEST_PORT || '')) {
  throw new Error('Cần CSDL app6_notes_test cô lập, MOBILE_DEMO_DB_PORT và APP6_EMULATOR_TEST_PORT.');
}
class TestApp {}
Module({ controllers: [App6NotesController], providers: [DatabaseService, App6NotesService] })(TestApp);

async function main() {
  const app = await NestFactory.create(TestApp, { logger: false });
  app.setGlobalPrefix('api');
  const client = new Client({
    host: process.env.MOBILE_DEMO_DB_HOST || '127.0.0.1', port: Number(process.env.MOBILE_DEMO_DB_PORT),
    database: 'app6_notes_test', user: process.env.MOBILE_DEMO_DB_USER || 'mobile_demo',
    password: process.env.MOBILE_DEMO_DB_PASSWORD || 'mobile_demo_local',
  });
  await client.connect();
  app.getHttpAdapter().getInstance().post('/__test__/tamper/:id', async (request, response) => {
    if (!/^[1-9]\d{0,9}$/.test(request.params.id) || Number(request.params.id) > 2147483647) return response.sendStatus(400);
    try {
      const result = await client.query('SELECT content FROM app6_notes WHERE id = $1 AND encrypted = true', [request.params.id]);
      if (!result.rows.length) return response.sendStatus(404);
      const envelope = JSON.parse(result.rows[0].content);
      const bytes = Buffer.from(envelope.ciphertext_and_tag, 'base64');
      if (bytes.length <= 16) return response.sendStatus(400);
      bytes[0] ^= 1;
      envelope.ciphertext_and_tag = bytes.toString('base64');
      await client.query('UPDATE app6_notes SET content = $1 WHERE id = $2', [JSON.stringify(envelope), request.params.id]);
      return response.sendStatus(204);
    } catch {
      return response.sendStatus(500);
    }
  });
  await app.listen(Number(process.env.APP6_EMULATOR_TEST_PORT), '127.0.0.1');
  console.log('APP6_EMULATOR_TEST_READY');
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
