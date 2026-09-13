"""
Сервис авторизации и скинов Old Ways для Minecraft 1.6.4.

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
from fastapi import BackgroundTasks, FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import FileResponse, PlainTextResponse
from pydantic import BaseModel, Field

import rcon

DB_PATH = Path(os.environ.get('OW_DB', '/data/auth.sqlite3'))
SKIN_DIR = Path(os.environ.get('OW_SKINS', '/data/skins'))
SESSION_TTL = int(os.environ.get('OW_SESSION_TTL', 60 * 60 * 24 * 7))  # неделя
JOIN_TTL = int(os.environ.get('OW_JOIN_TTL', 60))                      # окно на рукопожатие
# Хватает на скин 1024x512: обычный 64x32 весит около килобайта, самый
# крупный HD — десятки килобайт, запас взят на нежатые PNG.
MAX_SKIN_BYTES = 512 * 1024
# Разрешённые размеры текстур: развёртка 1.6.4 (вдвое шире, чем выше) в любом
# кратном увеличении. Больше 1024x512 не пускаем — это уже мегабайты видеопамяти
# на каждого игрока в поле зрения, а разницы на экране не видно.
TEXTURE_SIZES = [(64 * k, 32 * k) for k in (1, 2, 4, 8, 16)]
DIST_DIR = Path(os.environ.get('OW_DIST', '/data/dist'))
# Общий скин: его получают все, кто не загрузил свой.
DEFAULT_SKIN = Path(os.environ.get('OW_DEFAULT_SKIN', '/opt/auth/default_skin.png'))

# Причина отзыва, которую лаунчер показывает игроку как есть
EVICTED = 'в аккаунт вошли с другого устройства'
PASSWORD_CHANGED = 'пароль изменён'
# Та же причина, но для игрока в мире: он видит её экраном отключения,
# где обращение на «вы» уместнее протокольной формулировки.
KICK_EVICTED = 'Вы вошли с другого устройства'
KICK_PASSWORD_CHANGED = 'Пароль изменён, войдите заново'

SEED_USER = os.environ.get('OW_ADMIN_USER', 'flower')
SEED_PASSWORD = os.environ.get('OW_ADMIN_PASSWORD', 'flower')

app = FastAPI(title='Old Ways Auth', version='0.1.0')


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
    token          TEXT PRIMARY KEY,
    user_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at     INTEGER NOT NULL,
    expires_at     INTEGER NOT NULL,
    revoked_at     INTEGER,
    revoked_reason TEXT
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
    (SKIN_DIR / 'cloaks').mkdir(parents=True, exist_ok=True)
    with db() as conn:
        conn.executescript(SCHEMA)
        migrate(conn)
        seed_admin(conn)


def migrate(conn: sqlite3.Connection) -> None:
    """Дотягивает старую базу до текущей схемы.

    База переживает обновления сервиса, поэтому недостающие столбцы
    добавляются на месте: пересоздавать таблицу — терять живые сеансы.
    """
    have = {row['name'] for row in conn.execute('PRAGMA table_info(sessions)')}
    for column, kind in (('revoked_at', 'INTEGER'), ('revoked_reason', 'TEXT')):
        if column not in have:
            conn.execute(f'ALTER TABLE sessions ADD COLUMN {column} {kind}')


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
              f'как сервис станет доступен извне, или задайте OW_ADMIN_PASSWORD.')


# ----------------------------------------------------------------- вспомогательное

def find_user(conn, username: str):
    return conn.execute('SELECT * FROM users WHERE username = ?', (username,)).fetchone()


def user_by_session(conn, token: str):
    row = conn.execute(
        'SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id '
        'WHERE s.token = ? AND s.expires_at > ? AND s.revoked_at IS NULL',
        (token, now())).fetchone()
    return row


def session_gone(conn, token: str) -> str:
    """Почему пропуск не подошёл — это видит игрок в лаунчере."""
    row = conn.execute('SELECT revoked_reason FROM sessions WHERE token = ?',
                       (token,)).fetchone()
    if row and row['revoked_reason']:
        return row['revoked_reason']
    return 'сессия недействительна'


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
def api_login(body: LoginRequest, tasks: BackgroundTasks):
    with db() as conn:
        user = find_user(conn, body.username)
        if not user or not bcrypt.checkpw(body.password.encode(), user['password_hash']):
            # одинаковый ответ на неизвестный логин и неверный пароль
            raise HTTPException(401, 'Неверный логин или пароль')

        token = secrets.token_urlsafe(32)
        expires = now() + SESSION_TTL
        conn.execute('DELETE FROM sessions WHERE expires_at <= ?', (now(),))
        # На аккаунт — один живой пропуск: новый вход выбивает прежний.
        # Пропуск не удаляем, а помечаем: по метке прежний лаунчер узнает,
        # что случилось, и скажет игроку правду вместо «сессия истекла».
        evicted = conn.execute('UPDATE sessions SET revoked_at = ?, revoked_reason = ? '
                               'WHERE user_id = ? AND revoked_at IS NULL',
                               (now(), EVICTED, user['id'])).rowcount
        # Прежний игрок мог уже войти в мир — там гашение пропуска его не трогает:
        # ядро 1.6.4 спрашивает нас только в момент входа. Выбиваем через консоль,
        # и только если действительно было кого выбивать.
        if evicted:
            tasks.add_task(rcon.kick, user['username'], KICK_EVICTED)
        conn.execute('INSERT INTO sessions (token, user_id, created_at, expires_at) '
                     'VALUES (?, ?, ?, ?)', (token, user['id'], now(), expires))

        # Флаг живой, а не наследство от сидинга: если пароль снова стал равен
        # нику, предупреждение должно вернуться.
        weak = body.password.lower() == user['username'].lower()
        return LoginResponse(username=user['username'], session=token, expires_at=expires,
                             is_admin=bool(user['is_admin']),
                             must_change_password=bool(user['must_change']) or weak)


@app.get('/api/session', response_model=LoginResponse)
def api_session(x_session: str = Header(...)):
    """Кому принадлежит пропуск.

    Лаунчер держит выданный токен между запусками и при старте спрашивает,
    жив ли он: сеанс могли закрыть сменой пароля или он просто протух.
    """
    with db() as conn:
        user = user_by_session(conn, x_session)
        if not user:
            raise HTTPException(401, session_gone(conn, x_session))
        row = conn.execute('SELECT expires_at FROM sessions WHERE token = ?',
                           (x_session,)).fetchone()
        return LoginResponse(username=user['username'], session=x_session,
                             expires_at=row['expires_at'],
                             is_admin=bool(user['is_admin']),
                             must_change_password=bool(user['must_change']))


@app.delete('/api/session')
def api_session_delete(x_session: str = Header(...)):
    """Выход: пропуск гасится, дальше по нему ничего не сделать."""
    with db() as conn:
        conn.execute('DELETE FROM sessions WHERE token = ?', (x_session,))
    return {'ok': True}


class PasswordChange(BaseModel):
    old_password: str
    new_password: str = Field(min_length=6, max_length=128)


@app.post('/api/password')
def api_password(body: PasswordChange, tasks: BackgroundTasks, x_session: str = Header(...)):
    with db() as conn:
        user = user_by_session(conn, x_session)
        if not user:
            raise HTTPException(401, session_gone(conn, x_session))
        if not bcrypt.checkpw(body.old_password.encode(), user['password_hash']):
            raise HTTPException(403, 'старый пароль не подходит')
        conn.execute('UPDATE users SET password_hash = ?, must_change = 0 WHERE id = ?',
                     (bcrypt.hashpw(body.new_password.encode(), bcrypt.gensalt()), user['id']))
        # Пропуска не удаляем, а гасим с причиной: лаунчеру на другой машине
        # есть что сказать игроку, кроме «сессия недействительна».
        revoked = conn.execute('UPDATE sessions SET revoked_at = ?, revoked_reason = ? '
                               'WHERE user_id = ? AND revoked_at IS NULL',
                               (now(), PASSWORD_CHANGED, user['id'])).rowcount
        if revoked:
            tasks.add_task(rcon.kick, user['username'], KICK_PASSWORD_CHANGED)
    return {'ok': True, 'note': 'все сессии завершены, войдите заново'}


async def _accept_texture(request: Request, token: str, directory: Path):
    """Приём скина или плаща: проверка сеанса, размера файла и развёртки.

    HD-текстуры (128x64 и далее до 1024x512) понимает не сам клиент 1.6.4,
    а OptiFine из нашей сборки: он тянет картинку на кратную сетку вместо
    жёстких 64x32. Игроку без OptiFine достанется мыло, но такого игрока у нас
    и нет — мод едет в раздаче.
    """
    # сначала аутентификация, потом разбор тела: посторонний не должен узнавать
    # по коду ответа, валиден ли его файл
    with db() as conn:
        user = user_by_session(conn, token)
        if not user:
            raise HTTPException(401, session_gone(conn, token))

    data = await request.body()
    if len(data) > MAX_SKIN_BYTES:
        raise HTTPException(413, f'файл больше {MAX_SKIN_BYTES // 1024} КБ')
    size = png_size(data)
    if size is None:
        raise HTTPException(415, 'это не PNG')
    if size not in TEXTURE_SIZES:
        allowed = ', '.join(f'{w}x{h}' for w, h in TEXTURE_SIZES)
        raise HTTPException(422, f'нужен размер из набора {allowed}, а тут {size[0]}x{size[1]}')

    directory.mkdir(parents=True, exist_ok=True)
    (directory / f'{user["username"].lower()}.png').write_bytes(data)
    return {'ok': True, 'width': size[0], 'height': size[1]}


@app.post('/api/skin')
async def api_skin(request: Request, x_session: str = Header(...)):
    return await _accept_texture(request, x_session, SKIN_DIR)


@app.post('/api/cape')
async def api_cape(request: Request, x_session: str = Header(...)):
    """Плащ игрока. В 1.6.4 это отдельная текстура той же развёртки."""
    return await _accept_texture(request, x_session, SKIN_DIR / 'cloaks')


@app.delete('/api/skin')
def api_skin_delete(x_session: str = Header(...)):
    """Убрать свой скин: игрок снова получает общий скин сервера."""
    with db() as conn:
        user = user_by_session(conn, x_session)
        if not user:
            raise HTTPException(401, session_gone(conn, x_session))
    path = SKIN_DIR / f'{user["username"].lower()}.png'
    existed = path.is_file()
    if existed:
        path.unlink()
    return {'ok': True, 'removed': existed}


@app.delete('/api/cape')
def api_cape_delete(x_session: str = Header(...)):
    with db() as conn:
        user = user_by_session(conn, x_session)
        if not user:
            raise HTTPException(401, session_gone(conn, x_session))
    path = SKIN_DIR / 'cloaks' / f'{user["username"].lower()}.png'
    existed = path.is_file()
    if existed:
        path.unlink()
    return {'ok': True, 'removed': existed}


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
        # клиент воспримет 404 как «текстуры нет» и возьмёт стандартную
        raise HTTPException(404, 'нет текстуры')
    return Response(path.read_bytes(), media_type='image/png',
                    headers={'Cache-Control': 'no-cache', 'X-Skin': 'user'})


@app.get('/MinecraftSkins/{name}.png')
def skin(name: str):
    """Скин игрока, а без него — общий скин сервера.

    Отдавать 404 было бы честнее, но тогда клиент рисует ванильного Стива,
    и стандартный вид сервера теряется. Заголовок X-Skin говорит лаунчеру,
    свой это скин или общий: по нему подписывается предпросмотр.
    """
    path = SKIN_DIR / f'{name.lower()}.png'
    if path.is_file():
        return _serve_texture(SKIN_DIR, name)
    if DEFAULT_SKIN.is_file():
        return Response(DEFAULT_SKIN.read_bytes(), media_type='image/png',
                        headers={'Cache-Control': 'no-cache', 'X-Skin': 'default'})
    raise HTTPException(404, 'нет текстуры')


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
