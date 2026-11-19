import { Controller, Get, Header, StreamableFile } from '@nestjs/common';
import { Readable } from 'node:stream';
import { setImmediate as yieldToEventLoop } from 'node:timers/promises';

const ROWS = 500_000;
const CHUNK_ROWS = 1000;
const categories = ['Điện tử', 'Gia dụng', 'Sách', 'Văn phòng', 'Thể thao', 'Thời trang', 'Đồ chơi', 'Nhà bếp'];

// Generate deterministically in small chunks: repeated downloads are identical,
// and backpressure lets a disconnected client stop generation without a 30 MB buffer.
async function* sampleCsv(): AsyncGenerator<string> {
  yield 'id,ten,danh_muc,gia,so_luong\n';
  for (let start = 1; start <= ROWS; start += CHUNK_ROWS) {
    const lines: string[] = [];
    const end = Math.min(start + CHUNK_ROWS, ROWS + 1);
    for (let id = start; id < end; id++) {
      const price = 25000 + ((id * 17000) % 2000000);
      lines.push(`${id},Sản phẩm mẫu ${String(id).padStart(6, '0')},${categories[(id - 1) % categories.length]},${price},${id % 30}\n`);
    }
    yield lines.join('');
    await yieldToEventLoop();
  }
}

@Controller('file-demo')
export class FileDemoController {
  @Get('products.csv')
  @Header('Cache-Control', 'no-store')
  file(): StreamableFile {
    return new StreamableFile(Readable.from(sampleCsv()), {
      type: 'text/csv; charset=utf-8',
      disposition: 'attachment; filename="products-demo.csv"',
    });
  }
}
