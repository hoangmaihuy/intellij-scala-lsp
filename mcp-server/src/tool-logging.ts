import { logger } from './logger.js';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';

type ToolHandler<T> = (args: T, extra: any) => CallToolResult | Promise<CallToolResult>;

export function withToolLogging<T>(toolName: string, handler: ToolHandler<T>): ToolHandler<T> {
  return async (args: T, extra: any) => {
    const start = Date.now();
    logger.info(`MCP tool:${toolName} params: ${JSON.stringify(args)}`);
    try {
      const result = await handler(args, extra);
      const elapsed = Date.now() - start;
      const textLen = result.content?.reduce((sum, c) => sum + ('text' in c ? c.text?.length ?? 0 : 0), 0) ?? 0;
      logger.info(`MCP tool:${toolName} -> ${elapsed}ms success (${textLen} chars)`);
      return result;
    } catch (err) {
      const elapsed = Date.now() - start;
      logger.info(`MCP tool:${toolName} -> ${elapsed}ms ERROR: ${err}`);
      throw err;
    }
  };
}
