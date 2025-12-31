import { existsSync } from 'fs';
import { readdir, stat } from 'fs/promises';
import { extname, join, normalize, sep } from 'path';
import type { ScreenshotEntry } from '../shared/types';
import { getScreenshotsDir } from './status';

type BunFile = ReturnType<typeof Bun.file>;

interface ListOptions {
  category?: string | null;
  character?: string | null;
}

const IMAGE_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg', '.webp']);

function parseScreenshotFilename(filename: string): { title: string; capturedAt: number | null } {
  const base = filename.replace(/\.[^/.]+$/, ''); // strip extension
  const match = base.match(/^(?<title>.+?)\s+(?<timestamp>\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})$/);

  if (!match || !match.groups) {
    return { title: base, capturedAt: null };
  }

  const { title, timestamp } = match.groups;
  const [datePart, timePart] = timestamp.split('_');
  const iso = `${datePart}T${timePart.replace(/-/g, ':')}Z`;
  const parsed = Date.parse(iso);

  return {
    title: title.trim(),
    capturedAt: Number.isFinite(parsed) ? parsed : null,
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
        const { title, capturedAt } = parseScreenshotFilename(fileEntry.name);
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

function assertPathWithin(baseDir: string, targetPath: string): void {
  const normalized = normalize(targetPath).replace(/^[/\\]+/, '');
  const resolved = join(baseDir, normalized);
  const baseWithSep = baseDir.endsWith(sep) ? baseDir : `${baseDir}${sep}`;

  if (resolved !== baseDir && !resolved.startsWith(baseWithSep)) {
    throw new Error('Invalid screenshot path');
  }
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

  assertPathWithin(baseDir, relativePath);
  const normalized = normalize(relativePath).replace(/^[/\\]+/, '');
  const fullPath = join(baseDir, normalized);

  if (!existsSync(fullPath)) {
    return null;
  }

  const file = Bun.file(fullPath);
  if (!(await file.exists())) {
    return null;
  }

  const ext = extname(fullPath);
  return { file, contentType: guessContentType(ext) };
}

