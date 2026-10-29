const marker = /<!-- independent-review: ([^\n]*) -->/;

function evaluate(reviews, sha) {
  const trusted = new Set(['OWNER', 'MEMBER', 'COLLABORATOR']);
  const current = reviews.filter(r => r.commit_id === sha && trusted.has(r.author_association));
  if (current.some(r => r.state === 'CHANGES_REQUESTED')) return false;
  const latest = current.toSorted((a, b) => a.id - b.id).at(-1);
  if (!latest || !['COMMENTED', 'APPROVED'].includes(latest.state)) return false;
  const body = latest.body ?? '';
  if ((body.match(/<!-- independent-review:/g) ?? []).length !== 1) return false;
  const match = body.match(marker);
  if (!match) return false;
  try {
    const report = JSON.parse(match[1]);
    return report?.schema === 1 && report.head_sha === sha && report.outcome === 'pass' && report.blocking_findings === 0;
  } catch {
    return false;
  }
}
module.exports = { evaluate };
