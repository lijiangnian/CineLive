#!/usr/bin/env bash
# Takes an already verified PUBLIC bundle. Private bootstrap packages must never be passed here.
set -euo pipefail
: "${1:?Usage: publish_github.sh public-bundle-directory tag}"
: "${2:?Specify a version tag}"
bundle_dir="$1"
release_tag="$2"
python3 - "$bundle_dir" <<'PY'
import sys,json,zipfile,hashlib
from pathlib import Path
p=Path(sys.argv[1]);m=json.loads((p/'manifest.json').read_text())
for a in m['artifacts']:
 f=p/a['url'].rsplit('/',1)[-1]
 assert f.stat().st_size==a['size'] and hashlib.sha256(f.read_bytes()).hexdigest()==a['sha256']
 with zipfile.ZipFile(f) as z:assert 'assets/family-maintenance.txt' not in z.namelist(), 'Private bootstrap APK rejected'
assert list(p.glob('CineLive-source-*.zip')), 'Include matching GPL source snapshot'
PY
gh release create "$release_tag" --repo lijiangnian/CineLive --draft --title "CineLive $release_tag" --notes-file "$bundle_dir/RELEASE.md"
gh release upload "$release_tag" --repo lijiangnian/CineLive "$bundle_dir"/*.apk "$bundle_dir/manifest.json" "$bundle_dir"/CineLive-source-*.zip
gh release edit "$release_tag" --repo lijiangnian/CineLive --draft=false --latest
