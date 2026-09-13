#!/usr/bin/env python3
"""
Java для лаунчера: рантайм, который вкладывается в exe, и JDK, которым он собирается.

В репозиторий они не идут — это сотни мегабайт чужих бинарников. Раньше их
качали руками с adoptium.net, что нормально на своей машине и неудобно на
сервере, где всё делается командами.

    python3 tools/fetch_jre.py            # jre8/  — Java 8 под Windows, её вложит exe
    python3 tools/fetch_jre.py --jdk      # jdk8/  — JDK 8 под текущую систему
    python3 tools/fetch_jre.py --jdk --os linux

Рантайм всегда нужен **виндовый**, даже когда сборка идёт на Linux: внутрь exe
вкладывается та Java, на которой лаунчер будет работать у игрока. А JDK нужен
той системы, где идёт сборка, — он только компилирует.

Берём Temurin через API Adoptium: ссылка и sha256 приходят оттуда же, так что
скачанное сверяется, а не принимается на веру.
"""

import argparse
import hashlib
import json
import shutil
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
API = 'https://api.adoptium.net/v3/assets/latest/8/hotspot'
AGENT = 'old-ways-mirror/1.0'


def ask(image: str, system: str) -> dict:
    """Что Adoptium предлагает для этой системы: ссылка, размер, sha256."""
    url = f'{API}?os={system}&architecture=x64&image_type={image}&vendor=eclipse'
    request = urllib.request.Request(url, headers={'User-Agent': AGENT})
    with urllib.request.urlopen(request, timeout=60) as answer:
        assets = json.load(answer)
    if not assets:
        raise SystemExit(f'Adoptium не знает сборки {image} под {system}')
    package = assets[0]['binary']['package']
    return {'name': assets[0]['release_name'], 'link': package['link'],
            'size': package.get('size', 0), 'sha256': package['checksum']}


def download(url: str, target: Path, expected: str) -> None:
    request = urllib.request.Request(url, headers={'User-Agent': AGENT})
    digest = hashlib.sha256()
    with urllib.request.urlopen(request, timeout=120) as answer, target.open('wb') as out:
        while True:
            chunk = answer.read(1 << 20)
            if not chunk:
                break
            digest.update(chunk)
            out.write(chunk)
            # В журнале сервера бегущая строка превращается в сотню строк,
            # поэтому шевелимся только когда на нас смотрят.
            if sys.stdout.isatty():
                print(f'\r   {target.stat().st_size / 2 ** 20:.0f} МБ', end='', flush=True)
    if sys.stdout.isatty():
        print()
    got = digest.hexdigest()
    if got != expected:
        target.unlink()
        raise SystemExit(f'сумма не сошлась: {got} вместо {expected}')


def unpack(archive: Path, into: Path) -> None:
    """Распаковать, выбросив верхний каталог вида jdk8u452-b09-jre."""
    staging = into.parent / (into.name + '.new')
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    if archive.suffix == '.zip':
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(staging)
    else:
        with tarfile.open(archive) as tf:
            tf.extractall(staging)

    inside = [path for path in staging.iterdir()]
    source = inside[0] if len(inside) == 1 and inside[0].is_dir() else staging
    if into.exists():
        shutil.rmtree(into)
    source.rename(into)
    if staging.exists():
        shutil.rmtree(staging, ignore_errors=True)


def main() -> int:
    ap = argparse.ArgumentParser(description='Скачать Java 8 для сборки лаунчера.')
    ap.add_argument('--jdk', action='store_true',
                    help='JDK для сборки вместо рантайма для вложения')
    ap.add_argument('--os', dest='system', default=None,
                    help='система: windows, linux, mac (по умолчанию — что нужно)')
    ap.add_argument('--dest', default=None, help='куда распаковать')
    ap.add_argument('--force', action='store_true', help='перекачать, даже если уже есть')
    args = ap.parse_args()

    image = 'jdk' if args.jdk else 'jre'
    # Рантайм вкладывается в exe и работает у игрока — он всегда виндовый.
    # JDK только компилирует, поэтому берётся под систему сборки.
    default_os = ('linux' if sys.platform.startswith('linux')
                  else 'mac' if sys.platform == 'darwin' else 'windows')
    system = args.system or (default_os if args.jdk else 'windows')
    dest = Path(args.dest) if args.dest else ROOT / (f'{image}8')

    marker = dest / 'bin' / ('javac' if args.jdk else 'java')
    if not args.force and (marker.is_file() or marker.with_suffix('.exe').is_file()):
        print(f'{dest} уже на месте — ключ --force, чтобы перекачать')
        return 0

    found = ask(image, system)
    print(f'{found["name"]}: {image} под {system}, {found["size"] / 2 ** 20:.0f} МБ')
    archive = ROOT / ('.' + found['link'].rsplit('/', 1)[-1])
    try:
        download(found['link'], archive, found['sha256'])
        print(f'   распаковка в {dest}')
        unpack(archive, dest)
    finally:
        if archive.exists():
            archive.unlink()
    print(f'готово: {dest}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
