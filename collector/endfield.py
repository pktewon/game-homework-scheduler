"""Collect Korean Endfield version notices for the Asia profile (stdlib only)."""
from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from urllib.robotparser import RobotFileParser

DEFAULT_URL = 'https://endfield.gryphline.com/ko-kr/news/9560'
PROFILE_KEY = 'endfield-gryphline-asia'
USER_AGENT = 'GameHomeworkScheduler/0.1 (+https://github.com/pktewon/game-homework-scheduler)'
HOST = 'endfield.gryphline.com'
DATE = re.compile(r'(\d{4})/(\d{1,2})/(\d{1,2})\s+(\d{1,2}):(\d{2})')
VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'}


class CollectorError(ValueError):
    """A fetch or parse could not safely produce a replacement catalog."""


@dataclass
class Node:
    tag: str
    attrs: dict[str, str] = field(default_factory=dict)
    children: list = field(default_factory=list)

    def walk(self):
        yield self
        for child in self.children:
            if isinstance(child, Node):
                yield from child.walk()

    def text(self):
        if self.tag in {'script', 'style'}:
            return ''
        return ''.join(c.text() if isinstance(c, Node) else c for c in self.children)


class PageParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Node('document')
        self.stack = [self.root]

    def handle_starttag(self, tag, attrs):
        node = Node(tag, dict(attrs))
        self.stack[-1].children.append(node)
        if tag == 'br':
            node.children.append(' ')
        if tag not in VOID:
            self.stack.append(node)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in VOID:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                return

    def handle_data(self, data):
        self.stack[-1].children.append(data)


def clean(text):
    return ' '.join(text.replace('\u200b', '').split())


def validate_url(url):
    p = urlsplit(url)
    if (p.scheme != 'https' or p.netloc != HOST
            or not re.fullmatch(r'/ko-kr/news/\d+/?', p.path)
            or p.query or p.fragment):
        raise CollectorError('Only https://endfield.gryphline.com/ko-kr/news/<id> is supported.')
    return url.rstrip('/')


class OfficialRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        p = urlsplit(newurl)
        if p.scheme != 'https' or p.netloc != HOST:
            raise CollectorError('Redirect outside the official HTTPS host was rejected.')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_html(url, timeout=25):
    url = validate_url(url)
    opener = build_opener(OfficialRedirects())

    def get(address, limit):
        with opener.open(Request(address, headers={'User-Agent': USER_AGENT}), timeout=timeout) as response:
            data = response.read(limit + 1)
            if len(data) > limit:
                raise CollectorError('Response exceeded the configured size limit.')
            return data.decode('utf-8-sig'), response.geturl()

    robots_url = f'https://{HOST}/robots.txt'
    try:
        rules, _ = get(robots_url, 100_000)
    except HTTPError as error:
        if error.code != 404:
            raise CollectorError(f'robots.txt returned HTTP {error.code}; no notice fetched.') from error
        rules = ''
    robot = RobotFileParser(robots_url)
    robot.parse(rules.splitlines())
    if not robot.can_fetch(USER_AGENT, url):
        raise CollectorError('robots.txt does not allow this collector to fetch the notice.')
    source, final_url = get(url, 2_000_000)
    validate_url(final_url)
    return source


def extract_article(source):
    parser = PageParser()
    parser.feed(source)
    nodes = list(parser.root.walk())
    bodies = [n for n in nodes if n.tag == 'div' and 'NoticeDetail_content__' in n.attrs.get('class', '')]
    titles = [clean(n.text()) for n in nodes if n.tag == 'h1']
    if len(bodies) != 1 or len(titles) != 1:
        raise CollectorError('Official article markup changed: expected one title and one notice body.')
    lines = [clean(n.text()) for n in bodies[0].walk() if n.tag == 'p']
    if '■ 신규 이벤트' not in lines:
        raise CollectorError('This version-notice format has no supported 신규 이벤트 section.')
    return titles[0], lines


def asia_offset(lines):
    for line in lines:
        if line.startswith('Asia 서버:'):
            m = re.search(r'UTC([+-])(\d{1,2})(?::(\d{2}))?', line)
            if m:
                minutes = (int(m[2]) * 60 + int(m[3] or 0)) * (1 if m[1] == '+' else -1)
                if abs(minutes) > 14 * 60:
                    raise CollectorError('Invalid Asia timezone offset.')
                return timezone(timedelta(minutes=minutes)), f'{m[1]}{int(m[2]):02d}:{int(m[3] or 0):02d}'
    raise CollectorError('Asia UTC offset was not found; do not assume Korean display time.')


