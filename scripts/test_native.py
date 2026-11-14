#!/usr/bin/env python3
"""Generate a disposable fixture (FFmpeg used only by tests), exercise the real JNI bridge."""
import os,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parents[1]
ide=pathlib.Path(os.environ.get('IDEA_HOME',str(pathlib.Path.home()/'Applications/IntelliJ IDEA.app/Contents')))
jdk=ide/'jbr/Contents/Home'; classes=root/'build/classes'; fixture=root/'build/日本語 sample.mp4'
test_classes=root/'build/test-classes'; test_classes.mkdir(exist_ok=True)
def run(*args,**kwargs): subprocess.run([str(x) for x in args],check=True,**kwargs)
def ffmpeg(*args): run('ffmpeg','-hide_banner','-loglevel','error','-y',*args)
ffmpeg('-f','lavfi','-i','testsrc2=size=1920x1080:rate=30','-f','lavfi','-i','sine=frequency=440:sample_rate=48000',
       '-t','5','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-c:a','aac',fixture)
# Left half red, right half blue: catches R/B swaps in the BGRA -> xRGB path.
colors=root/'build/color-halves.mp4'
ffmpeg('-f','lavfi','-i','color=c=red:size=640x360:rate=30,drawbox=x=320:y=0:w=320:h=360:color=blue:t=fill','-t','2',
       '-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p',colors)
# Metadata fixtures: NTSC 29.97 fps in QuickTime, then the same stream rotated 90° CCW / mirrored.
ntsc=root/'build/ntsc.mov'; rotated=root/'build/rotated.mp4'; mirrored=root/'build/mirrored.mp4'
ffmpeg('-f','lavfi','-i','testsrc2=size=640x360:rate=30000/1001','-t','1','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p',ntsc)
metadata=[ntsc]
# -display_rotation / -display_hflip need FFmpeg 7+; older versions skip only the rotation checks.
options=subprocess.run(['ffmpeg','-hide_banner','-h','full'],capture_output=True,text=True).stdout
if 'display_rotation' in options and 'display_hflip' in options:
    ffmpeg('-display_rotation','90','-i',ntsc,'-c','copy',rotated)
    ffmpeg('-display_hflip','-i',ntsc,'-c','copy',mirrored)
    metadata+=[rotated,mirrored]
else:
    print('SKIP rotation/mirroring info checks: this ffmpeg lacks -display_rotation/-display_hflip (FFmpeg 7+)')
# HDR fixtures: one mid-gray code value tagged BT.709 vs PQ; the player must tone-map PQ, not pass it through.
hdr_props=[]
if 'libx265' in subprocess.run(['ffmpeg','-hide_banner','-encoders'],capture_output=True,text=True).stdout:
    gray_sdr=root/'build/gray-sdr.mp4'; gray_pq=root/'build/gray-pq.mp4'
    for out,(prim,trc,matrix) in ((gray_sdr,('bt709','bt709','bt709')),(gray_pq,('bt2020','smpte2084','bt2020nc'))):
        ffmpeg('-f','lavfi','-i','color=c=0x828282:size=640x360:rate=30','-t','1','-c:v','libx265','-preset','ultrafast',
               '-pix_fmt','yuv420p10le','-tag:v','hvc1','-color_primaries',prim,'-color_trc',trc,'-colorspace',matrix,
               '-x265-params',f'log-level=error:colorprim={prim}:transfer={trc}:colormatrix={matrix}',out)
    hdr_props=[f'-Davpreview.hdrPair={gray_sdr}|{gray_pq}']
else:
    print('SKIP HDR to SDR check: this ffmpeg lacks libx265')
run(jdk/'bin/javac','--release','21','-cp',classes,'-d',test_classes,root/'tests/NativeSmoke.java')
run(jdk/'bin/java','--enable-native-access=ALL-UNNAMED','-Davpreview.headlessSmoke=true','-Djava.awt.headless=true',*hdr_props,'-cp',os.pathsep.join(map(str,[classes,test_classes])),'NativeSmoke',fixture,colors,*metadata,timeout=60)
