"""Publish an already verified repository feed without touching a working tree.

The caller must validate the staged release's complete acceptance chain first.
This module copies only the existing publisher's two sealed output files. It
never builds, signs, dispatches CI, deploys Render, or claims a live release.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


class PublicationError(ValueError):
    pass


FILES = ('release/OfferFilter.apk', 'release/latest.json')
SHA = re.compile(r'[0-9a-f]{40}')


def _need(value, message):
    if not value:
        raise PublicationError(message)


def _environment(config=()):
    # Inherited Git plumbing overrides must never redirect this operation.
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    for key in tuple(env):
        if key.startswith('OFFER_FILTER_') and any(s in key for s in ('KEY', 'PASSWORD', 'PASS_SPEC')):
            del env[key]
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
               GIT_ATTR_NOSYSTEM='1', GIT_NO_REPLACE_OBJECTS='1',
               GIT_TERMINAL_PROMPT='0')
    # Transfer only configured authentication in memory, never a .git/config,
    # credentials file, hook, or filter. Values are not command-line arguments.
    if config:
        env['GIT_CONFIG_COUNT'] = str(len(config))
        for index, (key, value) in enumerate(config):
            env['GIT_CONFIG_KEY_' + str(index)] = key
            env['GIT_CONFIG_VALUE_' + str(index)] = value
    return env


def _git(repo, *args, data=None, optional=False, config=()):
    result = subprocess.run(
        ['git', '-c', 'core.hooksPath=' + os.devnull,
         '-c', 'core.attributesFile=' + os.devnull,
         '-c', 'protocol.ext.allow=never', '-c', 'commit.gpgSign=false',
         '-c', 'gc.auto=0', '-C', str(repo), *map(str, args)],
        input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env=_environment(config))
    # Git errors can include a credential-bearing remote URL: do not echo them.
    if optional and result.returncode:
        return None
    _need(result.returncode == 0, 'Git operation refused; no force push or working-tree changes performed')
    return result.stdout


def _text(repo, *args):
    return _git(repo, *args).decode().strip()


def _authentication(checkout):
    # Read the trusted integration repository's configured credential helper
    # and HTTP authorization headers, including normal user Git configuration.
    # Helpers run only for an explicit push/fetch and Git never prompts here.
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    result = subprocess.run(['git', '-C', str(checkout), 'config', '--null',
        '--get-regexp', r'^(credential\..*|http(\..*)?\.extraheader)$'],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    _need(result.returncode in (0, 1), 'Cannot read configured Git authentication')
    return [tuple(entry.decode().split('\n', 1))
            for entry in result.stdout.split(b'\0') if entry]


def _json(data):
    def unique(items):
        out = {}
        for key, value in items:
            _need(key not in out, 'Duplicate feed or manifest field')
            out[key] = value
        return out
    return json.loads(data, object_pairs_hook=unique)


def _info(data):
    return dict(size=len(data), sha256=hashlib.sha256(data).hexdigest())


def _feed(staged):
    staged = Path(staged).absolute()
    _need(staged == staged.resolve(), 'Staged release path must not contain symlinks')
    _need((staged / 'manifest.json').is_file() and not (staged / 'manifest.json').is_symlink(),
          'Sealed staged release required')
    manifest = _json((staged / 'manifest.json').read_bytes())
    _need(manifest.get('stage') == 'staged', 'Wrong publication stage')
    payload = {}
    for name in FILES:
        path = staged / name
        _need(path.is_file() and path == path.resolve(), 'Regular staged release files required')
        data = path.read_bytes()
        _need(manifest.get('files', {}).get(name) == _info(data), 'Staged release bytes changed')
        payload[name] = data
    feed = _json(payload['release/latest.json'])
    _need(SHA.fullmatch(feed.get('sourceCommit', '')) and
          feed['sourceCommit'] == manifest.get('sourceCommit'), 'Feed source identity differs')
    _need(feed.get('packageName') == 'com.local.dasherfilter', 'Wrong release package')
    _need(type(feed.get('versionCode')) is int and feed['versionCode'] > 0, 'Invalid release version')
    _need(_info(payload['release/OfferFilter.apk']) ==
          dict(size=feed.get('size'), sha256=feed.get('sha256')), 'APK differs from staged feed')
    return payload, feed


def _same_pair(repo, commit, payload):
    return all(_git(repo, 'show', commit + ':' + name, optional=True) == value
               for name, value in payload.items())


def _ancestor(repo, older, newer):
    return _git(repo, 'merge-base', '--is-ancestor', older, newer, optional=True) is not None


def _version_forward(repo, commit, feed):
    current = _git(repo, 'show', commit + ':release/latest.json', optional=True)
    if current is not None:
        old = _json(current)
        _need(type(old.get('versionCode')) is int and feed['versionCode'] > old['versionCode'],
              'Release version must increase; an identical published pair is the only retry exception')


def publish(staged: Path, checkout: Path, expected_main: str,
            remote='origin', branch='main', push=False) -> dict:
    """Prepare, or explicitly push, an atomic source/feed Git publication.

    ``checkout`` is a full integration repository whose named local branch
    contains the exact staged source and descends from ``expected_main``.
    Uncommitted work is ignored and untouched. A prepared commit is retained
    under refs/offerfilter/publications/<sha>, never on the user's branch.
    With push=True the destination is freshly fetched and must still equal the
    expected commit, except an already published identical pair is a success.
    A normal non-force push also rejects a divergent race after that fetch.
    The expected-SHA check is a preflight check, not an atomic remote lease.
    """
    _need(os.environ.get('GITHUB_ACTIONS') != 'true' and os.environ.get('RENDER') != 'true',
          'Publication orchestration is local-only')
    payload, feed = _feed(staged)
    checkout = Path(checkout).absolute()
    _need(checkout == checkout.resolve(), 'Integration checkout must not contain symlinks')
    _need(SHA.fullmatch(expected_main or ''), 'Exact expected remote commit required')
    _need(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', remote), 'Invalid configured remote name')
    _git(checkout, 'check-ref-format', 'refs/heads/' + branch)
    _need(_text(checkout, 'rev-parse', '--is-shallow-repository') == 'false',
          'Use a full integration repository, not a shallow recovery checkout')
    local = _text(checkout, 'rev-parse', '--verify', 'refs/heads/' + branch + '^{commit}')
    _need(SHA.fullmatch(local), 'Invalid integration branch')
    urls = _text(checkout, 'remote', 'get-url', '--push', '--all', remote).splitlines()
    _need(len(urls) == 1 and urls[0] and not urls[0].startswith('-') and '::' not in urls[0],
          'Exactly one standard Git push destination required')
    url = urls[0]
    # Resolve relative local remotes as Git would in the original checkout.
    if ':' not in url and not url.startswith('/'):
        url = str((checkout / url).resolve())
    source = feed['sourceCommit']
    receipt = dict(sourceCommit=source, versionCode=feed['versionCode'],
                   sha256=feed['sha256'], expectedMain=expected_main,
                   repositoryPublished=False, liveChannelVerified=False,
                   mirrorRequired=True, hostedBuildRequested=False)
    authentication = _authentication(checkout) if push else ()
    with tempfile.TemporaryDirectory(prefix='offerfilter-publish-') as temp:
        repo = Path(temp)
        _git(repo, 'init', '--bare', '--template=')
        _git(repo, 'fetch', '--no-tags', '--no-write-fetch-head', str(checkout),
             'refs/heads/' + branch + ':refs/heads/integration')
        _need(_text(repo, 'rev-parse', 'refs/heads/integration') == local,
              'Integration branch changed during publication preparation')
        _need(_ancestor(repo, source, local), 'Integration branch does not contain staged source')
        _need(_ancestor(repo, expected_main, local), 'Integration branch does not descend from expected main')
        remote_head = expected_main
        if push:
            _git(repo, 'fetch', '--no-tags', '--no-write-fetch-head', url,
                 'refs/heads/' + branch + ':refs/heads/destination', config=authentication)
            remote_head = _text(repo, 'rev-parse', 'refs/heads/destination')
            if _same_pair(repo, remote_head, payload):
                _need(_ancestor(repo, source, remote_head) and _ancestor(repo, expected_main, remote_head),
                      'Identical destination feed lacks expected source ancestry')
                return dict(receipt, publicationCommit=remote_head, repositoryPublished=True,
                            alreadyPublished=True, remoteFreshness='fetched now')
            _need(remote_head == expected_main, 'Remote branch advanced; reconcile its changes before retrying')
        since_source = set(_git(repo, 'diff', '--name-only', '-z', source, local).decode().strip('\0').split('\0')) - {''}
        _need(since_source <= set(FILES),
              'Integration source differs from the frozen APK source; prepare a new candidate')
        if _same_pair(repo, local, payload):
            commit = local
        else:
            _version_forward(repo, local, feed)
            _version_forward(repo, remote_head, feed)
            _git(repo, 'read-tree', local)
            for name, data in payload.items():
                blob = _git(repo, 'hash-object', '-w', '--stdin', data=data).decode().strip()
                _git(repo, 'update-index', '--add', '--cacheinfo', '100644,' + blob + ',' + name)
            tree = _text(repo, 'write-tree')
            message = ('Release OfferFilter ' + str(feed.get('versionName', feed['versionCode'])) +
                       ' [skip ci] [skip render]\n\nSource: ' + source + '\n')
            commit = _git(repo, '-c', 'user.name=OfferFilter Release Pipeline',
                          '-c', 'user.email=release@offerfilter.invalid',
                          'commit-tree', tree, '-p', local, data=message.encode()).decode().strip()
            changed = set(_git(repo, 'diff-tree', '--no-commit-id', '--name-only', '-r', '-z',
                               local, commit).decode().strip('\0').split('\0'))
            _need(changed <= set(FILES) and changed, 'Publication changed files outside the release pair')
        _need(_same_pair(repo, commit, payload), 'Publication commit does not preserve exact staged bytes')
        ref = 'refs/offerfilter/publications/' + commit
        _git(checkout, 'fetch', '--no-tags', '--no-write-fetch-head', str(repo), commit + ':' + ref)
        if push:
            # Deliberately no force or force-with-lease. A divergent concurrent
            # change fails Git's normal fast-forward check; none is overwritten.
            _git(repo, 'push', '--porcelain', url, commit + ':refs/heads/' + branch,
                 config=authentication)
            _git(repo, 'fetch', '--no-tags', '--no-write-fetch-head', url,
                 'refs/heads/' + branch + ':refs/heads/observed', config=authentication)
            observed = _text(repo, 'rev-parse', 'refs/heads/observed')
            _need(_ancestor(repo, commit, observed) and _same_pair(repo, observed, payload),
                  'Destination changed after push; inspect the retained publication commit')
        return dict(receipt, publicationCommit=commit, preparedRef=ref,
                    repositoryPublished=bool(push), alreadyPublished=False,
                    remoteFreshness='fetched now' if push else 'not checked; preparation only')
