import { Injectable, Logger, OnModuleInit, ServiceUnavailableException } from '@nestjs/common';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { DatabaseService } from '../database.service';

@Injectable()
export class TransportDemoService implements OnModuleInit {
  private readonly logger = new Logger('App7Transport');

  constructor(private readonly database: DatabaseService) {}

  async onModuleInit(): Promise<void> {
    const migration = await readFile(join(__dirname, '..', '..', 'database', '004_app7_transport_submissions.sql'), 'utf8');
    await this.database.transaction(async (client) => {
      await client.query("SELECT pg_advisory_xact_lock(hashtext('mobile_optimization_app7_transport_v1'))");
      await client.query(migration);
    });
  }

  async submit(username: string, password: string): Promise<string> {
    try {
      const result = await this.database.query<{ id: string }>(
        'INSERT INTO app7_transport_submissions (username, password) VALUES ($1, $2) RETURNING id::text AS id',
        [username, password],
      );
      return result.rows[0].id;
    } catch {
      // Do not expose or log submitted credentials or database error details.
      this.logger.error('Không thể lưu dữ liệu App 7 vào PostgreSQL.');
      throw new ServiceUnavailableException('Không thể lưu dữ liệu giả lập vào CSDL.');
    }
  }
}
