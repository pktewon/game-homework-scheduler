from copy import deepcopy
from contextlib import redirect_stderr
from hashlib import sha256
from io import StringIO
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from collector.endfield import CollectorError, DEFAULT_URL, atomic_json, build_catalog
from collector.publish import digest, groups_digest, main, make_release, phase_expectation, publish_files
from test_endfield import STAMP, daily_page, page


def fixture(source=None):
    source = source or page(
        '1. 「출석」 출석 체크 이벤트', '· 이벤트 기간: 업데이트 후 ~ 2026/11/05 11:59(서버 시간)',
        '· 이벤트 설명: 누적 7일 출석 시 보상을 받습니다.',
        '2. 「도전」 도전 이벤트', '· 이벤트 시간: 2026/10/29 04:00 ~ 2026/11/05 04:00',
        '· 이벤트 콘텐츠 업데이트 시간: 2026/11/01 04:00',
        '· 이벤트 설명: 매일 지정 임무를 완료합니다.')
    registry = {}
    candidate = build_catalog(source, DEFAULT_URL, registry, STAMP, accept_new=True)
    review = {'decision': 'APPROVED_WITH_UNRESOLVED_TIMES', 'sourceUrl': DEFAULT_URL,
              'sourceContentHash': candidate['source']['contentHash'],
              'candidateFingerprint': candidate['catalogFingerprint'], 'expectedGroupsHash': groups_digest(candidate),
              'reviewedAt': STAMP, 'method': 'SYNTHETIC_TEST_ONLY',
              'phases': {p['sourceKey']: phase_expectation(p) for g in candidate['groups'] for p in g['phases']}}
    return candidate, review, source, registry


class PublicationTests(unittest.TestCase):
    def test_unknown_time_survives_without_automatic_activation(self):
        release = make_release(*fixture())
        p = release['groups'][0]['phases'][0]
        self.assertTrue(release['autoImportAllowed'])
        self.assertEqual(release['publicationState'], 'PUBLISHED')
        self.assertEqual(p['reviewState'], 'SOURCE_REVIEWED')
        self.assertEqual(p['timeState'], 'NEEDS_REVIEW')
        self.assertIsNone(p['startAt'])
        self.assertFalse(p['appPolicy']['countdownEligible'])
        self.assertFalse(p['appPolicy']['deadlineNotificationEligible'])
        self.assertIsNone(release['importContract']['dailyResetMinute'])
        task = p['dailyTaskTemplates'][0]
        self.assertFalse(task['autoCreateRecordsAllowed'])
        self.assertEqual(task['cumulativeTargetDays'], 7)
        self.assertEqual(task['completionMode'], 'MANUAL_ONLY')

    def test_content_update_is_not_an_event_window_or_completion_task(self):
        p = make_release(*fixture())['groups'][1]['phases'][1]
        self.assertEqual(p['timeShape'], 'INSTANT')
        self.assertEqual(p['occursAt'], '2026-10-31T20:00:00Z')
        self.assertIsNone(p['endAt'])
        self.assertEqual(p['checkTemplates'], [])
        self.assertFalse(p['appPolicy']['createCompletionChecks'])
        self.assertFalse(p['appPolicy']['deadlineNotificationEligible'])

    def test_missing_or_partial_review_rejected(self):
        for mode in ('decision', 'phase'):
            c, r, s, reg = fixture()
            if mode == 'decision':
                r.pop('decision')
            else:
                r['phases'].pop(next(iter(r['phases'])))
            with self.subTest(mode=mode), self.assertRaises(CollectorError):
                make_release(c, r, s, reg)

    def test_source_change_invalidates_review(self):
        c, r, s, reg = fixture(daily_page())
        with self.assertRaises(CollectorError):
            make_release(c, r, s.replace('11/05', '11/06'), reg)

    def test_date_or_template_tampering_rejected(self):
        for field in ('date', 'template', 'profile'):
            c, r, s, reg = fixture()
            p = c['groups'][0]['phases'][0]
            if field == 'date':
                p['startAt'] = '2026-10-15T04:00:00Z'
            elif field == 'template':
                p['checkTemplates'][0]['title'] = '검수하지 않은 수정'
            else:
                c['profile']['serverUtcOffset'] = '+09:00'
            with self.subTest(field=field), self.assertRaises(CollectorError):
                make_release(c, r, s, reg)

    def test_forged_matching_review_hash_still_requires_source_replay(self):
        c, r, s, reg = fixture()
        c['groups'][0]['phases'][0]['checkTemplates'][0]['title'] = '변조'
        r['expectedGroupsHash'] = groups_digest(c)
        with self.assertRaises(CollectorError):
            make_release(c, r, s, reg)

    def test_personal_records_never_published(self):
        c, r, s, reg = fixture()
        c['groups'][0]['phases'][0]['checkTemplates'][0]['isDone'] = True
        with self.assertRaises(CollectorError):
            make_release(c, r, s, reg)

    def test_republish_identical_review_keeps_bytes_and_revision(self):
        args = fixture()
        first = make_release(*args)
        self.assertEqual(make_release(*args, previous=first), first)
        updated = list(deepcopy(args))
        updated[1]['annotations'] = {updated[0]['groups'][0]['groupKey']: [{'text': '검수 메모 추가'}]}
        second = make_release(*updated, previous=first)
        self.assertEqual(second['releaseRevision'], first['releaseRevision'] + 1)
        self.assertEqual(second['groups'][0]['phases'][0]['sourceKey'], first['groups'][0]['phases'][0]['sourceKey'])
        self.assertNotEqual(digest(second), digest(first))

    def test_manifest_integrity_and_immutable_release(self):
        with tempfile.TemporaryDirectory(prefix='.collector-test-', dir=Path(__file__).parent) as tmp:
            release = make_release(*fixture())
            manifest = publish_files(release, tmp)
            entry = manifest['profiles'][0]
            payload = (Path(tmp) / entry['path']).read_bytes()
            self.assertEqual(sha256(payload).hexdigest(), entry['sha256'])
            self.assertEqual(len(payload), entry['bytes'])
            self.assertEqual(payload, (Path(tmp) / 'endfield-asia.json').read_bytes())
            before = (Path(tmp) / 'manifest.json').read_bytes()
            altered = deepcopy(release)
            altered['warnings'].append('같은 버전 번호로 다른 내용')
            with self.assertRaises(CollectorError):
                publish_files(altered, tmp)
            self.assertEqual((Path(tmp) / 'manifest.json').read_bytes(), before)

    def test_failed_live_verification_preserves_previous_release(self):
        with tempfile.TemporaryDirectory(prefix='.collector-test-', dir=Path(__file__).parent) as tmp:
            base = Path(tmp)
            c, r, _, reg = fixture()
            for name, value in [('candidate', c), ('review', r), ('registry', reg)]:
                atomic_json(base / f'{name}.json', value)
            atomic_json(base / 'manifest.json', {'previous': True})
            before = (base / 'manifest.json').read_bytes()
            with patch('collector.publish.fetch_html', side_effect=CollectorError('offline')), redirect_stderr(StringIO()):
                code = main(['--candidate', str(base / 'candidate.json'), '--review', str(base / 'review.json'),
                             '--registry', str(base / 'registry.json'), '--output-dir', str(base)])
            self.assertEqual(code, 1)
            self.assertEqual((base / 'manifest.json').read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
