#!/usr/bin/env python3
"""Portable nonsecret local-release evidence; capsule import executes no code."""
from __future__ import annotations
import ctypes
import hashlib
import io
import json
import os
import pathlib
import re
import stat
import subprocess
import tempfile
import unicodedata
import zipfile

Path = pathlib.Path
STAGES = ('prepared', 'signed', 'accepted', 'staged', 'verified')
MAX_FILE = 128 * 1024 * 1024
MAX_PACK = 256 * 1024 * 1024
MAX_ARCHIVE = 512 * 1024 * 1024
MAX_METADATA = 4 * 1024 * 1024
MAX_MEMBERS = 20000
HEX40 = re.compile(r'[0-9a-f]{40}\Z')
HEX64 = re.compile(r'[0-9a-f]{64}\Z')
SECRET_SUFFIXES = ('.p12', '.pfx', '.jks', '.keystore', '.pem', '.key')

class CapsuleError(ValueError):
    pass

def _need(ok, message):
    if not ok:
        raise CapsuleError(message)

def _json_bytes(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()

def _pairs(pairs):
    result = {}
    for key, value in pairs:
        _need(key not in result, 'Duplicate JSON field')
        result[key] = value
    return result

def _json(data):
    try:
        return json.loads(data, object_pairs_hook=_pairs)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise CapsuleError('Invalid JSON metadata') from error

def _path(path):
    path = Path(path).absolute()
    _need(path == path.resolve(), 'Linked or traversing path refused')
    for parent in (path, *path.parents):
        _need(not parent.is_symlink(), 'Symlink path refused')
    return path

def _name(name):
    _need(isinstance(name, str) and name and name == unicodedata.normalize('NFC', name), 'Invalid archive path')
    _need(not name.startswith('/') and '\\' not in name and '\x00' not in name, 'Unsafe archive path')
    parts = name.split('/')
    _need(all(p not in ('', '.', '..') for p in parts), 'Unsafe archive component')
    for part in parts:
        lower = part.casefold()
        _need(not lower.startswith('.env') and not lower.endswith(SECRET_SUFFIXES), 'Private key or environment file refused')
        _need(lower != '.git', 'Git control files must not be archived')
    return name

def _read(path, limit=MAX_FILE):
    path = _path(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        before = os.fstat(fd)
        _need(stat.S_ISREG(before.st_mode) and 0 <= before.st_size <= limit, 'Not a bounded regular file')
        chunks, total = [], 0
        while True:
            block = os.read(fd, min(65536, limit - total + 1))
            if not block:
                break
            total += len(block)
            _need(total <= limit, 'File grew beyond limit')
            chunks.append(block)
        def stamp(s):
            return s.st_dev, s.st_ino, s.st_mode, s.st_size, s.st_mtime_ns, s.st_ctime_ns
        _need(total == before.st_size and stamp(before) == stamp(os.fstat(fd)) == stamp(path.stat(follow_symlinks=False)), 'File changed while reading')
        return b''.join(chunks)
    finally:
        os.close(fd)

def _info(data):
    return {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}

def file_info(path):
    return _info(_read(path, MAX_ARCHIVE))

def read_json(path):
    """Parse the same bounded, stable bytes whose descriptor was checked."""
    return _json(_read(path, MAX_METADATA))

def _write(path, data):
    path = _path(path)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())

def _new_destination(path):
    path = _path(path)
    _need(path.parent.is_dir() and not path.exists(), 'Destination must be new with an existing unlinked parent')
    return path

def _promote_file(staged, destination):
    # Linking is atomic and never replaces an existing destination.
    os.link(staged, destination, follow_symlinks=False)
    os.unlink(staged)

def _promote_dir(staged, destination):
    # Ordinary POSIX rename may replace an empty directory. Never do that here.
    libc = ctypes.CDLL(None, use_errno=True)
    if hasattr(libc, 'renameat2'):
        fn = libc.renameat2
        fn.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        result = fn(-100, os.fsencode(staged), -100, os.fsencode(destination), 1)
    elif hasattr(libc, 'renamex_np'):
        fn = libc.renamex_np
        fn.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        result = fn(os.fsencode(staged), os.fsencode(destination), 4)
    else:
        raise CapsuleError('Atomic no-replace directory promotion is unavailable')
    if result:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error), str(destination))

