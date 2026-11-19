import { Injectable, Logger, OnApplicationShutdown } from '@nestjs/common';
import { Server, IncomingMessage } from 'node:http';
import { Duplex } from 'node:stream';
import { WebSocket, WebSocketServer } from 'ws';

@Injectable()
export class App3SyncEvents implements OnApplicationShutdown {
  private readonly logger = new Logger(App3SyncEvents.name);
  private server?: Server;
  private sockets?: WebSocketServer;
  private upgrade?: (request: IncomingMessage, socket: Duplex, head: Buffer) => void;

  attach(server: Server): void {
    if (this.server) return;
    this.server = server;
    this.sockets = new WebSocketServer({ noServer: true, maxPayload: 1024, perMessageDeflate: false });
    this.sockets.on('error', () => this.logger.warn('Máy chủ WebSocket App 3 gặp lỗi.'));
    this.sockets.on('connection', (socket) => {
      socket.on('error', () => this.logger.warn('Kết nối đồng bộ App 3 bị gián đoạn.'));
      try { socket.send(JSON.stringify({ type: 'connected' })); }
      catch { this.logger.warn('Không gửi được trạng thái WebSocket App 3.'); }
    });
    this.upgrade = (request, socket, head) => {
      if (request.url?.split('?', 1)[0] !== '/api/app3-sync/events') { socket.destroy(); return; }
      this.sockets?.handleUpgrade(request, socket, head, (client) => this.sockets?.emit('connection', client, request));
    };
    server.on('upgrade', this.upgrade);
  }

  notify(epoch: string, version: string): void {
    const message = JSON.stringify({ type: 'catalog_changed', epoch, version });
    for (const client of this.sockets?.clients || []) {
      if (client.readyState === WebSocket.OPEN) {
        try {
          client.send(message, (error) => { if (error) this.logger.warn('Không gửi được tín hiệu đồng bộ App 3.'); });
        } catch {
          this.logger.warn('Không gửi được tín hiệu đồng bộ App 3.');
        }
      }
    }
  }

  onApplicationShutdown(): void {
    if (this.server && this.upgrade) this.server.off('upgrade', this.upgrade);
    for (const socket of this.sockets?.clients || []) socket.terminate();
    this.sockets?.close();
  }
}
