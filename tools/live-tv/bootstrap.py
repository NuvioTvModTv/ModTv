# Disposable CI setup; requires outbound access to official Android/Gradle/JDK hosts.
import os,pathlib,urllib.request,zipfile,tarfile,concurrent.futures,subprocess,urllib.parse,shutil
root=pathlib.Path('/workspace');sdk=root/'android-sdk';sdk.mkdir(exist_ok=True)
def get(item):
 name,url=item;p=root/name
 if p.exists() and p.stat().st_size>10000000:return p
 with urllib.request.urlopen(url,timeout=90) as r:
  with p.open('wb') as f: shutil.copyfileobj(r,f)
 print('Downloaded',name,flush=True);return p
jobs=[('jdk17.tar.gz','https://cdn.azul.com/zulu/bin/zulu17.58.21-ca-jdk17.0.15-linux_x64.tar.gz'),('gradle.zip','https://downloads.gradle.org/distributions/gradle-8.13-bin.zip'),('android-cli.zip','https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip')]
if (root/'jdk17/bin/java').exists():jobs=[j for j in jobs if j[0]!='jdk17.tar.gz']
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: list(pool.map(get,jobs))
if not (root/'jdk17').exists():
 with tarfile.open(root/'jdk17.tar.gz') as t:
  base=t.getmembers()[0].name.split('/')[0];t.extractall(root)
 (root/base).rename(root/'jdk17')
with zipfile.ZipFile(root/'gradle.zip') as z:z.extractall(root)
with zipfile.ZipFile(root/'android-cli.zip') as z:z.extractall(sdk/'cmdline-tools')
(sdk/'cmdline-tools/cmdline-tools').rename(sdk/'cmdline-tools/latest')
for directory in [root/'gradle-8.13/bin',sdk/'cmdline-tools/latest/bin']:
 for f in directory.iterdir():f.chmod(0o755)
shutil.copy(root/'jdk17/lib/security/cacerts',root/'gradle-cacerts')
subprocess.run([str(root/'jdk17/bin/keytool'),'-importcert','-noprompt','-alias','runtime-proxy','-keystore',str(root/'gradle-cacerts'),'-storepass','changeit','-file','/usr/local/share/ca-certificates/nebula-dns.crt'],check=True)
env=dict(os.environ,JAVA_HOME=str(root/'jdk17'),JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=/workspace/gradle-cacerts')
p=urllib.parse.urlparse(os.environ['HTTPS_PROXY'])
cmd=[str(sdk/'cmdline-tools/latest/bin/sdkmanager'),f'--sdk_root={sdk}','--proxy=http',f'--proxy_host={p.hostname}',f'--proxy_port={p.port}']
subprocess.run(cmd+['--licenses'],input='y\n'*100,text=True,env=env,check=True)
subprocess.run(cmd+['platforms;android-36','build-tools;35.0.0','platform-tools'],env=env,check=True)
pathlib.Path('NuvioTV/local.properties').write_text('sdk.dir=/workspace/android-sdk\n')
