import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { LspClient } from '../lsp-client.js';
import { FileManager } from '../file-manager.js';
import { SymbolResolver } from '../symbol-resolver.js';
import { uriToPath } from '../utils.js';
import {
  TypeHierarchyItem, TypeHierarchyPrepareParams,
  CallHierarchyItem, CallHierarchyPrepareParams,
  CallHierarchyIncomingCall, CallHierarchyOutgoingCall,
} from 'vscode-languageserver-protocol';

export function registerHierarchyTools(
  mcp: McpServer, lsp: LspClient, fileManager: FileManager, symbolResolver: SymbolResolver,
): void {
  mcp.tool('supertypes', 'Get all supertypes (parent classes/traits) of a class or trait.',
    { symbolName: z.string().describe('Class or trait name') },
    async ({ symbolName }) => {
      const items = await prepareTypeHierarchy(lsp, fileManager, symbolResolver, symbolName);
      if (!items) return { content: [{ type: 'text' as const, text: `Could not resolve type hierarchy for '${symbolName}'` }] };
      const allSupers: string[] = [];
      for (const item of items) {
        const supers = await lsp.request<TypeHierarchyItem[]>('typeHierarchy/supertypes', { item });
        if (supers) for (const s of supers) allSupers.push(`  ${s.name} — ${uriToPath(s.uri)}:${s.range.start.line + 1}`);
      }
      if (allSupers.length === 0) return { content: [{ type: 'text' as const, text: `No supertypes found for '${symbolName}'` }] };
      return { content: [{ type: 'text' as const, text: `Supertypes of ${symbolName}:\n${allSupers.join('\n')}` }] };
    },
  );

  mcp.tool('subtypes', 'Get all subtypes (subclasses, implementations) of a class or trait.',
    { symbolName: z.string().describe('Class or trait name') },
    async ({ symbolName }) => {
      const items = await prepareTypeHierarchy(lsp, fileManager, symbolResolver, symbolName);
      if (!items) return { content: [{ type: 'text' as const, text: `Could not resolve type hierarchy for '${symbolName}'` }] };
      const allSubs: string[] = [];
      for (const item of items) {
        const subs = await lsp.request<TypeHierarchyItem[]>('typeHierarchy/subtypes', { item });
        if (subs) for (const s of subs) allSubs.push(`  ${s.name} — ${uriToPath(s.uri)}:${s.range.start.line + 1}`);
      }
      if (allSubs.length === 0) return { content: [{ type: 'text' as const, text: `No subtypes found for '${symbolName}'` }] };
      return { content: [{ type: 'text' as const, text: `Subtypes of ${symbolName}:\n${allSubs.join('\n')}` }] };
    },
  );

  mcp.tool('incoming_calls', 'Find all callers of a method or function.',
    { symbolName: z.string().describe('Method or function name') },
    async ({ symbolName }) => {
      const items = await prepareCallHierarchy(lsp, fileManager, symbolResolver, symbolName);
      if (!items) return { content: [{ type: 'text' as const, text: `Could not resolve call hierarchy for '${symbolName}'` }] };
      const allCallers: string[] = [];
      for (const item of items) {
        const incoming = await lsp.request<CallHierarchyIncomingCall[]>('callHierarchy/incomingCalls', { item });
        if (incoming) for (const call of incoming) allCallers.push(`  ${call.from.name} — ${uriToPath(call.from.uri)}:${call.from.range.start.line + 1}`);
      }
      if (allCallers.length === 0) return { content: [{ type: 'text' as const, text: `No callers found for '${symbolName}'` }] };
      return { content: [{ type: 'text' as const, text: `Callers of ${symbolName}:\n${allCallers.join('\n')}` }] };
    },
  );

  mcp.tool('outgoing_calls', 'Find all methods/functions called by a given method.',
    { symbolName: z.string().describe('Method or function name') },
    async ({ symbolName }) => {
      const items = await prepareCallHierarchy(lsp, fileManager, symbolResolver, symbolName);
      if (!items) return { content: [{ type: 'text' as const, text: `Could not resolve call hierarchy for '${symbolName}'` }] };
      const allCallees: string[] = [];
      for (const item of items) {
        const outgoing = await lsp.request<CallHierarchyOutgoingCall[]>('callHierarchy/outgoingCalls', { item });
        if (outgoing) for (const call of outgoing) allCallees.push(`  ${call.to.name} — ${uriToPath(call.to.uri)}:${call.to.range.start.line + 1}`);
      }
      if (allCallees.length === 0) return { content: [{ type: 'text' as const, text: `No outgoing calls found for '${symbolName}'` }] };
      return { content: [{ type: 'text' as const, text: `Calls from ${symbolName}:\n${allCallees.join('\n')}` }] };
    },
  );
}

async function prepareTypeHierarchy(lsp: LspClient, fileManager: FileManager, symbolResolver: SymbolResolver, symbolName: string): Promise<TypeHierarchyItem[] | null> {
  const symbols = await symbolResolver.resolve(symbolName);
  if (symbols.length === 0) return null;
  const sym = symbols[0];
  return lsp.request<TypeHierarchyItem[]>('textDocument/prepareTypeHierarchy', {
    textDocument: { uri: sym.location.uri }, position: sym.location.range.start,
  } as TypeHierarchyPrepareParams);
}

async function prepareCallHierarchy(lsp: LspClient, fileManager: FileManager, symbolResolver: SymbolResolver, symbolName: string): Promise<CallHierarchyItem[] | null> {
  const symbols = await symbolResolver.resolve(symbolName);
  if (symbols.length === 0) return null;
  const sym = symbols[0];
  return lsp.request<CallHierarchyItem[]>('textDocument/prepareCallHierarchy', {
    textDocument: { uri: sym.location.uri }, position: sym.location.range.start,
  } as CallHierarchyPrepareParams);
}
