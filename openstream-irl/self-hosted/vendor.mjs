import { copyFile, mkdir } from 'node:fs/promises';

// Resolve from the package, so this also works when invoked from another working directory.
const destination = new URL('../web/vendor/', import.meta.url);
await mkdir(destination, { recursive: true });
await copyFile(new URL('../node_modules/hls.js/dist/hls.min.js', import.meta.url), new URL('hls.min.js', destination));
await copyFile(new URL('../node_modules/hls.js/LICENSE', import.meta.url), new URL('hls.LICENSE.txt', destination));
