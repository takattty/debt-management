const { evaluate } = require('./review-gate.cjs');
const { validateDescription } = require('./pr-description.cjs');

// Commit statuses belong to a SHA, so all main-targeting PRs sharing it must pass together.
async function sweep(github, repo, defaultBranch, policySha) {
  if (!defaultBranch || !policySha) throw Error('Trusted default branch and policy SHA are required.');
  const path = '.github/workflows/review-event-relay.yml';
  const { data: trusted } = await github.rest.repos.getContent({ ...repo, path, ref: policySha });
  const listMain = async () => (await github.paginate(github.rest.pulls.list,
    { ...repo, state: 'open', per_page: 100 })).filter(pr => pr.base.ref === defaultBranch);
  const groups = new Map();
  for (const entry of await listMain()) {
    const { data: pr } = await github.rest.pulls.get({ ...repo, pull_number: entry.number });
    if (pr.state !== 'open' || pr.base.ref !== defaultBranch) continue;
    const group = groups.get(pr.head.sha) ?? [];
    group.push(pr);
    groups.set(pr.head.sha, group);
  }
  for (const [sha, group] of groups) {
    await github.rest.repos.createCommitStatus({ ...repo, sha, context: 'independent-review',
      state: 'pending', description: 'Rechecking all main PRs for this head.' });
    let passed = true;
    for (const pr of group) {
      let sameRelay = false;
      if (pr.head.repo) {
        try {
          const { data: candidate } = await github.rest.repos.getContent({ owner: pr.head.repo.owner.login,
            repo: pr.head.repo.name, path, ref: sha });
          sameRelay = candidate.type === 'file' && candidate.sha === trusted.sha;
        } catch (error) { if (error.status !== 404) throw error; }
      }
      const reviews = await github.paginate(github.rest.pulls.listReviews, { ...repo, pull_number: pr.number });
      if (!sameRelay || validateDescription(pr.body).length || !evaluate(reviews, sha)) passed = false;
    }
    const latest = (await listMain()).filter(pr => pr.head.sha === sha);
    // A new/closed/retargeted PR, body edit or head change requires the queued event to retry.
    if (latest.length !== group.length || group.some(pr =>
      !latest.some(current => current.number === pr.number && current.body === pr.body))) continue;
    await github.rest.repos.createCommitStatus({ ...repo, sha, context: 'independent-review',
      state: passed ? 'success' : 'failure',
      description: passed ? 'All main PRs: exact-head review and description passed.' : 'Missing/blocked review, incomplete PR body or modified relay.',
      target_url: `https://github.com/${repo.owner}/${repo.repo}/pull/${group[0].number}` });
  }
}

module.exports = { sweep };
