import { BadRequestException, Body, Controller, Get, Header, Post, Query } from '@nestjs/common';
import { App3SyncService } from './app3-sync.service';

@Controller('app3-sync')
export class App3SyncController {
  constructor(private readonly sync: App3SyncService) {}

  @Get('catalog')
  @Header('Cache-Control', 'no-store')
  catalog() { return this.sync.catalog(); }

  @Get('state')
  @Header('Cache-Control', 'no-store')
  state() { return this.sync.state(); }

  @Get('changes')
  @Header('Cache-Control', 'no-store')
  changes(@Query('epoch') epoch: unknown, @Query('after') after: unknown, @Query('limit') rawLimit: unknown) {
    if (typeof epoch !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(epoch)) {
      throw new BadRequestException('epoch không hợp lệ');
    }
    if (typeof after !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(after) || BigInt(after) > 9223372036854775807n) {
      throw new BadRequestException('after không hợp lệ');
    }
    if (rawLimit !== undefined && (typeof rawLimit !== 'string' || !/^\d{1,3}$/.test(rawLimit))) {
      throw new BadRequestException('limit không hợp lệ');
    }
    const limit = rawLimit === undefined ? 50 : Number(rawLimit);
    if (limit < 1 || limit > 100) throw new BadRequestException('limit phải từ 1 đến 100');
    return this.sync.changes(epoch.toLowerCase(), after, limit);
  }

  @Post('demo-books')
  create(@Body() body: unknown) {
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new BadRequestException('Dữ liệu không hợp lệ');
    const value = body as Record<string, unknown>;
    if (Object.keys(value).some((key) => !['title', 'author', 'priceVnd'].includes(key))) throw new BadRequestException('Trường không hợp lệ');
    const text = (key: string): string | undefined => {
      if (value[key] === undefined) return undefined;
      if (typeof value[key] !== 'string' || !(value[key] as string).trim() || (value[key] as string).trim().length > 120) {
        throw new BadRequestException(`${key} phải có từ 1 đến 120 ký tự`);
      }
      return (value[key] as string).trim();
    };
    const price = value.priceVnd;
    if (price !== undefined && (typeof price !== 'number' || !Number.isInteger(price) || price < 1 || price > 1_000_000_000)) {
      throw new BadRequestException('priceVnd phải là số nguyên dương không quá 1000000000');
    }
    return this.sync.addDemoBook({ title: text('title'), author: text('author'), priceVnd: price as number | undefined });
  }
}
