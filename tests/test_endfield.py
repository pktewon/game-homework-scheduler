from datetime import timezone, timedelta
from html import escape
from io import StringIO
from contextlib import redirect_stderr
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from collector.endfield import (
    DEFAULT_URL, CollectorError, atomic_json, build_catalog, main,
    parse_period, validate_url,
)

STAMP = '2026-10-23T03:00:00Z'
TZ = timezone(timedelta(hours=8))


def page(*paragraphs, offset=True):
    lines = (['Asia 서버: 2026/10/15 06:00 ~ 2026/10/15 12:00(UTC+8)'] if offset else [])
    lines += ['Americas / Europe 서버: 2026/10/14 17:00 ~ 2026/10/14 23:00(UTC-5)', '■ 신규 이벤트', *paragraphs, '■ 구매 센터 업데이트', '1. 이 부분은 수집 대상 아님']
    return '<h1>테스트용 버전 공지</h1><div class="Example-NoticeDetail_content__fixture">' + ''.join('<p>' + escape(x) + '</p>' for x in lines) + '</div>'


def daily_page(end='2026/11/05 04:00'):
    return page('1. 「테스트 임무」 기간 한정 이벤트',
                f'· 이벤트 시간: 2026/10/29 04:00 ~ {end}(서버 시간)',
                '· 이벤트 설명: 이벤트 기간에 매일 지정 임무를 완료하면 보상을 획득할 수 있습니다.')


def collect(source, registry=None, previous=None, accept=True):
    return build_catalog(source, DEFAULT_URL, {} if registry is None else registry, STAMP, previous, accept)


