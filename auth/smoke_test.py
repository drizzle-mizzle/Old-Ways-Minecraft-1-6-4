#!/usr/bin/env python3
"""
Дымовой тест сервиса авторизации: проверяет всё рукопожатие 1.6.4 целиком.

    python auth/smoke_test.py [--base http://127.0.0.1:8080] [--user flower] [--password flower]

Ничего, кроме стандартной библиотеки, не требует — запускается на любой машине,
где есть Python.
"""

import argparse
import json
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request
import zlib

PASSED, FAILED = [], []


def call(method, url, data=None, headers=None, raw=False):
    req = urllib.request.Request(url, data=data, method=method,
                                 headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            body = r.read()
            return r.status, body if raw else body.decode('utf8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf8', 'replace')


def check(name, condition, detail=''):
    (PASSED if condition else FAILED).append(name)
    mark = 'OK  ' if condition else 'FAIL'
    print(f'  [{mark}] {name}' + (f'  — {detail}' if detail else ''))


def make_png(width, height):
    """Минимальный валидный PNG нужного размера, без сторонних библиотек."""
    def chunk(tag, payload):
        return (struct.pack('>I', len(payload)) + tag + payload
                + struct.pack('>I', zlib.crc32(tag + payload) & 0xffffffff))

    ihdr = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)  # RGBA
    raw = b''.join(b'\x00' + b'\x7f\x7f\x7f\xff' * width for _ in range(height))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr)
            + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default='http://127.0.0.1:8080')
    ap.add_argument('--user', default='flower')
    ap.add_argument('--password', default='flower')
    args = ap.parse_args()
    base = args.base.rstrip('/')
    server_id = 'deadbeefcafe1234'

    print(f'сервис: {base}\n')

    print('здоровье и сидинг')
    status, body = call('GET', f'{base}/healthz')
    check('healthz отвечает 200', status == 200, body)
    check('аккаунт засеян', status == 200 and json.loads(body)['users'] >= 1)

    print('\nвход')
    creds = json.dumps({'username': args.user, 'password': args.password}).encode()
    status, body = call('POST', f'{base}/api/login', creds,
                        {'Content-Type': 'application/json'})
    check('верный пароль принят', status == 200, f'HTTP {status}')
    if status != 200:
        print('\nдальше без сессии смысла нет')
        return 1
    session = json.loads(body)['session']
    check('выдан признак смены пароля', json.loads(body)['must_change_password'] is True,
          'пароль совпадает с ником')

    bad = json.dumps({'username': args.user, 'password': 'не тот пароль'}).encode()
    status, _ = call('POST', f'{base}/api/login', bad, {'Content-Type': 'application/json'})
    check('неверный пароль отклонён', status == 401, f'HTTP {status}')

    status, _ = call('POST', f'{base}/api/login',
                     json.dumps({'username': 'нет-такого', 'password': 'x'}).encode(),
                     {'Content-Type': 'application/json'})
    check('неизвестный ник отклонён', status == 401, f'HTTP {status}')

    print('\nрукопожатие 1.6.4')
    q = urllib.parse.urlencode({'user': args.user, 'sessionId': session, 'serverId': server_id})
    status, body = call('GET', f'{base}/game/joinserver.jsp?{q}')
    check('joinserver отвечает OK', body.strip().lower() == 'ok', repr(body))

    q = urllib.parse.urlencode({'user': args.user, 'serverId': server_id})
    status, body = call('GET', f'{base}/game/checkserver.jsp?{q}')
    check('checkserver отвечает YES', body.strip() == 'YES', repr(body))

    status, body = call('GET', f'{base}/game/checkserver.jsp?{q}')
    check('рукопожатие одноразовое', body.strip() == 'NO', repr(body))

    q = urllib.parse.urlencode({'user': args.user, 'sessionId': 'подделка', 'serverId': server_id})
    status, body = call('GET', f'{base}/game/joinserver.jsp?{q}')
    check('чужая сессия отклонена', body.strip().lower() != 'ok', repr(body))

    q = urllib.parse.urlencode({'user': args.user, 'sessionId': session, 'serverId': server_id})
    call('GET', f'{base}/game/joinserver.jsp?{q}')
    q = urllib.parse.urlencode({'user': args.user, 'serverId': 'другой-server-id'})
    status, body = call('GET', f'{base}/game/checkserver.jsp?{q}')
    check('чужой serverId отклонён', body.strip() == 'NO', repr(body))

    print('\nскины')
    # заведомо отсутствующий ник, чтобы тест не зависел от прошлых прогонов
    status, body = call('GET', f'{base}/MinecraftSkins/nobody-has-this-skin.png', raw=True)
    check('без своего скина отдаётся общий', status == 200 and body[:4] == bytes([137, 80, 78, 71]),
          f'HTTP {status}')
    status, _ = call('GET', f'{base}/MinecraftCloaks/nobody-has-this.png', raw=True)
    check('без плаща отдаётся 404', status == 404, f'HTTP {status}')

    status, body = call('POST', f'{base}/api/skin', make_png(32, 32),
                        {'X-Session': session, 'Content-Type': 'image/png'})
    check('скин неверного размера отклонён', status == 422, f'HTTP {status}')

    status, body = call('POST', f'{base}/api/skin', 'не png вовсе'.encode(),
                        {'X-Session': session, 'Content-Type': 'image/png'})
    check('не-PNG отклонён', status == 415, f'HTTP {status}')

    png = make_png(64, 32)
    status, body = call('POST', f'{base}/api/skin', png,
                        {'X-Session': session, 'Content-Type': 'image/png'})
    check('скин 64x32 принят', status == 200, f'HTTP {status} {body}')

    status, got = call('GET', f'{base}/MinecraftSkins/{args.user}.png', raw=True)
    check('скин отдаётся байт в байт', status == 200 and got == png,
          f'HTTP {status}, {len(got) if isinstance(got, bytes) else "?"} байт')

    status, got = call('GET', f'{base}/MinecraftSkins/{args.user.upper()}.png', raw=True)
    check('регистр ника не важен', status == 200, f'HTTP {status}')

    # значение заголовка должно быть latin-1, поэтому подделка тут латиницей
    status, _ = call('POST', f'{base}/api/skin', png, {'X-Session': 'forged-token'})
    check('загрузка без сессии отклонена', status == 401, f'HTTP {status}')

    print(f'\nитог: пройдено {len(PASSED)}, провалено {len(FAILED)}')
    if FAILED:
        for name in FAILED:
            print(f'  провал: {name}')
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
