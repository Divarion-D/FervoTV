# Архитектура

> Это описание **текущего** состояния (после шагов P0.1 — разбиение на модули — и P0.2 — база данных и потоковый
> импорт). Остальное из P0 — интерфейс движка плеера и пр. — описано в
> [`docs/spec/01-architecture.md`](spec/01-architecture.md) и ещё не сделано.

Три Gradle-модуля:

| Модуль | Что | Зависит от |
|---|---|---|
| `core` | Android-библиотека **без Compose**: модели, источники и потоковые парсеры, база данных (Room, FTS, Paging), логотипы, HTTP, команды ввода, `Defaults`, `Repository` | — |
| `app-tv` | приложение (Compose, Media3): экраны, `MainActivity`, ресурсы. Manifest с `LEANBACK_LAUNCHER` и обычным `LAUNCHER` | `core` |
| `app-mobile` | то же приложение для телефонов: пока **копия UI** `app-tv`, manifest только с обычным `LAUNCHER`, без баннера | `core` |

До конца P0 выпускается **один APK — `app-tv`** (он, как и раньше, ставится и на TV, и на телефоны);
`app-mobile` собирается и проверяется lint, но в релиз пока не идёт. UI двух приложений разойдётся в P1.
Оба приложения имеют один `applicationId` (`io.github.divarion_d.fervotv`) и одну подпись: установка одного
заменяет другое с сохранением данных. Отдельные пакеты — решение владельца при выпуске.

Версия (`versionCode` / `versionName`) и подпись release — общие, в корневом `build.gradle.kts`
(`subprojects { plugins.withId("com.android.application") … }`), чтобы их нельзя было рассинхронизировать.

Ни DI, ни navigation-библиотеки, ни ViewModel: состояние экранов живёт в Compose (`remember`), источник и настройки — в
`SharedPreferences`, каналы — в базе SQLite (Room), экраны получают их как `Flow` / `PagingData`.

## Файлы

