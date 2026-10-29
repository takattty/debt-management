const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawnSync } = require('node:child_process');
const { requiredSections, validateDescription } = require('./pr-description.cjs');
const valid = requiredSections.map(name => `## ${name}\n具体的な変更と根拠。`).join('\n\n');

test('complete Japanese descriptions and populated evidence tables pass', () => {
  assert.deepEqual(validateDescription(valid), []);
  assert.deepEqual(validateDescription(valid.replace('## 検証\n具体的な変更と根拠。',
    '## 検証\n| 検証 | 結果 |\n| --- | --- |\n| 単体 | 6件成功 |')), []);
});

test('missing, repeated and empty sections cannot pass', () => {
  assert.equal(validateDescription(null).length, requiredSections.length);
  assert.ok(validateDescription(valid.replace('## なぜそれをやったか', '## Why')).length);
  assert.ok(validateDescription(`${valid}\n## 概要\n二重。`).length);
  assert.ok(validateDescription(valid.replace('## その他\n具体的な変更と根拠。', '## その他\nTODO')).length);
  assert.ok(validateDescription(valid.replace('## その他\n具体的な変更と根拠。', '## その他\n- [ ]')).length);
});

test('template instructions, unfilled tables and code-block headings do not count', () => {
  const template = fs.readFileSync(path.resolve(__dirname, '../pull_request_template.md'), 'utf8');
  assert.equal(validateDescription(template).length, requiredSections.length);
  assert.equal(validateDescription(`<!--\n${valid}\n-->`).length, requiredSections.length);
  assert.equal(validateDescription(`\x60\x60\x60md\n${valid}\n\x60\x60\x60`).length, requiredSections.length);
  const unfilled = valid.replace('## 検証\n具体的な変更と根拠。',
    '## 検証\n| 検証 | 結果 |\n| --- | --- |\n| 単体 | <!-- 結果を記載 --> |');
  assert.equal(validateDescription(unfilled).length, 1);
});

test('short or annotated fence-like lines cannot reveal headings inside a fenced block', () => {
  const tick = '\x60';
  for (const [open, middle, close] of [
    [tick.repeat(4) + 'md', tick.repeat(3), tick.repeat(4)],
    [tick.repeat(3) + 'md', tick.repeat(3) + 'js', tick.repeat(3)],
    ['~~~~md', '~~~', '~~~~'],
  ]) {
    assert.equal(validateDescription(`${open}\n${middle}\n${valid}\n${close}`).length, requiredSections.length);
  }
  assert.deepEqual(validateDescription(`${tick.repeat(4)}md\n${tick.repeat(5)}\n${valid}`), []);
});

test('tables without outer pipes still require filled values', () => {
  const replaceTable = table => valid.replace('## 検証\n具体的な変更と根拠。', `## 検証\n${table}`);
  assert.equal(validateDescription(replaceTable('検証 | 結果\n--- | ---\n単体 | ')).length, 1);
  assert.equal(validateDescription(replaceTable('検証 | 結果\n--- | ---\n単体 | TODO')).length, 1);
  assert.deepEqual(validateDescription(replaceTable('検証 | 結果\n--- | ---\n単体 | 6件成功')), []);
});

test('unclosed comments and raw HTML code blocks cannot supply visible headings', () => {
  assert.equal(validateDescription(`<!--\n${valid}`).length, requiredSections.length);
  for (const tag of ['pre', 'script', 'style', 'textarea']) {
    assert.equal(validateDescription(`<${tag}>\n${valid}\n</${tag}>`).length, requiredSections.length);
  }
  assert.deepEqual(validateDescription(valid.replace('## 概要', '## **概要**')), []);
  const literal = valid.replace('## 何をやったか\n具体的な変更と根拠。', '## 何をやったか\nHTMLの`<!--`を文字として扱います。');
  assert.deepEqual(validateDescription(literal), []);
});

test('CLI validates a file or PR event, and fails closed without exposing failed input', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pr-description-'));
  try {
    const filename = path.join(directory, 'input');
    const run = (...args) => spawnSync(process.execPath, [path.join(__dirname, 'pr-description.cjs'), ...args], { encoding: 'utf8' });
    fs.writeFileSync(filename, valid);
    assert.equal(run('--file', filename).status, 0);
    fs.writeFileSync(filename, JSON.stringify({ pull_request: { body: valid } }));
    assert.equal(run('--event', filename).status, 0);
    fs.writeFileSync(filename, JSON.stringify({ pull_request: { body: 'incomplete' } }));
    assert.equal(run('--event', filename).status, 1);
    fs.writeFileSync(filename, 'sensitive-input-invalid-json');
    const invalid = run('--event', filename);
    assert.equal(invalid.status, 2);
    assert.ok(!invalid.stderr.includes('sensitive-input'));
    fs.writeFileSync(filename, '{}');
    assert.equal(run('--event', filename).status, 2);
    assert.equal(run('--unknown', filename).status, 2);
  } finally {
    fs.rmSync(directory, { recursive: true });
  }
});