def promote_directory(staged, destination):
    """Atomically promote a private completed directory without replacement."""
    staged, destination = _path(staged), _new_destination(destination)
    _need(staged.is_dir(), 'Promotion source must be a directory')
    _promote_dir(staged, destination)

def _inventory(directory):
    directory = _path(directory)
    _need(directory.is_dir(), 'Stage directory missing')
    result, folded = {}, set()
    for current, dirs, files in os.walk(directory, followlinks=False):
        for name in dirs + files:
            path = Path(current) / name
            relative = _name(path.relative_to(directory).as_posix())
            _need(relative.casefold() not in folded, 'Ambiguous case-folded path')
            folded.add(relative.casefold())
            mode = path.lstat().st_mode
            _need(stat.S_ISREG(mode) or stat.S_ISDIR(mode), 'Nonregular stage member')
            if stat.S_ISREG(mode):
                result[relative] = path
    _need(len(result) <= MAX_MEMBERS, 'Too many stage files')
    return result

def seal(directory, metadata):
    directory = _path(directory)
    _need(isinstance(metadata, dict) and 'files' not in metadata, 'Reserved manifest field')
    paths = _inventory(directory)
    _need('manifest.json' not in paths, 'Stage already sealed')
    files = {name: file_info(path) for name, path in sorted(paths.items())}
    result = dict(metadata, files=files)
    data = _json_bytes(result)
    _need(len(data) <= MAX_METADATA, 'Manifest too large')
    with tempfile.NamedTemporaryFile(prefix='.manifest-', dir=directory, delete=False) as stream:
        temp = Path(stream.name)
        stream.write(data); stream.flush(); os.fsync(stream.fileno())
    try:
        _promote_file(temp, directory / 'manifest.json')
    finally:
        temp.unlink(missing_ok=True)
    return result

def verify(directory, expected_stage=None):
    directory = _path(directory)
    manifest = _json(_read(directory / 'manifest.json', MAX_METADATA))
    _need(isinstance(manifest, dict) and isinstance(manifest.get('files'), dict), 'Invalid stage manifest')
    if expected_stage is not None:
        _need(manifest.get('stage') == expected_stage, 'Unexpected stage identity')
    paths = _inventory(directory)
    _need(set(paths) == set(manifest['files']) | {'manifest.json'}, 'Stage file inventory differs')
    for name, info in manifest['files'].items():
        _name(name)
        _need(name != 'manifest.json' and file_info(paths[name]) == info, 'Stage hash or size mismatch')
    return manifest

def _git_env():
    env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_SYSTEM='/dev/null',
               GIT_NO_REPLACE_OBJECTS='1', GIT_NO_LAZY_FETCH='1', GIT_TERMINAL_PROMPT='0')
    return env

def _git_args(*args):
    return ['git', '--no-replace-objects', '-c', 'core.hooksPath=/dev/null', '-c', 'core.fsmonitor=false',
            '-c', 'protocol.allow=never', '-c', 'protocol.file.allow=never', *map(str, args)]

def _git(repo, *args, input=None):
    result = subprocess.run(_git_args(*args), cwd=repo, env=_git_env(), input=input, capture_output=True)
    _need(result.returncode == 0, 'Git source verification failed: ' + str(args[0]))
    return result.stdout

def _tree_entries(repo, source):
    result, folded = {}, set()
    for item in _git(repo, 'ls-tree', '-r', '-z', source).split(b'\0'):
        if not item:
            continue
        header, raw_name = item.split(b'\t', 1)
        mode, kind, oid = header.split()
        _need(mode in (b'100644', b'100755') and kind == b'blob', 'Source symlinks or submodules refused')
        name = _name(raw_name.decode('utf-8'))
        _need(name.casefold() not in folded, 'Ambiguous source filename')
        folded.add(name.casefold()); result[name] = oid.decode()
    _need(result and len(result) <= MAX_MEMBERS, 'Invalid source file count')
    return result