```
build.gradle.kts           версии плагинов; общие для app-tv/app-mobile версия, подпись release (secret()), R8
core/src/main/java/app/iptvplayer/core/
  Defaults.kt              ВСЕ значения по умолчанию и настраиваемые константы (для обоих приложений)
  Repository.kt            хранение Source и Settings; savedAt / load (импорт в БД) / categories / channels / channelAt /
                           matchLogos; migrateLegacyCache (playlist.tsv → БД)
  model/Model.kt           Channel (+ autoLogo, id), Source (M3u | Xtream), Settings, PlaylistRefresh, NO_GROUP
  db/Db.kt                 Room: таблицы source / category / channel / channel_fts (FTS4), PlaylistDao, AppDb, searchText()
  db/PlaylistStore.kt      импорт пачками, ChannelFilter, выдача: категории со счётчиками, Paging, канал по номеру
  source/m3u/M3uParser.kt  parseM3u(reader, baseUrl) { channel } — построчно; parseM3u(text) -> List<Channel> для тестов
  source/xtream/Xtream.kt  loadXtream(source, onCategory, onChannel): список каналов читается JsonReader-ом по элементу
  source/PlaylistCache.kt  чтение старого playlist.tsv (только для переноса в БД), Source.cacheKey()
  logos/LogoIndex.kt       база логотипов iptv-org: скачивание, кэш, поиск по tvg-id / названию; normName()
  logos/Backdrop.kt        pickBackdrop(pixels, n): выбор подложки логотипа по пикселям (чистая функция)
  net/Http.kt              httpGet(url), httpStream(url) { … }: User-Agent, таймауты, редиректы http↔https
  input/Action.kt          Action, keyAction(keyCode) — таблица «клавиша → команда»
core/schemas/…/AppDb/<версия>.json  экспорт схемы БД (Room) по версиям — основа для миграций, хранится в репозитории
core/src/main/assets/CHANGELOG.md  журнал изменений: «## версия — дата» + строки «- …»; попадает в оба APK, показывается в приложении
core/src/test/java/app/iptvplayer/core/
  source/m3u/M3uParserTest.kt, source/m3u/M3uStreamTest.kt, source/xtream/XtreamTest.kt, source/PlaylistCacheTest.kt,
  db/PlaylistStoreTest.kt, logos/LogoIndexTest.kt, logos/BackdropTest.kt; TestUtil.kt (SyntheticM3u, usedHeap),
  source/LegacyCache.kt (запись/чтение playlist.tsv целиком — для фикстур)

app-tv/src/main/java/app/iptvplayer/       (app-mobile/… — такая же копия)
  MainActivity.kt     App(): выбор экрана — Setup / Настройки / загрузка / ошибка / каналы; загрузка базы логотипов
  Input.kt            Compose-часть ввода: KeyEvent.toAction() (→ keyAction), Modifier.touchActions(), isTv(), initialFocus()
  ui/SetupScreen.kt   ввод источника: SegmentedButton M3U/Xtream, поля с иконками
  ui/SettingsScreen.kt настройки (TopAppBar + SettingItem), «О приложении», экран «Что нового» (ChangelogScreen)
  ui/Theme.kt         AppTheme: цветовая схема Material 3 (сгенерирована, см. «Дизайн»)
  ui/Components.kt    focusRing, BackButton, IconLabel, SettingItem — общие элементы
  ui/ChannelsScreen.kt поиск (M3 search bar), категории (NavigationDrawerItem / FilterChip), сетка ChannelCard
  ui/ChannelLogo.kt   логотип канала (Coil) с подложкой из pickBackdrop; BrokenLogos; инициалы, если логотипа нет
  ui/PlayerScreen.kt  ExoPlayer, оверлей, обработка Action
app-tv/src/main/res/drawable/ic_*.xml   иконки Material Symbols Rounded (banner.xml — только в app-tv)
.github/workflows/ci.yml       на каждый push/PR: testDebugUnitTest + lintDebug + assembleDebug всех модулей (оба debug APK — артефакт)
.github/workflows/release.yml  тег v<versionName>: тесты и lint всех модулей, подписанные APK/AAB app-tv, GitHub Release с текстом из CHANGELOG.md
```

Пакеты Kotlin: `app.iptvplayer.core.*` в `core`; в приложениях — прежние `app.iptvplayer` и `app.iptvplayer.ui`
(namespace обоих приложений — `app.iptvplayer`, у `core` — `app.iptvplayer.core`). Изменения в UI пока
вносятся в обе копии; проверить, что они не разошлись: `diff -r app-tv/src/main/java app-mobile/src/main/java`.

## Дизайн (Material 3)

- **Цвета** (`ui/Theme.kt`) — полная тёмная схема M3 со всеми ролями, включая `surfaceContainer*`, в «яркой» версии.
  Тона взяты из официальной библиотеки Google `@material/material-color-utilities` 0.3.0 (`TonalPalette`, исходный цвет `#FF8C1A`):
  - `primary` — сам исходный цвет (тон 70, насыщенность 63) вместо пастельного тона 80, который M3 даёт по умолчанию;
    `onPrimary` — тон 15 той же палитры (контраст 6,5:1);
  - поверхности — почти нейтральная палитра (насыщенность 3): оранжевый на ней выделяется, а не тонет в коричневом;
  - выбранные чипы, пункты боковой панели и сегментированные кнопки закрашены `primary` (цвета заданы в компонентах).
