import { Controller, Get, Header, NotFoundException, Param, StreamableFile } from '@nestjs/common';
import { createReadStream } from 'node:fs';
import { join } from 'node:path';

const samples = [
  { photo: '30428610', name: 'Tai nghe Studio', priceVnd: 890000 },
  { photo: '37101971', name: 'Máy ảnh DSLR', priceVnd: 8750000 },
  { photo: '30838490', name: 'Đồng hồ dây da', priceVnd: 1250000 },
  { photo: '27111647', name: 'Kính mát thời trang', priceVnd: 390000 },
  { photo: '1461048', name: 'Giày thể thao', priceVnd: 990000 },
  { photo: '6763232', name: 'Ly cà phê', priceVnd: 79000 },
  { photo: '17018424', name: 'Điện thoại thông minh', priceVnd: 5990000 },
  { photo: '13791389', name: 'Phụ kiện bàn làm việc', priceVnd: 490000 },
] as const;
const allowedPhotos = new Set<string>(samples.map((sample) => sample.photo));
const assetDirectory = join(__dirname, '..', '..', 'assets', 'image-demo');

@Controller('image-demo')
export class ImageDemoController {
  @Get('products')
  @Header('Cache-Control', 'no-store')
  products() {
    const items = Array.from({ length: 40 }, (_, index) => {
      const sample = samples[index % samples.length];
      const variant = Math.floor(index / samples.length) + 1;
      return {
        id: index + 1,
        name: `${sample.name} · Mẫu ${String(variant).padStart(2, '0')}`,
        priceVnd: sample.priceVnd,
        imagePath: `/api/image-demo/images/${sample.photo}`,
      };
    });
    return { items, returned: items.length, uniqueImages: samples.length, imageWidth: 2000, imageHeight: 1500 };
  }

  @Get('images/:photo')
  @Header('Cache-Control', 'no-store')
  image(@Param('photo') photo: string): StreamableFile {
    if (!allowedPhotos.has(photo)) throw new NotFoundException('Không tìm thấy ảnh mẫu');
    return new StreamableFile(createReadStream(join(assetDirectory, `${photo}.jpg`)), { type: 'image/jpeg' });
  }
}
