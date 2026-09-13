#!/usr/bin/env python3
"""
Собрать Old Ways.exe и положить готовый файл в папку выдачи.

Обёртка над launcher/package.py: та собирает exe в launcher/build, а здесь
он ещё и переезжает туда, откуда его раздают игрокам. Отдельный шаг нужен
ровно за этим — package.py кладёт рядом с exe промежуточные файлы сборки
(jar, объектники запускателя), и в папке выдачи им делать нечего.

Здесь же поднимается версия: каждая выкладка — новый номер, младшая цифра
прибавляется сама. На каждой сборке подряд её поднимать не стоит — отладочных
сборок за день десятки, и номера перестали бы что-либо значить; выкладка же
ровно одна на то, что увидят игроки.

    python tools/publish_exe.py --to "C:/Users/flower/Desktop/OldWaysPublish"
    python tools/publish_exe.py --to ... --set 0.3.0     # крупная перемена
    python tools/publish_exe.py --to ... --keep-version  # пересобрать то же самое
"""

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXE_NAME = 'Old Ways.exe'
VERSION_FILE = ROOT / 'launcher' / 'VERSION'

# Наборы, без которых собирать нечем: в git они не идут, поэтому проверяем
# заранее и говорим, что именно скачать, а не роняем сборку на полпути.
NEEDED = [
    ('jdk8', 'Temurin 8 JDK — https://adoptium.net'),
    ('jre8', 'Temurin 8 JRE — https://adoptium.net'),
    ('mingw64', 'MinGW-w64 gcc (вариант msvcrt) — '
                'https://github.com/niXman/mingw-builds-binaries'),
]


def read_version():
    return VERSION_FILE.read_text(encoding='utf8').strip()


def write_version(value):
    VERSION_FILE.write_text(value + '\n', encoding='utf8')


def next_version(current):
    """Поднять младшую цифру: 0.2.9 -> 0.2.10, счёт числом, а не строкой."""
    parts = (current.split('-')[0].split('.') + ['0', '0', '0'])[:3]
    try:
        numbers = [int(part) for part in parts]
    except ValueError:
        raise SystemExit(f'в {VERSION_FILE} не разобрать версию: {current!r}')
    numbers[2] += 1
    return '.'.join(str(number) for number in numbers)


def check_version(value):
    parts = value.split('.')
    if len(parts) != 3 or not all(part.isdigit() for part in parts):
        raise SystemExit(f'версия должна быть вида 1.2.3, а не {value!r}')
    return value


def main():
    ap = argparse.ArgumentParser(description='Сборка exe с выкладкой в папку выдачи.')
    ap.add_argument('--to', required=True, help='куда положить готовый exe')
    ap.add_argument('--preset', type=int, default=6, help='сила сжатия LZMA, 0..9')
    ap.add_argument('--set', dest='set_version', metavar='X.Y.Z',
                    help='задать версию вместо подъёма младшей цифры')
    ap.add_argument('--keep-version', action='store_true',
                    help='пересобрать с той же версией (перевыкладка того же)')
    args = ap.parse_args()

    missing = [(name, hint) for name, hint in NEEDED if not (ROOT / name).is_dir()]
    if missing:
        print('Не хватает наборов для сборки:')
        for name, hint in missing:
            print(f'  {ROOT / name} — {hint}')
        return 1

    target_dir = Path(args.to).resolve()
    target_dir.mkdir(parents=True, exist_ok=True)

    previous = read_version()
    if args.keep_version:
        version = previous
        print(f'версия: {version} (без подъёма)')
    else:
        version = check_version(args.set_version) if args.set_version else next_version(previous)
        write_version(version)
        print(f'версия: {previous} -> {version}')

    started = time.time()
    code = subprocess.run([sys.executable, str(ROOT / 'launcher' / 'package.py'),
                           '--preset', str(args.preset)], cwd=str(ROOT)).returncode
    if code:
        # Номер, которого нет ни в одном собранном файле, только путал бы:
        # следующая выкладка получила бы дырку в счёте.
        if version != previous:
            write_version(previous)
            print(f'версия возвращена на {previous}')
        print('\nСборка не удалась — файл в папке выдачи остался прежним.')
        return code

    built = ROOT / 'launcher' / 'build' / EXE_NAME
    if not built.is_file():
        print(f'\nСборка прошла, но файла нет: {built}')
        return 1

    # Кладём через временное имя: если копирование оборвётся, у игроков
    # останется прежний рабочий exe, а не обрезанный.
    target = target_dir / EXE_NAME
    temporary = target_dir / (EXE_NAME + '.new')
    shutil.copy2(built, temporary)
    os.replace(temporary, target)

    size = target.stat().st_size / 2 ** 20
    print(f'\nвыложено: {target} ({size:.1f} МБ, версия {version}, '
          f'{time.time() - started:.0f} с)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
