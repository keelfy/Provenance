# Provenance — вероятностная история предметов

Paper 26.3. Исходники — Java 21 (язык), сборка — JDK 25 (Paper 26.x собран под 25).
Цель Java 21 — облегчить бэкпорт на старые версии сервера.

## 0. Ключевые решения

**Вектор вне ItemStack.** PDC/компоненты с разными значениями ломают стакование.
Всё хранится во внешнем реестре (ledger), предмет не трогаем.

**Модель «счетов», не слотов.** Holder = инвентарь игрока / контейнер-блок / entity.
Holder × тип предмета → `Pool{count, origin, custody}`. Перемещения внутри holder'а — no-op.
Содержимое одного сундука одного типа смешивается (haircut). Предметы взаимозаменяемы — модель честная.

## 1. Математика (`core`, без Bukkit)

- `TaintVector` — разреженный `OriginId → доля`, Σ=1. top-K (16), остаток → `OTHER`, доли < ε отбрасываются.
- `OriginId` — игрок (UUID) или окружение: `MOB`, `LOOT`, `TRADE`, `CREATIVE`, `UNKNOWN` (предметы до плагина / неучтённые).
- Операции `Ledger`:
  - `mint(h, item, k, origin)` — предмет появился.
  - `transfer(a, b, item, k)` — `b.v = (b.n·b.v + k·a.v)/(b.n+k)`; у `a` уменьшается count, вектор тот же.
    Если у `a` меньше `k` — недостача минтится в `b` как `UNKNOWN`.
  - `convert(h, inputs, outputs)` — крафт/плавка; вектор выхода = смесь входов (иначе отмывание крафтом).
  - `burn(h, item, k)` — расход/установка/уничтожение.
  - `reconcile(h, actual)` — реальное < учёт → burn; реальное > учёт → mint `UNKNOWN`. Страховочная сетка.

Два вектора на пул:
- `origin` — кто произвёл предмет.
- `custody` — какой игрок последним положил предмет в этот пул. У пула игрока custody = сам игрок.
  Нужен для потоков через сундуки: A кладёт, B забирает → `F[A][B] += k·custody_A`.

## 2. Корреляции

- **Граф потоков** `F[A][B][item]` — передачи, включая сундуки, дроп, воронки. Только игроки-кастодианы.
- **Богатство** `W[P][Q] = Σ_pools count · custody_P · origin_Q`. Учитывает предметы P в сундуках
  (через custody). Ловит отмывание через посредников. Допущение: custody и origin внутри пула независимы.
- **Сходство** — косинус строк `W` по игрокам-источникам (окружение исключено, иначе шум).

## 3. Paper-слой (адаптеры)

Holders: игрок → UUID; контейнер-блок → `world:x:y:z` (двойной сундук — каноническая половина);
entity (item, вагонетка, осёл) → UUID. Ключ предмета MVP = `ItemType` key.

| Поток | События |
|---|---|
| Origin | `BlockDropItemEvent` (копатель), `EntityDeathEvent` (убийца / `MOB`), `LootGenerateEvent`, `PlayerFishEvent`, `BlockDispenseEvent` (раздатчик/выбрасыватель → item entity из пула блока); торговля и creative — через diff |
| Игрок ↔ контейнер | diff (ниже); проигрыватель, полка, горшок, кафедра без GUI — `PlayerInteractEvent` → тот же diff по `TileStateInventoryHolder` |
| Игрок ↔ рамка, стойка | `PlayerInteractEntityEvent`, `PlayerArmorStandManipulateEvent` → тот же diff; выбитый предмет рамки — подсказка из `EntityDamageEvent` / `HangingBreakEvent`, у стойки — подсказка смерти |
| Земля | `PlayerDropItemEvent`, `EntityPickupItemEvent` (игроки и мобы; моб держит пул до смерти), `ItemMergeEvent`, `EntityRemoveEvent` → `clear` (у рамки — тиком позже, пока не высыпан предмет) |
| Автоматика | `InventoryMoveItemEvent`, `InventoryPickupItemEvent` |
| Конверсия | `FurnaceSmeltEvent`, `BrewEvent` (только изменившиеся бутылки), `BlockCookEvent` (костёр), `CrafterCraftEvent` → `convert`; крафт и верстаки — через diff |

