"""Read-only public playlist and bounded media-packet probes; not a home-network guarantee."""
import concurrent.futures,json,subprocess,os,time
from pathlib import Path
from datetime import datetime,timezone
from urllib.parse import urlsplit
from tools.sources import fetch
from tools.live import parse_live
ROOT=Path(__file__).resolve().parents[1]
candidates=[(r['name'],r['urls'][0]) for r in json.loads((ROOT/'app/src/main/assets/cinelive-live-catalog.json').read_text())['sources']]
def run(item):
 name,url=item;r={'name':name,'url':url,'media_decode_sample_passed':False,'home_network_verified':False,'samples':[]}
 try:
  b,final,_=fetch(url,timeout=15);d=parse_live(b.decode('utf-8-sig'),final);r['entries']=len(d['channels']);r['format_ok']=True
  hosts=set(); selected=[]
  channels=sorted(d['channels'],key=lambda c:0 if any(x in c['name'].lower() for x in ['cctv','凤凰','tvbs','dw','france','al jazeera']) else 1)
  for c in channels:
   p=urlsplit(c['url']);h=p.hostname
   if p.scheme not in ('http','https') or h in hosts or c['network_scope']=='operator_or_lan_network':continue
   hosts.add(h);selected.append(c)
   if len(selected)==5:break
  for c in selected:
   args=['ffprobe','-v','error','-rw_timeout','8000000']
   if os.environ.get('HTTPS_PROXY'):args+=['-http_proxy',os.environ['HTTPS_PROXY']]
   if c['headers']:args += ['-headers',''.join(k+': '+v+'\r\n' for k,v in c['headers'].items())]
   args+=['-read_intervals','%+2','-count_packets','-show_entries','stream=codec_name,codec_type,nb_read_packets','-of','json',c['url']]
   probe={'channel':c['name'],'host':urlsplit(c['url']).hostname}
   try:
    p=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=18)
    streams=json.loads(p.stdout or '{}').get('streams',[])
    ok=any(int(s.get('nb_read_packets','0').replace('N/A','0'))>0 for s in streams)
    probe.update(media_packets_received=ok,streams=streams)
    if ok:
     decode_args=['ffmpeg','-v','error','-rw_timeout','8000000']
     if os.environ.get('HTTPS_PROXY'):decode_args+=['-http_proxy',os.environ['HTTPS_PROXY']]
     if c['headers']:decode_args+=['-headers',''.join(k+': '+v+'\r\n' for k,v in c['headers'].items())]
     decode_args+=['-i',c['url'],'-t','2','-map','0:v:0','-an','-f','null','-','-progress','pipe:1']
     decoded=subprocess.run(decode_args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=18)
     progress=decoded.stdout.decode(errors='replace').splitlines()
     frames=max([int(v.split('=',1)[1]) for v in progress if v.startswith('frame=')]+[0])
     good=decoded.returncode==0 and frames>0
     probe.update(video_decoded=good,frames=frames)
     if good:r['media_decode_sample_passed']=True
   except Exception as e:probe.update(media_packets_received=False,error=type(e).__name__)
   r['samples'].append(probe)
   if sum(s.get('video_decoded',False) for s in r['samples'])>=2:break
 except Exception as e:r.update(format_ok=False,error=type(e).__name__)
 return r
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:results=list(pool.map(run,candidates))
report={'checked_at':datetime.now(timezone.utc).isoformat(),'scope':'public network from build environment via session proxy; not household ISP; bounded media packets plus software video decoding; not Android hardware playback','results':results}
(ROOT/'reports').mkdir(exist_ok=True)
(ROOT/'reports/live-media-probes-0.1.5.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
for r in results:print(r['name'],r.get('entries',0),'video samples decoded',sum(s.get('video_decoded',False) for s in r['samples']))
