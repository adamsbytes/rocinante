import { existsSync } from 'fs';
import { readdir, stat, lstat } from 'fs/promises';
import { extname, join, normalize, sep, resolve } from 'path';
import { DateTime } from 'luxon';
import type { ScreenshotEntry } from '../shared/types';
import { getScreenshotsDir } from './status';

type BunFile = ReturnType<typeof Bun.file>;

interface ListOptions {
  category?: string | null;
  character?: string | null;
  /** Bot's timezone (e.g., "America/New_York") - timestamps in filenames are in this zone */
  timezone?: string;
}

const IMAGE_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg', '.webp']);

/**
 * Format title from RuneLite's "Thing(Detail)" format to "Thing - Detail".
 * Examples:
 *   "Woodcutting(10)" -> "Woodcutting - 10"
 *   "Quest(Dragon Slayer)" -> "Quest - Dragon Slayer"
 *   "Pet(Heron)" -> "Pet - Heron"
 */
function formatTitle(rawTitle: string): string {
  // Match pattern: "Name(Detail)" - capture name and detail
  const match = rawTitle.match(/^(.+?)\((.+)\)$/);
  if (match) {
    return `${match[1].trim()} - ${match[2].trim()}`;
  }
  return rawTitle;
}

/**
 * Parse screenshot filename to extract title and capture timestamp.
 * The timestamp in the filename is in the bot's local timezone.
 */
function parseScreenshotFilename(filename: string, timezone: string): { title: string; capturedAt: number | null } {
  const base = filename.replace(/\.[^/.]+$/, ''); // strip extension
  const match = base.match(/^(?<title>.+?)\s+(?<timestamp>\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})$/);

  if (!match || !match.groups) {
    return { title: formatTitle(base), capturedAt: null };
  }

  const { title, timestamp } = match.groups;
  // Parse timestamp in the bot's timezone, not UTC
  // Filename format: 2024-12-31_14-30-00 -> 2024-12-31T14:30:00
  const [datePart, timePart] = timestamp.split('_');
  const localTimeStr = `${datePart}T${timePart.replace(/-/g, ':')}`;
  
  const dt = DateTime.fromISO(localTimeStr, { zone: timezone });
  
  return {
    title: formatTitle(title.trim()),
    capturedAt: dt.isValid ? dt.toMillis() : null,
  };
}

function toIso(ms: number): string {
  return new Date(ms).toISOString();
}

export async function listScreenshots(botId: string, options: ListOptions = {}): Promise<ScreenshotEntry[]> {
  const baseDir = getScreenshotsDir(botId);
  const results: ScreenshotEntry[] = [];

  if (!existsSync(baseDir)) {
    return results;
  }

  const filterCategory = options.category?.toLowerCase() ?? null;
  const filterCharacter = options.character?.toLowerCase() ?? null;
  // Default to UTC if no timezone provided (shouldn't happen with proper bot config)
  const timezone = options.timezone ?? 'UTC';

  const characterDirs = await readdir(baseDir, { withFileTypes: true });

  for (const characterEntry of characterDirs) {
    if (!characterEntry.isDirectory()) continue;
    const character = characterEntry.name;
    if (filterCharacter && character.toLowerCase() !== filterCharacter) continue;

    const characterPath = join(baseDir, character);
    const categoryDirs = await readdir(characterPath, { withFileTypes: true });

    for (const categoryEntry of categoryDirs) {
      if (!categoryEntry.isDirectory()) continue;
      const category = categoryEntry.name;
      if (filterCategory && category.toLowerCase() !== filterCategory) continue;

      const categoryPath = join(characterPath, category);
      const files = await readdir(categoryPath, { withFileTypes: true });

      for (const fileEntry of files) {
        if (!fileEntry.isFile()) continue;
        const ext = extname(fileEntry.name).toLowerCase();
        if (!IMAGE_EXTENSIONS.has(ext)) continue;

        const fullPath = join(categoryPath, fileEntry.name);
        const fileStat = await stat(fullPath);
        const { title, capturedAt } = parseScreenshotFilename(fileEntry.name, timezone);
        const capturedAtMs = capturedAt ?? fileStat.mtimeMs;

        results.push({
          botId,
          character,
          category,
          title,
          filename: fileEntry.name,
          path: join(character, category, fileEntry.name),
          capturedAt: capturedAtMs,
          capturedAtIso: toIso(capturedAtMs),
          sizeBytes: fileStat.size,
        });
      }
    }
  }

  // Newest first
  results.sort((a, b) => b.capturedAt - a.capturedAt);
  return results;
}

async function resolvePathWithoutSymlinks(baseDir: string, targetPath: string): Promise<string> {
  const normalized = normalize(targetPath).replace(/^[/\\]+/, '');
  const baseResolved = resolve(baseDir);
  const candidate = resolve(baseResolved, normalized);
  const baseWithSep = baseResolved.endsWith(sep) ? baseResolved : `${baseResolved}${sep}`;

  if (candidate !== baseResolved && !candidate.startsWith(baseWithSep)) {
    throw new Error('Invalid screenshot path');
  }

  // Walk each path segment and reject if any segment is a symlink
  const parts = normalized.split(sep).filter(Boolean);
  let current = baseResolved;
  for (const part of parts) {
    current = join(current, part);
    const info = await lstat(current);
    if (info.isSymbolicLink()) {
      throw new Error('Symlinks are not allowed in screenshot paths');
    }
  }

  return candidate;
}

function guessContentType(ext: string): string {
  switch (ext.toLowerCase()) {
    case '.jpg':
    case '.jpeg':
      return 'image/jpeg';
    case '.webp':
      return 'image/webp';
    default:
      return 'image/png';
  }
}

export async function getScreenshotFile(botId: string, relativePath: string): Promise<{ file: BunFile; contentType: string } | null> {
  const baseDir = getScreenshotsDir(botId);
  if (!existsSync(baseDir)) {
    return null;
  }

  // Validate file extension first (allowlist: only image formats)
  const ext = extname(relativePath).toLowerCase();
  if (!IMAGE_EXTENSIONS.has(ext)) {
    return null;
  }

  let fullPath: string;
  try {
    fullPath = await resolvePathWithoutSymlinks(baseDir, relativePath);
  } catch {
    return null;
  }

  let fileStat;
  try {
    fileStat = await lstat(fullPath);
  } catch {
    return null;
  }
  
  // Reject symlinks
  if (fileStat.isSymbolicLink()) {
    return null;
  }
  
  // Reject non-files
  if (!fileStat.isFile()) {
    return null;
  }
  
  // Reject hardlinks (nlink > 1 means file has multiple directory entries)
  // This prevents hardlink attacks where attacker links sensitive files into screenshots dir
  if (fileStat.nlink > 1) {
    return null;
  }

  const file = Bun.file(fullPath);
  if (!(await file.exists())) {
    return null;
  }

  return { file, contentType: guessContentType(ext) };
}

