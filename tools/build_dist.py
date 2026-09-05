#!/usr/bin/env python3
"""
Сборка раздачи клиента: из зеркала делает каталог, который отдаёт сервер.

Зачем отдельный шаг. Манифест Mojang описывает набор запуска правилами
(`rules`, `natives`, классификаторы), и разбирать их пришлось бы в лаунчере —
на каждой машине заново, одинаково. Вместо этого правила раскрываются здесь,
один раз, и лаунчер получает готовые списки: вот classpath для Windows, вот
natives, вот файлы с их хешами. Лаунчеру остаётся качать и сверять.

Клиент в раздаче — базовый, ванильный. Адрес нашего сервера в него не
вписывается: с Forge это невозможно (FML сверяет классы со своими двоичными
заплатками), поэтому строки правит coremod прямо при загрузке классов —
mirror/mods/oldways-auth-1.0.jar, его собирает tools/build_coremod.py.

Моды берутся из mirror/mods и раздаются с путём minecraft/mods: лаунчер
кладёт файлы туда же, куда качает всё остальное, и папка модов у игрока
собирается сама.

Использование:
    python tools/build_dist.py --mirror mirror --out dist
    python tools/build_dist.py --out dist --check     # сверить готовую раздачу
    python tools/build_dist.py --vanilla              # раздача без Forge и модов
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


def maven_path(name):
    """net.minecraft:launchwrapper:1.8 -> net/minecraft/launchwrapper/1.8/launchwrapper-1.8.jar"""
    group, artifact, version = name.split(':')
    return '{0}/{1}/{2}/{1}-{2}.jar'.format(group.replace('.', '/'), artifact, version)


def forge_libraries(profile, lock, mirror):
    """Библиотеки, которые есть у Forge и нет у ванили, в порядке из его профиля.

    Хеши берутся из client/forge-1.6.4.lock: профиль Forge их не несёт —
    он старого образца, где вместо хешей были только имена и maven-адреса.
    """
    out = []
    for lib in profile['libraries']:
        path = 'libraries/' + maven_path(lib['name'])
        sha1 = lock.get(path[len('libraries/'):])
        if sha1 is None:
            continue                       # ванильная библиотека, она уже собрана
        source = mirror / path
        if not source.is_file():
            raise SystemExit(f'нет библиотеки Forge: {source}, начните с tools/fetch_forge.py')
        out.append((path, sha1, source.stat().st_size))
    return out


def mods(directory):
    """Файлы модов из зеркала: путь в раздаче — minecraft/mods/<имя>."""
    if not directory.is_dir():
        return []
    out = []
    for path in sorted(directory.iterdir()):
        if not path.is_file() or path.suffix.lower() not in ('.jar', '.zip'):
            continue
        out.append(('minecraft/mods/' + path.name, sha1_file(path), path.stat().st_size, path))
    return out


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
    ap.add_argument('--forge', default='client/forge-1.6.4.json')
    ap.add_argument('--forge-lock', default='client/forge-1.6.4.lock')
    ap.add_argument('--mods', default=None, help='папка с модами (по умолчанию <зеркало>/mods)')
    ap.add_argument('--vanilla', action='store_true',
                    help='собрать раздачу без Forge и модов')
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

    main_class = version['mainClass']
    arguments = version['minecraftArguments']
    mod_files = []
    if not args.vanilla:
        # Forge меняет и точку входа, и аргументы: игру запускает launchwrapper,
        # а FML цепляется к ней через tweakClass.
        profile = json.loads(Path(args.forge).read_text(encoding='utf8'))
        lock = json.loads(Path(args.forge_lock).read_text(encoding='utf8'))
        main_class = profile['mainClass']
        arguments = profile['minecraftArguments']
        extra = forge_libraries(profile, lock, mirror)
        for path, sha1, size in extra:
            files[path] = [sha1, size, None]
        for os_name in TARGET_OS:
            # библиотеки Forge идут первыми — так их раскладывал его установщик
            classpath[os_name] = [path for path, _, _ in extra] + classpath[os_name]
        mod_files = mods(Path(args.mods) if args.mods else mirror / 'mods')
        for path, sha1, size, _ in mod_files:
            files[path] = [sha1, size, None]
        print(f'Forge: +{len(extra)} библиотек, модов {len(mod_files)}')

    print(f'в наборе {len(files)} файлов')

    # моды и библиотеки Forge лежат в зеркале не по пути раздачи
    sources = {path: source for path, _, _, source in mod_files}

    missing, entries, total_bytes = [], [], 0
    for path in sorted(files):
        sha1, size, os_set = files[path]
        src = sources.get(path, mirror / path)
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
        'mainClass': main_class,
        'arguments': arguments,
        'assets': version['assets'],
        'client': client_path,
        # С Forge клиент правит coremod при загрузке классов, а не лаунчер
        # на диске: изменённый jar не переживёт двоичных заплаток FML.
        'patchClient': args.vanilla,
        'classpath': classpath,
        'natives': natives,
        'files': entries,
    }
    (out / 'manifest.json').write_text(
        json.dumps(manifest, indent=1, ensure_ascii=False), encoding='utf8')

    print(f'раздача собрана: {out} ({total_bytes / 2 ** 20:.1f} МБ)')
    if mod_files:
        print('моды: ' + ', '.join(Path(path).name for path, _, _, _ in mod_files))
    print(f'classpath: windows {len(classpath["windows"])} библиотек, '
          f'natives {len(natives["windows"])}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
