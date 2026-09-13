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
JAR_NAME = 'oldways-launcher.jar'
VERSION_FILE = ROOT / 'launcher' / 'VERSION'
# Папка отгрузки: отсюда лаунчер тянет и сборку игры, и самого себя.
# Сервис читает версию прямо из этих файлов и отдаёт её по /api/launcher.
DIST_LAUNCHER = ROOT / 'dist' / 'launcher'

def missing():
    """Чего не хватает для сборки — с подсказкой, откуда это взять.

    Проверяем заранее: сказать «поставьте mingw-w64» полезнее, чем уронить
    сборку на полпути невнятной ошибкой компоновщика. Наборы зависят от
    системы: на Windows это распакованные рядом каталоги, на Linux —
    кросс-компилятор и JDK из пакетов.
    """
    suffix = '.exe' if os.name == 'nt' else ''
    gaps = []

    if not (ROOT / 'jre8' / 'bin').is_dir():
        gaps.append(('jre8/ — виндовая Java 8, её вкладывает в себя exe',
                     'python3 tools/fetch_jre.py'))

    home = os.environ.get('JAVA_HOME')
    jdk_here = (ROOT / 'jdk8' / 'bin' / ('javac' + suffix)).is_file()
    jdk_home = bool(home) and (Path(home) / 'bin' / ('javac' + suffix)).is_file()
    if not (jdk_here or jdk_home or shutil.which('javac')):
        gaps.append(('JDK 8 — им собирается jar',
                     'python tools/fetch_jre.py --jdk' if os.name == 'nt'
                     else 'sudo apt install openjdk-8-jdk-headless'))

    if os.name == 'nt':
        if not (ROOT / 'mingw64' / 'bin' / 'gcc.exe').is_file():
            gaps.append(('mingw64/ — им собирается запускатель',
                         'github.com/niXman/mingw-builds-binaries, '
                         'вариант posix-seh-msvcrt'))
    elif not shutil.which('x86_64-w64-mingw32-gcc'):
        gaps.append(('кросс-компилятор под Windows',
                     'sudo apt install mingw-w64'))
    return gaps


def read_version():
    return VERSION_FILE.read_text(encoding='utf8').strip()


def write_version(value):
    VERSION_FILE.write_text(value + '\n', encoding='utf8', newline='\n')


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


def place(source, target):
    """Положить файл на место через временное имя.

    Если копирование оборвётся, у игроков останется прежний рабочий файл,
    а не обрезанный: os.replace на одном томе меняет имя одним действием.
    """
    temporary = target.with_name(target.name + '.new')
    shutil.copy2(source, temporary)
    os.replace(temporary, target)
    return target


def main():
    ap = argparse.ArgumentParser(description='Сборка exe с выкладкой в папку выдачи.')
    ap.add_argument('--to', help='куда ещё положить exe, кроме папки отгрузки')
    ap.add_argument('--preset', type=int, default=6, help='сила сжатия LZMA, 0..9')
    ap.add_argument('--set', dest='set_version', metavar='X.Y.Z',
                    help='задать версию вместо подъёма младшей цифры')
    ap.add_argument('--keep-version', action='store_true',
                    help='пересобрать с той же версией (перевыкладка того же)')
    ap.add_argument('--no-dist', action='store_true',
                    help='не класть файлы в папку отгрузки (dist/launcher)')
    args = ap.parse_args()

    gaps = missing()
    if gaps:
        print('Не хватает для сборки:')
        for what, how in gaps:
            print(f'  {what}')
            print(f'      {how}')
        return 1

    # Папка отгрузки нужна всегда, а вот отдельная папка «для себя» — только
    # если попросили: на сервере exe и так оказывается там, откуда его качают.
    target_dir = None
    if args.to:
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

    target = place(built, target_dir / EXE_NAME) if target_dir else None

    # В отгрузку едут оба файла. Обычное обновление у игрока меняет только jar:
    # он весит две мегабайты против двадцати трёх, а exe — тот же запускатель
    # с той же Java внутри, менять его есть смысл, только когда меняются они.
    if not args.no_dist:
        DIST_LAUNCHER.mkdir(parents=True, exist_ok=True)
        place(built, DIST_LAUNCHER / EXE_NAME)
        place(ROOT / 'launcher' / 'build' / JAR_NAME, DIST_LAUNCHER / JAR_NAME)
        print(f'в отгрузке: {DIST_LAUNCHER}')

    where = target or (DIST_LAUNCHER / EXE_NAME)
    size = where.stat().st_size / 2 ** 20
    print(f'\nвыложено: {where} ({size:.1f} МБ, версия {version}, '
          f'{time.time() - started:.0f} с)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
