type LogLevel = 'debug' | 'info' | 'warn' | 'error';

const LEVELS: Record<LogLevel, number> = { debug: 0, info: 1, warn: 2, error: 3 };

const currentLevel: LogLevel = (process.env.LOG_LEVEL as LogLevel) || 'info';

function log(level: LogLevel, msg: string, ...args: unknown[]): void {
  if (LEVELS[level] >= LEVELS[currentLevel]) {
    const timestamp = new Date().toISOString();
    const formatted = args.length > 0 ? `${msg} ${args.map(a => JSON.stringify(a)).join(' ')}` : msg;
    process.stderr.write(`[${timestamp}] [${level.toUpperCase()}] ${formatted}\n`);
  }
}

export const logger = {
  debug: (msg: string, ...args: unknown[]) => log('debug', msg, ...args),
  info: (msg: string, ...args: unknown[]) => log('info', msg, ...args),
  warn: (msg: string, ...args: unknown[]) => log('warn', msg, ...args),
  error: (msg: string, ...args: unknown[]) => log('error', msg, ...args),
};
