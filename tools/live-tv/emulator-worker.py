# Keeps emulator and adb in the same process namespace; accepts adb-only JSON requests.
import subprocess,pathlib,time,json,os,urllib.parse
sdk=pathlib.Path('/workspace/android-sdk');p=urllib.parse.urlparse(os.environ['HTTPS_PROXY'])
env=dict(os.environ,JAVA_HOME='/workspace/jdk17',JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=/workspace/gradle-cacerts')
subprocess.run([str(sdk/'cmdline-tools/latest/bin/sdkmanager'),f'--sdk_root={sdk}','--proxy=http',f'--proxy_host={p.hostname}',f'--proxy_port={p.port}','emulator','system-images;android-28;android-tv;x86'],env=env,check=True)
subprocess.run([str(sdk/'cmdline-tools/latest/bin/avdmanager'),'create','avd','--force','-n','live-tv-api28','-k','system-images;android-28;android-tv;x86','-d','tv_1080p'],input='no\n',text=True,env=env,check=True)
import http.server,functools,threading
fixture=pathlib.Path('/tmp/live-fixture')
if fixture.exists():
 server=http.server.ThreadingHTTPServer(('0.0.0.0',8765),functools.partial(http.server.SimpleHTTPRequestHandler,directory=str(fixture)))
 threading.Thread(target=server.serve_forever,daemon=True).start()
root=pathlib.Path('/tmp/android-queue');root.mkdir(exist_ok=True)
adb=str(sdk/'platform-tools/adb')
subprocess.run([adb,'start-server'],check=True)
emu=subprocess.Popen([str(sdk/'emulator/emulator'),'-avd','live-tv-api28','-no-window','-no-audio','-no-boot-anim','-no-snapshot','-accel','off','-gpu','swiftshader_indirect','-memory','1024','-cores','2'])
try:
 while not (root/'stop').exists() and emu.poll() is None:
  for path in sorted(root.glob('*.request')):
   req=json.loads(path.read_text());path.rename(path.with_suffix('.running'))
   try:
    proc=subprocess.run([adb]+req['args'],capture_output=True,timeout=req.get('timeout',60))
    if req.get('binary'):pathlib.Path(req['binary']).write_bytes(proc.stdout);stdout='saved'
    else:stdout=proc.stdout.decode(errors='replace')
    result={'code':proc.returncode,'stdout':stdout,'stderr':proc.stderr.decode(errors='replace')}
   except Exception as e:result={'error':str(e)}
   path.with_suffix('.result').write_text(json.dumps(result))
  time.sleep(1)
finally:emu.terminate()
