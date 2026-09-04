#!/usr/bin/env python3
"""
Зеркалирование набора запуска Minecraft 1.6.4.

Клиентский jar сам по себе игру не запускает: нужны ещё 18 библиотек,
архивы нативов и 1120 файлов ассетов — всего около 168 МБ на трёх разных
хостах Mojang. Легаси-версии однажды перестанут раздавать, поэтому набор
зеркалируется целиком, пока это возможно.

В репозитории лежат только манифесты (client/*.json) и этот скрипт. Байты
качаются сюда и в git не попадают. Если раздача Mojang к тому моменту умрёт,
скрипт напечатает точный список: какой файл, с каким sha1 и по какому пути
положить вручную.

    python tools/fetch_client.py                # скачать недостающее
    python tools/fetch_client.py --check        # только проверить, не качать
    python tools/fetch_client.py --dest D:/mc   # другое место

Раскладка повторяет каноническую структуру лаунчера Minecraft, чтобы
собранное зеркало можно было отдать лаунчеру без переупаковки:

    versions/1.6.4/1.6.4.jar
    libraries/<путь из манифеста>
    assets/indexes/legacy.json
    assets/objects/<две буквы sha1>/<sha1>
"""

import argparse
import hashlib
import json
import shutil
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
VERSION_JSON = REPO / 'client' / '1.6.4.json'
ASSETS_JSON = REPO / 'client' / 'assets-legacy.json'
ASSET_BASE = 'https://resources.download.minecraft.net'

RETRIES = 3
TIMEOUT = 60


class Item:
    __slots__ = ('rel', 'url', 'sha1', 'size', 'kind')

    def __init__(self, rel, url, sha1, size, kind):
        self.rel, self.url, self.sha1, self.size, self.kind = rel, url, sha1, size, kind


def sha1_of(path):
    h = hashlib.sha1()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def build_list():
    """Собирает полный список файлов из закреплённых манифестов."""
    if not VERSION_JSON.is_file() or not ASSETS_JSON.is_file():
        raise SystemExit(f'нет манифестов в {REPO / "client"} — репозиторий скопирован не полностью')

    version = json.loads(VERSION_JSON.read_text(encoding='utf8'))
    assets = json.loads(ASSETS_JSON.read_text(encoding='utf8'))
    items = []

    client = version['downloads']['client']
    items.append(Item('versions/1.6.4/1.6.4.jar', client['url'], client['sha1'],
                      client['size'], 'клиент'))

    for lib in version['libraries']:
        downloads = lib.get('downloads', {})
        artifact = downloads.get('artifact')
        if artifact:
            items.append(Item('libraries/' + artifact['path'], artifact['url'],
                              artifact['sha1'], artifact['size'], 'библиотека'))
        # нативы лежат в classifiers; берём под все ОС, это всего пара мегабайт
        for name, entry in (downloads.get('classifiers') or {}).items():
            if 'natives' in name:
                items.append(Item('libraries/' + entry['path'], entry['url'],
                                  entry['sha1'], entry['size'], 'нативы'))

    index = version['assetIndex']
    items.append(Item('assets/indexes/legacy.json', index['url'], index['sha1'],
                      index['size'], 'индекс ассетов'))

    for obj in assets['objects'].values():
        h = obj['hash']
        items.append(Item(f'assets/objects/{h[:2]}/{h}',
                          f'{ASSET_BASE}/{h[:2]}/{h}', h, obj['size'], 'ассет'))

    # один и тот же ассет встречается под разными именами — дедуплицируем
    unique = {}
    for it in items:
        unique.setdefault(it.rel, it)
    return list(unique.values())


