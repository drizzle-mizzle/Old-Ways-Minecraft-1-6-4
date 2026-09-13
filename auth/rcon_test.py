"""Проверка клиента RCON против поддельного сервера. Живой Minecraft не нужен.

Запуск:  python auth/rcon_test.py

Смысл — не в протоколе как таковом (он не меняется с 2010 года), а в том, что
кик не должен ронять вход в аккаунт: мёртвый сервер, неверный пароль и пустая
настройка обязаны возвращать None, а не исключение из фонового задания.
"""

import os
import socket
import struct
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

SEEN = []      # что поддельный сервер получил: (тип пакета, тело)
FAILED = []


def check(what, condition, detail=''):
    if not condition:
        FAILED.append(what)
    mark = 'OK  ' if condition else 'ПРОВАЛ'
    print(f'  [{mark}] {what}' + (f'  — {detail}' if detail and not condition else ''))


def serve(listener, password):
    """Отвечает как ядро: неверный пароль — номер -1, команда — эхо ответа."""
    try:
        conn, _ = listener.accept()
    except OSError:
        return
    with conn:
        while True:
            head = conn.recv(4)
            if len(head) < 4:
                return
            size = struct.unpack('<i', head)[0]
            payload = b''
            while len(payload) < size:
                payload += conn.recv(size - len(payload))
            ident, kind = struct.unpack('<ii', payload[:8])
            body = payload[8:-2].decode('utf-8')
            SEEN.append((kind, body))
            if kind == 3:
                out = struct.pack('<ii', ident if body == password else -1, 2) + b'\0\0'
            else:
                out = struct.pack('<ii', ident, 0) + b'Kicked ' + body.split()[1].encode() + b'\0\0'
            conn.sendall(struct.pack('<i', len(out)) + out)


def talk(password_client, password_server, command='kick flower Вы вошли с другого устройства'):
    """Поднять поддельный сервер, сходить к нему свежим клиентом, вернуть ответ."""
    SEEN.clear()
    listener = socket.socket()
    listener.bind(('127.0.0.1', 0))
    listener.listen(1)
    threading.Thread(target=serve, args=(listener, password_server), daemon=True).start()
    try:
        return reload_client(port=listener.getsockname()[1],
                             password=password_client).command(command)
    finally:
        listener.close()


def reload_client(port, password, host='127.0.0.1'):
    """Настройки читаются при импорте, поэтому клиент каждый раз берётся заново."""
    os.environ.update(OW_RCON_HOST=host, OW_RCON_PORT=str(port),
                      OW_RCON_PASSWORD=password, OW_RCON_TIMEOUT='3')
    sys.modules.pop('rcon', None)
    import rcon
    return rcon


def main():
    print('RCON:')
    answer = talk('secret', 'secret')
    check('команда выполнена, ответ разобран', answer == 'Kicked flower', repr(answer))
    check('пароль ушёл отдельным пакетом типа 3', SEEN[:1] == [(3, 'secret')], repr(SEEN[:1]))
    check('кириллица в причине дошла целой',
          SEEN[1:] == [(2, 'kick flower Вы вошли с другого устройства')], repr(SEEN[1:]))

    check('неверный пароль — None, а не исключение', talk('wrong', 'secret') is None)

    client = reload_client(port=25575, password='')
    check('без пароля клиент выключен',
          client.enabled() is False and client.command('list') is None)

    # Порт 1 занят системой и на соединение отвечает отказом сразу.
    client = reload_client(port=1, password='secret')
    check('недоступный сервер — None, а не исключение', client.command('list') is None)

    print(f'\nитог: проверок {6 - len(FAILED)} из 6' +
          (f', провалено: {", ".join(FAILED)}' if FAILED else ''))
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
