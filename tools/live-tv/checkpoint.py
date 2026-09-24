from pathlib import Path
import zipfile,subprocess
root=Path('NuvioTV')
paths=subprocess.check_output(['git','-C',str(root),'diff','--name-only','HEAD'],text=True).splitlines()+subprocess.check_output(['git','-C',str(root),'ls-files','--others','--exclude-standard'],text=True).splitlines()
with zipfile.ZipFile('NuvioTV-LiveTV-checkpoint.zip','w',zipfile.ZIP_DEFLATED) as z:
 z.write('PORT-STATUS.md')
 for p in dict.fromkeys(paths):
  f=root/p
  if f.is_file():z.write(f)
 z.writestr('tracked.patch',subprocess.check_output(['git','-C',str(root),'diff','HEAD']))
