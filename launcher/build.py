#!/usr/bin/env python3
"""
Сборка лаунчера: javac + jar, без Maven и Gradle.

Зависимостей у лаунчера нет, исходников полтора десятка файлов — система
сборки тут была бы тяжелее самой программы. Нужен JDK 8: собирать надо тем
же рантаймом, на котором лаунчер будет работать у игрока.

    python launcher/build.py            # соберёт launcher/build/oldways-launcher.jar
    python launcher/build.py --run --   --cli --address localhost:8080 ...
"""

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
MAIN_CLASS = 'oldways.launcher.Main'
JAR_NAME = 'oldways-launcher.jar'
VERSION_FILE = HERE / 'VERSION'


def version():
    """Единственное место, где живёт номер версии."""
    return VERSION_FILE.read_text(encoding='utf8').strip()


def find_jdk(explicit):
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    if os.environ.get('JAVA_HOME'):
        candidates.append(Path(os.environ['JAVA_HOME']))
    candidates.append(ROOT / 'jdk8')
    for path in candidates:
        javac = path / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
        if javac.is_file():
            return path
    raise SystemExit('не нашёл JDK 8: положите его в jdk8/ или укажите --jdk')


def main():
    ap = argparse.ArgumentParser(description='Сборка лаунчера Old Ways.')
    ap.add_argument('--jdk', help='каталог JDK 8')
    ap.add_argument('--clean', action='store_true', help='пересобрать с нуля')
    ap.add_argument('--run', action='store_true', help='запустить после сборки')
    ap.add_argument('rest', nargs=argparse.REMAINDER, help='аргументы запуска после --')
    args = ap.parse_args()

    jdk = find_jdk(args.jdk)
    suffix = '.exe' if os.name == 'nt' else ''
    javac = jdk / 'bin' / ('javac' + suffix)
    jar = jdk / 'bin' / ('jar' + suffix)
    java = jdk / 'bin' / ('java' + suffix)

    build = HERE / 'build'
    classes = build / 'classes'
    if args.clean and build.exists():
        shutil.rmtree(build)
    classes.mkdir(parents=True, exist_ok=True)

    sources = sorted(str(p) for p in (HERE / 'src').rglob('*.java'))
    print(f'javac: {len(sources)} файлов, {jdk.name}')
    result = subprocess.run(
        [str(javac), '-encoding', 'UTF-8', '-Xlint:all',
         '-source', '8', '-target', '8', '-d', str(classes)] + sources)
    if result.returncode:
        return result.returncode

    # Картинки, шрифты и лицензия к ним едут внутри jar: лаунчер должен
    # оставаться одним файлом, который не от чего отвязать. Копируем их
    # к классам, а не передаём jar вторым каталогом: два дерева с общим
    # верхним каталогом дают ему duplicate entry.
    resources = HERE / 'resources'
    if resources.is_dir():
        shutil.copytree(resources, classes, dirs_exist_ok=True)

    # Версия попадает в jar дважды, и оба раза нужны. Ресурсом — её читает сам
    # лаунчер (Version.CURRENT) и на запуске из каталога классов, и из jar.
    # В манифесте — её читает сервис обновлений, которому разбирать class-файлы
    # ради одной строки было бы странно.
    ver = version()
    resource = classes / 'oldways' / 'launcher' / 'VERSION'
    resource.parent.mkdir(parents=True, exist_ok=True)
    resource.write_text(ver + '\n', encoding='utf8', newline='\n')
    manifest = build / 'MANIFEST.MF'
    manifest.write_text(
        'Manifest-Version: 1.0\n'
        f'Main-Class: {MAIN_CLASS}\n'
        'Implementation-Title: Old Ways Launcher\n'
        f'Implementation-Version: {ver}\n', encoding='utf8', newline='\n')

    target = build / JAR_NAME
    subprocess.run([str(jar), 'cfm', str(target), str(manifest), '-C', str(classes), '.'],
                   check=True)
    print(f'готово: {target} ({target.stat().st_size / 1024:.0f} КБ), версия {ver}')

    if args.run:
        rest = args.rest[1:] if args.rest[:1] == ['--'] else args.rest
        return subprocess.run([str(java), '-jar', str(target)] + rest).returncode
    return 0


if __name__ == '__main__':
    sys.exit(main())
