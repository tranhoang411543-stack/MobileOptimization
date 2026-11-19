import { Module } from '@nestjs/common';
import { DatabaseService } from './database.service';
import { HealthController } from './health.controller';
import { BooksController } from './books/books.controller';
import { BooksService } from './books/books.service';
import { ImageDemoController } from './image-demo/image-demo.controller';
import { FileDemoController } from './file-demo/file-demo.controller';
import { App3SyncController } from './app3-sync/app3-sync.controller';
import { App3SyncService } from './app3-sync/app3-sync.service';
import { App3SyncEvents } from './app3-sync/app3-sync.events';
import { TransportDemoController } from './transport-demo/transport-demo.controller';
import { TransportDemoService } from './transport-demo/transport-demo.service';
import { App6NotesController } from './app6-notes/app6-notes.controller';
import { App6NotesService } from './app6-notes/app6-notes.service';

@Module({
  controllers: [HealthController, BooksController, ImageDemoController, FileDemoController, App3SyncController, TransportDemoController, App6NotesController],
  providers: [DatabaseService, BooksService, App3SyncService, App3SyncEvents, App6NotesService, TransportDemoService],
})
export class AppModule {}
