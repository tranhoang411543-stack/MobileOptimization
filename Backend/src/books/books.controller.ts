import { BadRequestException, Controller, Get, Header, Query } from '@nestjs/common';
import { BooksService } from './books.service';

@Controller('books')
export class BooksController {
  constructor(private readonly books: BooksService) {}

  @Get('catalog')
  @Header('Cache-Control', 'no-store')
  catalog(
    @Query('mode') mode: unknown,
    @Query('limit') rawLimit: unknown,
    @Query('afterId') rawAfterId: unknown,
    @Query('page') rawPage: unknown,
  ) {
    if (mode !== 'all' && mode !== 'paged') {
      throw new BadRequestException('mode phải là all hoặc paged');
    }
    if (mode === 'all') {
      if (rawLimit !== undefined || rawAfterId !== undefined || rawPage !== undefined) {
        throw new BadRequestException('limit, afterId và page chỉ dùng với mode=paged');
      }
      return this.books.catalogAll();
    }
    if (rawLimit !== undefined && (typeof rawLimit !== 'string' || !/^\d{1,2}$/.test(rawLimit))) {
      throw new BadRequestException('limit phải là số nguyên từ 1 đến 50');
    }
    const limit = rawLimit === undefined ? 20 : Number(rawLimit);
    if (limit < 1 || limit > 50) {
      throw new BadRequestException('limit phải là số nguyên từ 1 đến 50');
    }
    if (rawPage !== undefined) {
      if (rawAfterId !== undefined) {
        throw new BadRequestException('page và afterId không được dùng cùng nhau');
      }
      if (typeof rawPage !== 'string' || !/^\d{1,10}$/.test(rawPage)) {
        throw new BadRequestException('page phải là số nguyên dương');
      }
      const page = Number(rawPage);
      if (page < 1 || page > 2147483647) {
        throw new BadRequestException('page phải từ 1 đến 2147483647');
      }
      return this.books.catalogNumberedPage(page, limit);
    }
    if (rawAfterId !== undefined && (typeof rawAfterId !== 'string' || !/^\d{1,10}$/.test(rawAfterId))) {
      throw new BadRequestException('afterId phải là mã sách không âm');
    }
    const afterId = rawAfterId === undefined ? 0 : Number(rawAfterId);
    if (afterId > 2147483647) {
      throw new BadRequestException('afterId vượt giới hạn mã sách');
    }
    return this.books.catalogPage(afterId, limit);
  }

  @Get('search')
  search(@Query('q') rawQuery: unknown, @Query('limit') rawLimit: unknown) {
    if (typeof rawQuery !== 'string') {
      throw new BadRequestException('q phải là một chuỗi tìm kiếm');
    }
    const query = rawQuery.trim();
    if (query.length < 1 || query.length > 100) {
      throw new BadRequestException('q phải có từ 1 đến 100 ký tự');
    }
    if (rawLimit !== undefined && (typeof rawLimit !== 'string' || !/^\d{1,2}$/.test(rawLimit))) {
      throw new BadRequestException('limit phải là số nguyên từ 1 đến 50');
    }
    const limit = rawLimit === undefined ? 20 : Number(rawLimit);
    if (limit < 1 || limit > 50) {
      throw new BadRequestException('limit phải là số nguyên từ 1 đến 50');
    }
    return this.books.search(query, limit);
  }
}