def iso(dt):
    return dt.astimezone(timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z')


def parse_instant(text, tz):
    for offset in re.finditer(r'UTC([+-])(\d{1,2})(?::(\d{2}))?', text):
        minutes = (int(offset[2]) * 60 + int(offset[3] or 0)) * (1 if offset[1] == '+' else -1)
        if timedelta(minutes=minutes) != tz.utcoffset(None):
            raise CollectorError('Explicit timestamp offset conflicts with the selected Asia profile.')
    matches = list(DATE.finditer(text))
    if not matches:
        return None
    if len(matches) != 1:
        raise CollectorError('An endpoint contains multiple explicit dates; review is required.')
    try:
        return iso(datetime(*(int(x) for x in matches[0].groups()), tzinfo=tz))
    except ValueError as error:
        raise CollectorError(f'Invalid calendar date: {matches[0].group()}') from error


def parse_period(text, tz):
    if text.count('~') != 1:
        raise CollectorError('Expected one start/end range; unsupported period requires review.')
    left, right = text.split('~')
    start, end = parse_instant(left, tz), parse_instant(right, tz)
    if start and end and start >= end:
        raise CollectorError('Period end must be later than start.')
    reasons = []
    if not start:
        reasons.append('START_NOT_EXPLICIT')
    if not end:
        reasons.append('END_NOT_EXPLICIT')
    return start, end, reasons


def event_blocks(lines):
    begin = lines.index('■ 신규 이벤트')
    end = next((i for i in range(begin + 1, len(lines)) if lines[i].startswith('■')), len(lines))
    blocks, current = [], None
    for i in range(begin + 1, end):
        heading = re.fullmatch(r'\d+\.\s+(.+)', lines[i])
        if heading:
            current = {'title': heading[1], 'lines': [], 'headingParagraph': i}
            blocks.append(current)
        elif current is not None and lines[i]:
            current['lines'].append((i, lines[i]))
    if not blocks or len({b['title'] for b in blocks}) != len(blocks):
        raise CollectorError('Missing or duplicate event headings; review the notice structure.')
    return blocks


def main_period(block):
    matches = []
    pairs = block['lines']
    for position, (index, line) in enumerate(pairs):
        m = re.match(r'^[·•]\s*이벤트\s*(?:개방\s*)?(?:기간|시간)\s*:\s*(.*)$', line)
        if not m:
            continue
        if m[1]:
            matches.append((index, m[1]))
            continue
        region = None
        for j, following in pairs[position + 1:]:
            if following.startswith(('·', '•', '※')):
                break
            if '서버:' in following:
                region = 'ASIA' if following.startswith('Asia 서버:') else 'OTHER'
            elif region == 'ASIA' and '~' in following:
                matches.append((j, following))
    if len(matches) != 1:
        raise CollectorError(f"Expected exactly one Asia period for {block['title']!r}; found {len(matches)}.")
    return matches[0]


def stable_ids(registry, url, titles, accept_new):
    registry.setdefault('schemaVersion', 1)
    notices = registry.setdefault('notices', {})
    entries = notices.setdefault(url, [])
    assigned = {}
    for title in titles:
        matches = [e for e in entries if title in e['aliases']]
        if len(matches) > 1:
            raise CollectorError(f'Duplicate identity aliases for {title!r}.')
        if not matches:
            if not accept_new:
                raise CollectorError(f'Unregistered event name {title!r}. Add a rename alias or explicitly accept a genuinely new event.')
            used = [int(e['groupKey'].rsplit('-', 1)[1]) for e in entries]
            key = f"endfield-{url.rsplit('/', 1)[1]}-event-{max(used, default=0) + 1:04d}"
            matches = [{'groupKey': key, 'aliases': [title]}]
            entries.append(matches[0])
        assigned[title] = matches[0]['groupKey']
    if len(set(assigned.values())) != len(assigned):
        raise CollectorError('Two event headings resolved to one identity; review aliases.')
    return assigned


def build_catalog(source, url, registry, checked_at, previous=None, accept_new=False):
    url = validate_url(url)
    title, lines = extract_article(source)
    tz, offset = asia_offset(lines)
    blocks = event_blocks(lines)
    ids = stable_ids(registry, url, [b['title'] for b in blocks], accept_new)
    previous_phases = {p['sourceKey']: p for g in (previous or {}).get('groups', []) for p in g['phases']}
    groups = []

    def make_phase(block, key, kind, period_index, period_text, *, start_override=None, parent_end=None):
        if start_override is None:
            start, end, reasons = parse_period(period_text, tz)
        else:
            start, end = start_override, parent_end
            reasons = []
            if end and start >= end:
                raise CollectorError('Additional stage opens at or after the event end.')
        group_key = ids[block['title']]
        phase = {
            'sourceKey': f'{PROFILE_KEY}:{group_key}:{key}', 'revision': 1,
            'phaseKind': kind, 'title': block['title'] + {'play': '', 'stage': ' · 콘텐츠 업데이트', 'shop': ' · 교환 마감'}[key],
            'timeShape': 'INSTANT' if key == 'stage' else 'WINDOW',
            'occursAt': start if key == 'stage' else None,
            'startAt': None if key == 'stage' else start, 'endAt': None if key == 'stage' else end,
            'timeState': 'NEEDS_REVIEW' if reasons else 'CONFIRMED',
            'reviewReasons': reasons,
            'sourceUrl': url, 'sourceCheckedAt': checked_at,
            'sourceEvidence': {'section': '신규 이벤트', 'heading': block['title'], 'paragraph': period_index,
                               'periodText': period_text, 'timeZoneBasis': 'Asia / UTC' + offset},
            'checkTemplates': ([] if key == 'stage' else [{'sourceItemKey': 'wanted-exchanges', 'title': '원하는 교환 보상 수령 확인'}] if kind == 'SHOP' else [
                {'sourceItemKey': 'goal', 'title': '선택한 이벤트 목표 달성 확인'},
                {'sourceItemKey': 'rewards', 'title': '이벤트 보상 수령 확인'},
            ]),
            'dailyTaskTemplates': [],
        }
        if key == 'play':
            description = ' '.join(t for _, t in block['lines'] if t.startswith('· 이벤트 설명'))
            count = re.search(r'누적\s*(\d+)일\s*출석', description)
            task_kind = 'ATTENDANCE' if count else ('DAILY_MISSION' if '매일' in description else None)
            if task_kind:
                task = {'sourceTaskKey': 'daily-attendance' if count else 'daily-mission',
                        'title': '오늘 출석과 보상 수령 확인' if count else '오늘 이벤트 목표와 보상 확인',
                        'recurrence': 'DAILY', 'resetPolicy': 'GAME_RESET',
                        'templateOrigin': 'SUGGESTED_FROM_NOTICE', 'evidenceKind': task_kind}
                if count:
                    task['cumulativeTargetDays'] = int(count[1])
                phase['dailyTaskTemplates'].append(task)
        old = previous_phases.get(phase['sourceKey'])
        if old:
            semantic = lambda p: {k: p.get(k) for k in ['phaseKind', 'title', 'timeShape', 'occursAt', 'startAt', 'endAt', 'timeState', 'reviewReasons', 'checkTemplates', 'dailyTaskTemplates']}
            phase['revision'] = old['revision'] + (semantic(old) != semantic(phase))
        return phase

    for block in blocks:
        index, period = main_period(block)
        text = ' '.join(t for _, t in block['lines'])
        kind = 'ATTENDANCE' if '출석 체크' in block['title'] else ('CHALLENGE' if '도전 이벤트' in block['title'] else ('WEB' if '웹 이벤트' in text else 'PLAY'))
        primary = make_phase(block, 'play', kind, index, period)
        phases = [primary]
        updates = [(i, t) for i, t in block['lines'] if re.match(r'^[·•]\s*이벤트 콘텐츠 업데이트 시간\s*:', t)]
        if len(updates) > 1:
            raise CollectorError('Multiple stage updates require an explicit identity mapping.')
        for i, update in updates:
            when = parse_instant(update, tz)
            if not when:
                raise CollectorError('Additional stage update has no explicit timestamp.')
            phases.append(make_phase(block, 'stage', 'CONTENT_UPDATE', i, update, start_override=when, parent_end=primary['endAt']))
        shops = [(i, t) for i, t in block['lines'] if re.match(r'^[·•]\s*「[^」]+」\s*개방\s*(?:기간|시간)\s*:', t)]
        if len(shops) > 1:
            raise CollectorError('Multiple shop windows require an explicit identity mapping.')
        for i, shop in shops:
            if '교환' not in text:
                raise CollectorError('A secondary quoted period could not be safely classified as a shop.')
            phases.append(make_phase(block, 'shop', 'SHOP', i, shop.split(':', 1)[1].strip()))
        groups.append({'groupKey': ids[block['title']], 'title': block['title'], 'phases': phases})

    digest = sha256('\n'.join(lines).encode('utf-8')).hexdigest()
    comparable = json.loads(json.dumps(groups, ensure_ascii=False))
    for group in comparable:
        for phase in group['phases']:
            phase.pop('sourceCheckedAt')
    fingerprint = sha256(json.dumps({'sourceHash': digest, 'groups': comparable}, ensure_ascii=False, sort_keys=True).encode('utf-8')).hexdigest()
    same_catalog = previous and previous.get('catalogFingerprint') == fingerprint
    revision = (previous or {}).get('catalogRevision', 0) + (0 if same_catalog else 1)
    phases = [p for g in groups for p in g['phases']]
    catalog = {
        'schemaVersion': 1, 'catalogRevision': revision, 'generatedAt': checked_at,
        'collectorVersion': '0.2.0', 'catalogFingerprint': fingerprint,
        'exampleOnly': False, 'publicationState': 'CANDIDATE', 'autoImportAllowed': False,
        'profile': {'profileKey': PROFILE_KEY, 'gameKey': 'endfield', 'publisherKey': 'GRYPHLINE',
                    'serverRegion': 'ASIA', 'locale': 'ko-KR', 'serverUtcOffset': offset},
        'source': {'url': url, 'title': title, 'checkedAt': checked_at, 'contentHash': digest},
        'coverage': {'includedSection': '신규 이벤트', 'eventGroups': len(groups),
                     'notParsed': ['이벤트 및 콘텐츠 업데이트', '헤드헌팅 및 신청', '메일 및 상시 콘텐츠']},
        'summary': {'groups': len(groups), 'phases': len(phases),
                    'confirmedPeriods': sum(p['timeShape'] == 'WINDOW' and p['timeState'] == 'CONFIRMED' for p in phases),
                    'reviewPeriods': sum(p['timeShape'] == 'WINDOW' and p['timeState'] != 'CONFIRMED' for p in phases),
                    'confirmedInstants': sum(p['timeShape'] == 'INSTANT' for p in phases),
                    'dailyTasks': sum(len(p['dailyTaskTemplates']) for p in phases)},
        'warnings': ['신규 이벤트 구간만 지원하는 초기 수집기입니다. 다른 콘텐츠 구간은 별도 확인해야 합니다.',
                     '업데이트 후·점검 전의 시각은 추정하지 않습니다. 앱 배포 전 검수가 필요합니다.',
                     '숙제는 확인용 제안 템플릿입니다. 게임 내 개별 임무나 개인 진행 상태가 아닙니다.',
                     '일일 초기화 시각은 이 공지만으로 확정하지 않으며 게임 프로필에서 별도 확인해야 합니다.'],
        'groups': groups,
    }
    return catalog


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, name = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(handle, 'w', encoding='utf-8', newline='\n') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def main(argv=None):
    args_parser = argparse.ArgumentParser(description=__doc__)
    args_parser.add_argument('--url', default=DEFAULT_URL)
    args_parser.add_argument('--output', type=Path, default=Path('data/endfield-asia.candidate.json'))
    args_parser.add_argument('--registry', type=Path, default=Path('collector/identities.json'))
    args_parser.add_argument('--html-file', type=Path, help='Parse a previously saved official HTML instead of fetching.')
    args_parser.add_argument('--save-html', type=Path, help='Save fetched HTML locally for debugging; do not publish full notices.')
    args_parser.add_argument('--accept-new-events', action='store_true', help='Allocate identities for reviewed new event names; check rename aliases first.')
    args = args_parser.parse_args(argv)
    try:
        if args.output.resolve() == args.registry.resolve():
            raise CollectorError('Output and identity registry must use different files.')
        if args.save_html and args.save_html.resolve() in {args.output.resolve(), args.registry.resolve()}:
            raise CollectorError('Debug HTML cannot overwrite the catalog or identity registry.')
        url = validate_url(args.url)
        source = args.html_file.read_text(encoding='utf-8-sig') if args.html_file else fetch_html(url)
        if args.save_html:
            args.save_html.parent.mkdir(parents=True, exist_ok=True)
            args.save_html.write_text(source, encoding='utf-8', newline='\n')
        registry = json.loads(args.registry.read_text(encoding='utf-8')) if args.registry.exists() else {}
        previous = json.loads(args.output.read_text(encoding='utf-8')) if args.output.exists() else None
        if previous and previous.get('source', {}).get('url') != url:
            raise CollectorError('Existing output belongs to a different source URL; choose a different output path.')
        checked_at = iso(datetime.now(timezone.utc))
        catalog = build_catalog(source, url, registry, checked_at, previous, args.accept_new_events)
        atomic_json(args.registry, registry)
        atomic_json(args.output, catalog)
        print(json.dumps({'output': args.output.as_posix(), **catalog['summary'], 'publicationState': 'CANDIDATE'}, ensure_ascii=False))
        return 0
    except (CollectorError, OSError, URLError, UnicodeError, json.JSONDecodeError) as error:
        print(f'Collection failed; previous JSON kept: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