def create_source_pack(repo, destination):
    repo, destination = _path(repo), _new_destination(destination)
    source = _git(repo, 'rev-parse', '--verify', 'HEAD').strip().decode()
    tree = _git(repo, 'rev-parse', '--verify', 'HEAD^{tree}').strip().decode()
    _need(HEX40.fullmatch(source) and HEX40.fullmatch(tree), 'Expected SHA-1 Git repository')
    _tree_entries(repo, source)
    ids = _git(repo, 'rev-list', '--objects', '--no-walk', '--no-object-names', source)
    _need(all(HEX40.fullmatch(x.decode()) for x in ids.splitlines()), 'Invalid object inventory')
    with tempfile.NamedTemporaryFile(prefix='.source-pack-', dir=destination.parent, delete=False) as output:
        temp = Path(output.name)
        result = subprocess.run(_git_args('pack-objects', '--stdout', '--no-reuse-delta'), cwd=repo,
                                env=_git_env(), input=ids, stdout=output, stderr=subprocess.PIPE)
        output.flush(); os.fsync(output.fileno())
    try:
        _need(result.returncode == 0, 'Source pack creation failed')
        info = _info(_read(temp, MAX_PACK))
        _promote_file(temp, destination)
    finally:
        temp.unlink(missing_ok=True)
    return {'sourceCommit': source, 'sourceTree': tree, 'sourcePack': info}

def restore_source_pack(pack, destination, source, tree):
    _need(HEX40.fullmatch(source or '') and HEX40.fullmatch(tree or ''), 'Invalid pinned source identity')
    destination = _new_destination(destination)
    data = _read(pack, MAX_PACK)
    with tempfile.TemporaryDirectory(prefix='.source-restore-', dir=destination.parent) as temporary:
        staged = Path(temporary) / 'source'; staged.mkdir(mode=0o700)
        _git(staged, 'init', '-q', '--template=')
        # Preserve original commit bytes while declaring absent parent history.
        _write(staged / '.git/shallow', (source + '\n').encode())
        _git(staged, 'index-pack', '--stdin', '--strict', input=data)
        _need(_git(staged, 'cat-file', '-t', source).strip() == b'commit', 'Expected source commit')
        _need(_git(staged, 'rev-parse', source + '^{tree}').strip().decode() == tree, 'Source tree mismatch')
        closure = set(_git(staged, 'rev-list', '--objects', '--no-walk', '--no-object-names', source).splitlines())
        inventory = set(_git(staged, 'cat-file', '--batch-all-objects', '--batch-check=%(objectname)').splitlines())
        _need(closure == inventory, 'Source pack contains objects outside candidate tree closure')
        entries = _tree_entries(staged, source)
        object_sizes = _git(staged, 'cat-file', '--batch-check=%(objectsize)', input=('\n'.join(entries.values()) + '\n').encode())
        sizes = [int(x) for x in object_sizes.splitlines()]
        _need(all(x <= MAX_FILE for x in sizes) and sum(sizes) <= MAX_ARCHIVE, 'Expanded source too large')
        _git(staged, 'update-ref', 'refs/heads/recovered-release', source)
        _git(staged, 'symbolic-ref', 'HEAD', 'refs/heads/recovered-release')
        _git(staged, 'reset', '--hard', source)
        _git(staged, 'fsck', '--full', '--strict')
        _need(not _git(staged, 'status', '--porcelain', '--untracked-files=all').strip(), 'Restored source is not clean')
        _promote_dir(staged, destination)

def _outer_name(name):
    _name(name)
    _need(name in ('run.json', 'source.pack', 'capsule-manifest.json') or
          (name.split('/')[0] in STAGES and '/' in name), 'Unexpected capsule member')

