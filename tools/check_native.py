"""Compile and execute the Android-independent native source-maintenance checks."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import argparse


def main():
    root=Path(__file__).resolve().parent.parent
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-dir',type=Path,default=root)
    args=parser.parse_args()
    javac=shutil.which('javac')
    if not javac and os.environ.get('JAVA_HOME'):
        candidate=Path(os.environ['JAVA_HOME'])/'bin/javac'
        if candidate.is_file():javac=str(candidate)
    if not javac:
        candidates=sorted((Path.home()/'.gradle/jdks').glob('*/bin/javac'))
        if candidates:javac=str(candidates[0])
    if not javac:raise SystemExit('JDK 17+ is required; set JAVA_HOME or put javac on PATH')
    java=str(Path(javac).resolve().with_name('java'))
    base=args.android_dir/'app/src'
    files=[base/'main/java/com/fongmi/android/tv/source/UpdateAddress.java',
           base/'main/java/com/fongmi/android/tv/source/SourceMaintenance.java',
           base/'test/java/com/fongmi/android/tv/source/SourceMaintenanceCheck.java',
           base/'main/java/com/fongmi/android/tv/source/ReleaseArtifact.java',
           base/'test/java/com/fongmi/android/tv/source/ReleaseArtifactCheck.java',
           base/'main/java/com/fongmi/android/tv/source/SourceStore.java',
           base/'test/java/com/fongmi/android/tv/source/SourceStoreCheck.java',
           base/'main/java/com/fongmi/android/tv/source/DeviceAddress.java',
           base/'test/java/com/fongmi/android/tv/source/DeviceAddressCheck.java']
    files += [base/'main/java/com/fongmi/android/tv/source/LiveProbeEvidence.java',
              base/'test/java/com/fongmi/android/tv/source/LiveProbeEvidenceCheck.java',
              base/'test/java/com/fongmi/android/tv/source/SourceStoreConcurrencyCheck.java']
    failover=args.android_dir/'tools/tests/SourceFailoverCheck.java'
    if failover.is_file():
        files += [base/'main/java/com/fongmi/android/tv/source/SourceFailover.java',failover]
    with tempfile.TemporaryDirectory(prefix='cineshelf-native-') as output:
        subprocess.run([javac,'--release','17','-d',output,*map(str,files)],check=True)
        subprocess.run([java,'-cp',output,'com.fongmi.android.tv.source.SourceMaintenanceCheck'],check=True)
        subprocess.run([java,'-cp',output,'com.fongmi.android.tv.source.ReleaseArtifactCheck'],check=True)
        subprocess.run([java,'-cp',output,'com.fongmi.android.tv.source.SourceStoreCheck'],check=True)
        subprocess.run([java,'-cp',output,'com.fongmi.android.tv.source.DeviceAddressCheck'],check=True)
        for name in ['LiveProbeEvidenceCheck','SourceStoreConcurrencyCheck']:
            subprocess.run([java,'-cp',output,'com.fongmi.android.tv.source.'+name],check=True)
        if failover.is_file():
            subprocess.run([java,'-cp',output,'com.fongmi.android.tv.source.SourceFailoverCheck'],check=True)


if __name__=='__main__':main()