**Diff** (`InventoryListener` + `core/Settlement`), а не разбор `InventoryAction`. На первом click/drag/close
за тик игрок и контейнер сверяются (`reconcile`), на следующем тике остаток дельты — эффект взаимодействия:
- `CONTAINER` — top +k, игрок −k → `transfer`; остаток → `reconcile`.
- `WORKSTATION` (2×2, верстак, наковальня, точило, …) — входные слоты считаются игроком; пропавшее = вход,
  появившееся = выход → `convert`. Покрывает shift-крафт без `CraftItemEvent`.
  Появившееся без единого входа (creative, инвентарь игрока тоже `CRAFTING`) — не крафт, а `mint` с `mintOrigin`.
- `MERCHANT` — появившееся минтится `TRADE`, оплата сгорает.
- `SELF` (инвентарь, эндер-сундук, creative, GUI плагинов) — `reconcile`.

**Вложенное содержимое.** Стек считается вместе с тем, что лежит внутри (шалкер, мешок, компоненты `CONTAINER` /
`BUNDLE_CONTENTS`): `ItemKeys.count` рекурсивен. Поэтому diff и все `transfer` по стеку (`Ledger.transfer(from, to, Map)`)
двигают шалкер вместе с содержимым. Установка шалкера переносит содержимое от игрока в блок, ломание — из блока в предмет.

Холдинги игрока = инвентарь + эндер-сундук + курсор + входные слоты верстака (без RESULT).
Creative-игрок минтит `CREATIVE` вместо `UNKNOWN`.

**Spawn-контексты** (`SpawnContexts`) — подсказки на один тик для item entity, которые появляются без
ссылки на источник: дроп моба (`MOB`/убийца), смерть игрока (из пула игрока), выброс раздатчика/выбрасывателя (из пула блока), высыпание контейнера при
ломании/взрыве (из пула контейнера; перед этим контейнер сверяется, чтобы неучтённое ушло как `UNKNOWN`).

**Двойные сундуки** — один holder по левой половине. При постановке второй половины пулы переезжают
в канонический id (`Ledger.transferAll`), при ломании половины — остаток переезжает к выжившей.

**Reconcile** — вход/выход, первый клик тика, перед дропом/подбором, раз в 100 тиков. Неучтённые пути
(установка блоков, еда, команды, плагины) самоисправляются; точные события только добавляют атрибуцию.

## 4. Хранение и потоки

- Ledger в памяти, меняется только на главном потоке.
- SQLite (драйвер поставляет Paper), `plugins/Provenance/provenance.db`, WAL:
  `pool(holder_kind, holder_id, item, count, origin, custody)`, `flow(from_origin, to_origin, item, amount)`;
  вектор — текст `origin=share;…`. Дней в `flow` нет: суммы накопительные.
- `Ledger` помечает тронутые holder'ы и рёбра `FlowGraph`; `drainChanges()` отдаёт неизменяемый снимок
  (`LedgerChanges`), пустой список у holder'а = удалить. Снимок на main раз в 30 с → `WriteBehind`
  (один поток, порядок сохраняется, неудачный батч повторяется перед следующим). Flush в `onDisable`.
- Holder записывается целиком (delete + insert), поток — `INSERT OR REPLACE`, батч — одна транзакция.
- MVP: полная загрузка при старте. Ленивая по `ChunkLoadEvent` — при > ~1M пулов.

### История (event sourcing, для модерации: дюпы и кражи)

- Источник правды — журнал событий `LedgerEvent` (`Minted`, `Transferred`, `Burned`, `Converted`, `Cleared`);
  `Pool`/`FlowGraph` — проекции (`PoolProjection`, `FlowProjection`). Состояние меняет только `LedgerState.apply(event)`:
  и вживую, и при replay.
  Составные операции разворачиваются в простые: недостача при `transfer` = `Transferred(moved)` + `Minted(UNKNOWN)`,
  `reconcile` = `Minted`/`Burned`, `transferAll` = N `Transferred`; `Converted` хранит входы/выходы по порядку ключей.