def fetch(item, dest):
    """Скачивает файл, если его нет или он битый. Возвращает 'ok' | 'skip' | текст ошибки."""
    target = dest / item.rel
    if target.is_file() and target.stat().st_size == item.size and sha1_of(target) == item.sha1:
        return 'skip'

    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + '.part')
    last = ''
    for attempt in range(1, RETRIES + 1):
        try:
            with urllib.request.urlopen(item.url, timeout=TIMEOUT) as r, open(tmp, 'wb') as f:
                shutil.copyfileobj(r, f, 1 << 20)
            got = sha1_of(tmp)
            if got != item.sha1:
                last = f'sha1 не совпал: ждали {item.sha1}, получили {got}'
                continue
            tmp.replace(target)
            return 'ok'
        except (urllib.error.URLError, OSError, TimeoutError) as exc:
            last = f'{type(exc).__name__}: {exc}'
    tmp.unlink(missing_ok=True)
    return last or 'неизвестная ошибка'


def write_inventory(dest, items):
    lines = ['# опись зеркала Minecraft 1.6.4 — sha1, размер, путь', '']
    for it in sorted(items, key=lambda x: x.rel):
        lines.append(f'{it.sha1}  {it.size:>9}  {it.rel}')
    total = sum(i.size for i in items)
    lines += ['', f'# файлов: {len(items)}, суммарно {total} байт ({total / 1e6:.2f} МБ)']
    (dest / 'CONTENTS.txt').write_text('\n'.join(lines) + '\n', encoding='utf8')


def main():
    ap = argparse.ArgumentParser(description='Зеркалирование набора запуска Minecraft 1.6.4.')
    ap.add_argument('--dest', default=str(REPO / 'mirror'), help='куда складывать (по умолчанию ./mirror)')
    ap.add_argument('--jobs', type=int, default=8, help='параллельных загрузок')
    ap.add_argument('--check', action='store_true', help='только проверить наличие, ничего не качать')
    args = ap.parse_args()

    dest = Path(args.dest)
    items = build_list()
    total = sum(i.size for i in items)
    print(f'набор: {len(items)} файлов, {total / 1e6:.2f} МБ -> {dest}')

    if args.check:
        missing = [i for i in items
                   if not (dest / i.rel).is_file()
                   or (dest / i.rel).stat().st_size != i.size
                   or sha1_of(dest / i.rel) != i.sha1]
        print(f'на месте: {len(items) - len(missing)}, отсутствует или битых: {len(missing)}')
        report_missing(missing)
        return 1 if missing else 0

    dest.mkdir(parents=True, exist_ok=True)
    done = {'ok': 0, 'skip': 0}
    failed = []
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for item, result in zip(items, pool.map(lambda i: fetch(i, dest), items)):
            if result in done:
                done[result] += 1
            else:
                failed.append((item, result))
            processed = done['ok'] + done['skip'] + len(failed)
            if processed % 100 == 0 or processed == len(items):
                print(f'  {processed}/{len(items)}  скачано {done["ok"]}, '
                      f'уже было {done["skip"]}, ошибок {len(failed)}')

    print(f'\nитог: скачано {done["ok"]}, пропущено {done["skip"]}, ошибок {len(failed)}')
    if not failed:
        write_inventory(dest, items)
        print(f'опись: {dest / "CONTENTS.txt"}')
        return 0

    report_missing([i for i, _ in failed], [e for _, e in failed])
    return 1


def report_missing(items, errors=None):
    if not items:
        return
    print('\n' + '=' * 72)
    print('НЕ УДАЛОСЬ ПОЛУЧИТЬ ФАЙЛЫ. Если раздача Mojang больше не работает,')
    print('возьмите их из другого зеркала или с рабочей машины и положите по')
    print('указанным путям — скрипт сверит sha1 при следующем запуске с --check.')
    print('=' * 72)
    for n, item in enumerate(items):
        print(f'\n{item.rel}')
        print(f'    тип   {item.kind}')
        print(f'    sha1  {item.sha1}')
        print(f'    байт  {item.size}')
        print(f'    ссылка {item.url}')
        if errors:
            print(f'    ошибка {errors[n]}')
    print(f'\nвсего не хватает: {len(items)}')


if __name__ == '__main__':
    sys.exit(main())
