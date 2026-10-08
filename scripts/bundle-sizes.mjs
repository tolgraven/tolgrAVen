#!/usr/bin/env node
import {readFileSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {gzipSync, brotliCompressSync, constants} from 'node:zlib';

const directory = process.argv[2];
const names = JSON.parse(readFileSync(join(directory, 'bundles.json'), 'utf8'));
const rows = names.map(name => {
  const bytes = readFileSync(join(directory, name));
  return {bundle: name, raw: bytes.length,
    gzip: gzipSync(bytes, {level: 9}).length,
    brotli: brotliCompressSync(bytes, {params: {[constants.BROTLI_PARAM_QUALITY]: 11}}).length};
});
const total = rows.reduce((sum, row) => ({bundle: 'TOTAL', raw: sum.raw + row.raw,
  gzip: sum.gzip + row.gzip, brotli: sum.brotli + row.brotli}),
  {bundle: 'TOTAL', raw: 0, gzip: 0, brotli: 0});
console.table([...rows, total]);
writeFileSync(join(directory, 'sizes.json'), JSON.stringify({bundles: rows, total}, null, 2) + '\n');
