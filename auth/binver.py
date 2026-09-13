"""Версия прямо из бинарника: jar — из манифеста, exe — из ресурса Windows.

Зачем так, а не рядом лежащим version.json: файл с номером легко разъезжается
с тем, что на самом деле выложено. Бинарник врать о себе не может — и Windows,
и сервис читают одну и ту же запись внутри него.

Ничего, кроме стандартной библиотеки: образ сервиса из-за одной функции
пересобирать с новой зависимостью не хочется.
"""

from __future__ import annotations

import re
import struct
import zipfile
from pathlib import Path

# Подпись VS_FIXEDFILEINFO — с неё начинается запись о версии внутри ресурса.
FIXED_FILE_INFO = 0xFEEF04BD
VERSION_KEY = re.compile(r'^Implementation-Version:\s*(.+)$', re.MULTILINE)


def jar_version(path: Path) -> str | None:
    """Версия из META-INF/MANIFEST.MF собранного jar."""
    try:
        with zipfile.ZipFile(path) as archive:
            text = archive.read('META-INF/MANIFEST.MF').decode('utf-8', 'replace')
    except (OSError, KeyError, zipfile.BadZipFile):
        return None
    # Манифест умеет переносить длинные строки — продолжение начинается
    # с пробела. Нашей строке до переноса далеко, но склеить недорого.
    text = text.replace(chr(13), '').replace(chr(10) + ' ', '')
    found = VERSION_KEY.search(text)
    return found.group(1).strip() if found else None


def exe_version(path: Path) -> str | None:
    """Версия из ресурса VERSIONINFO внутри PE-файла.

    Разбирать дерево ресурсов целиком незачем: достаточно найти секцию .rsrc
    и поискать подпись в ней. Искать по всему файлу нельзя — у нашего exe
    за запускателем идёт сжатая сборка на двадцать мегабайт, и четыре байта
    подписи там могут встретиться просто так.
    """
    try:
        data = path.read_bytes()
    except OSError:
        return None
    section = resource_section(data)
    if section is None:
        return None
    start, size = section
    blob = data[start:start + size]
    mark = struct.pack('<I', FIXED_FILE_INFO)
    at = blob.find(mark)
    if at < 0 or at + 16 > len(blob):
        return None
    # За подписью: версия структуры, потом версия файла двумя половинами —
    # старшая даёт X и Y, младшая Z и номер сборки, который мы не ведём.
    high, low = struct.unpack_from('<II', blob, at + 8)
    return f'{high >> 16}.{high & 0xFFFF}.{low >> 16}'


def resource_section(data: bytes):
    """Где в файле лежит секция .rsrc: (смещение, длина). None — не PE."""
    if len(data) < 0x40 or data[:2] != b'MZ':
        return None
    pe = struct.unpack_from('<I', data, 0x3C)[0]
    if pe + 24 > len(data) or data[pe:pe + 4] != b'PE' + bytes(2):
        return None
    sections, optional_size = struct.unpack_from('<H', data, pe + 6)[0], \
        struct.unpack_from('<H', data, pe + 20)[0]
    table = pe + 24 + optional_size
    for index in range(sections):
        entry = table + index * 40
        if entry + 40 > len(data):
            return None
        name = data[entry:entry + 8].rstrip(bytes(1)).decode('ascii', 'replace')
        if name == '.rsrc':
            size, offset = struct.unpack_from('<II', data, entry + 16)
            return offset, size
    return None