- **Референсы**:
  - [Android TV: Cards](https://developer.android.com/design/ui/tv/guides/components/cards) и
    [Compose for TV](https://android-developers.googleblog.com/2023/05/building-pixel-perfect-living-room-experiences-compose-for-tv.html):
    на TV нужен «big, bold focus» (увеличение, рамка, свечение, цвет), логотипам подходит 16:9 или 1:1, на карточках — без длинных подписей,
    навигация — боковая панель;
  - IPTV-плееры (Tvizo, Smarters и др.): нейтрально-тёмный фон и один яркий тёплый акцент.
- **Фокус карточки канала** по гайду TV: заливка `primary`, увеличение до 1,06 и свечение цвета `primary`
  (`zIndex`, чтобы увеличенная карточка рисовалась поверх соседних).
- **Подложка логотипа** (`ui/ChannelLogo.kt` рисует, `core/logos/Backdrop.kt` выбирает): одна неподвижная подложка не подходит половине логотипов
  (чёрный текст пропадает на тёмном, белый — на светлом). Поэтому после загрузки картинка уменьшается до 24×24
  и по её пикселям `pickBackdrop` выбирает подложку (результат кэшируется по URL):
  - логотип со своим фоном (непрозрачен на 90% и больше) — сплошной цвет его краёв, логотип как бы заполняет плитку;
  - тёмный логотип (тёмный в среднем или больше 25% почти чёрных пикселей) — светлый тёплый градиент;
  - светлый или цветной — диагональный градиент, окрашенный в основной цвет логотипа;
  - логотипа нет или он ещё грузится — тот же градиент фирменного оранжевого цвета, плюс инициалы.
  Радиальное «свечение» пробовали и отказались: смотрится как пятно.
- **Битые логотипы** (`BrokenLogos` в `ui/ChannelLogo.kt`): ссылка, ответившая 4xx, запоминается в
  `SharedPreferences("broken_logos")` (ключ — URL, значение — время) и больше не загружается: плитка сразу берёт следующий вариант.
  Если с одного сервера пришло `Defaults.BROKEN_LOGO_HOST_FAILS` (3) ответов 4xx и ни одной удачной загрузки,
  запоминается весь сервер (`host:<имя>`), и логотипы с него больше не запрашиваются уже в этом запуске.
  Записи живут `Defaults.BROKEN_LOGO_RETRY_MS` (7 дней). Сетевые сбои (таймаут, нет сети) не запоминаются.
- **Подбор логотипов запускается сразу после загрузки плейлиста**, в той же корутине, а не в эффекте,
  который ждёт первого кадра сетки.
  Логотипы грузятся с `User-Agent: Defaults.USER_AGENT`: Wikimedia отвечает 403 на стандартный `okhttp/…`.
- **Поиск**: в покое это кнопка-плашка, поле ввода с клавиатурой открывается только по OK или тапу.
  Иначе на TV одно перемещение фокуса на поле открывает клавиатуру, и пульт в ней застревает.
- **Иконки** — Material Symbols Rounded из [google/material-design-icons](https://github.com/google/material-design-icons)
  (`symbols/android/<name>/materialsymbolsrounded/<name>_24px.xml`). Из XML удалён `android:tint="?attr/colorControlNormal"`
  (это атрибут AppCompat, которого в проекте нет), цвет задаёт `Icon` в Compose. Новая иконка: скачать по тому же пути
  и сохранить как `res/drawable/ic_<name>.xml`.
- **Компоненты**: строка поиска в стиле M3 search bar; категории — `NavigationDrawerItem` со счётчиками (широкий экран / TV)
  или `FilterChip` (телефон); каналы — `Card`; настройки — `ListItem` + `Switch`; источник — `SegmentedButton`;
  кнопки с иконками (`IconLabel`). Плеер — во весь экран (системные панели скрыты), инфо-плашка на градиенте.
- **Edge-to-edge**: `enableEdgeToEdge` (светлые иконки системных панелей), каждый экран сам делает `safeDrawingPadding()`.
  Плеер — во весь экран.
- **Фокус для пульта**: `Modifier.focusRing(shape)` рисует рамку 3 dp цвета `primary` вокруг элемента в фокусе
  (стандартная подсветка фокуса в M3 с дивана почти не видна). Модификатор ставится перед кликабельными модификаторами компонента.

## Значения по умолчанию и настройки

- `core/Defaults.kt` — единственное место для дефолтов и констант обоих приложений: значения настроек (`AUTO_LOGOS`, `LOGO_SOURCE`),
  сеть (`USER_AGENT`, таймауты, редиректы, срок кэша логотипов), база (`IMPORT_BATCH_SIZE`, `CHANNEL_PAGE_SIZE`), интерфейс (debounce поиска, время показа инфо, порог свайпа).
- `Settings` (Model.kt) — то, что меняет пользователь; поля по умолчанию берутся из `Defaults`.
  Хранятся в `SharedPreferences("settings")`: `autoLogos`, `logoSource`. Отдельно от источника плейлиста (`"iptv"`).
- **О приложении**: версия читается из установленного пакета (`PackageManager`), поэтому всегда совпадает с
  `versionName` / `versionCode` в корневом `build.gradle.kts`, а в debug-сборке помечается `· debug`.
  `Defaults.AUTHOR` и `Defaults.GITHUB_URL` — автор и ссылка. Если браузера нет (частый случай на TV-приставках),
  ссылка показывается во всплывающем сообщении.
- **Новая настройка** = поле в `Settings` + дефолт в `Defaults` + чтение/запись в `Repository.settings` (всё в `core`) + строка в `SettingsScreen` (в обоих приложениях)
  (`SettingItem` с `trailing = { Switch(...) }` для переключателя).
- Источник логотипов меняется кнопкой «Применить», а не по каждому символу: смена источника перекачивает базу.

## Поток данных

```
SetupScreen ──Source──▶ Repository.source (SharedPreferences "iptv")
                              │
App: LaunchedEffect(source) ──▶ Repository.load(source)  [Dispatchers.IO]
                              ├─ M3u:    httpStream / ContentResolver ─▶ parseM3u(reader) — по строке
                              └─ Xtream: loadXtream ─▶ player_api.php — get_live_streams по элементу
                              ▼  каналы по одному
                        PlaylistStore.import ──пачки по 1000, транзакции──▶ SQLite (fervo.db)
                                                                              │ Flow / PagingData
ChannelsScreen ◀── categories(source) (счётчики) + channels(ChannelFilter) ───┘
                    фильтр = ключ источника + категория + поиск (FTS, debounce 250 мс)
                    клик ──▶ PlayerScreen(число каналов, номер, channelAt(filter, i))
```

- **Загрузка** запускается в `App` вне веток `when`, поэтому открытие и отмена редактора источника не перезагружают плейлист.
- **Сначала сохранённое, потом сеть** (`App`, `LaunchedEffect(source, attempt)`):
  1. `Repository.savedAt(source)` — если в базе есть каналы этого источника, сетка сразу открывается из базы,
     и параллельно запускается подбор логотипов;
  2. если `settings.playlistRefresh.isDue(savedAt)`, в фоне вызывается `refresh()` → `Repository.load()`.
     Пока идёт импорт, на экране старые каналы; новые подменяют их одной транзакцией в конце. Ошибка при показанном
     списке — только сообщение (Toast) и текст в настройках, сохранённое остаётся рабочим без интернета;
  3. сохранённого нет (первый запуск или новый источник) — экран загрузки с кнопкой «Настройки», при ошибке — экран ошибки.
  Экраны подписаны на базу: после подмены категории, счётчики и сетка обновляются сами, без сброса категории,
  поиска, прокрутки и фокуса (прокрутка в начало — только когда пользователь сам меняет фильтр).

### База данных (`core/db/`)

Room 2.6 (SQLite), файл `fervo.db`, версия схемы 1, схема экспортируется в `core/schemas/` (каждое изменение схемы —
новая версия и миграция; разрушающего сброса нет: в эту же базу позже придут пользовательские данные).

| Таблица | Столбцы | Индексы |
|---|---|---|
| `source` | `id`, `sourceKey` (= `Source.cacheKey()`), `savedAt` (null — импорт ещё идёт) | `sourceKey` |
| `category` | `id`, `sourceId`, `name`, `position` (порядок провайдера / первого появления) | `(sourceId, name)` unique |
| `channel` | `id`, `sourceId`, `categoryId`, `position`, `name`, `url`, `logo`, `tvgId`, `autoLogo` | `(sourceId, position)`, `(sourceId, categoryId, position)` |
| `channel_fts` | FTS4, `rowid` = `channel.id`, `name` = `searchText(name)` | — |

- **Строка `source` — это один импорт.** Импорт создаёт новую строку с `savedAt = null` и пишет её категории и каналы
  пачками (`Defaults.IMPORT_BATCH_SIZE` = 1000 каналов на транзакцию). Экраны видят только строку с `savedAt`, поэтому
  во время импорта показывают прежние каналы. В конце одна транзакция ставит `savedAt` и удаляет все остальные строки
  (у приложения один сохранённый источник, как было с `playlist.tsv`). Ошибка, отмена или **пустой ответ** удаляют
  недописанный импорт — сохранённое не затирается; остатки импорта, прерванного гибелью процесса, удаляются перед следующим.
  Импорты (загрузка, перенос старого кэша) идут по одному (`Mutex` в `Repository`).
- **Привязка к источнику**: запросы ищут каналы по `sourceKey`, другой URL или аккаунт Xtream их не подхватит.
- **Выдача экранам** (`PlaylistStore`): `categories(key)` — `Flow` непустых категорий по порядку со счётчиками;
  `channels(ChannelFilter)` — `PagingData` (страница `Defaults.CHANNEL_PAGE_SIZE`, плейсхолдеры включены: число элементов
  известно сразу, прокрутка переживает подмену); `channelAt(filter, i)` — канал по номеру (переключение в плеере).
  `ChannelFilter` = ключ источника + имя категории + поиск, а не id строк: после фонового обновления тот же фильтр
  (и тот же `Pager`) показывает новые строки. Категория и источник находятся некоррелированными подзапросами,
  поэтому SQLite идёт по индексу `(sourceId, categoryId, position)`.
- **Поиск** — FTS4 по названиям, совпадение по началу слов: запрос «мат фут» → `мат* фут*` (все слова, И).
  Стандартный токенизатор SQLite сворачивает регистр только у латиницы, поэтому и в индекс, и в запрос идёт
  `searchText()`: нижний регистр, `ё→е`, всё, кроме букв и цифр, — пробел. Поиск — внутри выбранной категории.
  Индекс заполняет импорт (не триггеры), так что запись `autoLogo` его не трогает.
- **Перенос `playlist.tsv`** (v0.1 хранил последний плейлист в `filesDir/playlist.tsv`: строка `#<ключ>\t<время>`, потом
  `имя\tURL\tгруппа\tлоготип\ttvg-id`). При первом `savedAt()` новой версии файл построчно импортируется в базу с его
  ключом и временем загрузки (офлайн-старт и расписание обновления продолжаются как были) и **удаляется**; повреждённый
  файл тоже удаляется (v0.1 его бы проигнорировал). Источник и настройки в `SharedPreferences` не меняются.
- **Автообновление** (`Settings.playlistRefresh`, по умолчанию `Defaults.PLAYLIST_REFRESH = ON_START`):
  при каждом запуске / каждые 6 часов / раз в день / раз в неделю / только вручную. Сохранённые каналы открываются сразу при любом варианте.
  «Обновить плейлист сейчас» в настройках запускает `refresh()` принудительно.
- **Плеер рисуется поверх списка**, а не вместо него. Поэтому фильтры, позиция прокрутки и фокус сохраняются без отдельного state holder. Плеер забирает фокус и поглощает все D-pad-клавиши, чтобы фокус не «провалился» в список под ним.
- **Плитки каналов**: `LazyVerticalGrid` с `GridCells.Adaptive` — 104 dp на узком экране (3 колонки на телефоне), 150 dp на широком.
  Элементы — из `LazyPagingItems` (`paging-compose`): в памяти только загруженные страницы, ещё не загруженные позиции
  рисуются пустыми карточками. Номер на карточке — позиция в отфильтрованном списке.
- **Плеер** получает число каналов отфильтрованного списка и `channelAt(i)`; CH+/CH− идут по кругу по этому списку,
  каждый канал читается из базы по номеру.
  Логотип грузит Coil (`AsyncImage`, кэш в памяти и на диске). При `logo == null` или ошибке загрузки показываются инициалы.
- **Поворот экрана** не пересоздаёт Activity (`configChanges` в манифесте), поэтому поток не перезапускается.

### Хранение источника

`SharedPreferences("iptv")`:

| ключ | значение |
|---|---|
| `type` | `"m3u"` или `"xtream"` |
| `url` | для m3u: `http(s)://…` или `content://…` |
| `server`, `user`, `pass`, `hls` | для xtream |

Пароль хранится открытым текстом в приватном хранилище приложения.

## M3U-парсер

- Читает `Reader` построчно (`parseM3u(reader, baseUrl) { channel -> … }`), файл целиком в память не загружается;
  ошибка в одной записи пропускает только её.
- Снимает BOM, понимает `\r\n`, пропускает пустые строки и неизвестные теги (`#EXTVLCOPT`, `#KODIPROP` и т.п.).
- `#EXTINF`: атрибуты `key="value"` (используются `tvg-id`, `tvg-logo`, `group-title`, `tvg-name`).
  Имя канала — всё после **первой запятой вне кавычек**, поэтому `group-title="A, B",Канал, HD` разбирается корректно.
- Группа: `group-title` → `#EXTGRP` → «Без категории».
- Логотип: `tvg-logo` → нестандартный `logo`. Относительный путь достраивается от URL плейлиста
  (`parseM3u(text, baseUrl)`); для локального файла остаётся как есть.
- Строки-URL без `#EXTINF` тоже принимаются (имя берётся из последнего сегмента пути).

## Автоподгрузка логотипов

`Repository.matchLogos(source, logoSource)` — в `App` после открытия сохранённого списка, после каждого обновления и при смене
настроек логотипов. `LogoIndex.load(ctx, logoSource, channels)` получает каналы из БД как `Sequence` (читается страницами по
1000, не целиком), а найденный логотип записывается в `channel.autoLogo` (только изменившиеся строки, пачками).
Каналы показываются сразу, логотипы появляются, когда подбор закончился. Автоподгрузка выключена — `autoLogo` очищается.

1. Скачивает `{source}/logos.json` (`channel → url`; основной логотип важнее вариантов для отдельных фидов)
   и `{source}/channels.json` (`id`, `name`, `alt_names`). Это ~14 МБ JSON (по сети ~2 МБ в gzip),
   поэтому разбор идёт потоково через `android.util.JsonReader`, без загрузки всего файла в память.
2. Строит полный индекс в памяти: `"id:<id в нижнем регистре>" → url` и `normName(имя) → url`
   для всех имён и альтернативных названий (~60 тыс. ключей).
3. **Оставляет только то, что используется:** логотипы, совпавшие с каналами текущего плейлиста
   (для ruiptv — около 10 КБ вместо 4 МБ). Полный индекс после этого отбрасывается, кэши других источников удаляются.
4. Пишет результат в `cacheDir/logos-<hash источника>.tsv`. Первая строка — отпечаток плейлиста
   (`#<число каналов>:<сумма хэшей tvg-id|name>` — не зависит от порядка и считается за один проход), дальше строки `ключ\turl`.
   Пересборка (повторное скачивание) происходит, если плейлист изменился или кэшу больше 7 дней
   (`Defaults.LOGO_INDEX_MAX_AGE_MS`). Если пересобрать не удалось, используется старый кэш.

`normName`: нижний регистр, `ё→е`, убираются теги качества (`hd fhd uhd 4k sd hevc orig` — только как отдельные слова)
и всё, кроме букв и цифр. На тестовом плейлисте ruiptv совпало 190 из 215 каналов.

`ChannelLogo` перебирает кандидатов `[логотип из плейлиста, Channel.autoLogo]` и переходит к следующему при ошибке загрузки.
Если не подошёл ни один — показывает инициалы.

## Xtream Codes

```
GET {server}/player_api.php?username=U&password=P                          → user_info.auth == 1
GET …&action=get_live_categories  → [{category_id, category_name}]
GET …&action=get_live_streams     → [{stream_id, name, category_id, stream_icon, epg_channel_id}]
URL потока: {server}/live/{U}/{P}/{stream_id}.ts   (или .m3u8, если включён флаг HLS)
```

Ответ авторизации и список категорий маленькие — их читает `org.json`. Список каналов может быть огромным, поэтому
`readLiveStreams` читает его `android.util.JsonReader`-ом элемент за элементом. Поля принимаются и строкой, и числом;
`null`, вложенные объекты и записи без `stream_id` пропускаются (ломается только эта запись). Категории передаются
в базу в порядке провайдера до каналов.

`httpGet` (`core/net/Http.kt`) сам обрабатывает редиректы (до 5): `HttpURLConnection` не переходит между http и https.

## Воспроизведение

`ExoPlayer` + `DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, DefaultHttpDataSource.Factory()))`:

- тип потока определяется по расширению URL: `.m3u8` → HLS, остальное → progressive (TS, MP4 и др.);
- `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` у элемента без MIME → повтор с `APPLICATION_M3U8`
  (ссылки на HLS без расширения);
- `ERROR_CODE_BEHIND_LIVE_WINDOW` → `seekToDefaultPosition()` + `prepare()`;
- остальные ошибки выводятся в оверлее, OK вызывает `prepare()` заново;
- при уходе приложения в фон (`ON_STOP`) воспроизведение ставится на паузу, при возврате продолжается (`LifecycleStartEffect`);
- User-Agent — константа `Defaults.USER_AGENT`, один и тот же для плейлистов и потоков.

## Ввод: пульт и сенсор

Главная идея: экраны не знают про keycode и pointer-события, они работают с `Action`.
Команды и таблица клавиш — в `core/input/Action.kt` (`keyAction(keyCode)`, без Compose);
Compose-обёртки (`KeyEvent.toAction()`, `Modifier.touchActions`, `initialFocus`) — в `Input.kt` каждого приложения.

```kotlin
enum class Action { Up, Down, Left, Right, Ok, ChannelUp, ChannelDown, PlayPause, Menu }
```

```
Пульт / клавиатура ── KeyEvent.toAction() → keyAction() ──┐
                                                          ├──▶ handle(Action)   (PlayerScreen)
Сенсор ──────────── Modifier.touchActions ───────────────┘
Back (пульт, кнопка, жест) ──▶ BackHandler
```

- **Списки, сетка и формы** не обрабатывают клавиши вручную. Элементы — обычные `clickable` (`FocusItem`, `ChannelTile`), поэтому навигация D-pad по фокусу (в том числе по сетке `LazyVerticalGrid`) и касания работают средствами Compose. Элемент в фокусе подсвечивается фоном и рамкой — без этого пультом пользоваться невозможно.
- **Плеер**: `onKeyEvent { it.toAction() }` на фокусируемом `Box` и прозрачный слой `touchActions` поверх `PlayerView` вызывают одну и ту же `handle(Action)`. `PlayerView` фокус не получает (`FOCUS_BLOCK_DESCENDANTS`).
- **TV-режим** (`Context.isTv()`, `UI_MODE_TYPE_TELEVISION`) только задаёт начальный фокус (`Modifier.initialFocus()`, фокус в `ChannelsScreen`). Раскладка выбирается по ширине экрана, а не по типу устройства.
- **Back** в `Action` не входит: `BackHandler` одинаково ловит кнопку пульта, кнопку телефона и жест «назад».

### Как добавить новый тип ввода

1. Геймпад или цифровые кнопки — добавить keycode в `keyAction()` в `core` (или новое значение в `Action`).
2. Новый жест — добавить детектор в `Modifier.touchActions`.
3. Новое действие в плеере — ветка в `handle` в `PlayerScreen.kt`. `when` по `Action` исчерпывающий, поэтому компилятор укажет все места, которые нужно обновить.

`Left`/`Right` в плеере пока ничего не делают — зарезервированы под список каналов поверх видео и аудиодорожки.

## Манифест

`app-tv`:
- `LEANBACK_LAUNCHER` + `android:banner` — приложение появляется в лаунчере Android TV; обычный `LAUNCHER` — и в лаунчере телефона.
- `android.software.leanback` и `android.hardware.touchscreen` с `required="false"` — один APK ставится и на TV, и на телефоны.

`app-mobile`: только обычный `LAUNCHER`, без баннера и `uses-feature`.

Оба: `usesCleartextTraffic="true"` — IPTV почти всегда работает по http; одинаковые `configChanges`.

## Как расширять

| Задача | Где |
|---|---|
| VOD / сериалы Xtream | `Xtream.kt`: `get_vod_categories`, `get_vod_streams`, `get_series`, `get_series_info`; URL `/movie/U/P/{id}.{ext}`, `/series/U/P/{id}.{ext}` |
| EPG | `Xtream.kt`: `get_short_epg&stream_id=`; для M3U — XMLTV из `url-tvg` в `#EXTM3U` по `Channel.tvgId` |
| Несколько плейлистов / избранное | `Repository`: список `Source` вместо одного; избранное — множество URL в prefs |
| User-Agent/Referer из плейлиста | `M3uParser`: разбирать `#EXTVLCOPT:http-user-agent`, хранить в `Channel`, передавать в `DefaultHttpDataSource.Factory.setDefaultRequestProperties` |

## Тесты

`./gradlew testDebugUnitTest` (все тесты — в `core`, JVM; 27 тестов). Тесты базы и `JsonReader` идут под **Robolectric**:
Room и `android.util.JsonReader` требуют Android-классов, а Robolectric даёт настоящий SQLite (с FTS4) прямо в JVM —
без устройства и эмулятора, на CI тем же `testDebugUnitTest`. Куча тестовой JVM ограничена 256 МБ (`core/build.gradle.kts`).
- `M3uParserTest` — атрибуты, запятая в кавычках и в имени, `#EXTGRP`, CRLF/BOM/неизвестные теги, голый список URL,
  fallback на `tvg-name`, `logo`/`tvg-logo` и относительные пути;
- `LogoIndexTest` — `normName` и порядок поиска (сначала tvg-id, потом название);
- `PlaylistCacheTest` — формат старого `playlist.tsv`, ключ источника, `PlaylistRefresh.isDue`;
- `BackdropTest` — выбор подложки логотипа по пикселям;
- `M3uStreamTest` — разбор из `Reader` совпадает с разбором текста; 100 000 записей без роста кучи;
- `XtreamTest` (Robolectric) — потоковый `get_live_streams`: порядок, категории, HLS-флаг, битые записи, не-массив;
- `PlaylistStoreTest` (Robolectric, база в файле) — запись/чтение, категории со счётчиками и порядком, FTS-поиск
  (регистр, ё/е, префиксы, несколько слов, внутри категории), канал по номеру, привязка к источнику; пустой и
  оборванный импорт не затирают сохранённое; старые каналы видны до конца импорта; `autoLogo`; перенос `playlist.tsv`;
  **импорт 100 000 каналов**: M3U генерируется на лету (сам вход памяти не занимает), каждые 10 000 каналов —
  `System.gc()` и замер занятой кучи; критерий — рост от первого замера до максимума < 8 МБ (сам список каналов
  занял бы ~40 МБ). Сейчас: рост — единицы КБ, импорт ~4 с, FTS-поиск по 100 000 — ~30 мс.

Загрузка базы логотипов, HTTP и UI unit-тестами не покрыты — их проверяют на устройстве. Тест миграций схемы
появится со второй версией схемы (сейчас версия 1, мигрировать не из чего).
