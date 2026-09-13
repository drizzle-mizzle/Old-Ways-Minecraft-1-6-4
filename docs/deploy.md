# Развёртывание на чистом Ubuntu VPS

Проверено на Ubuntu 22.04 и 24.04. Всё, кроме сборки exe, делается на самом
сервере: питоновские инструменты не требуют ничего, кроме стандартной
библиотеки, а остальное живёт в контейнерах.

## Что понадобится

- **VPS**: 2 ядра, **6 ГБ ОЗУ** (4 ГБ хватит, если убавить память сервера —
  см. шаг 8), 20 ГБ диска. Ядро 1.6.4 однопоточное, больше двух ядер ему
  не нужно.
- **Порты наружу**: 25565 (игра) и 8080 (вход, скины, раздача сборки).
  RCON (25575) наружу не выставляется никогда — он живёт внутри сети compose.
- **С вашей машины** понадобится перенести то, чего нет в репозитории:
  три мода из `mirror/mods` и собранный лаунчер. Моды не в git по понятной
  причине: OptiFine и Rei's Minimap чужие, раздавать их мы не вправе.
- **Домен** не обязателен, но без него пароли игроков идут по сети открытым
  текстом — см. «Домен и HTTPS» в конце.

---

## Шаг 1. Пользователь, брандмауэр, время

Под root сразу после первого входа:

```bash
adduser --gecos "" oldways
usermod -aG sudo oldways
cp -r ~/.ssh /home/oldways/
chown -R oldways:oldways /home/oldways/.ssh
timedatectl set-timezone Europe/Moscow

ufw allow OpenSSH
ufw allow 25565/tcp
ufw allow 8080/tcp
ufw --force enable
```

Дальше всё — от `oldways`, не от root. Если вход по ключу уже работает, стоит
поставить в `/etc/ssh/sshd_config` строки `PermitRootLogin no` и
`PasswordAuthentication no`.

## Шаг 2. Docker

