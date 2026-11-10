"""Verify current VPN package-union notices without borrowing the Guard feature graph."""
from pathlib import Path, PurePosixPath
import importlib.util, os, re

KIND = 'current-vpn-core-package-union-legal-delivery'
ANCHOR = 'pinned-current-vpn-license-delivery'
HASH = re.compile('[a-f0-9]{64}')


def need(value, message):
    if not value:
        raise ValueError(message)


def helper(repo, name):
    path = Path(repo) / 'scripts' / name
    need(not path.is_symlink() and path.is_file(), 'License verifier helper missing')
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def relative(name):
    need(isinstance(name, str) and not any(ord(c) < 32 or ord(c) == 127 or c == '\\' for c in name),
         'Unsafe license member')
    path = PurePosixPath(name)
    need(path.parts and str(path) == name and not path.is_absolute() and
         all(p.casefold() not in ('.', '..', '.git', 'agents.md') for p in path.parts), 'Unsafe license member')
    return path


def load(repo, original, abis, profile, core_root=None, core_native=None):
    repo, original = Path(repo), Path(original)
    normal = helper(repo, 'normal-core-native.py')
    read, document = normal.read, normal.document
    anchor = document(read(repo / 'config/current-vpn-license-bindings.json'))
    need(set(anchor) == {'schema', 'kind', 'coverage', 'snapshotSHA256', 'inputQualificationSHA256'} and
         type(anchor['schema']) is int and anchor['schema'] == 1 and anchor['kind'] == ANCHOR and
         anchor['coverage'] == 'core-package-union-complete' and
         all(isinstance(anchor[k], str) and HASH.fullmatch(anchor[k]) for k in
             ('snapshotSHA256', 'inputQualificationSHA256')), 'Complete current VPN license anchor required')
    raw = read(original / 'CURRENT-LICENSE-DELIVERY.json', expected_hash=anchor['snapshotSHA256'])
    record = document(raw)
    need(set(record) == {'schema', 'kind', 'consumer', 'profile', 'inputQualificationSHA256', 'source',
                         'normalRoleReceiptSHA256', 'files', 'retainedAndroidNoticeOrigin', 'claims'} and
         type(record['schema']) is int and record['schema'] == 1 and record['kind'] == KIND and
         record['consumer'] == 'vpn' and record['profile'] == profile == 'android4' and
         len(abis) == len(set(abis)) == 4 and set(abis) == set(normal.ABIS) and
         record['inputQualificationSHA256'] == anchor['inputQualificationSHA256'],
         'Current VPN legal consumer/profile differs')
    cfg = normal.config(repo, 'vpn')
    need(record['source'] == {k: cfg[v] for k, v in (
        ('producerCommit', 'producerCommit'), ('producerTree', 'producerTree'),
        ('checkoutCommit', 'sourceCommit'), ('checkoutTree', 'sourceTree'),
        ('compiledManifestSHA256', 'compiledSourceManifestSHA256'))} and
        record['normalRoleReceiptSHA256'] == cfg['normalRoleReceiptSHA256'], 'Current VPN source/native role differs')
    need(record['claims'] == {'runtimeQualified': False, 'APKQualified': False,
         'completeConsumerLegalQualification': False, 'exactCompiledUnitGraphClaim': False,
         'perUnitFeatureTopology': 'UNKNOWN_NOT_CLAIMED'}, 'VPN legal scope cannot be promoted')
    rows = record['files']
    need(isinstance(rows, dict) and 0 < len(rows) <= 4096 and
         len(rows) == len({n.casefold() for n in rows}), 'Exact current VPN license file set required')
    expected = set(rows) | {'CURRENT-LICENSE-DELIVERY.json', 'MANIFEST.json'}
    actual = {p.relative_to(original).as_posix() for p in original.rglob('*') if p.is_file() or p.is_symlink()}
    need(actual == expected, 'Current VPN license namespace differs')
    bodies = {}
    for name, pin in rows.items():
        relative(name)
        need(isinstance(pin, dict) and set(pin) == {'bytes', 'sha256'} and type(pin['bytes']) is int and
             0 < pin['bytes'] <= 1048576 and isinstance(pin['sha256'], str) and HASH.fullmatch(pin['sha256']),
             'Current VPN legal member declaration differs')
        body = read(original / name, expected_size=pin['bytes'], expected_hash=pin['sha256'])
        body.decode('utf-8')
        need(not any(x in body for x in (b'/Users/', b'/srv/foxhole/', b'-----BEGIN PRIVATE KEY-----')) and
             not body.startswith((b'\x7fELF', b'PK\x03\x04', b'dex\n', b'\0asm')), 'Non-public legal member refused')
        bodies[name] = body
    qualification = document(bodies['CURRENT-CORE-PROJECTION.json'])
    need(normal.digest(bodies['CURRENT-CORE-PROJECTION.json']) == anchor['inputQualificationSHA256'] and
         qualification['consumer'] == 'vpn' and qualification['kind'] ==
         'current-vpn-source-native-package-union-legal-input-admission' and qualification['coverage'] == 'complete' and
         qualification['source'] == record['source'] and qualification['normalReceiptSHA256'] == cfg['normalRoleReceiptSHA256'] and
         qualification['nativeLibraries'] == cfg['libraries'] and qualification['features'] == cfg['features'] and
         qualification['claims'] == record['claims'], 'Actual VPN-only input qualification differs')
    need(set(qualification['targets']) == set(normal.TARGETS), 'VPN legal targets omitted')
    for target in normal.TARGETS:
        row = qualification['targets'][target]
        cdx = 'native/' + target + '.cdx.json'
        notice = 'native/' + target + '.notices.json'
        need(normal.digest(bodies[cdx]) == row['selectedSBOMSHA256'] and
             normal.digest(bodies[notice]) == row['noticeManifestSHA256'], 'Actual VPN target union differs')
        notice_record = document(bodies[notice])
        need(notice_record['coverage'] == 'complete' and notice_record['uncovered'] == [] and
             len(notice_record['files']) == row['noticeFiles'] and notice_record['sbom_sha256'] == row['selectedSBOMSHA256'],
             'VPN target notice coverage differs')
        bom = document(bodies[cdx])
        nodes = [bom['metadata']['component']] + bom['components']
        refs = {c['bom-ref'] for c in nodes}
        need(len(refs) == len(nodes) == row['packages'], 'VPN target package inventory differs')
        owners = set()
        for entry in notice_record['files']:
            relative(entry['path'])
            name = 'native/' + entry['path']
            need(normal.digest(bodies[name]) == entry['sha256'] and set(entry['owners']) <= refs and
                 entry['owners'], 'VPN notice bytes/owners differ')
            owners.update(entry['owners'])
        need(owners == refs, 'VPN package notice owner missing')
    origin = record['retainedAndroidNoticeOrigin']
    need(set(origin) == {'manifestSHA256', 'sourceInputsSHA256', 'qualificationScope'} and
         origin['qualificationScope'] == 'Original VPN Android notice capture; not a new dependency-resolution claim' and
         normal.digest(bodies['android/MANIFEST.json']) == origin['manifestSHA256'] and
         normal.digest(bodies['android/SOURCE-INPUTS.json']) == origin['sourceInputsSHA256'],
         'Original VPN Android notice identity differs')
    manifest = document(read(original / 'MANIFEST.json'))
    need(manifest['core_revision'] == cfg['producerCommit'] and manifest['androidDeliveryProfile'] == 'android4' and
         manifest['abis'] == normal.ABIS and manifest['targets'] == normal.TARGETS and
         manifest['kind'] == KIND and manifest['files'] == [dict(path=n, **rows[n]) for n in sorted(rows)] +
         [dict(path='CURRENT-LICENSE-DELIVERY.json', bytes=len(raw), sha256=normal.digest(raw))],
         'Current VPN root manifest differs')
    core_root = core_root or os.environ.get('FOXCORE_SOURCE_ROOT')
    core_native = core_native or os.environ.get('FOXCORE_VPN_JNI_ROOT')
    need(core_root and core_native, 'Normal VPN license validation requires exact source and normal-role bank')
    binding = helper(repo, 'core-source-binding.py').resolve(repo, Path(core_root), cfg['producerCommit'])
    need(binding['sourceCommit'] == cfg['sourceCommit'] and binding['sourceTree'] == cfg['sourceTree'] and
         binding['compiledSourceManifestSha256'] == cfg['compiledSourceManifestSHA256'], 'Current VPN source checkout differs')
    normal.verify(repo, Path(core_native), 'vpn', normal.ABIS)
    return {'manifest': manifest, 'record': record, 'snapshotSHA256': normal.digest(raw),
            'repo': repo, 'original': original, 'abis': list(abis), 'profile': profile,
            'coreRoot': core_root, 'coreNative': core_native}


def recheck(material):
    current = load(material['repo'], material['original'], material['abis'], material['profile'],
                   material['coreRoot'], material['coreNative'])
    need(current['snapshotSHA256'] == material['snapshotSHA256'], 'Current VPN legal source changed')
