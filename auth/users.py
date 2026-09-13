#!/usr/bin/env python3
"""
Аккаунты игроков: завести, сменить пароль, посмотреть, удалить.

Регистрации на сервисе нет и пока не планируется: сервер маленький и закрытый,
аккаунты раздаёт владелец. Отдельная команда вместо ручного INSERT нужна не
ради удобства, а ради пароля — он должен лежать в базе хешем bcrypt, и делать
это руками в sqlite3 слишком легко сделать неправильно.

Запускается внутри контейнера сервиса, там уже есть и bcrypt, и база:

    docker exec mc164-auth python users.py list
    docker exec mc164-auth python users.py add ВасяПупкин
    docker exec mc164-auth python users.py add Петя --password своипароль
    docker exec mc164-auth python users.py passwd Вася
    docker exec mc164-auth python users.py remove Вася

Пароль, придуманный не самим игроком, помечается как временный: лаунчер
предложит сменить его при первом входе.
"""

from __future__ import annotations

import argparse
import os
import secrets
import sqlite3
import sys
import time
from contextlib import contextmanager
from pathlib import Path

import bcrypt

DB_PATH = Path(os.environ.get('OW_DB', '/data/auth.sqlite3'))


@contextmanager
def db():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA foreign_keys = ON')
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def find(conn, username: str):
    return conn.execute('SELECT * FROM users WHERE username = ?', (username,)).fetchone()


def kick(username: str, reason: str) -> None:
    """Выбить из мира, если сервер рядом. Нет RCON — ничего страшного."""
    try:
        import rcon
        rcon.kick(username, reason)
    except Exception as error:          # noqa: BLE001 — инструмент не должен падать из-за этого
        print(f'  (в мир не достучались: {error})')


def make_password() -> str:
    """Короткий, но не угадываемый: его придётся диктовать голосом."""
    return secrets.token_urlsafe(9)


def cmd_list(args) -> int:
    with db() as conn:
        rows = conn.execute(
            'SELECT u.username, u.is_admin, u.must_change, u.created_at, '
            '  (SELECT COUNT(*) FROM sessions s '
            '    WHERE s.user_id = u.id AND s.revoked_at IS NULL AND s.expires_at > ?) AS live '
            'FROM users u ORDER BY u.created_at', (int(time.time()),)).fetchall()
    if not rows:
        print('аккаунтов нет')
        return 0
    print(f'{"ник":<20} {"заведён":<12} {"права":<8} {"пароль":<10} вход')
    for row in rows:
        when = time.strftime('%d.%m.%Y', time.localtime(row['created_at']))
        print(f'{row["username"]:<20} {when:<12} '
              f'{"админ" if row["is_admin"] else "игрок":<8} '
              f'{"временный" if row["must_change"] else "свой":<10} '
              f'{"есть" if row["live"] else "нет"}')
    return 0


def check_nickname(name: str) -> str | None:
    """Ник должна принять и игра: 1.6.4 знает только латиницу и подчёркивание.

    Сервис-то стерпит что угодно, а вот клиент с кириллицей до сервера
    не доедет — и разбираться в этом пришлось бы уже по логам игры.
    """
    if not 3 <= len(name) <= 16:
        return 'ник должен быть от 3 до 16 знаков — так требует игра'
    if not all(letter.isascii() and (letter.isalnum() or letter == '_') for letter in name):
        return 'в нике допустимы только латинские буквы, цифры и подчёркивание'
    return None


def cmd_add(args) -> int:
    wrong = check_nickname(args.username)
    if wrong:
        print(wrong)
        return 1
    password = args.password or make_password()
    with db() as conn:
        if find(conn, args.username):
            print(f'аккаунт {args.username!r} уже есть — пароль меняют командой passwd')
            return 1
        conn.execute(
            'INSERT INTO users (username, password_hash, is_admin, must_change, created_at) '
            'VALUES (?, ?, ?, ?, ?)',
            (args.username, bcrypt.hashpw(password.encode(), bcrypt.gensalt()),
             1 if args.admin else 0, 0 if args.password else 1, int(time.time())))
    print(f'заведён {args.username}' + (' (администратор)' if args.admin else ''))
    if not args.password:
        print(f'пароль: {password}')
        print('передайте его игроку — лаунчер предложит сменить пароль при первом входе')
    return 0


def cmd_passwd(args) -> int:
    password = args.password or make_password()
    with db() as conn:
        user = find(conn, args.username)
        if not user:
            print(f'нет такого аккаунта: {args.username!r}')
            return 1
        conn.execute('UPDATE users SET password_hash = ?, must_change = ? WHERE id = ?',
                     (bcrypt.hashpw(password.encode(), bcrypt.gensalt()),
                      0 if args.password else 1, user['id']))
        # Прежние пропуска гасим с причиной: лаунчеру на другой машине есть
        # что сказать игроку, кроме «сессия недействительна».
        conn.execute('UPDATE sessions SET revoked_at = ?, revoked_reason = ? '
                     'WHERE user_id = ? AND revoked_at IS NULL',
                     (int(time.time()), 'пароль изменён', user['id']))
    print(f'пароль {user["username"]} изменён, прежние входы сброшены')
    if not args.password:
        print(f'пароль: {password}')
    kick(user['username'], 'Пароль изменён, войдите заново')
    return 0


def cmd_remove(args) -> int:
    with db() as conn:
        user = find(conn, args.username)
        if not user:
            print(f'нет такого аккаунта: {args.username!r}')
            return 1
        if user['is_admin'] and not args.force:
            print('это администратор — если правда надо, добавьте --force')
            return 1
        conn.execute('DELETE FROM sessions WHERE user_id = ?', (user['id'],))
        conn.execute('DELETE FROM users WHERE id = ?', (user['id'],))
    print(f'аккаунт {user["username"]} удалён')
    kick(user['username'], 'Аккаунт удалён')
    print('скин и плащ остались на диске — уберите их сами, если нужно')
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description='Аккаунты игроков Old Ways.')
    sub = ap.add_subparsers(dest='command', required=True)

    sub.add_parser('list', help='показать все аккаунты').set_defaults(run=cmd_list)

    add = sub.add_parser('add', help='завести аккаунт')
    add.add_argument('username')
    add.add_argument('--password', help='свой пароль вместо случайного')
    add.add_argument('--admin', action='store_true', help='права администратора')
    add.set_defaults(run=cmd_add)

    passwd = sub.add_parser('passwd', help='сменить пароль и сбросить входы')
    passwd.add_argument('username')
    passwd.add_argument('--password', help='свой пароль вместо случайного')
    passwd.set_defaults(run=cmd_passwd)

    remove = sub.add_parser('remove', help='удалить аккаунт')
    remove.add_argument('username')
    remove.add_argument('--force', action='store_true', help='не щадить администратора')
    remove.set_defaults(run=cmd_remove)

    args = ap.parse_args()
    if not DB_PATH.is_file():
        print(f'базы нет: {DB_PATH}. Сервис хоть раз запускался?')
        return 1
    return args.run(args)


if __name__ == '__main__':
    sys.exit(main())
