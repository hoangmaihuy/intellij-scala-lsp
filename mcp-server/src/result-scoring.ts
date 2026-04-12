import { Location } from 'vscode-languageserver-protocol';
import { uriToPath } from './utils.js';
import { MatchQuality } from './symbol-resolver.js';

// Scoring constants
export const MAX_DETAILED_RESULTS = 10;
export const SCORE_PROJECT = 100;
export const SCORE_EXACT = 50;
export const SCORE_COMPANION = 40;
export const SCORE_SUFFIX = 10;

export interface ScoredLocation {
  location: Location;
  score: number;
  label?: string;
}

/**
 * Score a location based on relevance factors.
 * Higher score = more relevant.
 */
export function scoreLocation(
  location: Location,
  projectPath: string,
  matchQuality?: MatchQuality,
): number {
  let score = 0;
  const filePath = uriToPath(location.uri);

  // Project files rank higher than dependencies
  if (filePath.startsWith(projectPath)) {
    score += SCORE_PROJECT;
  }

  // Match quality scoring
  if (matchQuality === 'exact') {
    score += SCORE_EXACT;
  } else if (matchQuality === 'companion') {
    score += SCORE_COMPANION;
  } else if (matchQuality === 'suffix') {
    score += SCORE_SUFFIX;
  }

  return score;
}

/**
 * Sort items by score descending.
 */
export function sortByScore<T>(
  items: T[],
  scoreFn: (item: T) => number,
): T[] {
  return [...items].sort((a, b) => scoreFn(b) - scoreFn(a));
}

/**
 * Format a location as a compact summary (file:line).
 * Shortens jar paths for readability.
 */
export function formatLocationSummary(location: Location): string {
  const filePath = uriToPath(location.uri);
  const line = location.range.start.line + 1;

  // Shorten jar paths: ~/.cache/coursier/.../lib.jar!/pkg/Class.scala
  const jarMatch = filePath.match(/\.jar!\/(.+)$/);
  if (jarMatch) {
    const jarIndex = filePath.lastIndexOf('.jar!/');
    const jarName = filePath.substring(
      filePath.lastIndexOf('/', jarIndex - 1) + 1,
      jarIndex + 4,
    );
    return `.../${jarName}!/${jarMatch[1]}:${line}`;
  }

  return `${filePath}:${line}`;
}

/**
 * Format multiple locations as a compact list.
 */
export function formatLocationSummaries(locations: Location[]): string {
  return locations.map(formatLocationSummary).join('\n');
}
