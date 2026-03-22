/**
 * Test harness that captures MCP tool handlers from registerTools()
 * and lets tests call them directly without MCP transport.
 */
import { CallToolResult } from '@modelcontextprotocol/sdk/types.js';

type ToolHandler = (args: Record<string, unknown>) => Promise<CallToolResult>;

interface RegisteredTool {
  name: string;
  description: string;
  handler: ToolHandler;
}

/**
 * A fake McpServer that captures tool registrations.
 * Compatible with the McpServer.tool() signature used by registerTools().
 */
export class TestToolRunner {
  private tools = new Map<string, RegisteredTool>();

  /** McpServer.tool() compatible method — captures the handler */
  tool(name: string, ...rest: unknown[]): unknown {
    // The last argument is always the callback
    const cb = rest[rest.length - 1] as ToolHandler;
    const description = typeof rest[0] === 'string' ? rest[0] as string : '';

    this.tools.set(name, { name, description, handler: cb });

    // Return a minimal RegisteredTool-like object
    return { update: () => {}, remove: () => {} };
  }

  /** Call a tool by name with arguments, returns the text content */
  async callTool(name: string, args: Record<string, unknown> = {}): Promise<string> {
    const tool = this.tools.get(name);
    if (!tool) {
      throw new Error(`Tool '${name}' not registered. Available: ${[...this.tools.keys()].join(', ')}`);
    }

    const result = await tool.handler(args);
    const textContent = result.content
      .filter((c): c is { type: 'text'; text: string } => c.type === 'text')
      .map(c => c.text)
      .join('\n');

    return textContent;
  }

  /** Get all registered tool names */
  get toolNames(): string[] {
    return [...this.tools.keys()];
  }
}
