# Дизайн: Поддержка подписок

## Цель

Дать пользователю возможность добавлять подписки (subscription URL), из которых
приложение вытягивает пачку серверов и превращает каждый в обычный конфиг.
Это первая из двух фич (вторая — правила роутинга, отдельным циклом).

## Контекст (как устроено сейчас)

- Каждый конфиг — самодостаточный `.json` файл в `filesDir` (полный: inbounds +
  outbounds + routing из `assets/template`).
- При коннекте выбранный файл читается, инжектится stats (`ConfigUtils`) и
  скармливается в stdin `libxray.so` **как есть**. Мержа роутинга нет.
- Импорт: буфер/текст → `ConfigFormatConverter` (сейчас `SimpleXrayFormatConverter`,
  `VlessLinkConverter`) → сохраняется как файл.
- Настройки хранятся через ContentProvider-обёртку `Preferences`.
- Скачка по URL уже есть (`MainViewModel.downloadRuleFile`): OkHttp, proxy-aware
  (роутит через SOCKS `127.0.0.1:socksPort` если VPN включён), с прогрессом.

## Решения (согласовано)

- Формат подписок: **base64-список ссылок** (`vless://` / `vmess://` / `ss://` /
  `trojan://`). Самый распространённый формат панелей.
- Модель хранения: **каждый сервер = отдельный `.json` файл** (полный, с
  template-роутингом), помеченный принадлежностью подписке через реестр.
- Обновление: **ручная кнопка** (без фона/WorkManager).
- UI: **секция в экране `Config`** (не отдельный таб).
- Синхронизация вынесена в **отдельный `SubscriptionManager`** (не в `FileManager`).
- Удаление отдельного сервера подписки **заблокировано** — можно удалить только
  всю подписку. Редактирование сервера доступно, но правки затираются на рефреше.
- Нейминг файлов серверов: `<имя подписки> - <имя сервера>.json`.

## Архитектура

### 1. Модель данных

Новый ключ в `Preferences` — `SUBSCRIPTIONS`, хранится JSON-списком (по образцу
`configFilesOrder`, сериализация через существующий Gson):

```
data class Subscription(
    val id: String,          // timestamp создания
    val name: String,
    val url: String,
    val lastUpdated: Long,   // 0 = ещё не обновлялась
    val files: List<String>  // имена .json файлов этой группы
)
```

Принадлежность файла подписке хранится в реестре (`files`), а НЕ в имени файла —
не завязываемся на парсинг строк. Добавляем в `Preferences`:
`var subscriptions: List<Subscription>` (get/set с Gson, как `configFilesOrder`).

### 2. Парсеры ссылок (`common/configFormat/`)

По существующему интерфейсу `ConfigFormatConverter` (`detect` / `convert`):

- `VlessLinkConverter` — уже есть.
- `VmessLinkConverter` — `vmess://` (base64-JSON тело).
- `ShadowsocksLinkConverter` — `ss://` (`ss://base64@host:port#name` и
  `ss://base64(method:pass@host:port)#name`).
- `TrojanLinkConverter` — `trojan://`.

Каждый возвращает `DetectedConfig` = `(name, fullConfigJson)`, где конфиг полный
и с template-роутингом (как сейчас делает `VlessLinkConverter`). Регистрируем
новые парсеры в `ConfigFormatConverter.knownImplementations`.

Побочный эффект (желательный): ручной импорт `vmess/ss/trojan` из буфера
заработает автоматически, т.к. идёт через тот же `knownImplementations`.

### 3. Парсер подписки

`SubscriptionParser` (объект в `common/configFormat/` или рядом):

```
fun parse(context, rawBody): List<DetectedConfig>
```

Логика: сырой ответ → если это base64, декодировать → разбить по строкам →
каждую непустую строку через `ConfigFormatConverter.convertOrNull` → собрать
успешные `DetectedConfig`. Нераспознанные строки пропускаются (не роняют
подписку). Возврат пустого списка = ошибка «нет валидных серверов».

### 4. Синхронизация — `SubscriptionManager`

Отдельный класс (аналог `FileManager`, конструктор `(application, prefs, fileManager)`).
Внутри — тот же proxy-aware OkHttp, что и в `downloadRuleFile`.

- `suspend fun add(name, url): Result<Subscription>` — создать запись (пустую),
  затем `refresh`.
