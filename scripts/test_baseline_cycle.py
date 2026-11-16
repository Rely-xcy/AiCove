import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('cycle', Path(__file__).with_name('baseline_cycle.py'))
cycle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cycle)
BRANCH = 'feat/upstream-plugin-refresh'
SOURCE = '5' * 40


def run(number, sha=None, **changes):
    result = dict(id=number, run_number=number, head_sha=sha or f'{number:040x}',
                  head_branch=BRANCH, status='completed', conclusion='success',
                  created_at='2026-11-16T05:00:00Z', path='.github/workflows/beta.yml')
    result.update(changes)
    return result


class CounterTest(unittest.TestCase):
    def count(self, runs, current, state=None, since='2026-11-15T00:00:00Z'):
        return cycle.successful_revisions(runs, BRANCH, current, state or {}, since)

    def test_fifth_successful_revision_triggers(self):
        self.assertEqual(5, len(self.count([run(n) for n in range(1, 5)], run(5, status='in_progress', conclusion=None))))
        self.assertEqual(4, len(self.count([run(n) for n in range(1, 4)], run(4))))

    def test_rerun_of_same_sha_is_not_another_version(self):
        self.assertEqual(1, len(self.count([run(1, SOURCE), run(2, SOURCE)], run(3, SOURCE))))

    def test_failed_cancelled_and_other_branch_do_not_count(self):
        rows = [run(1, conclusion='failure'), run(2, conclusion='cancelled'),
                run(3, head_branch='other'), run(4, status='in_progress', conclusion=None)]
        self.assertEqual({run(5)['head_sha']}, self.count(rows, run(5)))

    def test_old_sources_and_profile_commits_never_count_again(self):
        state = dict(beta_run_number=5, source_sha=run(5)['head_sha'],
                     published_sha=run(6)['head_sha'], counted_shas=[run(2)['head_sha']],
                     profile_commits=[run(1)['head_sha']])
        rows = [run(3), run(7, run(6)['head_sha']), run(8, run(5)['head_sha']),
                run(9, run(2)['head_sha']), run(10, run(1)['head_sha']), run(15)]
        self.assertEqual({run(11)['head_sha']}, self.count(rows, run(11), state))

    def test_initial_time_boundary_compares_instants_not_strings(self):
        self.assertEqual(2, len(self.count([run(1)], run(2), since='2026-11-16T12:00:00+08:00')))


