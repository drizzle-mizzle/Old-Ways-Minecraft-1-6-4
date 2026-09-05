#!/usr/bin/env python3
"""
Сборка раздачи клиента: из зеркала делает каталог, который отдаёт сервер.

Зачем отдельный шаг. Манифест Mojang описывает набор запуска правилами
(`rules`, `natives`, классификаторы), и разбирать их пришлось бы в лаунчере —
на каждой машине заново, одинаково. Вместо этого правила раскрываются здесь,
один раз, и лаунчер получает готовые списки: вот classpath для Windows, вот
natives, вот файлы с их хешами. Лаунчеру остаётся качать и сверять.

Клиент в раздаче — базовый: ванильный, без адреса нашего сервера. Адрес
лаунчер вписывает сам при первом запуске (порт tools/patch_jars.py на Java),
поэтому смена домена не требует пересборки раздачи.

Использование:
    python tools/build_dist.py --mirror mirror --out dist
    python tools/build_dist.py --out dist --check     # сверить готовую раздачу
"""

import argparse
import hashlib
import json
import os
import shutil
import sys
import time
from pathlib import Path

TARGET_OS = ('windows', 'linux', 'osx')


def sha1_file(path, chunk=1 << 20):
    h = hashlib.sha1()
    with open(path, 'rb') as f:
        while True:
            block = f.read(chunk)
            if not block:
                break
            h.update(block)
    return h.hexdigest()


def rule_allows(rules, os_name):
    """Раскрывает `rules` манифеста Mojang для конкретной ОС.

    Правило без `os` действует всегда, с `os` — только для своей системы.
    Условие по версии ОС (у Mojang им отсечён древний osx 10.5) считаем
    несовпадающим: версия машины игрока на этапе сборки неизвестна.
    """
    if not rules:
        return True
    allowed = False
    for rule in rules:
        cond = rule.get('os')
        if cond is not None:
            if cond.get('name') != os_name or 'version' in cond:
                continue
        allowed = rule['action'] == 'allow'
    return allowed


def collect(version, assets_index):
    """Разбирает манифесты в (файлы, classpath по ОС, natives по ОС, путь клиента).

    files: путь -> [sha1, размер, множество ОС или None, если файл нужен всем]
    """
    files = {}
    classpath = {name: [] for name in TARGET_OS}
    natives = {name: [] for name in TARGET_OS}

    def need(path, sha1, size, os_name):
        old = files.get(path)
        if old is None:
            files[path] = [sha1, size, None if os_name is None else {os_name}]
            return
        if old[0] != sha1:
            raise SystemExit(f'один путь с разным содержимым: {path}')
        if os_name is None or old[2] is None:
            old[2] = None
        else:
            old[2].add(os_name)

    for lib in version['libraries']:
        rules = lib.get('rules')
        downloads = lib.get('downloads', {})
        artifact = downloads.get('artifact')
        classifiers = downloads.get('classifiers', {})
        for os_name in TARGET_OS:
            if not rule_allows(rules, os_name):
                continue
            if artifact:
                # `path` в манифесте Mojang отсчитывается от каталога библиотек
                path = 'libraries/' + artifact['path']
                need(path, artifact['sha1'], artifact['size'], os_name)
                classpath[os_name].append(path)
            native_key = (lib.get('natives') or {}).get(os_name)
            if native_key and native_key in classifiers:
                art = classifiers[native_key]
                path = 'libraries/' + art['path']
                need(path, art['sha1'], art['size'], os_name)
                natives[os_name].append(path)

    client = version['downloads']['client']
    client_path = 'versions/{0}/{0}.jar'.format(version['id'])
    need(client_path, client['sha1'], client['size'], None)

    index = version['assetIndex']
    need('assets/indexes/%s.json' % version['assets'],
         index['sha1'], index['size'], None)
    for obj in assets_index['objects'].values():
        digest = obj['hash']
        need('assets/objects/%s/%s' % (digest[:2], digest), digest, obj['size'], None)

    return files, classpath, natives, client_path


def place(src, dst):
    """Кладёт файл в раздачу: жёсткой ссылкой, если можно, иначе копией."""
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        dst.unlink()
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def check(out):
    manifest = json.loads((out / 'manifest.json').read_text(encoding='utf8'))
    bad = 0
    for entry in manifest['files']:
        path = out / 'files' / entry['path']
        if not path.is_file():
            print(f'нет файла: {entry["path"]}')
            bad += 1
        elif path.stat().st_size != entry['size']:
            print(f'размер не сходится: {entry["path"]}')
            bad += 1
        elif sha1_file(path) != entry['sha1']:
            print(f'хеш не сходится: {entry["path"]}')
            bad += 1
    print(f'проверено {len(manifest["files"])}, расхождений {bad}')
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(description='Сборка раздачи клиента из зеркала.')
    ap.add_argument('--mirror', default='mirror', help='зеркало набора запуска')
    ap.add_argument('--out', default='dist', help='куда сложить раздачу')
    ap.add_argument('--version', default='client/1.6.4.json')
    ap.add_argument('--assets', default='client/assets-legacy.json')
    ap.add_argument('--name', default='Old Ways', help='имя сборки в манифесте')
    ap.add_argument('--check', action='store_true',
                    help='не собирать, а сверить готовую раздачу с её манифестом')
    args = ap.parse_args()

    out = Path(args.out)
    if args.check:
        return check(out)

    version = json.loads(Path(args.version).read_text(encoding='utf8'))
    assets_index = json.loads(Path(args.assets).read_text(encoding='utf8'))
    mirror = Path(args.mirror)

    files, classpath, natives, client_path = collect(version, assets_index)
    print(f'в наборе {len(files)} файлов')

    missing, entries, total_bytes = [], [], 0
    for path in sorted(files):
        sha1, size, os_set = files[path]
        src = mirror / path
        if not src.is_file():
            missing.append(path)
            continue
        place(src, out / 'files' / path)
        entry = {'path': path, 'sha1': sha1, 'size': size}
        if os_set is not None:
            entry['os'] = sorted(os_set)
        entries.append(entry)
        total_bytes += size

    if missing:
        print(f'\nв зеркале нет {len(missing)} файлов, начните с tools/fetch_client.py:')
        for path in missing[:10]:
            print(f'  {path}')
        if len(missing) > 10:
            print(f'  … и ещё {len(missing) - 10}')
        return 1

    manifest = {
        'format': 1,
        'name': args.name,
        'id': version['id'],
        'built': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'mainClass': version['mainClass'],
        'arguments': version['minecraftArguments'],
        'assets': version['assets'],
        'client': client_path,
        'classpath': classpath,
        'natives': natives,
        'files': entries,
    }
    (out / 'manifest.json').write_text(
        json.dumps(manifest, indent=1, ensure_ascii=False), encoding='utf8')

    print(f'раздача собрана: {out} ({total_bytes / 2 ** 20:.1f} МБ)')
    print(f'classpath: windows {len(classpath["windows"])} библиотек, '
          f'natives {len(natives["windows"])}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
