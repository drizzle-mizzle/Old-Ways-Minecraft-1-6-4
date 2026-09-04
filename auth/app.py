"""
Сервис авторизации и скинов W-Factory для Minecraft 1.6.4.

Реализует легаси-протокол, который в 1.6.4 зашит в клиент и сервер обычными
строками (см. tools/patch_jars.py). Проверено по байткоду:

  * клиент  bcw.class            — читает первую строку ответа joinserver.jsp
                                   и сравнивает с "ok" через equalsIgnoreCase;
  * сервер  ThreadLoginVerifier  — читает первую строку ответа checkserver.jsp
                                   и сравнивает с "YES" через equals.

Рукопожатие целиком:

  1. Лаунчер логинится: POST /api/login -> выдаётся session-токен.
  2. Клиент запускается с --username <ник> --session <токен>.
  3. При входе на сервер клиент дёргает joinserver.jsp?user&sessionId&serverId.
     Мы проверяем токен и запоминаем пару (ник, serverId).
  4. Сервер дёргает checkserver.jsp?user&serverId и получает YES, если пара
     совпала и не протухла.

serverId — непрозрачная для нас строка, которую сервер сообщает клиенту
в рукопожатии. Сравниваем как есть.
"""

from __future__ import annotations

import os
import secrets
import sqlite3
import struct
import time
from contextlib import contextmanager
from pathlib import Path

import bcrypt
from fastapi import FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import FileResponse, PlainTextResponse
from pydantic import BaseModel, Field

DB_PATH = Path(os.environ.get('WF_DB', '/data/auth.sqlite3'))
SKIN_DIR = Path(os.environ.get('WF_SKINS', '/data/skins'))
SESSION_TTL = int(os.environ.get('WF_SESSION_TTL', 60 * 60 * 24 * 7))  # неделя
JOIN_TTL = int(os.environ.get('WF_JOIN_TTL', 60))                      # окно на рукопожатие
MAX_SKIN_BYTES = 64 * 1024
DIST_DIR = Path(os.environ.get('WF_DIST', '/data/dist'))

SEED_USER = os.environ.get('WF_ADMIN_USER', 'flower')
SEED_PASSWORD = os.environ.get('WF_ADMIN_PASSWORD', 'flower')

app = FastAPI(title='W-Factory Auth', version='0.1.0')


