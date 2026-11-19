const { randomBytes } = require('node:crypto');
require('ts-node/register');
require('reflect-metadata');
const { DatabaseService } = require('../src/database.service');

// Each run owns a new database; never delete or reset the user's demo database.
async function withApp7TestDatabase(run) {
  const name = `app7_transport_test_${randomBytes(8).toString('hex')}`;
  if (!/^app7_transport_test_[0-9a-f]{16}$/.test(name)) throw new Error('Tên CSDL kiểm thử không hợp lệ.');
  const admin = new DatabaseService();
  let created = false;
  try {
    await admin.query(`CREATE DATABASE "${name}"`);
    created = true;
    return await run({ ...process.env, MOBILE_DEMO_DB_NAME: name, APP7_ISOLATED_TEST: '1' }, name);
  } finally {
    try {
      if (created) await admin.query(`DROP DATABASE "${name}"`);
    } finally {
      await admin.onModuleDestroy();
    }
  }
}

module.exports = { withApp7TestDatabase };