- Команды `Ledger` принимают `Cause` (mine, kill, loot, pickup, drop, container, craft, hopper, smelt, trade, reconcile)
  и кладут `Envelope(seq, time, cause, event)` в буфер; `drainChanges()` отдаёт его вместе со снимком (`events`, `lastSeq`).
- `Transferred.custody` заполняется, когда получатель — игрок: чьи это были предметы (кража). При replay пересчитывается.
- Таблица `event` (индексы `(holder, seq)`, `(other, seq)`), `meta.last_seq`. Снимок, события и `last_seq` пишутся
  одной транзакцией. Загрузка = снимок + replay событий с `seq > last_seq`; `seq` уникален, повтор батча безопасен.
- Хранение (по умолчанию): события с игроком и любые `Minted` — 30 дней, чистая автоматика (блок ↔ блок) — 3 дня;
  чистка раз в сутки на потоке записи. Сроки и периоды задаются в `config.yml` (`Settings`), там же радиусы
  атрибуции и размеры выдачи команд. Пересборка с нуля не поддерживается. Схема v2 без миграции: старая БД сбрасывается.
- Подряд идущие `Transferred` блок ↔ блок с одной причиной, парой холдеров и предметом (тикающая воронка) `Journal`
  склеивает в одно событие, пока оно не ушло в запись: `seq` остаётся сплошным, `time` берётся от первого.
  Включено по умолчанию, отключается `journal.merge-automation: false` в `config.yml`.
- Бакеты (`journal.bucket-minutes`, по умолчанию 60): когда бакет закончился (+10 мин на опоздавшие предметы), `SqliteStore.compact`
  (поток записи, раз в бакет) складывает в одну строку события, равные во всём, кроме времени и количества
  (`Bucketing`): `seq` и `time` от первого, `until` — время последнего. Место события — `chunk` (`world:x:z`, колонка
  16×16): у блока из координат id, у сущности из `HolderMeta.chunk` на момент события; события разных чанков не
  склеиваются. Убитые существа (причина `kill`) схлопываются в один холдер `mob:<тип>@<чанк>#<убийца>`: ферма — одна строка, а истории двух игроков в одном чанке не пересекаются. Перед этим выпавшие стеки сворачиваются, как в вебе: «добыто → предмет →
  игрок» становится «добыто → игрок». Трогаются только события с `seq <= last_seq` (уже в снимке), поэтому replay и
  состояние не меняются. Стек, подобранный в следующем бакете, остаётся сырым и сворачивается в вебе.
- Запрос истории идёт на потоке записи после `flush()`, поэтому видит всё записанное до него.

## 5. Команды (Brigadier, `LifecycleEvents.COMMANDS`)

Право `provenance.admin` (по умолчанию op). Только чтение, сверяют игрока перед ответом.
- `/prov hand` — origin стака в руке (по всем предметам этого типа у игрока).
- `/prov block` — пулы контейнера под прицелом: количество, origin, custody.
- `/prov player <p>` — `W[p]`: ожидаемые предметы по origin и доли.
- `/prov links [p]` — связи: отдал / получил / сходство.
- `/prov history block` — журнал контейнера под прицелом (кто клал, кто забирал и чьи это были предметы).
- `/prov history player <p> [item]` — журнал игрока; `Minted` с origin `unknown`/`creative` выделены красным (кандидаты в дюп).

## 6. Пакеты

Gradle-подпроекты: `:core` (только JDK, тесты), `:storage`, `:app` (логика плагина без платформы) и `:paper`
(адаптер Bukkit; jar плагина с вшитыми core, storage и app, `runServer` в корневом `run/`).

