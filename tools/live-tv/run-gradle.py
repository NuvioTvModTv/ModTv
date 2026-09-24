# CI launcher for this isolated runtime. Normal builds should use ./gradlew.
import os,sys,subprocess,urllib.parse,pathlib
if '-I' in sys.argv:
 i=sys.argv.index('-I')+1;sys.argv[i]=str((pathlib.Path('NuvioTV')/sys.argv[i]).resolve())
p=urllib.parse.urlparse(os.environ['HTTPS_PROXY'])
env=dict(os.environ,JAVA_HOME=('/workspace/zulu17' if pathlib.Path('/workspace/zulu17').exists() else '/workspace/jdk17'),GRADLE_USER_HOME='/workspace/gradle-home',CI_USE_DEBUG_SIGNING='true',TZ='UTC')
args=['/workspace/gradle-8.13/bin/gradle','-p','NuvioTV','--no-daemon','--max-workers=1','-Pkotlin.compiler.execution.strategy=in-process','-Dorg.gradle.jvmargs=-Xmx5120m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8',f'-Dhttps.proxyHost={p.hostname}',f'-Dhttps.proxyPort={p.port}',f'-Dhttp.proxyHost={p.hostname}',f'-Dhttp.proxyPort={p.port}','-Djavax.net.ssl.trustStore=/workspace/gradle-cacerts']+sys.argv[1:]
raise SystemExit(subprocess.call(args,env=env))
