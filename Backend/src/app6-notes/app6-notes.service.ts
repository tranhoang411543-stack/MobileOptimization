import { ConflictException, Injectable, NotFoundException, OnModuleInit } from '@nestjs/common';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { DatabaseService } from '../database.service';

interface NoteSummaryRow {
  id: number;
  title: string;
  encrypted: boolean;
  created_at: Date;
  updated_at: Date | null;
}
interface NoteRow extends NoteSummaryRow { content: string }
export interface StoredNote {
  title: string;
  content: string;
  encrypted: boolean;
}
export interface CreateNote extends StoredNote { requestId: string }
export interface UpdateNote extends StoredNote { expectedRevision: string }

@Injectable()
export class App6NotesService implements OnModuleInit {
  constructor(private readonly database: DatabaseService) {}

  async onModuleInit(): Promise<void> {
    const migrations = await Promise.all(['002_app6_notes.sql', '003_app6_note_updated_at.sql']
      .map((name) => readFile(join(__dirname, '..', '..', 'database', name), 'utf8')));
    await this.database.transaction(async (client) => {
      await client.query("SELECT pg_advisory_xact_lock(hashtext('mobile_optimization_app6_notes_v1'))");
      for (const migration of migrations) await client.query(migration);
    });
  }

  async list(afterId: number | null, limit: number) {
    return this.database.transaction(async (client) => {
      const count = await client.query<{ total: string }>('SELECT COUNT(*)::text AS total FROM app6_notes');
      const total = Number(count.rows[0].total);
      if (!Number.isSafeInteger(total)) throw new Error('Tổng số ghi chú vượt giới hạn hỗ trợ');
      const rows = await client.query<NoteSummaryRow>(
        `SELECT id, title, encrypted, created_at, updated_at FROM app6_notes
         WHERE ($1::integer IS NULL OR id < $1) ORDER BY id DESC LIMIT $2`,
        [afterId, limit + 1],
      );
      const items = rows.rows.slice(0, limit).map(toSummary);
      return {
        total,
        items,
        nextCursor: rows.rows.length > limit ? items[items.length - 1].id : null,
      };
    }, true);
  }

  async get(id: number) {
    const result = await this.database.query<NoteRow>(
      'SELECT id, title, content, encrypted, created_at, updated_at FROM app6_notes WHERE id = $1', [id],
    );
    if (!result.rows.length) throw new NotFoundException('Không tìm thấy ghi chú.');
    const row = result.rows[0];
    return { ...toSummary(row), content: row.content, revision: revisionOf(row) };
  }

  async create(input: CreateNote) {
    const inserted = await this.database.query<NoteSummaryRow>(
      `INSERT INTO app6_notes (request_id, title, content, encrypted)
       VALUES ($1::uuid, $2, $3, $4) ON CONFLICT (request_id) DO NOTHING
       RETURNING id, title, encrypted, created_at, updated_at`,
      [input.requestId, input.title, input.content, input.encrypted],
    );
    if (inserted.rows.length) return toSummary(inserted.rows[0]);

    // Retrying the same request is safe even when its first response was lost.
    const existing = await this.database.query<NoteRow>(
      'SELECT id, title, content, encrypted, created_at, updated_at FROM app6_notes WHERE request_id = $1::uuid', [input.requestId],
    );
    const note = existing.rows[0];
    if (!note || !matches(note, input)) {
      throw new ConflictException('Mã yêu cầu đã được dùng cho ghi chú khác.');
    }
    return toSummary(note);
  }

  async update(id: number, input: UpdateNote) {
    return this.database.transaction(async (client) => {
      const result = await client.query<NoteRow>(
        'SELECT id, title, content, encrypted, created_at, updated_at FROM app6_notes WHERE id = $1 FOR UPDATE', [id],
      );
      const current = result.rows[0];
      if (!current) throw new NotFoundException('Không tìm thấy ghi chú.');
      if (revisionOf(current) !== input.expectedRevision) {
        // A retry after a lost response is safe when the requested values are already stored.
        if (matches(current, input)) return toSummary(current);
        throw new ConflictException('Ghi chú đã thay đổi; hãy mở lại trước khi lưu.');
      }
      const updated = await client.query<NoteSummaryRow>(
        `UPDATE app6_notes SET title = $2, content = $3, encrypted = $4
         WHERE id = $1 RETURNING id, title, encrypted, created_at, updated_at`,
        [id, input.title, input.content, input.encrypted],
      );
      return toSummary(updated.rows[0]);
    });
  }
}

function toSummary(row: NoteSummaryRow) {
  return {
    id: row.id,
    title: row.title,
    encrypted: row.encrypted,
    createdAt: row.created_at.toISOString(),
    updatedAt: row.updated_at?.toISOString() ?? null,
  };
}

function matches(row: NoteRow, input: StoredNote): boolean {
  return row.title === input.title && row.content === input.content && row.encrypted === input.encrypted;
}

function revisionOf(row: NoteRow): string {
  return createHash('sha256').update(JSON.stringify([
    row.id, row.title, row.content, row.encrypted,
  ])).digest('hex');
}
