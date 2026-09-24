# Local integration-test addon. Never installed into release builds automatically.
import pathlib,json,datetime,subprocess
p=pathlib.Path('/tmp/live-fixture');p.mkdir(exist_ok=True);base='http://10.0.2.2:8765'
subprocess.run(['ffmpeg','-y','-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc=size=320x180:rate=15','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','12','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-g','30','-c:a','aac','-f','hls','-hls_time','2','-hls_list_size','0',str(p/'live.m3u8')],check=True)
(p/'catalog/tv').mkdir(parents=True,exist_ok=True);(p/'stream/tv').mkdir(parents=True,exist_ok=True)
(p/'manifest.json').write_text(json.dumps({'id':'org.nuvio.test.livetv','name':'Live TV Validation','version':'1.0.0','description':'Local integration test fixture','types':['tv'],'resources':['catalog','stream'],'catalogs':[{'type':'tv','id':'live','name':'Test channels'}]}))
(p/'catalog/tv/live.json').write_text(json.dumps({'metas':[{'id':'one','type':'tv','name':'One HD','genres':['News']},{'id':'two','type':'tv','name':'Two','genres':['Sports']},{'id':'none','type':'tv','name':'No EPG or logo','genres':['News']}]}))
for c in ['one','two','none']:(p/f'stream/tv/{c}.json').write_text(json.dumps({'streams':[{'url':base+'/live.m3u8','name':'Local HLS','behaviorHints':{'proxyHeaders':{'request':{'User-Agent':'FixtureTV','Referer':base+'/'}}}}]}))
now=datetime.datetime.now(datetime.timezone.utc);stamp=lambda dt:dt.strftime('%Y%m%d%H%M%S %z')
xml='<tv>'
for c in ['one','two']:xml+=f'<channel id="{c}"><display-name>{c.title()}</display-name></channel>'
for c in ['one','two']:
 for i in range(-1,6):xml+=f'<programme channel="{c}" start="{stamp(now+datetime.timedelta(hours=i))}" stop="{stamp(now+datetime.timedelta(hours=i+1))}"><title>Program {i+2} {c}</title><desc>Local test programme description.</desc></programme>'
(p/'guide.xml').write_text(xml+'</tv>')