- `suspend fun refresh(sub): Result<Subscription>`:
  1. Скачать тело по `sub.url` (OkHttp, через SOCKS если сервис включён).
  2. `SubscriptionParser.parse` → список серверов. Пусто → `Result.failure`,
     **старые файлы не трогаем**.
  3. Сгенерировать имена файлов: `<sub.name> - <serverName>.json`,
     санитизация через `FilenameValidator`, дедуп суффиксом ` (2)`, ` (3)`…
  4. Удалить **только** файлы из `sub.files` (ручные конфиги и файлы других
     подписок не трогаем).
  5. Записать новые файлы (форматирование через `ConfigUtils.formatConfigContent`).
  6. Обновить реестр: новый `files`, `lastUpdated = now`.
  7. Починить `configFilesOrder` (убрать исчезнувшие, добавить новые в конец
     блока подписки) и `selectedConfigPath` (если указывал на удалённый файл —
     сброс в `null`).
- `suspend fun delete(sub)` — удалить файлы группы + запись из реестра + чистка
  `configFilesOrder`, `selectedConfigPath`.

**Гарантия целостности:** любая ошибка до шага 4 оставляет предыдущее состояние
нетронутым.

### 5. ViewModel

Расширяем существующий `MainViewModel` (там уже живёт логика конфигов и скачки):

- `subscriptions: StateFlow<List<Subscription>>`
- `subscriptionSyncState: StateFlow<Map<String, SyncStatus>>` — статус по `id`
  (Idle / Syncing / Error) для спиннера и сообщения об ошибке на карточке.
- `addSubscription(name, url)`, `refreshSubscription(id)`, `deleteSubscription(id)`.
- После рефреша — `refreshConfigFileList()` (уже есть), и если сервис запущен и
  выбранный файл сменился — существующий `onReloadConfig`.

### 6. UI — секция в экране `Config`

`ConfigScreen` дорабатывается:

- **Сверху** — список подписок карточками: имя, кол-во серверов,
  «обновлено N назад», иконки 🔄 (обновить, во время рефреша — спиннер) и 🗑
  (удалить всю подписку, с подтверждением). Кнопка/строка «+» → диалог
  (поля: имя, URL).
- **Ниже** — серверы, сгруппированные заголовками подписок; в конце секция
  «Свои» для ручных конфигов. Карта `filename -> subscriptionId` строится из
  реестра; файлы не в одной подписке = ручные.
- Выбор сервера, редактор — как сейчас. **У серверов подписки НЕТ иконки
  удаления** (заблокировано). Удаление доступно только ручным конфигам.
- **Reorder** остаётся только для секции «Свои». Серверы подписки идут в порядке
  из подписки.

### 7. Обработка ошибок

- Сеть упала / не-2xx / битый base64 / 0 распознанных ссылок → снекбар с текстом
  ошибки, состояние подписки и файлы целые.
- Коллизии имён серверов → дедуп суффиксом.
- Выбранный сервер удалён рефрешем → `selectedConfigPath = null`.
- Рефреш при запущенном сервисе, если выбранный файл заменён → reload конфига.

## Тестирование

Юнит-тесты (JUnit, как в проекте):

- Каждый парсер ссылок (`vmess/ss/trojan/vless`) на реальных сэмплах →
  корректный `DetectedConfig` с ожидаемым outbound.
- `SubscriptionParser`: base64-тело, plain-тело, смесь валидных и мусорных строк
  (мусор пропущен), пустой результат.
- Дедуп/санитизация имён файлов.
- `SubscriptionManager` sync-логика с фейковым fetcher'ом:
  - `add` → `refresh` → файлы созданы, реестр обновлён, `lastUpdated` выставлен.
  - повторный `refresh` с другим набором → старые файлы удалены, новые записаны,
    `configFilesOrder`/`selectedConfigPath` починены, ручные конфиги живы.
  - ошибка сети → предыдущие файлы и реестр не тронуты.
  - `delete` → файлы группы удалены, ручные не тронуты.

## Вне scope v1 (YAGNI)

- Авто-обновление в фоне (WorkManager).
- Формат Clash-YAML и sip008.
- Реордер серверов внутри подписки.
- Модель «свап outbound» (один контейнер на подписку).
- Правила роутинга — отдельная фича, отдельный спек.
