"""Publish an explicitly reviewed Endfield catalog; never publish a fresh candidate implicitly."""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import sys
from urllib.error import URLError

from collector.endfield import CollectorError, PROFILE_KEY, atomic_json, build_catalog, fetch_html, iso

RAW_BASE = 'https://raw.githubusercontent.com/pktewon/game-homework-scheduler/main/data/'
TIME_FIELDS = ('phaseKind', 'timeShape', 'occursAt', 'startAt', 'endAt', 'timeState', 'reviewReasons')
PERSONAL_FIELDS = {'isDone', 'completedAt', 'completionRecords', 'dailyTaskRecords', 'userId', 'accessToken'}


def digest(value):
    return sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def groups_digest(catalog):
    groups = deepcopy(catalog['groups'])
    for group in groups:
        for phase in group['phases']:
            phase.pop('sourceCheckedAt', None)
    return digest(groups)


def phase_expectation(phase):
    return {**{key: phase[key] for key in TIME_FIELDS},
            'dailyTasks': [{key: task.get(key) for key in ('sourceTaskKey', 'evidenceKind', 'cumulativeTargetDays')}
                           for task in phase['dailyTaskTemplates']]}


def no_personal_fields(value):
    if isinstance(value, dict):
        if PERSONAL_FIELDS.intersection(value):
            raise CollectorError('Personal state must never enter the public feed.')
        for child in value.values():
            no_personal_fields(child)
    elif isinstance(value, list):
        for child in value:
            no_personal_fields(child)