```
Gradle-модули: `core` → `storage` → `app` → `paper` (плагин собирается в один jar).

core/          Ledger (команды → события; читается через LedgerView), Journal (seq, часы, склейка автоматики),
               LedgerState → Projection: PoolProjection, FlowProjection (+ Pool, FlowGraph),
               Settlement (diff → transfer/convert), LedgerChanges (снимок для записи)
core/model/    Holder (HolderId + HolderMeta), BlockPos, Kind, OriginId, ItemKey, Cause, Chunk — значения без зависимостей
core/taint/    TaintVector (зависит от model)
core/event/    LedgerEvent, Envelope (зависят от model, taint)
core/analysis/ Correlation, Link — только чтение Ledger
               Зависимости строго вниз: analysis → core → event → taint → model
storage/   SqliteStore (ChangeSink), WriteBehind, VectorCodec — чистый JDBC, без Bukkit
app/       Логика плагина без платформы (anti-corruption layer): повтор под другое ядро = новый адаптер, не новая логика.
           Settings (через ConfigSource), ProvenanceStorage, inventory/{InventoryReconciler, ViewTarget, ViewKind},
           world/{SpawnContexts, SpawnSource, WorldPos, ItemDrop},
           observe/{World, Automation, Item, Showcase}Events — факты игры → команды Ledger,
           command/{ProvService, Labels, HistoryView, Line, Span, Tone, Sender} — команды /prov и их вывод,
           web/{ReportJson, ReportPublisher},
           port/{Scheduler, Log, ConfigSource, PlayerDirectory, OnlinePlayer} — всё, что нужно от платформы
paper/     Адаптер Bukkit/Paper: PaperScheduler, PaperLog, PaperConfig, PaperDirectory, PaperPlayer, PaperSender,
           Holders, ItemKeys, Views, Positions (Bukkit → значения app/core),
           listener/{Inventory, ItemEntity, World, Automation, Showcase}Listener — событие Bukkit → вызов *Events,
           command/ProvCommand — дерево Brigadier и права, остальное в ProvService
```

## 7. Этапы

1. **Каркас + core** — Gradle, загрузка плагина, математика ledger'а, unit-тесты. ✅
2. **Paper-слой** — holders, origin-слушатели, diff, земля, воронки, крафт/плавка, reconcile. ✅
   Проверено на `runServer` консолью (воронки, печь, дроп моба → воронка). Сценарии с игроками — на этапе 5.
3. **Хранение** — SQLite, write-behind. ✅ Тесты: round-trip, удаление, повтор батча.
   Проверено на `runServer`: запись при остановке, загрузка при старте.
4. **Команды** — `/prov …`. ✅ Проверен запуск и ошибки с консоли; вывод с данными — на этапе 5.
5. **Ручная приёмка** на `runServer` по сценариям ниже.

## 8. Приёмка

1. Alice копает 10 алмазов → сундук; Bob берёт 5 → `W[Bob]` = Alice 100%, `F[Alice][Bob]` = 5.
2. Bob копает ещё 5 → 50/50.
3. Bob крафтит блок из 9 алмазов → Carol через дроп → доля Alice сохранена.
4. `/give` → после reconcile `UNKNOWN`.
5. Unit: сохранение количества, Σ=1, смешивание, конверсия.

## 9. Вне MVP (и триггер)

- Ключ предмета с компонентами (зачарования) — когда важны ценные уникальные вещи.
- Взвешивание по ценности в `F`/`W`.
- Журнал событий для пересчёта другими алгоритмами (poison, FIFO).
- Отмывание через установку блока: поставил чужой алмазный блок, сломал → origin = ломающий.
  Триггер: заметное отмывание ценных блоков; лечится хранением origin установленных блоков.
- Крафтер, вставляющий результат в соседний контейнер (не в мир): выход там станет `UNKNOWN`.
- Вагонетки, подбирающие предметы; содержимое вагонеток при разрушении — `UNKNOWN`.
- Установка/снятие шалкера диспенсером, остаток крафта (пустое ведро) в крафтере — `UNKNOWN`.
- Частичный подбор воронкой (воронка почти полная) — недостача на земле позже станет `UNKNOWN`.
- `hopper.disable-move-event` в конфиге Paper отключает `InventoryMoveItemEvent` → воронки станут `UNKNOWN`.
- Folia, веб-дашборд.
