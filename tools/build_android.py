"""Build both FongMi flavors with the pinned player source; never signs or publishes a release."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
from urllib.parse import urlsplit

MEDIA_REVISION = "3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d"


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-dir", type=Path, default=root)
    parser.add_argument("--media-dir", type=Path, default=root / "scratch/fongmi-media")
    parser.add_argument("--sdk-dir", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(root / "scratch/android-sdk"))))
    parser.add_argument("--python", default=shutil.which("python3.10"))
    parser.add_argument("--trust-store", type=Path)
    parser.add_argument("--china-mirror", action="store_true")
    parser.add_argument("--maintenance-private-assets-dir", type=Path, help="External private assets directory; never include in source archive")
    parser.add_argument("--tv-abi", choices=["armeabi-v7a", "arm64-v8a"], default="armeabi-v7a")
    parser.add_argument("--phone-abi", choices=["armeabi-v7a", "arm64-v8a"], default="arm64-v8a")
    parser.add_argument("--log", type=Path, default=root / "reports/android-build.log")
    args = parser.parse_args()
    if not args.python:
        parser.error("Python 3.10 is required by Chaquopy; provide --python /path/to/python3.10")
    if not (args.sdk_dir / "platforms/android-37.0/android.jar").is_file():
        parser.error("Install Android SDK platforms;android-37.0 and build-tools;36.0.0 first")
    if not (args.media_dir / "settings.gradle.kts").is_file():
        parser.error("Clone FongMi/media release-1.11.0-fongmi first (see docs/BASELINE.md)")
    revision = subprocess.check_output(["git", "-C", str(args.media_dir), "rev-parse", "HEAD"], text=True).strip()
    if revision != MEDIA_REVISION:
        parser.error(f"Unverified player revision: expected {MEDIA_REVISION}")
    command = ["bash", "gradlew", "--no-daemon", "--max-workers=4",
               f"-PfongmiMediaDir={args.media_dir.resolve()}", f"-PbuildPython={args.python}",
               "-I", "gradle/media-compat.init.gradle"]
    if args.maintenance_private_assets_dir:
        private = args.maintenance_private_assets_dir.resolve()
        if not (private / "family-maintenance.txt").is_file():
            parser.error("Private maintenance profile file is missing")
        if private.is_relative_to(args.android_dir.resolve()):
            parser.error("Keep private assets outside the source tree")
        command += [f"-PmaintenancePrivateAssetsDir={private}"]
    if args.china_mirror:
        command += ["-PchinaMavenMirror=true", "-I", "gradle/china-mirror.init.gradle"]
    # The Java process does not automatically honor HTTPS_PROXY. Keep the inherited
    # proxy route and normal certificate verification; never use trust-all flags.
    proxy = urlsplit(os.environ.get("HTTPS_PROXY", ""))
    if proxy.hostname:
        for scheme in ("http", "https"):
            command += [f"-D{scheme}.proxyHost={proxy.hostname}", f"-D{scheme}.proxyPort={proxy.port or 80}"]
    if args.trust_store:
        if not args.trust_store.is_file():
            parser.error("Java trust store does not exist")
        command += [f"-Djavax.net.ssl.trustStore={args.trust_store.resolve()}"]
    command += ["-Djava.util.concurrent.ForkJoinPool.common.parallelism=16"]
    env = os.environ.copy()
    env["ANDROID_HOME"] = str(args.sdk_dir.resolve())
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with args.log.open("w") as output:
        # Avoid AGP signing/archive executor starvation when multiple ABI archives close together.
        for flavor, abi in [("Leanback", args.tv_abi), ("Mobile", args.phone_abi)]:
            result = subprocess.run(command + [f"-PapkAbis={abi}", f":app:assemble{flavor}Debug"], cwd=args.android_dir, env=env, stdout=output, stderr=subprocess.STDOUT)
            if result.returncode: break
    print(f"Android build exit: {result.returncode}; log: {args.log}")
    if result.returncode:
        print(args.log.read_text(errors="replace")[-8000:])
    else:
        for flavor, abi in [("leanback", args.tv_abi), ("mobile", args.phone_abi)]:
            print(args.android_dir / f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-{abi}-debug.apk")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
