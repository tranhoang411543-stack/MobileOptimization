import { createInterface } from 'node:readline';

type TerminalInput = NodeJS.ReadableStream & { isTTY?: boolean; unref?: () => void };
type TerminalOutput = NodeJS.WritableStream & { isTTY?: boolean };

interface ConsoleOptions {
  input?: TerminalInput;
  output?: TerminalOutput;
  clearScreen?: () => void;
}

/** Enable local terminal commands only for an interactive backend process. */
export function enableConsoleCommands(options: ConsoleOptions = {}): (() => void) | undefined {
  const input = options.input ?? process.stdin;
  const output = options.output ?? process.stdout;
  if (!input.isTTY || !output.isTTY) return undefined;

  const clearScreen = options.clearScreen ?? (() => {
    console.clear();
    // Supported terminals also clear their scrollback, beyond the visible viewport.
    output.write('\u001b[3J');
  });
  const reader = createInterface({ input, terminal: false, crlfDelay: Infinity });
  reader.on('line', (line: string) => {
    const command = line.trim().toLowerCase();
    if (command === 'cls' || command === 'clear') {
      clearScreen();
      output.write('Đã xóa log hiển thị. Backend vẫn đang chạy.\n');
    } else if (command) {
      output.write('Lệnh không hợp lệ. Nhập cls rồi nhấn Enter để xóa log.\n');
    }
  });
  // The HTTP server, not stdin, should keep the process alive.
  input.unref?.();
  output.write('Nhập cls rồi nhấn Enter để xóa log; nhấn Ctrl+C để dừng backend.\n');
  return () => reader.close();
}
