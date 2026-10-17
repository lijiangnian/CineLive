"""Read-only TVBox configuration inspection. Never loads remote executable code."""
import argparse
import concurrent.futures
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import json5

MAX_BYTES = 4 * 1024 * 1024


def wire_url(url):
    p = urllib.parse.urlsplit(url)
    if p.scheme not in ("http", "https") or not p.hostname or p.username or p.password:
        raise ValueError("expected HTTP(S) URL without embedded credentials")
    host = p.hostname.encode("idna").decode("ascii")
    if ":" in host:
        host = "[" + host + "]"
    if p.port:
        host += ":" + str(p.port)
    return urllib.parse.urlunsplit((p.scheme, host,
        urllib.parse.quote(p.path, safe="/%:@"),
        urllib.parse.quote(p.query, safe="%=&+/:?@"), ""))


def fetch(url, timeout=12):
    request = urllib.request.Request(wire_url(url), headers={
        "User-Agent": "CineShelf-Research/0.1", "Accept-Encoding": "identity"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read(MAX_BYTES + 1)
        if len(body) > MAX_BYTES:
            raise ValueError("response exceeds 4 MiB limit")
        return body, response.geturl(), response.status


def reference(value, base):
    if not isinstance(value, str):
        return None
    if value.startswith(("./", "../", "http://", "https://", "/")):
        return urllib.parse.urljoin(base, value.split(";md5;", 1)[0])
    return None


def inspect_config(body, base):
    text = body.decode("utf-8-sig")
    try:
        data = json.loads(text)
        syntax = "json"
    except json.JSONDecodeError:
        data = json5.loads(text, allow_duplicate_keys=False)
        syntax = "json5"
    if not isinstance(data, dict):
        raise ValueError("configuration root must be an object")
    if data.get("msg"):
        raise ValueError("configuration reports an upstream error")
    sites = data.get("sites", [])
    depots = data.get("urls", [])
    lives = data.get("lives", [])
    if any(not isinstance(v, list) for v in (sites, depots, lives)):
        raise ValueError("sites, urls and lives must be arrays")
    if not any((sites, depots, lives)):
        raise ValueError("no nonempty sites, urls or lives")
    valid_sites = [s for s in sites if isinstance(s, dict)
                   and isinstance(s.get("key"), str) and s["key"].strip()
                   and isinstance(s.get("api"), str) and s["api"].strip()]
    valid_depots = [s for s in depots if isinstance(s, dict)
                    and reference(s.get("url"), base)]
    if depots and not valid_depots:
        raise ValueError("depot has no valid configuration URLs")
    if sites and not valid_sites:
        raise ValueError("no valid site definitions")
    keys = Counter(s["key"] for s in valid_sites)
    dependencies = set()
    if ref := reference(data.get("spider"), base):
        dependencies.add(ref)
    for s in valid_sites:
        for field in ("api", "jar", "ext"):
            if ref := reference(s.get(field), base):
                dependencies.add(ref)
    return {
        "syntax": syntax,
        "kind": "depot" if depots else ("vod" if sites else "live"),
        "site_count": len(sites), "valid_site_count": len(valid_sites),
        "invalid_site_count": len(sites) - len(valid_sites),
        "duplicate_site_keys": sorted(k for k, n in keys.items() if n > 1),
        "site_types": dict(Counter(str(s.get("type", 0)) for s in valid_sites)),
        "depot_count": len(depots), "live_group_count": len(lives),
        "has_spider": bool(data.get("spider")),
        "dependencies": sorted(dependencies),
        "search_verified": False, "playback_verified": False,
        "dependency_execution": "not_performed",
    }


def probe(source):
    attempts = []
    for url in source["urls"]:
        start = time.monotonic()
        attempt = {"url": url}
        try:
            body, final_url, status = fetch(url)
            attempt.update(http_status=status, final_url=final_url,
                           bytes=len(body), sha256=hashlib.sha256(body).hexdigest())
            attempt["inspection"] = inspect_config(body, final_url)
            attempt["status"] = "configuration_parsed"
        except urllib.error.HTTPError as e:
            attempt.update(status="http_error", http_status=e.code)
        except (ValueError, UnicodeError) as e:
            attempt.update(status="invalid_or_unsupported_config", error=type(e).__name__)
        except Exception as e:
            attempt.update(status="network_error", error=type(e).__name__)
        attempt["elapsed_ms"] = round((time.monotonic() - start) * 1000)
        attempts.append(attempt)
        if attempt["status"] == "configuration_parsed":
            break
    return {**source, "status": attempts[-1]["status"] if attempts else "no_url",
            "attempts": attempts}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("catalog", type=Path)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    catalog = json.loads(args.catalog.read_text())
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(probe, catalog["sources"]))
    report = {"checked_at": datetime.now(timezone.utc).isoformat(),
              "scope": "Configuration bytes and structure only; no Spider execution, search or playback.",
              "summary": dict(Counter(r["status"] for r in results)), "results": results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report["summary"], ensure_ascii=False))


if __name__ == "__main__":
    main()
