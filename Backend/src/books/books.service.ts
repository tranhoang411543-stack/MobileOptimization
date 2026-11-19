import { Injectable } from '@nestjs/common';
import { performance } from 'node:perf_hooks';
import { DatabaseService } from '../database.service';

interface BookRow {
  id: number;
  title: string;
  author: string;
  price_vnd: number;
}

@Injectable()
export class BooksService {
  constructor(private readonly database: DatabaseService) {}

  async catalogAll() {
    const start = performance.now();
    const result = await this.database.query<BookRow>(
      'SELECT id, title, author, price_vnd FROM books ORDER BY id',
    );
    const databaseMs = Math.round((performance.now() - start) * 100) / 100;
    return {
      mode: 'all',
      items: result.rows.map(toBook),
      returned: result.rows.length,
      hasMore: false,
      nextCursor: null,
      timing: { databaseMs },
    };
  }

  async catalogPage(afterId: number, limit: number) {
    const start = performance.now();
    // Fetch one extra row to detect the next page without a separate COUNT query.
    const result = await this.database.query<BookRow>(
      'SELECT id, title, author, price_vnd FROM books WHERE id > $1 ORDER BY id LIMIT $2',
      [afterId, limit + 1],
    );
    const databaseMs = Math.round((performance.now() - start) * 100) / 100;
    const hasMore = result.rows.length > limit;
    const rows = result.rows.slice(0, limit);
    return {
      mode: 'paged',
      items: rows.map(toBook),
      returned: rows.length,
      hasMore,
      nextCursor: hasMore ? rows[rows.length - 1].id : null,
      timing: { databaseMs },
    };
  }

  async catalogNumberedPage(requestedPage: number, limit: number) {
    const start = performance.now();
    const result = await this.database.transaction(async (client) => {
      // Keep the count and rows consistent even if another client updates books.
      const count = await client.query<{ total: string }>('SELECT COUNT(*)::text AS total FROM books');
      const total = Number(count.rows[0].total);
      if (!Number.isSafeInteger(total)) throw new Error('Tổng số sách vượt giới hạn hỗ trợ');
      const totalPages = Math.ceil(total / limit);
      const page = totalPages === 0 ? 1 : Math.min(requestedPage, totalPages);
      const rows = await client.query<BookRow>(
        'SELECT id, title, author, price_vnd FROM books ORDER BY id LIMIT $1 OFFSET $2',
        [limit, (page - 1) * limit],
      );
      return { total, totalPages, page, rows: rows.rows };
    }, true);
    const databaseMs = Math.round((performance.now() - start) * 100) / 100;
    const hasMore = result.page < result.totalPages;
    return {
      mode: 'paged',
      page: result.page,
      pageSize: limit,
      total: result.total,
      totalPages: result.totalPages,
      items: result.rows.map(toBook),
      returned: result.rows.length,
      hasMore,
      nextCursor: hasMore ? result.rows[result.rows.length - 1].id : null,
      timing: { databaseMs },
    };
  }

  async search(query: string, limit: number) {
    const start = performance.now();
    // strpos treats %, _ and quotes as literal input; the query is parameterized.
    const result = await this.database.query<BookRow>(
      `SELECT id, title, author, price_vnd
       FROM books
       WHERE strpos(lower(title), lower($1)) > 0
          OR strpos(lower(author), lower($1)) > 0
       ORDER BY id
       LIMIT $2`,
      [query, limit],
    );
    const databaseMs = Math.round((performance.now() - start) * 100) / 100;

    return {
      query,
      items: result.rows.map(toBook),
      returned: result.rows.length,
      timing: { databaseMs },
    };
  }
}

function toBook(book: BookRow) {
  return { id: book.id, title: book.title, author: book.author, priceVnd: book.price_vnd };
}
