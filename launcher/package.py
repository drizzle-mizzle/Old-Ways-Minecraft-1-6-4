#!/usr/bin/env python3
"""
Сборка одного exe: запускатель, рантайм Java и лаунчер в единственном файле.

Что получается на выходе: Old Ways.exe, который ничего не устанавливает.
При первом запуске он распаковывает Java и стартует лаунчер; дальше
запуск идёт сразу. Всё нажитое лежит в профиле пользователя, рядом
с exe не появляется ничего:

    %LOCALAPPDATA%\\Old Ways\\runtime\\   Java 8
    %LOCALAPPDATA%\\Old Ways\\game\\      клиент, ресурсы, настройки, миры

Что нужно для сборки (и то и другое в git не идёт):
    jdk8/     Temurin 8 JDK  — https://adoptium.net (как jre8, но JDK)
    mingw64/  MinGW-w64 gcc  — https://github.com/niXman/mingw-builds-binaries
              (x86_64 ... posix-seh-msvcrt: msvcrt, а не ucrt, чтобы exe
               работал и на Windows 7)

    python launcher/package.py
    python launcher/package.py --preset 9      # жать сильнее, собирать дольше
"""

import argparse
import lzma
import os
import re
import shutil
import struct
import subprocess
import sys
import time
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
EXE_NAME = 'Old Ways.exe'
TRAILER_MAGIC = b'OWPAY001'

# Из рантайма выбрасываем то, что игре и лаунчеру не нужно: средства разработки,
# запуск апплетов, движок JavaScript и данные локалей CLDR (Java 8 по умолчанию
# берёт локали не оттуда). Лицензии остаются на месте — они обязаны ехать с JRE.
JRE_DROP_FILES = [
    'bin/jjs.exe', 'bin/keytool.exe', 'bin/kinit.exe', 'bin/klist.exe',
    'bin/ktab.exe', 'bin/orbd.exe', 'bin/pack200.exe', 'bin/policytool.exe',
    'bin/rmid.exe', 'bin/rmiregistry.exe', 'bin/servertool.exe',
    'bin/tnameserv.exe', 'bin/unpack200.exe', 'bin/java-rmi.exe',
    'lib/ext/nashorn.jar', 'lib/ext/cldrdata.jar', 'lib/jfr.jar',
]
JRE_DROP_DIRS = ['lib/jfr', 'man', 'lib/missioncontrol', 'lib/visualvm']

MANIFEST = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <assemblyIdentity type="win32" name="OldWays.Launcher" version="1.0.0.0"/>
  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v3">
    <security>
      <requestedPrivileges>
        <requestedExecutionLevel level="asInvoker" uiAccess="false"/>
      </requestedPrivileges>
    </security>
  </trustInfo>
  <compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1">
    <application>
      <supportedOS Id="{e2011457-1546-43c5-a5fe-008deee3d3f0}"/>
      <supportedOS Id="{35138b9a-5d96-4fbd-8e2d-a2440225f93a}"/>
      <supportedOS Id="{4a2f28e3-53b9-4441-ba9c-d69d4a4a6e38}"/>
      <supportedOS Id="{1f676c76-80e1-4239-95bb-83d0f6d0da78}"/>
      <supportedOS Id="{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}"/>
    </application>
  </compatibility>
  <application xmlns="urn:schemas-microsoft-com:asm.v3">
    <windowsSettings>
      <dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true</dpiAware>
    </windowsSettings>
  </application>
</assembly>
'''

RC_TEMPLATE = '''1 ICON "{icon}"
1 24 "{manifest}"

1 VERSIONINFO
FILEVERSION {ver_comma}
PRODUCTVERSION {ver_comma}
FILEOS 0x4
FILETYPE 0x1
BEGIN
  BLOCK "StringFileInfo"
  BEGIN
    BLOCK "000004b0"
    BEGIN
      VALUE "CompanyName", "Old Ways"
      VALUE "FileDescription", "Old Ways - launcher for Minecraft 1.6.4"
      VALUE "FileVersion", "{ver}"
      VALUE "InternalName", "Old Ways"
      VALUE "OriginalFilename", "{exe}"
      VALUE "ProductName", "Old Ways"
      VALUE "ProductVersion", "{ver}"
    END
  END
  BLOCK "VarFileInfo"
  BEGIN
    VALUE "Translation", 0x0, 1200
  END
