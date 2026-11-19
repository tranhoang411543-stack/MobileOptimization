import { Injectable, OnModuleDestroy } from '@nestjs/common';
import { Pool, PoolClient, QueryResult, QueryResultRow } from 'pg';

@Injectable()
export class DatabaseService implements OnModuleDestroy {
  private readonly pool = new Pool({
    host: process.env.MOBILE_DEMO_DB_HOST || '127.0.0.1',
    port: Number(process.env.MOBILE_DEMO_DB_PORT || 5549),
    database: process.env.MOBILE_DEMO_DB_NAME || 'mobile_optimization',
    user: process.env.MOBILE_DEMO_DB_USER || 'mobile_demo',
    password: process.env.MOBILE_DEMO_DB_PASSWORD || 'mobile_demo_local',
    max: 10,
    connectionTimeoutMillis: 5000,
    statement_timeout: 5000,
  });

  async query<T extends QueryResultRow>(sql: string, values: unknown[] = []): Promise<QueryResult<T>> {
    return this.pool.query<T>(sql, values);
  }

  async checkConnection(): Promise<void> {
    await this.pool.query('SELECT 1');
  }

  async transaction<T>(fn: (client: PoolClient) => Promise<T>, readOnly = false): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query(readOnly ? 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY' : 'BEGIN');
      const result = await fn(client);
      await client.query('COMMIT');
      return result;
    } catch (error) {
      try { await client.query('ROLLBACK'); } catch { /* Preserve the original failure. */ }
      throw error;
    } finally {
      client.release();
    }
  }

  async onModuleDestroy(): Promise<void> {
    await this.pool.end();
  }
}
