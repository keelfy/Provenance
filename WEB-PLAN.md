# Веб-просмотр истории — план

Цель: админ выполняет команду в игре и получает ссылку. По ссылке открывается борд с деревом событий и деталями.
Фронт (Next.js) хостится у нас, доступа к БД сервера у него нет.

## 0. Решения

- **Снимок, не живая БД.** Плагин выполняет запрос, собирает JSON-снимок (`Report`) и отправляет его на бэкенд.
  Ссылка показывает результат на момент команды. Входящих подключений к MC-серверу нет, SQLite остаётся локальной.
  Ники, подписи holder'ов и названия предметов подставляются при сборке снимка: у сайта нет Bukkit.
- **Дерево = ограниченный BFS.** От корневого holder'а по `other` в обе стороны (кто отдал / кому ушло) с лимитами:
  глубина (2–3), число событий (~2000), окно по времени. Обход назад доходит до `Minted`: на `UNKNOWN`/`CREATIVE` —
  кандидаты в дюп. Используются существующие индексы `(holder, seq)`, `(other, seq)`.
- **Одноразовость = сгорание при первом JS-запросе + TTL.** Сгорание на GET страницы ломается превью-ботами
  Discord/Telegram, а JS они не исполняют. После первого запроса — короткое окно (N минут) для обновления страницы.
  TTL по умолчанию 24 ч, `/prov web revoke <token>`. Бэкенд хранит только `sha256(token)`; токен — 128 бит, base64url.
- **Токен во фрагменте:** `/view#<token>`. Фрагмент не попадает в логи, Referer и превью; страница статическая.
- **Плагин → бэкенд:** `java.net.http.HttpClient` (JDK), тело подписано HMAC-SHA256 общим секретом + timestamp
  (защита от повтора). Новых зависимостей в плагине нет; JSON — Gson из Paper (`compileOnly`).
- **Монорепо:** `plugin/` (Gradle) и `web/` (Next.js) в корне репозитория.

```
/prov web history player Bob diamond
   │ main: права, разбор аргументов
   ▼
WriteBehind (поток записи, после flush) ── ReportBuilder → Report JSON
   │ HttpClient, async, HMAC
   ▼
POST https://prov.example.com/api/reports ──► {token}
   │
   ▼
чат: [Открыть отчёт] → https://prov.example.com/view#<token>
                                     │
браузер: GET /api/reports/<token> ◄──┘ → борд
```

## 1. Контракт `Report` (v1)

```json
{
  "v": 1, "created": 1792300000000, "expires": 1792386400000,
  "query": { "kind": "history.player", "root": "PLAYER/<uuid>", "item": "minecraft:diamond", "depth": 2 },
  "holders": {
    "PLAYER/<uuid>": { "label": "Bob", "kind": "PLAYER" },
    "BLOCK/world:10:64:-3": { "label": "chest @ world 10 64 -3", "kind": "BLOCK" }
  },
  "events": [
    { "seq": 812, "time": 1792299000000, "cause": "container", "type": "TRANSFERRED",
      "holder": "BLOCK/world:10:64:-3", "other": "PLAYER/<uuid>", "item": "minecraft:diamond",
      "amount": 5, "custody": { "PLAYER/<alice>": 1.0 }, "suspicious": false }
  ],
  "pools": [],
  "truncated": false
}
```

`events` — `EventRow` в JSON; `holders` — таблица подписей. Схема лежит в одном месте (`web/` + Java-сериализатор),
TS-типы пишутся по ней вручную.

## 2. Этапы

### 2.0 Монорепо
- Gradle-проект (`settings.gradle.kts`, `build.gradle.kts`, `gradle/`, `gradlew*`, `core/`, `storage/`, `paper/`,
  `run/`, `mise.toml`) → `plugin/`. `ARCHITECTURE.md` → `plugin/`. Корневой `.gitignore` поправить.
- Перед переносом закоммитить текущие изменения: `git mv` по грязному дереву смешает перенос с правками.
- Проверка: `cd plugin && ./gradlew build` зелёный, `runServer` стартует.

### 2.1 `storage`: трассировка
- `SqliteStore.trace(root, item, since, depth, limit)` — итеративный BFS по фронту holder'ов,
  возвращает события + флаг `truncated`.
- `WriteBehind.trace(...)` по образцу `history()`: на потоке записи после `flush()`.
- Тесты (`SqliteStoreTest`): цепочка A → сундук → B → C, лимит глубины, обрезка по количеству, фильтр по предмету.

### 2.2 `paper`: публикация
- `web/ReportJson` — сериализация `Report`, подписи через `Labels`, `suspicious` как в `HistoryView`.
- `web/ReportPublisher` — HttpClient, HMAC, async; ответ на main → чат с `ClickEvent.openUrl`.
- `ProvCommand`: ветка `web` — `history player <p> [item]`, `history block`, `revoke <token>`. Право `provenance.web`.
- `config.yml`:
  ```yaml
  web:
    endpoint: ""        # пусто = выключено
    secret: ""
    ttl-hours: 24
    depth: 2
    max-events: 2000
  ```

### 2.3 `web/`: Next.js (App Router)
- `POST /api/reports` — проверка HMAC и timestamp, лимит размера тела, запись в SQLite (`better-sqlite3`)
  по `sha256(token)`, ответ `{token, expires}`.
- `GET /api/reports/[token]` — TTL, сгорание с окном обновления, `Cache-Control: no-store`. `DELETE` — revoke.
- `/view` — клиентская страница: токен из `location.hash`, `fetch` данных, состояния «истёк / не найден».
- Борд: `@xyflow/react` + раскладка `elkjs` (или `dagre`).
  - Узлы — holder'ы (игрок / блок / entity), рёбра — агрегированные `Transferred` по паре и предмету.
    `Minted`/`Burned` — входы/выходы на границе графа; подозрительные mint'ы красные.
  - Боковая панель: события выбранного узла/ребра, доли custody/origin.
  - Фильтры: предмет, причина, время.
- Практики (vercel-react-best-practices):
  - борд через `next/dynamic`, `ssr:false` (`bundle-dynamic-imports`);
  - индексы `Map` holder → события (`js-index-maps`);
  - раскладка в `useMemo`, при тысячах узлов — Web Worker;
  - фильтры через `useDeferredValue` / `startTransition`;
  - прямые импорты, без barrel-файлов.
- Деплой: `output: 'standalone'`, Docker за reverse proxy (HTTPS), cron-чистка просроченных отчётов.

## 3. Стоп-условие MVP

- `/prov web history player <p> [item]` и `/prov web history block` дают кликабельную ссылку.
- Ссылка открывает борд с деревом глубины 2 и панелью деталей.
- TTL, сгорание и revoke работают; превью ссылки в Discord её не сжигает.
- Тесты `trace` зелёные, HMAC отклоняет неподписанный/просроченный запрос.

## 4. Вне скоупа (будущее)

- **Туннель / обратный канал** для подгрузки соседей по клику (уход глубже в дерево). Варианты: плагин держит
  исходящее WebSocket-соединение к бэкенду, бэкенд проксирует запросы `trace`. Контракт `Report` не меняется —
  догружаются те же `events`/`holders`.
- Встроенный HTTP-сервер в плагине (`com.sun.net.httpserver`) с той же статической сборкой.
- Отчёты `links` / `player` (W, F, сходство), проигрывание по `seq`.
- Головы игроков с внешнего CDN (утекает IP посетителя третьей стороне).
