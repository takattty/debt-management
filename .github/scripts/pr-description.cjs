const fs = require('node:fs');
const { lexer } = require('marked');

const requiredSections = [
  '概要', '何をやったか', 'なぜそれをやったか', '何をやらなかったか',
  'ユーザーへの影響', '検証', 'その他',
];

// Check structure only. Correctness of the explanation and evidence remains a review responsibility.
function validateDescription(body) {
  const sections = new Map();
  let current;
  for (const token of lexer(body ?? '', { gfm: true })) {
    if (token.type === 'heading' && token.depth <= 2) {
      current = token.depth === 2 ? visibleText(token.tokens).trim() : undefined;
      if (!current) continue;
      const entries = sections.get(current) ?? [];
      entries.push([]);
      sections.set(current, entries);
    } else if (current) {
      sections.get(current).at(-1).push(token);
    }
  }
  return requiredSections.flatMap(name => {
    const entries = sections.get(name) ?? [];
    if (entries.length !== 1) return [`「${name}」の見出しを1つ記載してください。`];
    const content = entries[0].flatMap(contentValues).map(value => value.trim())
      .filter(line => line && !/^\[[ xX]\]$/.test(line)
        && !/^(?:(?:TODO|TBD|未記入|未記載|ここに記載)(?:\s*[:：].*)?|\.\.\.|…)[。.!！]?$/i.test(line));
    return content.length ? [] : [`「${name}」の内容を記載してください。`];
  });
}

function visibleText(tokens = []) {
  return tokens.map(token => token.type === 'html' ? '' : token.tokens ? visibleText(token.tokens) : token.text ?? '').join('');
}

function contentValues(token) {
  if (['html', 'code', 'space', 'hr'].includes(token.type)) return [];
  if (token.type === 'table') return token.rows.flatMap(row => row.slice(1).map(cell => visibleText(cell.tokens)));
  if (token.type === 'list') return token.items.flatMap(item => item.tokens.flatMap(contentValues));
  if (token.type === 'blockquote') return token.tokens.flatMap(contentValues);
  return [token.tokens ? visibleText(token.tokens) : token.text ?? ''];
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
