#!/usr/bin/env python3
"""
Сборка coremod, который правит адреса авторизации внутри игры.

Мод крошечный — два своих класса плюс ClassPatcher, взятый у лаунчера как
есть: разбирать constant pool дважды незачем. Собирается тем же JDK 8, но
под цель 6 — Forge 1.6.4 родом из времён Java 6, и запускать его могут на
чём угодно из той эпохи.

Нужны jar-ы Forge и launchwrapper: без них не с чем компилировать
IFMLLoadingPlugin и IClassTransformer. Оба лежат в зеркале — их кладёт
tools/fetch_forge.py.

    python tools/build_coremod.py
    python tools/build_coremod.py --out mirror/mods
"""

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR_NAME = 'oldways-auth-1.0.jar'
FORGE = ('libraries/net/minecraftforge/minecraftforge/9.11.1.1345/'
         'minecraftforge-9.11.1.1345.jar')
LAUNCHWRAPPER = 'libraries/net/minecraft/launchwrapper/1.8/launchwrapper-1.8.jar'

MANIFEST = '''Manifest-Version: 1.0
FMLCorePlugin: oldways.coremod.AuthPlugin
FMLCorePluginContainsFMLMod: false
'''


def main():
    ap = argparse.ArgumentParser(description='Сборка coremod Old Ways.')
    ap.add_argument('--jdk', default=str(ROOT / 'jdk8'))
    ap.add_argument('--mirror', default=str(ROOT / 'mirror'))
    ap.add_argument('--out', default=str(ROOT / 'mirror' / 'mods'))
    args = ap.parse_args()

    jdk = Path(args.jdk)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = jdk / 'bin' / ('javac' + suffix)
    jar = jdk / 'bin' / ('jar' + suffix)
    if not javac.is_file():
        # Системный JDK: на Linux его ставят пакетом, и требовать при этом
        # распакованный jdk8/ рядом с репозиторием было бы придиркой.
        found = shutil.which('javac')
        if not found:
            raise SystemExit(f'нет JDK 8: ни {jdk}, ни javac в PATH')
        jdk = Path(found).resolve().parent.parent
        javac = jdk / 'bin' / ('javac' + suffix)
        jar = jdk / 'bin' / ('jar' + suffix)

    mirror = Path(args.mirror)
    classpath = [mirror / FORGE, mirror / LAUNCHWRAPPER]
    missing = [p for p in classpath if not p.is_file()]
    if missing:
        print('нет библиотек Forge, начните с tools/fetch_forge.py:')
        for path in missing:
            print(f'  {path}')
        return 1

    build = ROOT / 'coremod' / 'build'
    classes = build / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)

    sources = sorted(str(p) for p in (ROOT / 'coremod' / 'src').rglob('*.java'))
    # ClassPatcher едет из лаунчера: один разбор class-файлов на два места
    sources.append(str(ROOT / 'launcher' / 'src' / 'oldways' / 'launcher' / 'ClassPatcher.java'))
    print(f'javac: {len(sources)} файлов')
    result = subprocess.run(
        [str(javac), '-encoding', 'UTF-8', '-nowarn',
         '-source', '6', '-target', '6', '-bootclasspath', str(jdk / 'jre' / 'lib' / 'rt.jar'),
         '-cp', os.pathsep.join(str(p) for p in classpath),
         '-d', str(classes)] + sources)
    if result.returncode:
        return result.returncode

    manifest = build / 'MANIFEST.MF'
    manifest.write_text(MANIFEST, encoding='ascii')

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    target = out / JAR_NAME
    subprocess.run([str(jar), 'cfm', str(target), str(manifest),
                    '-C', str(classes), '.'], check=True)
    print(f'готово: {target} ({target.stat().st_size / 1024:.0f} КБ)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
