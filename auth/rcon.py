"""Мини-клиент RCON: единственный способ дотянуться до консоли живого сервера.

Зачем. Пропуск гасится в базе мгновенно, но игрока, который уже в мире, это
не трогает: ядро 1.6.4 спрашивает сервис только в момент входа. Чтобы выбить
такого игрока, нужно сказать серверу `kick` — а консоль наружу торчит только
по RCON, он у ядра свой (`RemoteControlListener`, ключи `enable-rcon`,
`rcon.port`, `rcon.password`).

Протокол простой и древний (Source RCON): пакет — длина, номер, тип, тело,
два нуля. Тип 3 — представиться паролем, 2 — выполнить команду. Отказ в пароле
виден по номеру -1 в ответе: сервер не пишет причину, просто не признаёт.

Своя реализация, а не библиотека: тут полсотни строк и ноль новых зависимостей
в образе, который ради этого пришлось бы пересобирать.
"""

from __future__ import annotations

import logging
import os
import socket
import struct

log = logging.getLogger('oldways.rcon')

HOST = os.environ.get('OW_RCON_HOST', 'minecraft')
PORT = int(os.environ.get('OW_RCON_PORT', 25575))
PASSWORD = os.environ.get('OW_RCON_PASSWORD', '')
# Три секунды: сервер отвечает за миллисекунды, а ждать дольше нечего —
# кик не тот случай, ради которого стоит держать чужой запрос.
TIMEOUT = float(os.environ.get('OW_RCON_TIMEOUT', 3))

AUTH, COMMAND = 3, 2
AUTH_FAILED = -1


def enabled() -> bool:
    """Без пароля кик просто выключен: dev-запуск без сервера должен работать."""
    return bool(PASSWORD)


def _pack(ident: int, kind: int, body: str) -> bytes:
    payload = struct.pack('<ii', ident, kind) + body.encode('utf-8') + b'\0\0'
    return struct.pack('<i', len(payload)) + payload


def _read_exactly(sock: socket.socket, count: int) -> bytes:
    data = b''
    while len(data) < count:
        part = sock.recv(count - len(data))
        if not part:
            raise ConnectionError('сервер закрыл соединение')
        data += part
    return data


def _unpack(sock: socket.socket):
    size = struct.unpack('<i', _read_exactly(sock, 4))[0]
    if not 10 <= size <= 4096 + 16:
        raise ConnectionError(f'неправдоподобная длина пакета: {size}')
    payload = _read_exactly(sock, size)
    ident, kind = struct.unpack('<ii', payload[:8])
    return ident, kind, payload[8:-2].decode('utf-8', 'replace')


def command(text: str) -> str | None:
    """Выполнить команду в консоли сервера. Вернуть ответ или None при неудаче.

    Ошибки не пробрасываются: недоступный сервер не должен ронять вход в аккаунт.
    """
    if not enabled():
        return None
    try:
        with socket.create_connection((HOST, PORT), TIMEOUT) as sock:
            sock.settimeout(TIMEOUT)
            sock.sendall(_pack(1, AUTH, PASSWORD))
            ident, _, _ = _unpack(sock)
            if ident == AUTH_FAILED:
                log.error('RCON не принял пароль (%s:%s)', HOST, PORT)
                return None
            sock.sendall(_pack(2, COMMAND, text))
            _, _, answer = _unpack(sock)
            log.info('RCON %r -> %r', text, answer)
            return answer
    except OSError as error:
        log.warning('RCON недоступен (%s:%s): %s', HOST, PORT, error)
        return None


def kick(username: str, reason: str) -> None:
    """Выбить игрока из мира. Не в игре — сервер просто скажет, что не нашёл."""
    command(f'kick {username} {reason}')
