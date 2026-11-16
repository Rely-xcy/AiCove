#!/usr/bin/env python3
"""After five successful Beta revisions, capture, validate and publish a new profile."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.parse
import urllib.request
import zipfile

PROFILE = Path('app/src/main/baseline-prof.txt')
STATE = Path('.github/baseline-cycle.json')
COMMIT_PREFIX = 'perf(profile): refresh baseline for beta '
INTERVAL = 5
SHA = re.compile(r'^[0-9a-f]{40}$')
TYPE = r'(?:\[*(?:[ZBCSIJFD]|L[^\s;]+;))'
RULE = re.compile(r'^[HSP]*L[^\s;]+;(?:->[^\s()]+\((?:' + TYPE + r')*\)(?:V|' + TYPE + r'))?$')


def git(*args):
    return subprocess.check_output(['git', *args], text=True).strip()


def api(path, body=None):
    url = 'https://api.github.com/repos/' + os.environ['GITHUB_REPOSITORY'] + path
    request = urllib.request.Request(
        url, data=None if body is None else json.dumps(body).encode(),
        headers={'Authorization': 'Bearer ' + os.environ['GITHUB_TOKEN'],
                 'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'},
    )
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read()
        return json.loads(data) if data else None


def output(name, value):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as file:
        file.write(f'{name}={value}\n')


def load_state():
    if not STATE.exists():
        return {}
    state = json.loads(STATE.read_text())
    if state.get('schema') != 1:
        raise ValueError('Unsupported baseline cycle state')
    if state.get('branch') != os.environ['GITHUB_REF_NAME']:
        return {}
    return state


def successful_revisions(runs, branch, current, state, initial_since):
    """Count source commits, not reruns, failed runs or profile-only rebuilds."""
    boundary = state.get('beta_run_number', 0)
    excluded = set(state.get('counted_shas', [])) | set(state.get('profile_commits', []))
    excluded.update((state.get('source_sha'), state.get('published_sha')))
    revisions = set()
    for run in runs:
        if (run['head_branch'] != branch or run['status'] != 'completed'
                or run['conclusion'] != 'success' or run['run_number'] <= boundary):
            continue
        if not boundary and datetime.fromisoformat(run['created_at'].replace('Z', '+00:00')) <= datetime.fromisoformat(initial_since.replace('Z', '+00:00')):
            continue
        if run['run_number'] > current['run_number']:
            continue
        if run['head_sha'] not in excluded:
            revisions.add(run['head_sha'])
    # The gate runs after the current APK job succeeds, before the workflow completes.
    if current['run_number'] > boundary and current['head_sha'] not in excluded:
        revisions.add(current['head_sha'])
    return revisions


def beta_runs(branch, since):
    page = 1
    while True:
        query = urllib.parse.urlencode({'branch': branch, 'created': '>=' + since,
                                        'per_page': 100, 'page': page})
        rows = api('/actions/workflows/beta.yml/runs?' + query)['workflow_runs']
        yield from rows
        if len(rows) < 100:
            return
        page += 1


def branch_head(branch):
    return api('/git/ref/heads/' + urllib.parse.quote(branch, safe=''))['object']['sha']


def gate():
    branch = os.environ['GITHUB_REF_NAME']
    current = api('/actions/runs/' + os.environ['GITHUB_RUN_ID'])
    source = os.environ['GITHUB_SHA']
    if current['head_sha'] != source or current['head_branch'] != branch:
        raise ValueError('Beta source does not match the workflow ref')
    if git('log', '-1', '--format=%s').startswith(COMMIT_PREFIX):
        print('Profile rebuild: excluded from the five-version counter')
        return
    if branch_head(branch) != source:
        print('Branch advanced; next Beta will evaluate the counter')
        return
    state = load_state()
    if state:
        state['published_sha'] = git('log', '-1', '--format=%H', '--', str(STATE))
    since = state.get('beta_created_at') or git('log', '-1', '--format=%cI', '--', str(PROFILE))
    if not since:
        raise ValueError('Cannot determine the installed profile boundary')
    revisions = successful_revisions(beta_runs(branch, since), branch, current, state, since)
    print(f'{len(revisions)} successful distinct Beta revisions since the last profile; target {INTERVAL}')
    if len(revisions) < INTERVAL:
        return
    active = api('/actions/workflows/baseline-profile.yml/runs?' +
                 urllib.parse.urlencode({'branch': branch, 'per_page': 100}))['workflow_runs']
    if any(r['status'] != 'completed' and r['head_sha'] == source for r in active):
        print('A capture is already active for this source revision')
        return
    api('/actions/workflows/baseline-profile.yml/dispatches', {
        'ref': branch, 'inputs': {'source_sha': source, 'beta_run_id': str(current['id'])}
    })
    print(f'Dispatched baseline capture for {source}')


def merge_profiles(root):
    profiles = sorted(Path(root).rglob('*-prof.txt'))
    if not profiles:
        raise ValueError('No captured profiles')
    names = [p.name for p in profiles]
    for scenario in ('coldStartup', 'chatList'):
        if not any(scenario in name for name in names):
            raise ValueError('Missing capture scenario: ' + scenario)
    rules = set()
    for profile in profiles:
        lines = [s.strip() for s in profile.read_text().splitlines() if s.strip()]
        if not lines or any(not RULE.fullmatch(line) for line in lines):
            raise ValueError('Invalid or truncated profile: ' + str(profile))
        if not any('Lcom/aicove/' in line for line in lines):
            raise ValueError('Profile lacks unobfuscated app symbols: ' + str(profile))
        rules.update(lines)
    return '\n'.join(sorted(rules)) + '\n'


def validate_source(run, branch, source):
    if not SHA.fullmatch(source):
        raise ValueError('Full source SHA required')
    if (run['head_sha'] != source or run['head_branch'] != branch
            or run['status'] != 'completed' or run['conclusion'] != 'success'
            or run['path'] != '.github/workflows/beta.yml'):
        raise ValueError('Capture must originate from a successful Beta on this branch and SHA')


def prepare(root):
    branch = os.environ['GITHUB_REF_NAME']
    source = os.environ['SOURCE_SHA']
    run = api('/actions/runs/' + os.environ['BETA_RUN_ID'])
    validate_source(run, branch, source)
    head = branch_head(branch)
    if head != source:
        # A rerun may encounter the profile commit already published by this same cycle.
        git('fetch', 'origin', 'refs/heads/' + branch)
        remote_state = json.loads(git('show', 'FETCH_HEAD:' + str(STATE))) if git(
            'ls-tree', 'FETCH_HEAD', '--', str(STATE)) else {}
        if (remote_state.get('branch') == branch
                and remote_state.get('beta_run_id') == run['id']
                and git('log', '-1', '--format=%s', 'FETCH_HEAD') == COMMIT_PREFIX + source[:8]
                and git('rev-parse', 'FETCH_HEAD^') == source
                and git('rev-parse', 'FETCH_HEAD') == head):
            git('checkout', '--detach', 'FETCH_HEAD')
            output('ready', 'true')
            output('sha', head)
            return
        print('Branch advanced during capture; discard this stale result without changing the branch')
        output('ready', 'false')
        return
    if git('rev-parse', 'HEAD') != source:
        raise ValueError('Checkout is not the captured source SHA')
    previous = load_state()
    if previous.get('beta_run_number', 0) >= run['run_number']:
        print('Cycle already accounted for')
        output('ready', 'false')
        return
    since = previous.get('beta_created_at') or git('log', '-1', '--format=%cI', '--', str(PROFILE))
    profile_commits = set(previous.get('profile_commits', []))
    if previous:
        previous['published_sha'] = git('log', '-1', '--format=%H', '--', str(STATE))
        profile_commits.add(previous['published_sha'])
    counted = set(previous.get('counted_shas', [])) | successful_revisions(
        beta_runs(branch, since), branch, run, previous, since)
    merged = merge_profiles(root)  # Validate everything before touching the installed file.
    PROFILE.write_text(merged)
    STATE.write_text(json.dumps({'schema': 1, 'branch': branch, 'source_sha': source,
                                 'counted_shas': sorted(counted), 'profile_commits': sorted(profile_commits),
                                 'beta_run_id': run['id'], 'beta_run_number': run['run_number'],
                                 'beta_created_at': run['created_at'],
                                 'capture_run_id': os.environ['GITHUB_RUN_ID']}, indent=2) + '\n')
    git('config', 'user.name', 'github-actions[bot]')
    git('config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
    git('add', '--', str(PROFILE), str(STATE))
    git('commit', '-m', COMMIT_PREFIX + source[:8], '--', str(PROFILE), str(STATE))
    output('ready', 'true')
    output('sha', git('rev-parse', 'HEAD'))
    print('Validated profile rules:', len(merged.splitlines()))


def publish(apk):
    branch = os.environ['GITHUB_REF_NAME']
    source = os.environ['SOURCE_SHA']
    sha = git('rev-parse', 'HEAD')
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None or 'AndroidManifest.xml' not in archive.namelist():
            raise ValueError('Invalid APK')
        compiled = archive.read('assets/dexopt/baseline.prof')
        if (len(compiled) <= 16 or compiled[:4] != b'pro\x00'
                or not re.fullmatch(rb'\d{3}\x00', compiled[4:8])):
            raise ValueError('APK is missing a complete compiled baseline profile')
    remote = branch_head(branch)
    if remote not in (source, sha):
        print('Branch advanced during rebuild; do not publish a stale profile or APK')
        output('published', 'false')
        return
    if remote == source:
        # Regular fast-forward push; a concurrent branch update causes rejection, never a force push.
        git('push', 'origin', 'HEAD:refs/heads/' + branch)
    if branch_head(branch) != sha:
        raise ValueError('Remote head did not match the verified rebuild')
    Path('dist').mkdir(exist_ok=True)
    shutil.copyfile(apk, f'dist/aicode-universal-beta-{sha[:8]}.apk')
    mapping = Path('app/build/outputs/mapping/universalBeta/mapping.txt')
    if mapping.is_file():
        shutil.copyfile(mapping, f'dist/aicode-universal-beta-{sha[:8]}-mapping.txt')
    output('published', 'true')
    print('Published profile and rebuilt Beta:', sha)


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('gate')
    commands.add_parser('prepare').add_argument('--profiles', required=True)
    commands.add_parser('publish').add_argument('--apk', required=True)
    args = parser.parse_args()
    if args.command == 'gate': gate()
    elif args.command == 'prepare': prepare(args.profiles)
    else: publish(args.apk)


if __name__ == '__main__':
    main()
