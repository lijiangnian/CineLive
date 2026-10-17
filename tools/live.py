"""Read-only live playlist parsing; fetch success is not playback success."""
import argparse
import hashlib
import ipaddress
import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlsplit

from tools.sources import fetch

ATTR = re.compile(r'([\w-]+)\s*=\s*(?:"([^"]*)"|\x27([^\x27]*)\x27)')


def attributes(line):
    return {m.group(1).lower(): m.group(2) if m.group(2) is not None else m.group(3)
            for m in ATTR.finditer(line)}


def split_title(line):
    quote = None
    for i, char in enumerate(line):
        if char in ('"', "'"):
            if quote == char: quote = None
            elif quote is None: quote = char
        elif char == ',' and quote is None:
            return line[:i], line[i+1:].strip()
    raise ValueError('EXTINF lacks channel title delimiter')


def network_scope(url):
    p = urlsplit(url)
    if p.scheme in ('rtp', 'udp'):
        return 'operator_or_lan_network'
    try:
        address = ipaddress.ip_address(p.hostname or '')
        if not address.is_global: return 'operator_or_lan_network'
    except ValueError:
        pass
    return 'network_unverified'


def parse_live(text, base_url):
    lines = [line.strip() for line in text.lstrip('\ufeff').splitlines() if line.strip()]
    if not lines or text.lstrip().startswith('<'):
        raise ValueError('empty playlist or HTML')
    if any(line.startswith(('#EXT-X-STREAM-INF', '#EXT-X-TARGETDURATION', '#EXT-X-MEDIA-SEQUENCE')) for line in lines):
        raise ValueError('HLS stream manifest is not a channel subscription')
    channels, epgs, warnings = [], [], []
    group, pending, headers = '未分组', None, {}
    m3u = lines[0].startswith('#EXTM3U')
    if m3u:
        attrs = attributes(lines[0])
        for key in ('url-tvg', 'x-tvg-url'):
            epgs += [urljoin(base_url, u.strip()) for u in attrs.get(key, '').split(',') if u.strip()]
    for line in lines[1:] if m3u else lines:
        if line.startswith('#EXTINF:'):
            if pending: warnings.append('channel_without_url')
            meta, name = split_title(line)
            a = attributes(meta)
            pending = {'name': name or a.get('tvg-name',''), 'tvg_id': a.get('tvg-id'),
                       'group': a.get('group-title', group), 'logo': a.get('tvg-logo'),
                       'catchup': a.get('catchup'), 'catchup_source': a.get('catchup-source')}
            headers = {}
        elif line.startswith('#EXTVLCOPT:'):
            key, _, value = line[len('#EXTVLCOPT:'):].partition('=')
            names = {'http-user-agent':'User-Agent', 'http-referrer':'Referer'}
            if key in names: headers[names[key]] = value
        elif line.startswith('#EXTGRP:'):
            group = line[len('#EXTGRP:'):]
            if pending: pending['group'] = group
        elif line.startswith('#'):
            continue
        elif m3u:
            if pending:
                u = urljoin(base_url, line)
                if urlsplit(u).scheme in ('http','https','rtsp','rtmp','rtp','udp'):
                    channels.append({**pending, 'url':u, 'headers':dict(headers), 'network_scope':network_scope(u)})
                else: warnings.append('unsupported_channel_scheme')
                pending, headers = None, {}
        elif ',' in line:
            name, value = line.split(',',1)
            if value.strip() == '#genre#':
                group = name.strip()
                continue
            # TXT multi-line syntax. URLs containing literal # must be URL-encoded by the provider.
            for index, entry in enumerate(value.split('#')):
                u, _, label = entry.partition('$')
                if urlsplit(u.strip()).scheme not in ('http','https','rtsp','rtmp','rtp','udp'):
                    warnings.append('unsupported_channel_scheme')
                    continue
                channels.append({'name':name.strip(),'tvg_id':None,'group':group,'logo':None,
                                 'url':u.strip(),'line':label or str(index+1),'headers':{},
                                 'network_scope':network_scope(u.strip())})
    if pending: warnings.append('channel_without_url')
    if not channels: raise ValueError('no recognized channels')
    return {'format':'m3u' if m3u else 'txt','channels':channels,
            'epg_urls':list(dict.fromkeys(epgs)), 'warnings':dict(Counter(warnings))}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalog',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    results=[]
    for source in json.loads(args.catalog.read_text())['sources']:
        result={**source,'playback_verified':False}
        try:
            body, final, status=fetch(source['url'])
            data=parse_live(body.decode('utf-8-sig'),final)
            result.update(status='playlist_parsed',http_status=status,final_url=final,
                          sha256=hashlib.sha256(body).hexdigest(),format=data['format'],
                          channel_entries=len(data['channels']),epg_urls=data['epg_urls'],
                          protocols=dict(Counter(urlsplit(c['url']).scheme for c in data['channels'])),
                          network_scopes=dict(Counter(c['network_scope'] for c in data['channels'])),
                          warnings=data['warnings'])
        except Exception as e:
            result.update(status='fetch_or_parse_failed',error=type(e).__name__)
        results.append(result)
    report={'checked_at':datetime.now(timezone.utc).isoformat(),'results':results,
            'scope':'Playlist fetch and parsing only; no channel streaming or EPG fetch.'}
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(results,ensure_ascii=False,indent=2))


if __name__=='__main__':main()
