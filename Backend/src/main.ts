import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { DatabaseService } from './database.service';
import { connectionMetadata } from './connection-metadata';
import { requestTiming } from './request-timing';
import { enableConsoleCommands } from './console-commands';
import { App3SyncEvents } from './app3-sync/app3-sync.events';
import { Server } from 'node:http';
import { join } from 'node:path';
import { startDemoHttps } from './transport-demo/https-listeners';

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule);
  app.use(requestTiming());
  app.use(connectionMetadata());
  app.setGlobalPrefix('api');
  app.enableShutdownHooks();

  // Fail at startup rather than announcing a working API with no database.
  await app.get(DatabaseService).checkConnection();
  app.get(App3SyncEvents).attach(app.getHttpServer() as Server);
  await app.listen(3000, '127.0.0.1');
  try {
    const httpsDemo = await startDemoHttps(app.getHttpAdapter().getInstance(), join(__dirname, '..', '.local-tls', 'app7'));
    app.getHttpServer().once('close', () => { void httpsDemo.close(); });
    console.log('App 7: HTTPS thử nghiệm 127.0.0.1:3443; chứng chỉ không tin cậy 127.0.0.1:3444.');
  } catch (error) {
    console.warn('App 7: Không khởi động được HTTPS thử nghiệm:', error instanceof Error ? error.message : error);
    console.warn('Các API HTTP cũ vẫn hoạt động. Chạy Start.cmd để chuẩn bị chứng chỉ App 7.');
  }
  const closeConsoleCommands = enableConsoleCommands();
  if (closeConsoleCommands) app.getHttpServer().once('close', closeConsoleCommands);
}

bootstrap().catch((error: unknown) => {
  console.error('Không thể khởi động backend:', error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