class EndfieldCollectorTests(unittest.TestCase):
    def test_utc_conversion_and_suggested_daily_task(self):
        result = collect(daily_page())
        phase = result['groups'][0]['phases'][0]
        self.assertEqual(phase['startAt'], '2026-10-28T20:00:00Z')
        self.assertEqual(phase['endAt'], '2026-11-04T20:00:00Z')
        self.assertEqual(phase['timeState'], 'CONFIRMED')
        self.assertEqual(phase['dailyTaskTemplates'][0]['evidenceKind'], 'DAILY_MISSION')
        self.assertFalse(result['exampleOnly'])
        self.assertFalse(result['autoImportAllowed'])
        self.assertEqual(result['publicationState'], 'CANDIDATE')

    def test_asia_window_does_not_use_other_region_end(self):
        result = collect(page('1. 「지역별 임무」 기간 한정 이벤트', '· 이벤트 시간:',
                              'Asia 서버:', '2026/11/19 04:00 ~ 2026/11/26 04:00(서버 시간)',
                              'Americas / Europe 서버:', '2026/11/19 04:00 ~ 2026/11/25 17:00(서버 시간)',
                              '· 이벤트 설명: 매일 지정 임무를 확인합니다.'))
        self.assertEqual(result['groups'][0]['phases'][0]['endAt'], '2026-11-25T20:00:00Z')

    def test_maintenance_boundary_is_null_not_a_guessed_date(self):
        phase = collect(daily_page('버전 업데이트 점검 전'))['groups'][0]['phases'][0]
        self.assertIsNone(phase['endAt'])
        self.assertIn('END_NOT_EXPLICIT', phase['reviewReasons'])

    def test_after_update_is_not_scheduled_maintenance_end(self):
        phase = collect(page('1. 「출석 테스트」 출석 체크 이벤트',
                              '· 이벤트 기간: 버전 업데이트 후 ~ 2026/11/05 11:59(서버 시간)',
                              '· 이벤트 설명: 누적 7일 출석 체크 시 보상을 획득합니다.'))['groups'][0]['phases'][0]
        self.assertIsNone(phase['startAt'])
        self.assertEqual(phase['dailyTaskTemplates'][0]['cumulativeTargetDays'], 7)

    def test_stage_and_shop_have_separate_keys_and_deadlines(self):
        phases = collect(page('1. 「분리 테스트」 도전 이벤트',
                              '· 이벤트 개방 시간: 2026/11/05 12:00 ~ 버전 업데이트 점검 전',
                              '· 이벤트 콘텐츠 업데이트 시간: 2026/11/12 12:00(서버 시간)',
                              '· 「교환 테스트 상점」 개방 시간: 2026/11/05 12:00 ~ 2026/12/03 04:00(서버 시간)',
                              '· 이벤트 설명: 보상을 상점에서 교환할 수 있습니다.'))['groups'][0]['phases']
        self.assertEqual([p['phaseKind'] for p in phases], ['CHALLENGE', 'STAGE', 'SHOP'])
        self.assertIsNone(phases[0]['endAt'])
        self.assertEqual(phases[1]['startAt'], '2026-11-12T04:00:00Z')
        self.assertEqual(phases[2]['endAt'], '2026-12-02T20:00:00Z')
        self.assertEqual(phases[2]['timeState'], 'CONFIRMED')

    def test_no_daily_task_without_a_daily_or_attendance_basis(self):
        data = collect(page('1. 「일회성 목표」 기념 이벤트',
                            '· 이벤트 시간: 2026/10/29 04:00 ~ 2026/11/05 04:00(서버 시간)',
                            '· 이벤트 설명: 지정 임무를 완료합니다.'))
        self.assertEqual(data['summary']['dailyTasks'], 0)

    def test_no_personal_completion_state_is_exported(self):
        data = collect(daily_page())
        for group in data['groups']:
            for phase in group['phases']:
                for item in phase['checkTemplates'] + phase['dailyTaskTemplates']:
                    self.assertNotIn('isDone', item)
                    self.assertNotIn('completedAt', item)

    def test_repeat_collection_does_not_bump_revisions(self):
        registry = {}
        old = collect(daily_page(), registry)
        current = build_catalog(daily_page(), DEFAULT_URL, registry, '2026-10-23T04:00:00Z', old)
        self.assertEqual(current['catalogRevision'], old['catalogRevision'])
        self.assertEqual(current['groups'][0]['phases'][0]['revision'], 1)
        self.assertNotEqual(current['generatedAt'], old['generatedAt'])

    def test_date_extension_keeps_identity_and_increments_revision(self):
        registry = {}
        old = collect(daily_page(), registry)
        new = collect(daily_page('2026/11/06 04:00'), registry, old, accept=False)
        a, b = old['groups'][0]['phases'][0], new['groups'][0]['phases'][0]
        self.assertEqual(a['sourceKey'], b['sourceKey'])
        self.assertEqual(b['revision'], 2)

    def test_template_change_without_source_change_bumps_catalog_revision(self):
        registry = {}
        old = collect(daily_page(), registry)
        old['groups'][0]['phases'][0]['checkTemplates'][0]['title'] = '이전 수집기 템플릿'
        new = collect(daily_page(), registry, old, accept=False)
        self.assertEqual(new['source']['contentHash'], old['source']['contentHash'])
        self.assertGreater(new['catalogRevision'], old['catalogRevision'])

    def test_reordering_does_not_change_group_keys(self):
        a = ['「첫 이벤트」', '「둘째 이벤트」']
        def make(names):
            lines = []
            for i, name in enumerate(names, 1):
                lines += [f'{i}. {name} 기념 이벤트', '· 이벤트 시간: 2026/10/29 04:00 ~ 2026/11/05 04:00']
            return page(*lines)
        registry = {}
        old = collect(make(a), registry)
        new = collect(make(list(reversed(a))), registry, old, accept=False)
        self.assertEqual({g['title']: g['groupKey'] for g in old['groups']}, {g['title']: g['groupKey'] for g in new['groups']})

    def test_rename_requires_alias_instead_of_silent_duplicate(self):
        registry = {}
        collect(daily_page(), registry)
        renamed = daily_page().replace('테스트 임무', '새 이름')
        with self.assertRaises(CollectorError):
            collect(renamed, registry, accept=False)
        registry['notices'][DEFAULT_URL][0]['aliases'].append('「새 이름」 기간 한정 이벤트')
        self.assertEqual(collect(renamed, registry, accept=False)['groups'][0]['groupKey'], registry['notices'][DEFAULT_URL][0]['groupKey'])

    def test_missing_timezone_and_changed_markup_fail_closed(self):
        with self.assertRaises(CollectorError):
            collect(page('1. 「테스트」', '· 이벤트 시간: 2026/10/29 04:00 ~ 2026/11/05 04:00', offset=False))
        with self.assertRaises(CollectorError):
            collect('<h1>로그인 또는 오류 페이지</h1><p>■ 신규 이벤트</p>')

    def test_invalid_dates_and_conflicting_offset_are_rejected(self):
        for period in ['2026/02/30 04:00 ~ 2026/03/01 04:00',
                       '2026/11/05 04:00 ~ 2026/10/29 04:00',
                       '2026/10/29 04:00(UTC-5) ~ 2026/11/05 04:00']:
            with self.subTest(period=period), self.assertRaises(CollectorError):
                parse_period(period, TZ)

    def test_url_restrictions(self):
        for url in ['http://endfield.gryphline.com/ko-kr/news/9560',
                    'https://example.com/ko-kr/news/9560',
                    'https://endfield.gryphline.com/en-us/news/9560', DEFAULT_URL + '?x=1']:
            with self.subTest(url=url), self.assertRaises(CollectorError):
                validate_url(url)

    def test_failed_fetch_does_not_replace_previous_output(self):
        with tempfile.TemporaryDirectory(prefix='.collector-test-', dir=Path(__file__).parent) as temp:
            output = Path(temp) / 'catalog.json'
            atomic_json(output, {'keep': 'previous data'})
            original = output.read_bytes()
            with patch('collector.endfield.fetch_html', side_effect=CollectorError('HTTP failure')):
                with redirect_stderr(StringIO()):
                    status = main(['--output', str(output), '--registry', str(Path(temp) / 'registry.json')])
            self.assertEqual(status, 1)
            self.assertEqual(output.read_bytes(), original)

    def test_debug_html_cannot_overwrite_the_catalog(self):
        with tempfile.TemporaryDirectory(prefix='.collector-test-', dir=Path(__file__).parent) as temp:
            output = Path(temp) / 'catalog.json'
            atomic_json(output, {'keep': 'previous data'})
            original = output.read_bytes()
            with patch('collector.endfield.fetch_html') as fetch, redirect_stderr(StringIO()):
                status = main(['--output', str(output), '--save-html', str(output)])
            fetch.assert_not_called()
            self.assertEqual(status, 1)
            self.assertEqual(output.read_bytes(), original)


if __name__ == '__main__':
    unittest.main()