def create_capsule(run, zipdest):
    run, zipdest = _path(run), _new_destination(zipdest)
    members = {'run.json': _read(run / 'run.json', MAX_METADATA), 'source.pack': _read(run / 'source.pack', MAX_PACK)}
    _need(isinstance(_json(members['run.json']), dict), 'Invalid run metadata')
    stages = []
    for stage in STAGES:
        directory = run / stage
        if directory.exists() or directory.is_symlink():
            _need(not directory.is_symlink(), 'Linked stage refused')
            if not (directory / 'manifest.json').exists():
                continue
            verify(directory, stage)
            stages.append(stage)
            for name, path in _inventory(directory).items():
                members[stage + '/' + name] = _read(path, MAX_FILE)
            manifest = _json(members[stage + '/manifest.json'])
            _need(all(_info(members[stage + '/' + name]) == info for name, info in manifest['files'].items()), 'Stage changed during export')
    _need(sum(map(len, members.values())) <= MAX_ARCHIVE and len(members) < MAX_MEMBERS, 'Capsule too large')
    manifest = {'schema': 1, 'purpose': 'OFFERFILTER_LOCAL_RELEASE_RECOVERY', 'stages': stages,
                'files': {name: _info(data) for name, data in sorted(members.items())}}
    members['capsule-manifest.json'] = _json_bytes(manifest)
    _need(len(members['capsule-manifest.json']) <= MAX_METADATA, 'Capsule manifest too large')
    with tempfile.NamedTemporaryFile(prefix='.capsule-', dir=zipdest.parent, delete=False) as output:
        temp = Path(output.name)
    try:
        with zipfile.ZipFile(temp, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(members.items()):
                _outer_name(name)
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.external_attr = (stat.S_IFREG | 0o600) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)
        result = _info(_read(temp, MAX_ARCHIVE))
        _promote_file(temp, zipdest)
    finally:
        temp.unlink(missing_ok=True)
    return dict(result, stages=stages, members=len(members))

def restore_capsule(packet, sha, dest):
    _need(HEX64.fullmatch(sha or ''), 'Independent capsule SHA-256 required')
    dest = _new_destination(dest)
    data = _read(packet, MAX_ARCHIVE)
    _need(hashlib.sha256(data).hexdigest() == sha, 'Independent capsule SHA-256 mismatch')
    members, folded = {}, set()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        infos = archive.infolist()
        _need(len(infos) <= MAX_MEMBERS and sum(i.file_size for i in infos) <= MAX_ARCHIVE, 'Expanded capsule too large')
        for info in infos:
            _outer_name(info.filename)
            _need(info.filename == info.orig_filename and info.filename.casefold() not in folded, 'Duplicate or ambiguous capsule member')
            folded.add(info.filename.casefold())
            _need(not info.is_dir() and not info.flag_bits & 1 and info.compress_type in (0, 8), 'Unsupported capsule member')
            _need(stat.S_IFMT(info.external_attr >> 16) in (0, stat.S_IFREG), 'Nonregular capsule member')
            limit = MAX_PACK if info.filename == 'source.pack' else (MAX_METADATA if info.filename in ('run.json', 'capsule-manifest.json') else MAX_FILE)
            _need(info.file_size <= limit, 'Capsule member too large')
        for info in infos:
            members[info.filename] = archive.read(info)
    _need('capsule-manifest.json' in members, 'Capsule manifest missing')
    manifest = _json(members['capsule-manifest.json'])
    _need(isinstance(manifest, dict) and manifest.get('schema') == 1 and
          manifest.get('purpose') == 'OFFERFILTER_LOCAL_RELEASE_RECOVERY' and isinstance(manifest.get('files'), dict), 'Wrong capsule manifest')
    _need(set(members) == set(manifest['files']) | {'capsule-manifest.json'}, 'Capsule inventory differs')
    _need({'run.json', 'source.pack'} <= set(manifest['files']), 'Capsule source identity missing')
    stages = manifest.get('stages')
    _need(isinstance(stages, list) and all(isinstance(s, str) and s in STAGES for s in stages) and len(stages) == len(set(stages)), 'Invalid capsule stages')
    actual_stages = {name.split('/')[0] for name in members if '/' in name}
    _need(actual_stages == set(stages), 'Capsule stage inventory differs')
    for name, info in manifest['files'].items():
        _need(_info(members[name]) == info, 'Capsule member hash or size mismatch')
    _need(isinstance(_json(members['run.json']), dict), 'Invalid run metadata')
    with tempfile.TemporaryDirectory(prefix='.capsule-restore-', dir=dest.parent) as temporary:
        staged = Path(temporary) / 'run'; staged.mkdir(mode=0o700)
        for name, content in members.items():
            if name == 'capsule-manifest.json':
                continue
            target = staged / name
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            _write(target, content)
        for stage in stages:
            verify(staged / stage, stage)
        _promote_dir(staged, dest)
    return dict(manifest, capsuleSha256=sha)
