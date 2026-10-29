const test = require('node:test');
const assert = require('node:assert/strict');
const { sweep } = require('./review-sweep.cjs');
const { requiredSections } = require('./pr-description.cjs');
const sha = 'a'.repeat(40);
const body = requiredSections.map(section => `## ${section}\n説明。`).join('\n');
const pull = (number, overrides = {}) => ({ number, state: 'open', body,
  base: { ref: 'main' }, head: { sha, repo: { owner: { login: 'owner' }, name: 'repo' } }, ...overrides });
const review = { id: 1, commit_id: sha, state: 'COMMENTED', author_association: 'OWNER',
  body: `<!-- independent-review: {"schema":1,"head_sha":"${sha}","outcome":"pass","blocking_findings":0} -->` };

async function run(open, options = {}) {
  const statuses = [];
  let lists = 0;
  const github = { rest: { pulls: {
    list: () => {}, listReviews: () => {},
    get: async ({ pull_number }) => ({ data: open.find(pr => pr.number === pull_number) }),
  }, repos: {
    getContent: async ({ ref }) => ({ data: { type: 'file', sha: ref === 'trusted' || !options.modifiedRelay ? 'relay' : 'changed' } }),
    createCommitStatus: async data => { statuses.push(data); },
  } } };
  github.paginate = async (fn, args) => {
    if (fn === github.rest.pulls.list) return (++lists === 1 || !options.latest) ? open : options.latest;
    if (fn === github.rest.pulls.listReviews) return options.reviews?.[args.pull_number] ?? [review];
    throw Error('Unexpected API.');
  };
  await sweep(github, { owner: 'owner', repo: 'repo' }, 'main', 'trusted');
  return statuses.map(status => status.state);
}

test('current review, complete body and unchanged trusted relay are all required', async () => {
  assert.deepEqual(await run([pull(1)]), ['pending', 'success']);
  assert.deepEqual(await run([pull(1, { body: 'incomplete' })]), ['pending', 'failure']);
  assert.deepEqual(await run([pull(1)], { reviews: { 1: [] } }), ['pending', 'failure']);
  assert.deepEqual(await run([pull(1)], { modifiedRelay: true }), ['pending', 'failure']);
});

test('same-SHA main PRs fail together and another base cannot overwrite their status', async () => {
  assert.deepEqual(await run([pull(1, { body: 'incomplete' }), pull(2)]), ['pending', 'failure']);
  assert.deepEqual(await run([pull(1), pull(2)], { reviews: { 2: [] } }), ['pending', 'failure']);
  assert.deepEqual(await run([pull(1), pull(2)]), ['pending', 'success']);
  assert.deepEqual(await run([pull(1, { body: 'incomplete' }), pull(2, { base: { ref: 'other' } })]), ['pending', 'failure']);
  assert.deepEqual(await run([pull(1, { base: { ref: 'other' } })]), []);
});

test('body, head or group membership changes leave pending for the next event', async () => {
  assert.deepEqual(await run([pull(1)], { latest: [pull(1, { body: 'edited' })] }), ['pending']);
  assert.deepEqual(await run([pull(1)], { latest: [pull(1, { head: { sha: 'b'.repeat(40) } })] }), ['pending']);
  assert.deepEqual(await run([pull(1)], { latest: [] }), ['pending']);
  assert.deepEqual(await run([pull(1)], { latest: [pull(1), pull(2, { body: 'incomplete' })] }), ['pending']);
});