END
'''


def tool(root, *names):
    """Ищет утилиту в каталоге сборочного набора."""
    for name in names:
        path = root / 'bin' / name
        if path.is_file():
            return path
    raise SystemExit(f'не нашёл {names[0]} в {root}')


def launcher_version():
    text = (HERE / 'src/oldways/launcher/Main.java').read_text(encoding='utf8')
    found = re.search(r'VERSION\s*=\s*"([^"]+)"', text)
    return found.group(1) if found else '0'


def stage_runtime(jre, staging):
    """Копирует JRE в раздачу, выбрасывая ненужное."""
    runtime = staging / 'runtime'
    if runtime.exists():
        shutil.rmtree(runtime)
    shutil.copytree(jre, runtime)
    dropped = 0
    for name in JRE_DROP_FILES:
        path = runtime / name
        if path.is_file():
            dropped += path.stat().st_size
            path.unlink()
    for name in JRE_DROP_DIRS:
        path = runtime / name
        if path.is_dir():
            dropped += sum(f.stat().st_size for f in path.rglob('*') if f.is_file())
            shutil.rmtree(path)
    return runtime, dropped


def build_blob(staging):
    """Складывает дерево файлов в один поток записей для запускателя."""
    parts = []
    for path in sorted(staging.rglob('*')):
        name = path.relative_to(staging).as_posix().encode('utf8')
        parts.append(struct.pack('<I', len(name)))
        parts.append(name)
        if path.is_dir():
            parts.append(struct.pack('<BQ', 1, 0))
        else:
            data = path.read_bytes()
            parts.append(struct.pack('<BQ', 0, len(data)))
            parts.append(data)
    parts.append(struct.pack('<I', 0))    # конец цепочки
    return b''.join(parts)


def compile_stub(mingw, build, version, exe_name):
    gcc = tool(mingw, 'gcc.exe')
    windres = tool(mingw, 'windres.exe')

    (build / 'oldways.manifest').write_text(MANIFEST, encoding='utf8')
    ver_comma = ','.join((version.replace('-', '.').split('.') + ['0', '0', '0'])[:4])
    (build / 'oldways.rc').write_text(RC_TEMPLATE.format(
        icon=(HERE / 'stub/oldways.ico').as_posix(),
        manifest=(build / 'oldways.manifest').as_posix(),
        ver_comma=ver_comma, ver=version, exe=exe_name), encoding='utf8')

    subprocess.run([str(windres), str(build / 'oldways.rc'),
                    '-O', 'coff', '-o', str(build / 'oldways.res')], check=True)

    stub = build / 'stub.exe'
    subprocess.run([
        str(gcc), '-O2', '-s', '-mwindows', '-DUNICODE', '-D_UNICODE',
        # исходник в UTF-8, а строки сообщений должны стать UTF-16:
        # без этого Windows покажет в диалогах мусор
        '-finput-charset=UTF-8', '-fexec-charset=UTF-8',
        '-fwide-exec-charset=UTF-16LE',
        '-I', str(HERE / 'stub'),
        str(HERE / 'stub/oldways.c'), str(HERE / 'stub/LzmaDec.c'),
        str(build / 'oldways.res'), '-o', str(stub),
        '-lgdi32', '-luser32', '-lkernel32',
    ], check=True)
    return stub


def main():
    ap = argparse.ArgumentParser(description='Сборка Old Ways.exe с рантаймом внутри.')
    ap.add_argument('--jdk', default=str(ROOT / 'jdk8'))
    ap.add_argument('--jre', default=str(ROOT / 'jre8'), help='рантайм, который вложить')
    ap.add_argument('--mingw', default=str(ROOT / 'mingw64'))
    ap.add_argument('--preset', type=int, default=6, help='сила сжатия LZMA, 0..9')
    ap.add_argument('--out', default=str(HERE / 'build'))
    args = ap.parse_args()

    jre = Path(args.jre)
    mingw = Path(args.mingw)
    build = Path(args.out)
    if not jre.is_dir():
        raise SystemExit(f'нет рантайма: {jre}')
    if not (mingw / 'bin').is_dir():
        raise SystemExit(f'нет сборочного набора MinGW: {mingw}')
    build.mkdir(parents=True, exist_ok=True)

    print('== лаунчер ==')
    # Всегда с нуля: в каталоге классов иначе оседают файлы от прежних сборок
    # (переименованные пакеты, отладочные классы) и уезжают в раздачу.
    if subprocess.run([sys.executable, str(HERE / 'build.py'),
                       '--jdk', args.jdk, '--clean']).returncode:
        return 1
    jar = build / 'oldways-launcher.jar'
    if not jar.is_file():
        raise SystemExit(f'нет собранного лаунчера: {jar}')

    print('== рантайм ==')
    staging = build / 'staging'
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    runtime, dropped = stage_runtime(jre, staging)
    shutil.copy2(jar, staging / 'oldways-launcher.jar')
    total = sum(f.stat().st_size for f in staging.rglob('*') if f.is_file())
    print(f'   вложено {total / 2 ** 20:.1f} МБ, выброшено лишнего '
          f'{dropped / 2 ** 20:.1f} МБ')

    print('== сжатие ==')
    started = time.time()
    blob = build_blob(staging)
    packed = lzma.compress(blob, format=lzma.FORMAT_ALONE, preset=args.preset)
    # Питон в одиночном вызове ставит в заголовок «размер неизвестен»
    # (восемь байт FF). Запускателю размер нужен заранее — он под него
    # выделяет память, поэтому проставляем настоящий.
    packed = packed[:5] + struct.pack('<Q', len(blob)) + packed[13:]
    print(f'   {len(blob) / 2 ** 20:.1f} МБ -> {len(packed) / 2 ** 20:.1f} МБ '
          f'за {time.time() - started:.0f} с')

    print('== запускатель ==')
    version = launcher_version()
    stub = compile_stub(mingw, build, version, EXE_NAME)
    stub_size = stub.stat().st_size
    print(f'   {stub_size / 1024:.0f} КБ, версия {version}')

    target = build / EXE_NAME
    with open(target, 'wb') as out:
        out.write(stub.read_bytes())
        out.write(packed)
        out.write(struct.pack('<QQI', stub_size, len(packed), zlib.crc32(packed)))
        out.write(TRAILER_MAGIC)

    shutil.rmtree(staging)
    print(f'\nготово: {target} ({target.stat().st_size / 2 ** 20:.1f} МБ)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
