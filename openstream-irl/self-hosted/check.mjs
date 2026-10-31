import { readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';

for (const directory of ['self-hosted', 'web']) {
  for (const entry of await readdir(new URL(`../${directory}/`, import.meta.url), { withFileTypes: true })) {
    if (!entry.isFile() || !/\.(mjs|js)$/.test(entry.name)) continue;
    const result = spawnSync(process.execPath, ['--check', new URL(`../${directory}/${entry.name}`, import.meta.url).pathname], { stdio: 'inherit' });
    if (result.status !== 0) process.exit(result.status ?? 1);
  }
}
