import { copyFile, mkdir, readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

// The browser loads this UMD asset directly; keep it tied to the npm lockfile.
const require = createRequire(import.meta.url);
const packagePath = require.resolve('@supabase/supabase-js/package.json');
const installed = JSON.parse(await readFile(packagePath, 'utf8'));
const lock = JSON.parse(await readFile(new URL('../../package-lock.json', import.meta.url), 'utf8'));
const locked = lock.packages['node_modules/@supabase/supabase-js'].version;
if (installed.version !== locked) {
  throw new Error(`Supabase ${installed.version} differs from locked ${locked}; run npm ci`);
}
const destination = new URL('../../resources/public/vendor/supabase.js', import.meta.url);
await mkdir(new URL('.', destination), { recursive: true });
await copyFile(new URL('./dist/umd/supabase.js', pathToFileURL(packagePath)), destination);
console.log(`Synced Supabase browser SDK ${locked}`);