Официальный репозиторий, а не `apt install docker.io`: в дистрибутивном пакете
нет плагина `compose`.

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git python3
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
```

Выйдите и зайдите снова, чтобы применилась группа. Проверка:

```bash
docker run --rm hello-world
```

## Шаг 3. Репозиторий

Репозиторий приватный, поэтому нужен ключ развёртывания: он даёт доступ
только к нему одному, в отличие от личного токена.

```bash
ssh-keygen -t ed25519 -C "oldways-vps" -f ~/.ssh/id_ed25519 -N ""
cat ~/.ssh/id_ed25519.pub
```

Вывод добавьте в GitHub: репозиторий → Settings → Deploy keys → Add deploy key,
доступ только на чтение. Затем:

```bash
git clone git@github.com:drizzle-mizzle/Old-Ways-Minecraft-1-6-4.git ~/old-ways
cd ~/old-ways
```

## Шаг 4. Секреты

```bash
cp .env.example .env
python3 -c "import secrets; print(secrets.token_urlsafe(24))"
nano .env
```

Впишите сгенерированный пароль в `OW_RCON_PASSWORD` и свой — в
`OW_ADMIN_PASSWORD`: им заведётся основной аккаунт при первом запуске сервиса.
Оставите пустым — паролем станет ник, и сервис скажет об этом в логе.

`.env` в git не попадает.

## Шаг 5. Зеркало клиента и моды

Набор запуска (клиент, библиотеки, ассеты — около 146 МБ) и Forge качаются
прямо с зеркал Mojang и Forge, сверяя sha1:

```bash
python3 tools/fetch_client.py
python3 tools/fetch_forge.py
```

Моды перенесите со своей машины — команда выполняется **на ней**, в PowerShell:

```powershell
scp -r "C:/Users/flower/Desktop/Server/mirror/mods" oldways@ВАШ_IP:~/old-ways/mirror/
```

На сервере в `mirror/mods` должно оказаться три файла: `OptiFine_1.6.4_HD_U_D1.jar`,
`[1.6.4]ReiMinimap_v3.4_01.zip` и `oldways-auth-1.0.jar` — последний наш coremod,
он правит адреса авторизации уже в игре. Coremod можно собрать и на месте, но
тогда на VPS нужен JDK 8 распакованным в `jdk8/` рядом с репозиторием
(`python3 tools/build_coremod.py --jdk /путь/к/jdk8`); проще перенести готовый.

## Шаг 6. Раздача для лаунчера

```bash
python3 tools/build_dist.py
```

Соберёт `dist/`: манифест с хешами и файлы жёсткими ссылками на зеркало —
второй раз место они не занимают. Отсюда лаунчер докачивает игру.

## Шаг 7. Пропатченное ядро

Ванильное ядро ходит за проверкой входа на серверы Mojang. Заменяем адреса
на наш сервис:

```bash
python3 tools/patch_jars.py --input spigot-1.6.4-R2.1.jar --output server-auth.jar --auth-base http://auth:8080 --skin-base http://auth:8080
```

`auth` здесь — имя сервиса в сети compose, а не домен: сервер ходит к сервису
внутри Docker, поэтому при смене домена ядро перепатчивать не придётся.

## Шаг 8. Первый запуск

Если памяти меньше 6 ГБ, поправьте в `docker-compose.yml` три числа: `JAVA_XMS`
(например `1G`), `JAVA_XMX` (`2G`) и `deploy.resources.limits.memory` (`3G`).
Лимит контейнера держите выше `JAVA_XMX` — JVM берёт сверх кучи на метаданные
и стеки.

Там же, **до первого старта**, включите проверку входа. Иначе в томе останется
`online-mode=false`, и на сервер пустят кого угодно под любым ником:

```bash
sed -i 's/^online-mode=false/online-mode=true/' server.properties
```

Заодно в `server.properties` можно поправить `motd` и `max-players`. Запуск:

```bash
docker compose -f docker-compose.yml -f docker/compose.online.yml up -d --build
docker compose logs -f minecraft
```

Первый старт генерирует мир — это несколько минут. Готовность видно по строке
`Done (...)! For help, type "help"` и по `docker ps`: обе службы должны стать
`healthy`. В логе должно быть и `[entrypoint] RCON включён на порту 25575` —
им сервис выбивает из мира игрока, чей пропуск погас.

Дальше связка `-f docker-compose.yml -f docker/compose.online.yml` нужна
**каждый раз**: без второго файла поднимется непропатченное ядро.

## Шаг 9. Лаунчер в отгрузку

Чтобы лаунчер умел обновлять себя, положите рядом с раздачей его самого.
Собирается он только на Windows (нужен MinGW), поэтому переносим готовый.

Сначала впишите адрес сервера в `launcher/ADDRESS` — одной строкой, IP или
домен. Он попадёт в сборку и окажется в поле «Адрес сервера» при первом
запуске, так что игроку не придётся ничего вводить руками:

```powershell
"ВАШ_IP" | Out-File -Encoding ascii launcher/ADDRESS
python tools/publish_exe.py --to "C:/Users/flower/Desktop/OldWaysPublish"
scp -r "C:/Users/flower/Desktop/Server/dist/launcher" oldways@ВАШ_IP:~/old-ways/dist/
```

Сборка печатает, что получилось: `версия 0.3.2, адрес по умолчанию ВАШ_IP`.
Это именно **значение по умолчанию** — у тех, кто уже запускал лаунчер, адрес
сохранён в своём `launcher.properties`, и его никто не перетирает. Им проще
поправить поле в настройках.

Проверка на сервере:

```bash
curl -s localhost:8080/api/launcher
```

Сервис читает версию прямо из выложенных файлов и должен показать её и у exe,
и у jar. Сам exe раздавайте игрокам как удобно — хоть ссылкой на
`http://ВАШ_IP:8080/dist/launcher/exe`.

## Шаг 10. Проверка

```bash
curl -s localhost:8080/healthz
python3 auth/smoke_test.py --base http://127.0.0.1:8080 --user ВАШ_НИК --password ВАШ_ПАРОЛЬ
docker exec -w /srv mc164-auth python -c "import rcon; print(rcon.command('list'))"
```

Смоук-тест проходит 34 проверки и в конце возвращает аккаунту прежний скин.
Он честно входит в аккаунт, а на аккаунт живёт один пропуск — если этим ником
вы в это время сидите в лаунчере или в игре, вас выкинет. Это не поломка,
а ровно то поведение, которое он и проверяет.

Последняя команда должна ответить строкой про игроков онлайн — значит, сервис
достучался до консоли сервера и кик работает.

Наконец, в лаунчере на своей машине впишите в поле «Адрес сервера» ваш IP
(или домен) и войдите. Игровой порт стандартный, отдельно его писать не нужно.

---

## Аккаунты игроков

Регистрации на сервисе нет: сервер закрытый, аккаунты раздаёт владелец.
Заводятся они одной командой — на сервере или, если Docker смотрит туда же,
с любой машины:

