#!/usr/bin/env python3
"""Offline build against an installed IntelliJ IDEA 2026.3 (bundled JBR and Kotlin)."""
import os, pathlib, subprocess, zipfile, shutil, xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
IDE=pathlib.Path(os.environ.get('IDEA_HOME',str(pathlib.Path.home()/'Applications/IntelliJ IDEA.app/Contents')))
JDK=IDE/'jbr/Contents/Home'
BUILD=ROOT/'build'; CLASSES=BUILD/'classes'; HEADERS=BUILD/'headers'
VERSION=ET.parse(ROOT/'src/main/resources/META-INF/plugin.xml').getroot().findtext('version').strip()
ZIP=ROOT/'dist'/f'avplayer-preview-{VERSION}.zip'
for p in (CLASSES,HEADERS):
    shutil.rmtree(p,ignore_errors=True); p.mkdir(parents=True)
def run(*args): subprocess.run([str(x) for x in args],check=True)
build=(IDE/'Resources/build.txt').read_text().strip()
if not build.split('-')[-1].startswith('263.'):
    raise SystemExit('Expected IntelliJ IDEA 2026.3 / build 263; found '+build)
run(JDK/'bin/javac','--release','21','-h',HEADERS,'-d',CLASSES,*ROOT.glob('src/main/java/**/*.java'))
compiler=IDE/'plugins/Kotlin/kotlinc/lib'
cp=os.pathsep.join(map(str,[CLASSES,*sorted((IDE/'lib').rglob('*.jar'))]))
run(JDK/'bin/java','-cp',str(compiler/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-jvm-target','21','-no-stdlib','-no-reflect','-classpath',cp,'-d',CLASSES,*ROOT.glob('src/main/kotlin/**/*.kt'))
native=CLASSES/'native'; native.mkdir(exist_ok=True)
run('xcrun','clang++','-std=c++17','-fobjc-arc','-O2','-dynamiclib','-arch','arm64',
    '-mmacosx-version-min=13.0','-I'+str(JDK/'include'),'-I'+str(JDK/'include/darwin'),'-I'+str(HEADERS),
    ROOT/'native/NativePlayer.mm','-framework','AppKit','-framework','AVFoundation','-framework','CoreVideo',
    '-framework','CoreMedia','-framework','Accelerate','-o',native/'libavpreview.dylib')
run('codesign','--force','--sign','-',native/'libavpreview.dylib')
shutil.copytree(ROOT/'src/main/resources',CLASSES,dirs_exist_ok=True)
DIST=ROOT/'dist'; DIST.mkdir(exist_ok=True)
jar=BUILD/'avplayer-preview.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as out:
    for f in sorted(CLASSES.rglob('*')):
        if f.is_file(): out.write(f,f.relative_to(CLASSES))
with zipfile.ZipFile(ZIP,'w',zipfile.ZIP_DEFLATED) as out:
    out.write(jar,'avplayer-preview/lib/avplayer-preview.jar')
print('Built against',build,'→',ZIP)
