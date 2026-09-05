#!/usr/bin/env python3
"""
Зеркалирование того, чего не хватает Forge 1.6.4 поверх ванильного набора.

Forge требует те же библиотеки, что и ваниль, плюс шесть своих: сам forge,
launchwrapper, asm, две библиотеки scala и lzma. Часть лежит у Mojang, часть
на maven Forge, а сам forge приходится брать из universal-сборки: по пути
библиотеки его на maven нет — так его раскладывал установщик Forge.

Проверка: у каждого файла сверяется sha1 из client/forge-1.6.4.lock. Файла
нет — считается и записывается, поэтому первый запуск фиксирует набор,
а все следующие ловят подмену.

    python tools/fetch_forge.py             # докачать недостающее
    python tools/fetch_forge.py --check     # только проверить
"""

import argparse
import hashlib
import io
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FORGE_JSON = ROOT / 'client' / 'forge-1.6.4.json'
LOCK = ROOT / 'client' / 'forge-1.6.4.lock'
FORGE_VERSION = '1.6.4-9.11.1.1345'

MOJANG = 'https://libraries.minecraft.net/'
FORGE_MAVEN = 'https://maven.minecraftforge.net/'
UNIVERSAL = (FORGE_MAVEN + 'net/minecraftforge/forge/' + FORGE_VERSION
             + '/forge-' + FORGE_VERSION + '-universal.jar')

# Ванильные библиотеки уже в зеркале — их кладёт tools/fetch_client.py
VANILLA_PREFIXES = (
    'argo/', 'com/', 'commons-io/', 'net/java/', 'net/sf/', 'org/apache/',
    'org/bouncycastle/', 'org/lwjgl/',
)


def maven_path(name):
    """net.minecraft:launchwrapper:1.8 -> net/minecraft/launchwrapper/1.8/launchwrapper-1.8.jar"""
    group, artifact, version = name.split(':')
    return '{0}/{1}/{2}/{1}-{2}.jar'.format(group.replace('.', '/'), artifact, version)


def sha1_of(data):
    return hashlib.sha1(data).hexdigest()


def fetch(url):
    # maven Forge отвечает 403 на безымянного клиента
    request = urllib.request.Request(url, headers={'User-Agent': 'old-ways-mirror/1.0'})
    with urllib.request.urlopen(request, timeout=180) as answer:
        return answer.read()


def universal_jar():
    """Сам forge: качается как universal, а в зеркало ложится как библиотека."""
    data = fetch(UNIVERSAL)
    # заодно убеждаемся, что внутри тот самый набор, а не редирект-заглушка
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if 'version.json' not in archive.namelist():
            raise SystemExit('в universal-сборке Forge нет version.json — чужой файл?')
    return data


def main():
    ap = argparse.ArgumentParser(description='Зеркалирование библиотек Forge 1.6.4.')
    ap.add_argument('--dest', default=str(ROOT / 'mirror'))
    ap.add_argument('--check', action='store_true', help='не качать, только проверить')
    args = ap.parse_args()

    dest = Path(args.dest)
    profile = json.loads(FORGE_JSON.read_text(encoding='utf8'))
    lock = json.loads(LOCK.read_text(encoding='utf8')) if LOCK.is_file() else {}

    wanted = []
    for lib in profile['libraries']:
        path = maven_path(lib['name'])
        if path.startswith(VANILLA_PREFIXES):
            continue
        if lib['name'].startswith('net.minecraftforge:minecraftforge:'):
            source = None                      # особый случай: universal-сборка
        elif lib.get('url'):
            source = FORGE_MAVEN + path
        else:
            source = MOJANG + path
        wanted.append((path, source))

    bad = 0
    for path, source in wanted:
        target = dest / 'libraries' / path
        if target.is_file():
            digest = sha1_of(target.read_bytes())
        elif args.check:
            print(f'нет файла: {path}')
            bad += 1
            continue
        else:
            print(f'качаю {path}')
            data = universal_jar() if source is None else fetch(source)
            digest = sha1_of(data)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

        known = lock.get(path)
        if known is None:
            lock[path] = digest
            print(f'  запомнил sha1 {digest}')
        elif known != digest:
            print(f'  СХОДИТСЯ НЕ ТО: {path}\n   ждали {known}\n   вышло {digest}')
            bad += 1

    LOCK.write_text(json.dumps(lock, indent=1, sort_keys=True) + '\n', encoding='utf8')
    print(f'\nбиблиотек Forge: {len(wanted)}, расхождений {bad}')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