```bash
docker exec mc164-auth python users.py add Vasya_Pupkin
```

Пароль сервис придумает сам и напечатает один раз — передайте его игроку.
Он помечен как временный: лаунчер предложит сменить его при первом входе,
и после смены вы этого пароля уже не знаете, что и правильно.

Остальные команды:

```bash
docker exec mc164-auth python users.py list                     # кто заведён и у кого живой вход
docker exec mc164-auth python users.py add Petya --admin        # с правами администратора
docker exec mc164-auth python users.py add Petya --password ЕгоПароль
docker exec mc164-auth python users.py passwd Vasya_Pupkin      # забыл пароль: новый и сброс входов
docker exec mc164-auth python users.py remove Vasya_Pupkin      # удалить (администратора — только с --force)
```

Смена пароля и удаление гасят пропуска и выбивают игрока из мира, если он
в нём сидит.

Ник должен быть таким, какой примет сама игра: латиница, цифры и
подчёркивание, от 3 до 16 знаков. Кириллический ник сервис бы стерпел, а вот
клиент 1.6.4 с ним до сервера не доедет, поэтому команда такие отклоняет.
Регистр в нике не важен при входе, но в игре ник будет виден ровно так, как
вы его завели.

Оператора в игре назначают отдельно — это уже не аккаунт, а права на сервере:

```bash
docker exec mc164 mc "op Vasya_Pupkin"
```

## Обновления

| Что | Как |
|---|---|
| Код сервиса или образ сервера | `git pull`, затем `docker compose -f docker-compose.yml -f docker/compose.online.yml up -d --build` |
| Сборка игры (моды, версии) | `python3 tools/build_dist.py` — лаунчер докачает разницу сам |
| Лаунчер | собрать и выложить с Windows (шаг 9); игрокам делать ничего не нужно |
| Плагины | положить jar в `plugins/`, пересобрать образ — настройки в томе не тронутся |

Ядро нужно перепатчить (шаг 7), только если менялся сам `spigot-1.6.4-R2.1.jar`
или патчер.

## Бэкапы

Всё состояние сервера — миры, города Towny, классы Heroes, банк — лежит в томе,
а не в образе. Аккаунты и скины — в своём.

```bash
docker volume ls
mkdir -p ~/backups
docker exec mc164 mc "save-all"
docker run --rm -v old-ways_mcdata:/data -v ~/backups:/backup alpine tar czf /backup/mcdata-$(date +%F).tar.gz -C /data .
docker run --rm -v old-ways_authdata:/data -v ~/backups:/backup alpine tar czf /backup/authdata-$(date +%F).tar.gz -C /data .
```

Имя тома начинается с имени каталога, в который клонирован репозиторий —
сверьтесь с выводом `docker volume ls`. Останавливать сервер не нужно, но
честнее сначала сказать ему `save-all`, как в примере.

Перенести нынешний мир со старой машины: остановить сервер, распаковать
`world/`, `world_nether/`, `world_the_end/` в том и запустить снова.

## Домен и HTTPS

Без домена всё работает, но вход идёт по `http://` — пароль игрока летит по
сети открытым текстом. Лаунчер понимает и `https://`, так что лечится это
обратным прокси:

```bash
sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt-get update
sudo apt-get install -y caddy
```

`/etc/caddy/Caddyfile` целиком:

```
old-ways.ru {
    reverse_proxy 127.0.0.1:8080
}
```

Дальше `sudo systemctl reload caddy` — сертификат Let's Encrypt Caddy получит
сам. После этого закройте порт наружу (`ufw delete allow 8080/tcp`) и в
`docker-compose.yml` привяжите его к петле: `"127.0.0.1:8080:8080"`. Игрокам
сказать вводить `https://old-ways.ru`; игра всё так же пойдёт на
`old-ways.ru:25565`.

Java 8 в лаунчере знает корневой сертификат Let's Encrypt, доставлять ничего
не нужно.

## Что стоит знать заранее

- **Пароли по HTTP**, пока нет домена — см. выше. Скажите игрокам не брать
  пароль, который у них используется где-то ещё.
- **exe не подписан**: SmartScreen на чужой машине покажет предупреждение при
  первом запуске. Лечится только покупкой сертификата.
- **RCON — это вся консоль сервера.** Пароль длинный, порт не публикуется;
  не выставляйте 25575 наружу «чтобы удобнее было».
- **Остановка занимает до двух минут** (`stop_grace_period: 120s`): ядру 1.6.4
  нужно время сохранить миры. Не убивайте контейнер раньше.
