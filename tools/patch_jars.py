#!/usr/bin/env python3
"""
Переадресация авторизации и скинов Minecraft 1.6.4 на свои серверы.

Зачем это вообще возможно. В 1.6.4 ещё нет authlib: клиент и сервер ходят
за авторизацией и текстурами по обычному HTTP на адреса, зашитые в байткод
простыми строковыми константами. Ни подписей текстур, ни проверки ключей.
Вся поверхность — четыре константы:

    клиент  http://session.minecraft.net/game/joinserver.jsp?user=
    клиент  http://skins.minecraft.net/MinecraftSkins/%s.png
    клиент  http://skins.minecraft.net/MinecraftCloaks/%s.png
    сервер  http://session.minecraft.net/game/checkserver.jsp?user=

Константы в class-файле адресуются по индексу в constant pool, а не по
смещению в байтах, поэтому строку можно заменить на другой длины — достаточно
переписать пул целиком и оставить остальное как есть. Никакого ASM не нужно.

Клиентский jar подписан Mojang (META-INF/MOJANGCS.*). После правки классов
подпись становится недействительной, и Java откажется грузить классы, поэтому
подпись снимается — ровно то же делают при установке модов на 1.6.4.

Адрес меняется сколько угодно раз. Константа опознаётся по хвосту пути,
а не по адресу Mojang, поэтому уже пропатченный джарник можно перепатчить
на новый хост — это и делает адрес настраиваемым, без пересборки раздачи.

Использование:
    python tools/patch_jars.py --input client.jar --output client-patched.jar \\
        --auth-base http://localhost:8080 --skin-base http://localhost:8080

    python tools/patch_jars.py --input client.jar --show      # текущие адреса
    python tools/patch_jars.py --input server.jar --dry-run   # без записи
"""

import argparse
import shutil
import struct
import sys
import zipfile
from pathlib import Path

MAGIC = b'\xca\xfe\xba\xbe'

# Записи constant pool, кроме CONSTANT_Utf8: тег -> размер полезной нагрузки.
_FIXED_SIZES = {
    3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4,
    11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2,
}
_WIDE_TAGS = (5, 6)  # long и double занимают две позиции в пуле

SIG_SUFFIXES = ('.SF', '.DSA', '.RSA', '.EC')


class ClassFileError(Exception):
    pass


def _read_pool(data):
    """Разбирает constant pool. Возвращает (записи, смещение конца пула).

    Запись: (tag, payload_bytes). Для Utf8 payload — сама строка без префикса длины.
    """
    if data[:4] != MAGIC:
        raise ClassFileError('не class-файл: нет сигнатуры 0xCAFEBABE')
    count = struct.unpack_from('>H', data, 8)[0]
    entries, off, index = [], 10, 1
    while index < count:
        tag = data[off]
        if tag == 1:
            length = struct.unpack_from('>H', data, off + 1)[0]
            payload = data[off + 3:off + 3 + length]
            off += 3 + length
        elif tag in _FIXED_SIZES:
            size = _FIXED_SIZES[tag]
            payload = data[off + 1:off + 1 + size]
            off += 1 + size
        else:
            raise ClassFileError(f'неизвестный тег constant pool {tag} на смещении {off}')
        entries.append((tag, payload))
        index += 2 if tag in _WIDE_TAGS else 1
    return entries, off


def _write_pool(entries):
    out = bytearray()
    for tag, payload in entries:
        out.append(tag)
        if tag == 1:
            out += struct.pack('>H', len(payload))
        out += payload
    return bytes(out)


def _validate(data):
    """Проходит class-файл до конца. Ловит порчу структуры, а не только пула."""
    entries, off = _read_pool(data)

    def u2():
        nonlocal off
        v = struct.unpack_from('>H', data, off)[0]
        off += 2
        return v

    def u4():
        nonlocal off
        v = struct.unpack_from('>I', data, off)[0]
        off += 4
        return v

    def attributes():
        for _ in range(u2()):
            u2()                 # attribute_name_index
            length = u4()
            nonlocal off
            off += length

    u2(); u2(); u2()             # access_flags, this_class, super_class
    for _ in range(u2()):        # interfaces
        u2()
    for _ in range(u2()):        # fields
        u2(); u2(); u2()
        attributes()
    for _ in range(u2()):        # methods
        u2(); u2(); u2()
        attributes()
    attributes()                 # атрибуты класса

    if off != len(data):
        raise ClassFileError(f'разбор закончился на {off}, а файл длиной {len(data)}')
    return entries


def patch_class(data, auth, skin):
    """Переписывает адреса в константах. Возвращает (новые байты, список замен)."""
    entries = _validate(data)
    _, pool_end = _read_pool(data)

    applied, patched = [], []
    for tag, payload in entries:
        new = rewrite_url(payload, auth, skin) if tag == 1 else None
        if new is not None:
            applied.append((payload.decode(), new.decode()))
            patched.append((tag, new))
        else:
            patched.append((tag, payload))

    if not applied:
        return data, []

    result = data[:8] + struct.pack('>H', struct.unpack_from('>H', data, 8)[0]) \
        + _write_pool(patched) + data[pool_end:]
    _validate(result)             # круговая проверка: файл всё ещё разбирается целиком
    return result, applied


