"""Generate a release manifest from inspected, signed APKs. No secrets are read or uploaded."""
import argparse, hashlib, json, os, re, subprocess, zipfile
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',action='append',required=True,help='flavor:abi:/path/file.apk')
    parser.add_argument('--base-url',required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--sdk',type=Path,default=Path(os.environ.get('ANDROID_HOME','/missing-sdk')))
    parser.add_argument('--expected-cert',required=True)
    parser.add_argument('--allow-private',action='store_true',help='Only for private family NAS/Drive distribution')
    args=parser.parse_args()
    tools=args.sdk/'build-tools/36.0.0'
    artifacts=[];identity=None;seen=set();minimum=0
    for entry in args.apk:
        flavor,abi,name=entry.split(':',2);file=Path(name)
        if (flavor,abi) in seen:raise SystemExit('Duplicate artifact')
        seen.add((flavor,abi))
        with zipfile.ZipFile(file) as apk:
            if not args.allow_private and 'assets/family-maintenance.txt' in apk.namelist():raise SystemExit('Refusing private profile in public APK')
            if not any(s.startswith('lib/'+abi+'/') for s in apk.namelist()):raise SystemExit('Wrong APK architecture')
        badging=subprocess.check_output([str(tools/'aapt'),'dump','badging',str(file)],text=True)
        match=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging)
        if not match or match[1]!='dev.cineshelf.stream':raise SystemExit('Wrong application')
        current=match.groups()
        if identity and current!=identity:raise SystemExit('Mixed release versions')
        identity=current
        minimum=max(minimum,int(re.search(r"sdkVersion:'(\d+)'",badging)[1]))
        tree=subprocess.check_output([str(tools/'aapt'),'dump','xmltree',str(file),'AndroidManifest.xml'],text=True)
        if not re.search(r'dev.cineshelf.device[^\n]*\n[^\n]*"'+re.escape(flavor)+'"',tree):raise SystemExit('Wrong device flavor')
        cert=subprocess.check_output([str(tools/'apksigner'),'verify','--print-certs',str(file)],text=True)
        if args.expected_cert.lower() not in cert.lower():raise SystemExit('Signing certificate mismatch')
        artifacts.append(dict(flavor=flavor,abi=abi,url=args.base_url.rstrip('/')+'/'+file.name,sha256=hashlib.sha256(file.read_bytes()).hexdigest(),size=file.stat().st_size))
    manifest=dict(schemaVersion=1,packageName=identity[0],channel='stream-stable',versionCode=int(identity[1]),versionName=identity[2],minSdk=minimum,
                  notes='修复电视导航空白；新增 GitHub / 极空间更新、后台下载及隔离更新线路。',artifacts=artifacts)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    temp=args.output.with_suffix('.tmp');temp.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');temp.replace(args.output);args.output.chmod(0o644)
    print('Verified release manifest:',args.output)
if __name__=='__main__':main()
