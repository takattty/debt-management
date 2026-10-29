import { readFile, writeFile } from 'node:fs/promises';
import process from 'node:process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import openapiTS, { astToString } from 'openapi-typescript';

const source = new URL('../../contracts/openapi.yaml', import.meta.url);
const outputIndex = process.argv.indexOf('--output');
if (outputIndex !== -1 && !process.argv[outputIndex + 1]) throw new Error('--output requires a file path');
const target = outputIndex === -1 ? new URL('../src/generated/schema.ts', import.meta.url)
  : pathToFileURL(resolve(process.argv[outputIndex + 1]));
const generated = `/**\n * Generated from contracts/openapi.yaml. Do not edit directly.\n * Run npm run generate:api from frontend/.\n */\n\n${astToString(await openapiTS(source))}`;

if (process.argv.includes('--check')) {
  const current = await readFile(target, 'utf8').catch(() => '');
  if (current !== generated) {
    console.error('OpenAPI types are stale. Run npm run generate:api and commit src/generated/schema.ts.');
    process.exitCode = 1;
  } else {
    console.log('OpenAPI generated types are current.');
  }
} else {
  await writeFile(target, generated);
  console.log('Generated src/generated/schema.ts from contracts/openapi.yaml.');
}