# Опознаём константу по хвосту пути, а не по полному адресу Mojang. Иначе
# патч работает ровно один раз: во второй заход строки уже наши, оригинальных
# в джарнике нет, и патчер молча ничего не находит. С суффиксами адрес можно
# менять сколько угодно раз — это и делает его настраиваемым.
URL_SUFFIXES = {
    '/game/joinserver.jsp?user=': 'auth',
    '/game/checkserver.jsp?user=': 'auth',
    '/MinecraftSkins/%s.png': 'skin',
    '/MinecraftCloaks/%s.png': 'skin',
}


def rewrite_url(value: bytes, auth: str, skin: str):
    """Новый адрес для константы или None, если она нас не касается."""
    try:
        text = value.decode('utf8')
    except UnicodeDecodeError:
        return None
    if not text.startswith(('http://', 'https://')):
        return None
    for suffix, kind in URL_SUFFIXES.items():
        if text.endswith(suffix):
            base = (auth if kind == 'auth' else skin).rstrip('/')
            new = base + suffix
            return None if new == text else new.encode()
    return None


def find_urls(data: bytes):
    """Текущие адреса в class-файле — для режима --show."""
    found = []
    for tag, payload in _read_pool(data)[0]:
        if tag != 1:
            continue
        try:
            text = payload.decode('utf8')
        except UnicodeDecodeError:
            continue
        if text.startswith(('http://', 'https://')) and \
                any(text.endswith(s) for s in URL_SUFFIXES):
            found.append(text)
    return found


def strip_manifest_digests(manifest):
    """Убирает из MANIFEST.MF секции с хешами файлов, оставляя главную секцию.

    Секции разделены пустой строкой; хеши живут в секциях с ключом Name:.
    Главная секция несёт Main-Class и должна уцелеть.
    """
    head = manifest.split(b'\r\n\r\n')[0].split(b'\n\n')[0]
    return head.rstrip(b'\r\n') + b'\r\n\r\n'


def process(src, dst, auth, skin, dry_run=False, show=False):
    zin = zipfile.ZipFile(src)
    signed = [n for n in zin.namelist() if n.upper().endswith(SIG_SUFFIXES)]

    report, dropped = [], []
    entries = []
    for info in zin.infolist():
        name = info.filename
        data = zin.read(name)

        if name.upper().endswith(SIG_SUFFIXES):
            dropped.append(name)
            continue

        if name.endswith('.class'):
            try:
                if show:
                    for url in find_urls(data):
                        report.append((name, url, None))
                    continue
                data, applied = patch_class(data, auth, skin)
            except ClassFileError as exc:
                raise SystemExit(f'{name}: {exc}')
            for old, new in applied:
                report.append((name, old, new))

        if signed and name.upper() == 'META-INF/MANIFEST.MF':
            data = strip_manifest_digests(data)

        entries.append((info, data))

    if show:
        if signed:
            print(f'  джарник подписан: {", ".join(signed)}')
    else:
        if dropped:
            print(f'  подпись снята: {", ".join(dropped)}')
        if signed:
            print('  из MANIFEST.MF убраны секции с хешами файлов')

    for cls, old, new in report:
        if new is None:                       # режим --show: только текущее состояние
            print(f'  {cls}\n      {old}')
        else:
            print(f'  {cls}\n      {old}\n   -> {new}')
    if not report:
        print('  адресов не найдено или они уже такие — джарник не тронут')

    if show:
        return len(report)
    if dry_run:
        print('  --dry-run: файл не записан')
        return len(report)

    tmp = Path(str(dst) + '.tmp')
    with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
        for info, data in entries:
            # сохраняем исходный способ сжатия, чтобы не раздувать хранилища
            new_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            new_info.compress_type = info.compress_type
            new_info.external_attr = info.external_attr
            zout.writestr(new_info, data)
    shutil.move(str(tmp), str(dst))
    print(f'  записано: {dst} ({Path(dst).stat().st_size} байт)')
    return len(report)


def main():
    ap = argparse.ArgumentParser(
        description='Переадресация авторизации и скинов Minecraft 1.6.4 на свои серверы.')
    ap.add_argument('--input', required=True, help='исходный jar (клиент или сервер)')
    ap.add_argument('--output', help='куда записать; по умолчанию <input>-patched.jar')
    ap.add_argument('--auth-base', default='http://auth.invalid',
                    help='база для joinserver/checkserver, например http://auth.example.com')
    ap.add_argument('--skin-base', default='http://skins.invalid',
                    help='база для скинов и плащей, например http://skins.example.com')
    ap.add_argument('--dry-run', action='store_true', help='показать замены, ничего не писать')
    ap.add_argument('--show', action='store_true',
                    help='только показать текущие адреса в джарнике')
    args = ap.parse_args()

    src = Path(args.input)
    if not src.is_file():
        raise SystemExit(f'нет файла: {src}')
    dst = Path(args.output) if args.output else src.with_name(src.stem + '-patched.jar')

    print(f'{src.name}:')
    count = process(src, dst, args.auth_base, args.skin_base, args.dry_run, args.show)
    return 0 if count else 1


if __name__ == '__main__':
    sys.exit(main())
