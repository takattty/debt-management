const marker = /<!-- independent-review: ([^\n]*) -->/;

function evaluate(reviews, sha) {
  const trusted = new Set(['OWNER', 'MEMBER', 'COLLABORATOR']);
  const current = reviews.filter(r => r.commit_id === sha && trusted.has(r.author_association));
  if (current.some(r => r.state === 'CHANGES_REQUESTED')) return false;
  const reports = current.flatMap(review => {
    const match = (review.body ?? '').match(marker);
    if (!match || !['COMMENTED', 'APPROVED'].includes(review.state)) return [];
    try { return [{ id: review.id, report: JSON.parse(match[1]) }]; } catch { return [{ id: review.id, report: null }]; }
  }).sort((a, b) => a.id - b.id);
  const report = reports.at(-1)?.report;
  return report?.schema === 1 && report.head_sha === sha && report.outcome === 'pass' && report.blocking_findings === 0;
}
module.exports = { evaluate };
