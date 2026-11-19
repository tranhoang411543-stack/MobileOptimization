import { BadRequestException, Body, Controller, Header, HttpCode, Post } from '@nestjs/common';
import { TransportDemoService } from './transport-demo.service';

@Controller('transport-demo')
export class TransportDemoController {
  constructor(private readonly transport: TransportDemoService) {}

  @Post('submit')
  @HttpCode(200)
  @Header('Cache-Control', 'no-store')
  async submit(@Body() body: unknown): Promise<{ ok: true; message: string; recordId: string }> {
    if (!body || typeof body !== 'object' || Array.isArray(body)) {
      throw new BadRequestException('Cần username và password giả lập.');
    }
    const input = body as Record<string, unknown>;
    if (Object.keys(input).length !== 2 || typeof input.username !== 'string' || typeof input.password !== 'string' ||
        input.username.trim().length === 0 || input.username.length > 100 ||
        input.password.length === 0 || input.password.length > 128 ||
        input.username.includes('\u0000') || input.password.includes('\u0000')) {
      throw new BadRequestException('Username và password giả lập không hợp lệ.');
    }
    const recordId = await this.transport.submit(input.username, input.password);
    // This is not authentication; never echo the submitted credentials.
    return { ok: true, message: 'Đã lưu dữ liệu giả lập vào CSDL.', recordId };
  }
}
