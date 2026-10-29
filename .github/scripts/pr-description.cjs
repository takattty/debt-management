const fs = require('node:fs');

const requiredSections = [
  '概要', '何をやったか', 'なぜそれをやったか', '何をやらなかったか',
  'ユーザーへの影響', '検証', 'その他',
];

// Check structure only. Correctness of the explanation and evidence remains a review responsibility.
function validateDescription(body) {
  const text = (body ?? '').replace(/<!--[\s\S]*?-->/g, '');
  const sections = new Map();
  let current;
  let fence;
  for (const line of text.split(/\r?\n/)) {
    const delimiter = line.match(/^\s{0,3}(`{3,}|~{3,})/);
    if (delimiter) {
      if (!fence) fence = delimiter[1][0];
      else if (fence === delimiter[1][0]) fence = undefined;
      continue;
    }
    if (fence) continue;
    const heading = line.match(/^##\s+(.+?)\s*#*\s*$/);
    if (heading) {
      current = heading[1];
      const entries = sections.get(current) ?? [];
      entries.push([]);
      sections.set(current, entries);
    } else if (current) {
      sections.get(current).at(-1).push(line);
    }
  }
  return requiredSections.flatMap(name => {
    const entries = sections.get(name) ?? [];
    if (entries.length !== 1) return [`「${name}」の見出しを1つ記載してください。`];
    const lines = entries[0];
    const content = lines.flatMap((line, index) => {
      if (/^\s*\|/.test(line)) {
        if (/^\s*\|?[\s:|-]+\|\s*$/.test(line) || /^\s*\|?[\s:|-]+\|\s*$/.test(lines[index + 1] ?? '')) return [];
        // Row labels alone are not completed table values.
        return line.trim().replace(/^\||\|$/g, '').split('|').slice(1);
      }
      return [line];
    }).map(line => line.replace(/^\s*[-*+]\s*(?:\[[ xX]\]\s*)?/, '').replace(/<[^>]*>/g, '').replace(/[`*_#]/g, '').trim())
      .filter(line => line && !/^(?:(?:TODO|TBD|未記入|未記載|ここに記載)(?:\s*[:：].*)?|\.\.\.|…)[。.!！]?$/i.test(line));
    return content.length ? [] : [`「${name}」の内容を記載してください。`];
  });
}

if (require.main === module) {
  const [mode, filename, ...extra] = process.argv.slice(2);
  if (!['--file', '--event'].includes(mode) || !filename || extra.length) {
    console.error('Usage: node .github/scripts/pr-description.cjs --file BODY.md | --event EVENT.json');
    process.exitCode = 2;
  } else {
    try {
      const input = fs.readFileSync(filename, 'utf8');
      const event = mode === '--event' ? JSON.parse(input) : undefined;
      if (event && !event.pull_request) throw Error('Expected a pull_request event.');
      const errors = validateDescription(event ? event.pull_request.body : input);
      errors.forEach(error => console.error(error));
      if (errors.length) process.exitCode = 1;
      else console.log('PR description structure passed.');
    } catch {
      console.error('Could not read a PR description or pull_request event.');
      process.exitCode = 2;
    }
  }
}

module.exports = { requiredSections, validateDescription };