def make_release(candidate, review, source_html, registry, previous=None):
    """Verify source replay AND the independently recorded review before allowing import."""
    no_personal_fields(candidate)
    no_personal_fields(review)
    if (candidate['publicationState'] != 'CANDIDATE' or candidate['autoImportAllowed'] is not False
            or candidate['exampleOnly'] is not False or candidate['profile']['profileKey'] != PROFILE_KEY):
        raise CollectorError('Only this profile\'s real candidate can be reviewed.')
    if review.get('decision') != 'APPROVED_WITH_UNRESOLVED_TIMES':
        raise CollectorError('An explicit review decision is required.')
    replay = build_catalog(source_html, candidate['source']['url'], deepcopy(registry),
                           candidate['source']['checkedAt'], candidate)
    for field, actual in [('sourceContentHash', replay['source']['contentHash']),
                          ('candidateFingerprint', candidate['catalogFingerprint']),
                          ('expectedGroupsHash', groups_digest(candidate))]:
        if review.get(field) != actual:
            raise CollectorError(f'Stale or altered review: {field}.')
    if (review.get('sourceUrl') != candidate['source']['url']
            or candidate['source']['contentHash'] != replay['source']['contentHash']
            or groups_digest(replay) != groups_digest(candidate)
            or replay['catalogFingerprint'] != candidate['catalogFingerprint']):
        raise CollectorError('Candidate does not match the official source replay.')
    expected = review.get('phases', {})
    phases = [p for g in candidate['groups'] for p in g['phases']]
    if len(phases) != len({p['sourceKey'] for p in phases}) or set(expected) != {p['sourceKey'] for p in phases}:
        raise CollectorError('Review must cover every phase exactly once.')
    for phase in phases:
        if expected[phase['sourceKey']] != phase_expectation(phase):
            raise CollectorError(f"Review mismatch: {phase['sourceKey']}.")
    if candidate['summary'] != replay['summary'] or candidate['coverage'] != replay['coverage']:
        raise CollectorError('Candidate summary or scope was altered.')
    if candidate['profile'] != replay['profile'] or candidate['source']['title'] != replay['source']['title']:
        raise CollectorError('Candidate profile or official title was altered.')
    annotations = review.get('annotations', {})
    if not set(annotations).issubset({g['groupKey'] for g in candidate['groups']}):
        raise CollectorError('Annotation refers to an unknown group.')
    release = deepcopy(replay)
    release.update(publicationState='PUBLISHED', autoImportAllowed=True, reviewState='SOURCE_REVIEWED',
                   releaseSchemaVersion=1, generatedAt=review['reviewedAt'])
    release['review'] = {'reviewedAt': review['reviewedAt'], 'method': review['method'],
                         'reviewFile': 'reviews/endfield-9560.review.json', 'reviewHash': digest(review)}
    release['importContract'] = {
        'profileMustMatch': True, 'timestamps': 'UTC_ISO_8601', 'displayTimeZone': 'Asia/Seoul',
        'completionMode': 'MANUAL_ONLY', 'mergeKey': 'sourceKey',
        'checkMergeKey': ['sourceKey', 'sourceItemKey'], 'taskMergeKey': ['sourceKey', 'sourceTaskKey'],
        'preserveLocalChecksAndRecords': True, 'missingItems': 'KEEP_AND_FLAG_FOR_REVIEW',
        'unknownTimeLabel': '기간 확인 필요', 'unknownTimePolicy': 'KEEP_NULL_NO_AUTOMATIC_ACTIVATION',
        'dailyResetMinute': None, 'dailyResetState': 'NEEDS_CONFIRMATION',
        'personalOverrides': 'KEEP_AND_SHOW_SOURCE_CHANGE',
    }
    release['warnings'] = [w.replace('앱 배포 전 검수가 필요합니다.', '불명확한 시각은 확인 필요로 유지합니다.')
                           for w in release['warnings']]
    release['source']['checkedAt'] = review['reviewedAt']
    for group in release['groups']:
        group['notes'] = annotations.get(group['groupKey'], [])
        for phase in group['phases']:
            phase['sourceCheckedAt'] = review['reviewedAt']
            instant = phase['timeShape'] == 'INSTANT'
            known = phase['timeState'] == 'CONFIRMED'
            phase['reviewState'] = 'SOURCE_REVIEWED'
            phase['appPolicy'] = {
                'presentation': 'CONTENT_UPDATE_MARKER' if instant else 'EVENT_WINDOW',
                'countdownEligible': not instant and known,
                'deadlineNotificationEligible': not instant and known,
                'knownEndMayBeShown': phase['endAt'] is not None,
                'scheduledStateEvaluation': 'AT_OCCURS_AT' if instant else 'ONLY_WITH_BOTH_ENDPOINTS' if known else 'MANUAL_CONFIRMATION_REQUIRED',
                'createCompletionChecks': not instant,
                'dailyTaskActivation': 'NOT_APPLICABLE' if not phase['dailyTaskTemplates'] else 'AFTER_CONFIRMED_WINDOW_AND_CONFIGURED_RESET' if known else 'AFTER_TIME_CONFIRMATION_AND_CONFIGURED_RESET',
            }
            for task in phase['dailyTaskTemplates']:
                task['activationRequirements'] = ['CONFIRMED_EVENT_WINDOW', 'VERIFIED_GAME_RESET', 'USER_OPT_IN']
                task['autoCreateRecordsAllowed'] = False
                task['completionMode'] = 'MANUAL_ONLY'
    fingerprint = digest(release)
    same = previous and previous.get('publicationFingerprint') == fingerprint
    if same:
        return deepcopy(previous)
    release['publicationFingerprint'] = fingerprint
    release['releaseRevision'] = (previous or {}).get('releaseRevision', 0) + 1
    release['publishedAt'] = iso(datetime.now(timezone.utc))
    return release


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def publish_files(release, output_dir):
    """Write immutable content first, manifest last. Clients fetch the immutable URL."""
    output_dir = Path(output_dir)
    relative = f"releases/endfield-asia-v{release['releaseRevision']}.json"
    immutable = output_dir / relative
    payload = json_bytes(release)
    if immutable.exists() and immutable.read_bytes() != payload:
        raise CollectorError('Existing immutable release differs; refusing to overwrite.')
    manifest = {'schemaVersion': 1, 'profiles': [{
        'profileKey': PROFILE_KEY, 'releaseRevision': release['releaseRevision'], 'schemaVersion': 1,
        'releaseSchemaVersion': 1, 'url': RAW_BASE + relative, 'path': relative,
        'sha256': sha256(payload).hexdigest(), 'bytes': len(payload),
        'publishedAt': release['publishedAt'], 'sourceCheckedAt': release['source']['checkedAt'],
    }]}
    atomic_json(immutable, release)
    atomic_json(output_dir / 'endfield-asia.json', release)
    atomic_json(output_dir / 'manifest.json', manifest)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, default=Path('data/endfield-asia.candidate.json'))
    parser.add_argument('--review', type=Path, default=Path('reviews/endfield-9560.review.json'))
    parser.add_argument('--registry', type=Path, default=Path('collector/identities.json'))
    parser.add_argument('--output-dir', type=Path, default=Path('data'))
    parser.add_argument('--html-file', type=Path, help='Offline replay of already fetched official HTML; default fetches live source.')
    args = parser.parse_args(argv)
    try:
        read = lambda p: json.loads(p.read_text(encoding='utf-8'))
        candidate, review, registry = read(args.candidate), read(args.review), read(args.registry)
        latest = args.output_dir / 'endfield-asia.json'
        immutable = args.output_dir / f"releases/endfield-asia-v{(read(latest)['releaseRevision'] + 1) if latest.exists() else 1}.json"
        inputs = {p.resolve() for p in [args.candidate, args.review, args.registry] + ([args.html_file] if args.html_file else [])}
        outputs = {p.resolve() for p in [latest, args.output_dir / 'manifest.json', immutable]}
        if inputs & outputs:
            raise CollectorError('Publication must not overwrite its inputs.')
        source = args.html_file.read_text(encoding='utf-8-sig') if args.html_file else fetch_html(candidate['source']['url'])
        release = make_release(candidate, review, source, registry, read(latest) if latest.exists() else None)
        manifest = publish_files(release, args.output_dir)
        print(json.dumps({'releaseRevision': release['releaseRevision'], **release['summary'],
                          'url': manifest['profiles'][0]['url']}, ensure_ascii=False))
        return 0
    except (CollectorError, OSError, URLError, ValueError, KeyError, TypeError) as error:
        print(f'Publication failed; previous manifest retained: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