# --------------------------------------------------------------------------- БД

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash BLOB NOT NULL,
    is_admin      INTEGER NOT NULL DEFAULT 0,
    must_change   INTEGER NOT NULL DEFAULT 0,
    created_at    INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token      TEXT PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS joins (
    username   TEXT PRIMARY KEY COLLATE NOCASE,
    server_id  TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
"""


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


def now() -> int:
    return int(time.time())


@app.on_event('startup')
def startup() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    SKIN_DIR.mkdir(parents=True, exist_ok=True)
    with db() as conn:
        conn.executescript(SCHEMA)
        seed_admin(conn)


def seed_admin(conn: sqlite3.Connection) -> None:
    """Создаёт основной админский аккаунт, если его ещё нет."""
    row = conn.execute('SELECT id FROM users WHERE username = ?', (SEED_USER,)).fetchone()
    if row:
        return
    default = SEED_PASSWORD == SEED_USER
    conn.execute(
        'INSERT INTO users (username, password_hash, is_admin, must_change, created_at) '
        'VALUES (?, ?, 1, ?, ?)',
        (SEED_USER, bcrypt.hashpw(SEED_PASSWORD.encode(), bcrypt.gensalt()),
         1 if default else 0, now()),
    )
    print(f'[auth] создан админский аккаунт {SEED_USER!r}')
    if default:
        print(f'[auth] ВНИМАНИЕ: пароль совпадает с ником. Смените его до того, '
              f'как сервис станет доступен извне, или задайте WF_ADMIN_PASSWORD.')


# ----------------------------------------------------------------- вспомогательное

def find_user(conn, username: str):
    return conn.execute('SELECT * FROM users WHERE username = ?', (username,)).fetchone()


def user_by_session(conn, token: str):
    row = conn.execute(
        'SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id '
        'WHERE s.token = ? AND s.expires_at > ?', (token, now())).fetchone()
    return row


def png_size(data: bytes):
    """Размеры PNG из заголовка IHDR. None, если это не PNG."""
    if len(data) < 24 or data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR':
        return None
    return struct.unpack('>II', data[16:24])


# ---------------------------------------------------------------- API лаунчера

class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=16)
    password: str = Field(min_length=1, max_length=128)


class LoginResponse(BaseModel):
    username: str
    session: str
    expires_at: int
    is_admin: bool
    must_change_password: bool


@app.post('/api/login', response_model=LoginResponse)
def api_login(body: LoginRequest):
    with db() as conn:
        user = find_user(conn, body.username)
        if not user or not bcrypt.checkpw(body.password.encode(), user['password_hash']):
            # одинаковый ответ на неизвестный ник и неверный пароль
            raise HTTPException(401, 'неверный ник или пароль')

        token = secrets.token_urlsafe(32)
        expires = now() + SESSION_TTL
        conn.execute('DELETE FROM sessions WHERE expires_at <= ?', (now(),))
        conn.execute('INSERT INTO sessions (token, user_id, created_at, expires_at) '
                     'VALUES (?, ?, ?, ?)', (token, user['id'], now(), expires))

        # Флаг живой, а не наследство от сидинга: если пароль снова стал равен
        # нику, предупреждение должно вернуться.
        weak = body.password.lower() == user['username'].lower()
        return LoginResponse(username=user['username'], session=token, expires_at=expires,
                             is_admin=bool(user['is_admin']),
                             must_change_password=bool(user['must_change']) or weak)


class PasswordChange(BaseModel):
    old_password: str
    new_password: str = Field(min_length=6, max_length=128)


@app.post('/api/password')
def api_password(body: PasswordChange, x_session: str = Header(...)):
    with db() as conn:
        user = user_by_session(conn, x_session)
        if not user:
            raise HTTPException(401, 'сессия недействительна')
        if not bcrypt.checkpw(body.old_password.encode(), user['password_hash']):
            raise HTTPException(403, 'старый пароль не подходит')
        conn.execute('UPDATE users SET password_hash = ?, must_change = 0 WHERE id = ?',
                     (bcrypt.hashpw(body.new_password.encode(), bcrypt.gensalt()), user['id']))
        conn.execute('DELETE FROM sessions WHERE user_id = ?', (user['id'],))
    return {'ok': True, 'note': 'все сессии завершены, войдите заново'}


@app.post('/api/skin')
async def api_skin(request: Request, x_session: str = Header(...)):
    # сначала аутентификация, потом разбор тела: посторонний не должен узнавать
    # по коду ответа, валиден ли его файл
    with db() as conn:
        user = user_by_session(conn, x_session)
        if not user:
            raise HTTPException(401, 'сессия недействительна')

    data = await request.body()
    if len(data) > MAX_SKIN_BYTES:
        raise HTTPException(413, f'скин больше {MAX_SKIN_BYTES} байт')
    size = png_size(data)
    if size is None:
        raise HTTPException(415, 'это не PNG')
    if size not in ((64, 32), (64, 64)):
        raise HTTPException(422, f'1.6.4 понимает только 64x32 и 64x64, а тут {size[0]}x{size[1]}')

    (SKIN_DIR / f'{user["username"].lower()}.png').write_bytes(data)
    return {'ok': True, 'width': size[0], 'height': size[1]}


# -------------------------------------------------- легаси-протокол Minecraft

@app.get('/game/joinserver.jsp', response_class=PlainTextResponse)
def joinserver(user: str = '', sessionId: str = '', serverId: str = ''):
    """Клиент сообщает, что заходит на сервер. Ждёт "ok" (equalsIgnoreCase)."""
    with db() as conn:
        account = user_by_session(conn, sessionId)
        if not account or account['username'].lower() != user.lower():
            return PlainTextResponse('Bad login', status_code=200)
        conn.execute('DELETE FROM joins WHERE created_at <= ?', (now() - JOIN_TTL,))
        conn.execute('INSERT INTO joins (username, server_id, created_at) VALUES (?, ?, ?) '
                     'ON CONFLICT(username) DO UPDATE SET server_id = excluded.server_id, '
                     'created_at = excluded.created_at',
                     (account['username'], serverId, now()))
    return PlainTextResponse('OK')


@app.get('/game/checkserver.jsp', response_class=PlainTextResponse)
def checkserver(user: str = '', serverId: str = ''):
    """Сервер спрашивает, правда ли этот игрок только что заходил. Ждёт ровно "YES"."""
    with db() as conn:
        row = conn.execute('SELECT * FROM joins WHERE username = ?', (user,)).fetchone()
        if not row or row['server_id'] != serverId or row['created_at'] <= now() - JOIN_TTL:
            return PlainTextResponse('NO')
        # рукопожатие одноразовое
        conn.execute('DELETE FROM joins WHERE username = ?', (user,))
    return PlainTextResponse('YES')


def _serve_texture(directory: Path, name: str) -> Response:
    path = directory / f'{name.lower()}.png'
    if not path.is_file():
        # клиент воспримет 404 как «скина нет» и возьмёт стандартный
        raise HTTPException(404, 'нет текстуры')
    return Response(path.read_bytes(), media_type='image/png',
                    headers={'Cache-Control': 'no-cache'})


@app.get('/MinecraftSkins/{name}.png')
def skin(name: str):
    return _serve_texture(SKIN_DIR, name)


@app.get('/MinecraftCloaks/{name}.png')
def cloak(name: str):
    return _serve_texture(SKIN_DIR / 'cloaks', name)


# --- раздача сборки ---------------------------------------------------------
# Лаунчер берёт отсюда манифест и по нему докачивает недостающее. Каталог
# готовит tools/build_dist.py; всё, что здесь нужно, — отдать его как есть.

@app.get('/dist/manifest.json')
def dist_manifest():
    path = DIST_DIR / 'manifest.json'
    if not path.is_file():
        raise HTTPException(503, 'раздача не собрана')
    return Response(path.read_bytes(), media_type='application/json',
                    headers={'Cache-Control': 'no-cache'})


@app.get('/dist/files/{path:path}')
def dist_file(path: str):
    root = (DIST_DIR / 'files').resolve()
    try:
        target = (root / path).resolve()
    except OSError:
        raise HTTPException(400, 'плохой путь')
    # resolve() снимает и «..», и симлинки, поэтому достаточно проверить,
    # что итог всё ещё лежит внутри каталога раздачи
    if root not in target.parents or not target.is_file():
        raise HTTPException(404, 'нет файла')
    return FileResponse(target, media_type='application/octet-stream')


@app.get('/healthz')
def healthz():
    with db() as conn:
        users = conn.execute('SELECT COUNT(*) c FROM users').fetchone()['c']
    return {'ok': True, 'users': users}
