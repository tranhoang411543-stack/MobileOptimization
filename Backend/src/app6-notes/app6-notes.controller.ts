import { BadRequestException, Body, Controller, Get, Header, Param, Post, Put, Query } from '@nestjs/common';
import { App6NotesService, StoredNote } from './app6-notes.service';

const MAX_PLAIN_BYTES = 32 * 1024;
const MAX_ENVELOPE_BYTES = 64 * 1024;

@Controller('app6-notes')
export class App6NotesController {
  constructor(private readonly notes: App6NotesService) {}

  @Get()
  @Header('Cache-Control', 'no-store')
  list(@Query('afterId') rawAfterId: unknown, @Query('limit') rawLimit: unknown) {
    const afterId = rawAfterId === undefined ? null : positiveId(rawAfterId);
    if (rawLimit !== undefined && (typeof rawLimit !== 'string' || !/^\d{1,3}$/.test(rawLimit))) {
      throw new BadRequestException('limit không hợp lệ.');
    }
    const limit = rawLimit === undefined ? 50 : Number(rawLimit);
    if (limit < 1 || limit > 100) throw new BadRequestException('limit phải từ 1 đến 100.');
    return this.notes.list(afterId, limit);
  }

  @Get(':id')
  @Header('Cache-Control', 'no-store')
  get(@Param('id') rawId: unknown) { return this.notes.get(positiveId(rawId)); }

  @Post()
  @Header('Cache-Control', 'no-store')
  create(@Body() body: unknown) {
    const value = bodyObject(body, ['requestId', 'title', 'content', 'encrypted']);
    if (typeof value.requestId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.requestId)) {
      throw new BadRequestException('requestId không hợp lệ.');
    }
    return this.notes.create({ requestId: value.requestId.toLowerCase(), ...storedNote(value) });
  }

  @Put(':id')
  @Header('Cache-Control', 'no-store')
  update(@Param('id') rawId: unknown, @Body() body: unknown) {
    const value = bodyObject(body, ['expectedRevision', 'title', 'content', 'encrypted']);
    if (typeof value.expectedRevision !== 'string' || !/^[0-9a-f]{64}$/.test(value.expectedRevision)) {
      throw new BadRequestException('Phiên bản ghi chú không hợp lệ.');
    }
    return this.notes.update(positiveId(rawId), { expectedRevision: value.expectedRevision, ...storedNote(value) });
  }
}

function bodyObject(body: unknown, allowed: string[]): Record<string, unknown> {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new BadRequestException('Dữ liệu không hợp lệ.');
  const value = body as Record<string, unknown>;
  if (Object.keys(value).some((key) => !allowed.includes(key))) throw new BadRequestException('Trường không hợp lệ.');
  return value;
}

function storedNote(value: Record<string, unknown>): StoredNote {
  if (typeof value.encrypted !== 'boolean') throw new BadRequestException('encrypted phải là true hoặc false.');
  if (typeof value.title !== 'string' || !value.title.trim() || value.title.includes('\0')) {
    throw new BadRequestException('Tên ghi chú không hợp lệ.');
  }
  const title = value.title.trim();
  if (title.length > 120) {
    throw new BadRequestException('Tên ghi chú phải có từ 1 đến 120 ký tự.');
  }
  if (typeof value.content !== 'string' || !value.content.length || value.content.includes('\0') ||
      Buffer.byteLength(value.content, 'utf8') > (value.encrypted ? MAX_ENVELOPE_BYTES : MAX_PLAIN_BYTES)) {
    throw new BadRequestException('Nội dung ghi chú không hợp lệ hoặc quá lớn.');
  }
  if (value.encrypted) validateEnvelope(value.content, MAX_PLAIN_BYTES);
  return { title, content: value.content, encrypted: value.encrypted };
}

function positiveId(value: unknown): number {
  if (typeof value !== 'string' || !/^[1-9]\d{0,9}$/.test(value) || Number(value) > 2147483647) {
    throw new BadRequestException('Mã ghi chú không hợp lệ.');
  }
  return Number(value);
}

function validateEnvelope(content: string, maxClearBytes: number): void {
  try {
    const value: unknown = JSON.parse(content);
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error();
    const envelope = value as Record<string, unknown>;
    const base64 = (item: unknown): Buffer => {
      if (typeof item !== 'string' || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(item)) throw new Error();
      return Buffer.from(item, 'base64');
    };
    if (envelope.version !== 1 || envelope.algorithm !== 'AES-256-GCM' || base64(envelope.iv).length !== 12) throw new Error();
    const length = base64(envelope.ciphertext_and_tag).length;
    if (length <= 16 || length > maxClearBytes + 16) throw new Error();
  } catch {
    throw new BadRequestException('Định dạng nội dung mã hóa không hợp lệ.');
  }
}
