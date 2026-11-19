import { Controller, Get, Header, HttpCode, HttpStatus, ServiceUnavailableException } from '@nestjs/common';
import { DatabaseService } from './database.service';

@Controller('health')
export class HealthController {
  constructor(private readonly database: DatabaseService) {}

  // Lightweight heartbeat for the Android connection-reuse demo; no database query.
  @Get('keep-alive')
  @HttpCode(HttpStatus.NO_CONTENT)
  @Header('Cache-Control', 'no-store')
  keepAlive(): void {}

  @Get()
  async health(): Promise<{ status: 'ok'; database: 'ok' }> {
    try {
      await this.database.checkConnection();
      return { status: 'ok', database: 'ok' };
    } catch {
      throw new ServiceUnavailableException('Cơ sở dữ liệu chưa sẵn sàng');
    }
  }
}
