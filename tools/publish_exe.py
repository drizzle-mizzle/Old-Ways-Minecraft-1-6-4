#!/usr/bin/env python3
"""
Собрать Old Ways.exe и положить готовый файл в папку выдачи.

Обёртка над launcher/package.py: та собирает exe в launcher/build, а здесь
он ещё и переезжает туда, откуда его раздают игрокам. Отдельный шаг нужен
ровно за этим — package.py кладёт рядом с exe промежуточные файлы сборки
(jar, объектники запускателя), и в папке выдачи им делать нечего.

    python tools/publish_exe.py --to "C:/Users/flower/Desktop/OldWaysPublish"
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

# Наборы, без которых собирать нечем: в git они не идут, поэтому проверяем
# заранее и говорим, что именно скачать, а не роняем сборку на полпути.
NEEDED = [
    ('jdk8', 'Temurin 8 JDK — https://adoptium.net'),
    ('jre8', 'Temurin 8 JRE — https://adoptium.net'),
    ('mingw64', 'MinGW-w64 gcc (вариант msvcrt) — '
                'https://github.com/niXman/mingw-builds-binaries'),
]


def main():
    ap = argparse.ArgumentParser(description='Сборка exe с выкладкой в папку выдачи.')
    ap.add_argument('--to', required=True, help='куда положить готовый exe')
    ap.add_argument('--preset', type=int, default=6, help='сила сжатия LZMA, 0..9')
    args = ap.parse_args()

    missing = [(name, hint) for name, hint in NEEDED if not (ROOT / name).is_dir()]
    if missing:
        print('Не хватает наборов для сборки:')
        for name, hint in missing:
            print(f'  {ROOT / name} — {hint}')
        return 1

    target_dir = Path(args.to).resolve()
    target_dir.mkdir(parents=True, exist_ok=True)

    started = time.time()
    code = subprocess.run([sys.executable, str(ROOT / 'launcher' / 'package.py'),
                           '--preset', str(args.preset)], cwd=str(ROOT)).returncode
    if code:
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
    print(f'\nвыложено: {target} ({size:.1f} МБ, {time.time() - started:.0f} с)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
