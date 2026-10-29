const test = require('node:test');
const assert = require('node:assert/strict');
const { evaluate } = require('./review-gate.cjs');
const sha = 'a'.repeat(40);
const review = (id, outcome = 'pass', commit = sha, association = 'OWNER') => ({
  id, commit_id: commit, state: 'COMMENTED', author_association: association,
  body: `scope and evidence\n<!-- independent-review: ${JSON.stringify({ schema: 1, head_sha: commit, outcome, blocking_findings: outcome === 'pass' ? 0 : 1 })} -->`,
});
test('missing, stale, untrusted and blocked evidence cannot pass', () => {
  assert.equal(evaluate([], sha), false);
  assert.equal(evaluate([review(1, 'pass', 'b'.repeat(40))], sha), false);
  assert.equal(evaluate([review(1, 'pass', sha, 'NONE')], sha), false);
  assert.equal(evaluate([review(1, 'blocked')], sha), false);
});
test('latest exact-commit attestation and explicit findings govern acceptance', () => {
  assert.equal(evaluate([review(1)], sha), true);
  assert.equal(evaluate([review(1), review(2, 'blocked')], sha), false);
  assert.equal(evaluate([review(1, 'blocked'), review(2)], sha), true);
  assert.equal(evaluate([review(2), { ...review(3), state: 'CHANGES_REQUESTED' }], sha), false);
  assert.equal(evaluate([{ ...review(1), body: '<!-- independent-review: {invalid} -->' }], sha), false);
});
