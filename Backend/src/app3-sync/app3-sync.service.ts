import { ConflictException, Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { BooksService } from '../books/books.service';
import { DatabaseService } from '../database.service';
import { App3SyncEvents } from './app3-sync.events';

interface SyncHead { epoch: string; version: string }
interface ChangeRow { version: string; book_id: number; title: string; author: string; price_vnd: number }

@Injectable()
export class App3SyncService implements OnModuleInit {
  private readonly logger = new Logger('App3Database');

  constructor(
    private readonly database: DatabaseService,
    private readonly events: App3SyncEvents,
    private readonly books: BooksService,
  ) {}

  async catalog() {
    const startedAt = performance.now();
    try {
      const result = await this.books.catalogPage(0, 1000);
      this.logger.log(`GET /api/app3-sync/catalog | CSDL: ${result.timing.databaseMs.toFixed(2)} ms | Sách: ${result.returned}`);
      return result;
    } catch (error) {
      this.logger.log(`GET /api/app3-sync/catalog | CSDL: ${(performance.now() - startedAt).toFixed(2)} ms | Lỗi`);
      throw error;
    }
  }

  async onModuleInit(): Promise<void> {
    // Idempotent additive migration: existing PostgreSQL volumes keep their data.
    await this.database.transaction(async (client) => {
      await client.query("SELECT pg_advisory_xact_lock(hashtext('mobile_optimization_app3_sync_v1'))");
      await client.query(`CREATE TABLE IF NOT EXISTS app3_sync_state (
        id smallint PRIMARY KEY CHECK (id = 1), epoch uuid NOT NULL,
        version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
      )`);
      await client.query(`CREATE TABLE IF NOT EXISTS app3_sync_changes (
        version bigint PRIMARY KEY CHECK (version > 0), book_id integer NOT NULL UNIQUE,
        title text NOT NULL, author text NOT NULL, price_vnd integer NOT NULL CHECK (price_vnd > 0)
      )`);
      await client.query('INSERT INTO app3_sync_state (id, epoch, version) VALUES (1, $1, 0) ON CONFLICT (id) DO NOTHING', [randomUUID()]);
    });
  }

  async state(): Promise<SyncHead> {
    return this.measureDatabase('GET /api/app3-sync/state', async () => {
      const result = await this.database.query<SyncHead>('SELECT epoch::text, version::text FROM app3_sync_state WHERE id = 1');
      return result.rows[0];
    });
  }

  async changes(epoch: string, after: string, limit: number) {
    return this.measureDatabase('GET /api/app3-sync/changes (transaction)', () => this.database.transaction(async (client) => {
      const result = await client.query<SyncHead>('SELECT epoch::text, version::text FROM app3_sync_state WHERE id = 1');
      const head = result.rows[0];
      if (head.epoch !== epoch || BigInt(after) > BigInt(head.version)) {
        throw new ConflictException('Mốc đồng bộ không còn hợp lệ; cần tải lại dữ liệu ban đầu.');
      }
      const rows = await client.query<ChangeRow>(
        `SELECT version::text, book_id, title, author, price_vnd FROM app3_sync_changes
         WHERE version > $1::bigint ORDER BY version LIMIT $2`, [after, limit + 1],
      );
      const page = rows.rows.slice(0, limit);
      return {
        epoch: head.epoch,
        changes: page.map((row) => ({ version: row.version, book: {
          id: row.book_id, title: row.title, author: row.author, priceVnd: row.price_vnd,
        } })),
        nextCursor: page.length ? page[page.length - 1].version : after,
        hasMore: rows.rows.length > limit,
      };
    }, true));
  }

  async addDemoBook(input: { title?: string; author?: string; priceVnd?: number }) {
    const result = await this.measureDatabase('POST /api/app3-sync/demo-books (transaction)', () => this.database.transaction(async (client) => {
      // This lock also protects against collisions with other inserts into the existing books table.
      await client.query('LOCK TABLE books IN SHARE ROW EXCLUSIVE MODE');
      const head = await client.query<SyncHead>(
        'UPDATE app3_sync_state SET version = version + 1 WHERE id = 1 RETURNING epoch::text, version::text',
      );
      const idResult = await client.query<{ next_id: string }>('SELECT (COALESCE(MAX(id), 0)::bigint + 1)::text AS next_id FROM books');
      const id = Number(idResult.rows[0].next_id);
      if (id > 2147483647) throw new ConflictException('Đã hết mã sách có thể tạo.');
      const { epoch, version } = head.rows[0];
      const book = { id, title: input.title || `Sách đồng bộ mới #${version}`,
        author: input.author || 'Tác giả demo', priceVnd: input.priceVnd ?? 120000 };
      await client.query('INSERT INTO books (id, title, author, price_vnd) VALUES ($1, $2, $3, $4)',
        [book.id, book.title, book.author, book.priceVnd]);
      await client.query('INSERT INTO app3_sync_changes (version, book_id, title, author, price_vnd) VALUES ($1, $2, $3, $4, $5)',
        [version, book.id, book.title, book.author, book.priceVnd]);
      return { epoch, version, book };
    }));
    // Broadcast only after commit. The durable changes API repairs missed signals.
    this.events.notify(result.epoch, result.version);
    return result;
  }

  private async measureDatabase<T>(operation: string, action: () => Promise<T>): Promise<T> {
    const startedAt = performance.now();
    try {
      return await action();
    } finally {
      this.logger.log(`${operation} | CSDL: ${(performance.now() - startedAt).toFixed(2)} ms`);
    }
  }
}