class ProfileTest(unittest.TestCase):
    def test_requires_both_scenarios_and_unobfuscated_symbols(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)
            (p/'coldStartup-startup-prof.txt').write_text('Lcom/aicove/MainActivity;\n')
            with self.assertRaisesRegex(ValueError, 'chatList'): cycle.merge_profiles(p)
            (p/'chatList-baseline-prof.txt').write_text('La/b;\n')
            with self.assertRaisesRegex(ValueError, 'unobfuscated'): cycle.merge_profiles(p)

    def test_merges_deduplicates_and_rejects_bad_method(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)
            (p/'coldStartup-startup-prof.txt').write_text('Lcom/aicove/MainActivity;\n')
            chat = p/'chatList-baseline-prof.txt'
            chat.write_text('Lcom/aicove/MainActivity;\nHSLcom/aicove/Chat;->show([Ljava/lang/String;I)V\n')
            self.assertEqual(2, len(cycle.merge_profiles(p).splitlines()))
            chat.write_text('HLcom/aicove/Chat;->broken\n')
            with self.assertRaisesRegex(ValueError, 'Invalid'): cycle.merge_profiles(p)

    def test_validates_real_profile_rules(self):
        profile = Path(__file__).resolve().parents[1]/cycle.PROFILE
        invalid = [line for line in profile.read_text().splitlines() if line and not cycle.RULE.fullmatch(line)]
        self.assertEqual([], invalid[:5])

    def test_rejects_wrong_source_branch_workflow_or_failed_beta(self):
        good = run(5, SOURCE)
        cycle.validate_source(good, BRANCH, SOURCE)
        for key, value in [('head_sha', 'a'*40), ('head_branch', 'other'), ('conclusion', 'failure'),
                           ('status', 'in_progress'), ('path', '.github/workflows/ci.yml')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                cycle.validate_source(dict(good, **{key: value}), BRANCH, SOURCE)


class GateTest(unittest.TestCase):
    def test_dispatches_once_when_fifth_build_finishes(self):
        current = run(5, SOURCE, status='in_progress', conclusion=None)
        def api(path, body=None):
            if path == '/actions/runs/5': return current
            if body is not None: return None
            return {'workflow_runs': []}
        with patch.dict(os.environ, GITHUB_REF_NAME=BRANCH, GITHUB_SHA=SOURCE, GITHUB_RUN_ID='5'), \
             patch.object(cycle, 'load_state', return_value={}), \
             patch.object(cycle, 'git', side_effect=['fix: example', '2026-11-15T00:00:00Z']), \
             patch.object(cycle, 'branch_head', return_value=SOURCE), \
             patch.object(cycle, 'beta_runs', return_value=[run(n) for n in range(1, 5)]), \
             patch.object(cycle, 'api', side_effect=api) as api_mock:
            cycle.gate()
            self.assertEqual({'ref': BRANCH, 'inputs': {'source_sha': SOURCE, 'beta_run_id': '5'}}, api_mock.call_args.args[1])

    def test_existing_capture_blocks_duplicate_but_not_new_revision(self):
        for active_sha, dispatched in [(SOURCE, False), ('a'*40, True)]:
            current = run(5, SOURCE, status='in_progress', conclusion=None)
            def api(path, body=None):
                if path == '/actions/runs/5': return current
                if body is not None: return None
                return {'workflow_runs': [dict(status='in_progress', head_sha=active_sha)]}
            with self.subTest(active_sha=active_sha), \
                 patch.dict(os.environ, GITHUB_REF_NAME=BRANCH, GITHUB_SHA=SOURCE, GITHUB_RUN_ID='5'), \
                 patch.object(cycle, 'load_state', return_value={}), \
                 patch.object(cycle, 'git', side_effect=['fix: example', '2026-11-15T00:00:00Z']), \
                 patch.object(cycle, 'branch_head', return_value=SOURCE), \
                 patch.object(cycle, 'beta_runs', return_value=[run(n) for n in range(1, 5)]), \
                 patch.object(cycle, 'api', side_effect=api) as api_mock:
                cycle.gate()
                self.assertEqual(dispatched, any(len(c.args) == 2 for c in api_mock.call_args_list))


class PrepareIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous_dir = Path.cwd()
        os.chdir(self.temp.name)
        subprocess.run(['git', 'init', '-q'], check=True)
        cycle.git('config', 'user.name', 'test')
        cycle.git('config', 'user.email', 'test@example.invalid')
        cycle.PROFILE.parent.mkdir(parents=True)
        cycle.PROFILE.write_text('Lcom/aicove/Old;\n')
        cycle.STATE.parent.mkdir()
        cycle.git('add', '.')
        cycle.git('commit', '-qm', 'test source')
        self.source = cycle.git('rev-parse', 'HEAD')
        self.capture = Path('capture')
        self.capture.mkdir()
        for scenario in ['coldStartup-startup', 'chatList-baseline']:
            (self.capture/(scenario + '-prof.txt')).write_text('Lcom/aicove/New;\n')
        self.origin = run(5, self.source)
        self.env = patch.dict(os.environ, GITHUB_REF_NAME=BRANCH, SOURCE_SHA=self.source,
                              BETA_RUN_ID='5', GITHUB_RUN_ID='50')
        self.env.start()

    def tearDown(self):
        self.env.stop()
        os.chdir(self.previous_dir)
        self.temp.cleanup()

    def test_prepare_commits_only_valid_profile_and_counter(self):
        with patch.object(cycle, 'api', return_value=self.origin), \
             patch.object(cycle, 'branch_head', return_value=self.source), \
             patch.object(cycle, 'beta_runs', return_value=[run(n) for n in range(1, 5)]), \
             patch.object(cycle, 'output') as out:
            cycle.prepare(self.capture)
        self.assertEqual(self.source, cycle.git('rev-parse', 'HEAD^'))
        self.assertEqual({str(cycle.PROFILE), str(cycle.STATE)}, set(
            cycle.git('diff-tree', '--no-commit-id', '--name-only', '-r', 'HEAD').splitlines()))
        self.assertEqual('Lcom/aicove/New;\n', cycle.PROFILE.read_text())
        self.assertEqual(5, json.loads(cycle.STATE.read_text())['beta_run_number'])
        out.assert_any_call('sha', cycle.git('rev-parse', 'HEAD'))

    def test_counter_from_another_branch_is_not_inherited(self):
        cycle.STATE.write_text(json.dumps({'schema': 1, 'branch': 'other', 'beta_run_number': 999}))
        self.assertEqual({}, cycle.load_state())
        cycle.STATE.write_text(json.dumps({'schema': 1, 'branch': BRANCH, 'beta_run_number': 5}))
        self.assertEqual(5, cycle.load_state()['beta_run_number'])

    def test_branch_advance_discards_capture_without_commit(self):
        original_git = cycle.git
        calls = []
        def simulated_remote(*args):
            calls.append(args)
            if args[0] == 'fetch' or args[0] == 'ls-tree': return ''
            return original_git(*args)
        with patch.object(cycle, 'api', return_value=self.origin), \
             patch.object(cycle, 'branch_head', return_value='c' * 40), \
             patch.object(cycle, 'git', side_effect=simulated_remote), \
             patch.object(cycle, 'output') as out:
            cycle.prepare(self.capture)
        self.assertEqual(self.source, original_git('rev-parse', 'HEAD'))
        self.assertEqual('Lcom/aicove/Old;\n', cycle.PROFILE.read_text())
        self.assertFalse(cycle.STATE.exists())
        out.assert_called_with('ready', 'false')
        self.assertFalse(any(args[0] in ('commit', 'push') for args in calls))

    def test_retry_reuses_own_published_commit(self):
        with patch.object(cycle, 'api', return_value=self.origin), \
             patch.object(cycle, 'branch_head', return_value=self.source), \
             patch.object(cycle, 'beta_runs', return_value=[]), patch.object(cycle, 'output'):
            cycle.prepare(self.capture)
        published = cycle.git('rev-parse', 'HEAD')
        cycle.git('update-ref', 'refs/heads/published', published)
        cycle.git('checkout', '--detach', self.source)
        original_git = cycle.git
        def simulated_fetch(*args):
            if args[0] == 'fetch':
                Path('.git/FETCH_HEAD').write_text(published + '\n')
                return ''
            return original_git(*args)
        with patch.object(cycle, 'api', return_value=self.origin), \
             patch.object(cycle, 'branch_head', return_value=published), \
             patch.object(cycle, 'git', side_effect=simulated_fetch), \
             patch.object(cycle, 'output') as out:
            cycle.prepare(self.capture)
        self.assertEqual(published, cycle.git('rev-parse', 'HEAD'))
        out.assert_any_call('ready', 'true')
        out.assert_any_call('sha', published)

    def test_bad_capture_keeps_previous_profile_and_head(self):
        (self.capture/'chatList-baseline-prof.txt').write_text('Lcom/aicove/New;->broken\n')
        with patch.object(cycle, 'api', return_value=self.origin), \
             patch.object(cycle, 'branch_head', return_value=self.source), \
             patch.object(cycle, 'beta_runs', return_value=[]), \
             self.assertRaises(ValueError):
            cycle.prepare(self.capture)
        self.assertEqual('Lcom/aicove/Old;\n', cycle.PROFILE.read_text())
        self.assertFalse(cycle.STATE.exists())
        self.assertEqual(self.source, cycle.git('rev-parse', 'HEAD'))

    def test_publish_pushes_fast_forward_and_names_rebuilt_artifact(self):
        with patch.object(cycle, 'api', return_value=self.origin), \
             patch.object(cycle, 'branch_head', return_value=self.source), \
             patch.object(cycle, 'beta_runs', return_value=[]), patch.object(cycle, 'output'):
            cycle.prepare(self.capture)
        destination = Path(self.temp.name)/'remote.git'
        subprocess.run(['git', 'init', '--bare', '-q', str(destination)], check=True)
        cycle.git('remote', 'add', 'origin', str(destination))
        cycle.git('push', '-q', 'origin', self.source+':refs/heads/'+BRANCH)
        apk = Path('beta.apk')
        with zipfile.ZipFile(apk, 'w') as z:
            z.writestr('AndroidManifest.xml', b'manifest')
            z.writestr('assets/dexopt/baseline.prof', b'pro\x00015\x00' + b'profilebody'*4)
        head = cycle.git('rev-parse', 'HEAD')
        def remote_head(branch):
            return cycle.git('--git-dir='+str(destination), 'rev-parse', 'refs/heads/'+branch)
        with patch.object(cycle, 'branch_head', side_effect=remote_head), patch.object(cycle, 'output') as out:
            cycle.publish(apk)
            self.assertEqual(head, remote_head(BRANCH))
            self.assertEqual(apk.read_bytes(), Path(f'dist/aicode-universal-beta-{head[:8]}.apk').read_bytes())
            out.assert_called_with('published', 'true')
            cycle.publish(apk)
            out.assert_called_with('published', 'true')


class PublishTest(unittest.TestCase):
    def test_stale_rebuild_cannot_write_branch(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp)/'beta.apk'
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('AndroidManifest.xml', b'manifest')
                z.writestr('assets/dexopt/baseline.prof', b'pro\x00015\x00' + b'profilebody'*4)
            with patch.dict(os.environ, GITHUB_REF_NAME=BRANCH, SOURCE_SHA=SOURCE), \
                 patch.object(cycle, 'git', return_value='b'*40) as git_mock, \
                 patch.object(cycle, 'branch_head', return_value='c'*40), \
                 patch.object(cycle, 'output') as out:
                cycle.publish(apk)
                out.assert_called_with('published', 'false')
                self.assertFalse(any(c.args[0] == 'push' for c in git_mock.call_args_list))

    def test_truncated_compiled_profile_cannot_publish(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp)/'bad.apk'
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('AndroidManifest.xml', b'manifest')
                z.writestr('assets/dexopt/baseline.prof', b'pro\x00')
            with patch.dict(os.environ, GITHUB_REF_NAME=BRANCH, SOURCE_SHA=SOURCE), \
                 patch.object(cycle, 'git', return_value='b'*40), \
                 self.assertRaisesRegex(ValueError, 'complete compiled'):
                cycle.publish(apk)


if __name__ == '__main__':
    unittest.main()
