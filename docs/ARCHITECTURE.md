# Архитектура

Один модуль `app`, пакет `app.iptvplayer`. Ни DI, ни navigation-библиотеки, ни ViewModel: состояние живёт
в Compose (`remember`), источник хранится в `SharedPreferences`.

## Файлы

```
app/src/main/java/app/iptvplayer/
  MainActivity.kt     App(): выбор экрана — Setup / Настройки / загрузка / ошибка / каналы; загрузка базы логотипов
  Defaults.kt         ВСЕ значения по умолчанию и настраиваемые константы
  Model.kt            Channel, Source (M3u | Xtream), Settings, NO_GROUP
  LogoIndex.kt        база логотипов iptv-org: скачивание, кэш, поиск по tvg-id / названию; normName()
  M3uParser.kt        parseM3u(text) -> List<Channel>
  Xtream.kt           loadXtream(source) -> List<Channel>
  Repository.kt       хранение Source, load(source), httpGet(url), USER_AGENT
  Input.kt            Action, KeyEvent.toAction(), Modifier.touchActions(), isTv(), initialFocus()
  ui/SetupScreen.kt   ввод источника: SegmentedButton M3U/Xtream, поля с иконками
  ui/SettingsScreen.kt настройки (TopAppBar + SettingItem), «О приложении», экран «Что нового» (ChangelogScreen)
app/src/main/assets/CHANGELOG.md     журнал изменений: «## версия — дата» + строки «- …»; показывается в приложении
  ui/Theme.kt         AppTheme: цветовая схема Material 3 (сгенерирована, см. «Дизайн»)
  ui/Components.kt    focusRing, BackButton, IconLabel, SettingItem — общие элементы
  ui/ChannelsScreen.kt поиск (M3 search bar), категории (NavigationDrawerItem / FilterChip), сетка ChannelCard
  ui/ChannelLogo.kt   логотип канала с подложкой, подобранной по пикселям логотипа; инициалы, если логотипа нет
app/src/main/res/drawable/ic_*.xml   иконки Material Symbols Rounded
  ui/PlayerScreen.kt  ExoPlayer, оверлей, обработка Action
app/src/test/java/app/iptvplayer/M3uParserTest.kt
app/src/test/java/app/iptvplayer/LogoIndexTest.kt
app/src/test/java/app/iptvplayer/PlaylistCacheTest.kt
.github/workflows/ci.yml       на каждый push/PR: testDebugUnitTest + lintDebug + assembleDebug (debug APK — артефакт)
.github/workflows/release.yml  тег v<versionName>: тесты, lint, подписанные APK/AAB, GitHub Release с текстом из CHANGELOG.md
```

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
- **Подложка логотипа** (`ui/ChannelLogo.kt`): одна неподвижная подложка не подходит половине логотипов
  (чёрный текст пропадает на тёмном, белый — на светлом). Поэтому после загрузки картинка уменьшается до 24×24
  и по её пикселям выбирается подложка (результат кэшируется по URL):
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

- `Defaults.kt` — единственное место для дефолтов и констант: значения настроек (`AUTO_LOGOS`, `LOGO_SOURCE`),
  сеть (`USER_AGENT`, таймауты, редиректы, срок кэша логотипов), интерфейс (debounce поиска, время показа инфо, порог свайпа).
- `Settings` (Model.kt) — то, что меняет пользователь; поля по умолчанию берутся из `Defaults`.
  Хранятся в `SharedPreferences("settings")`: `autoLogos`, `logoSource`. Отдельно от источника плейлиста (`"iptv"`).
- **О приложении**: версия читается из установленного пакета (`PackageManager`), поэтому всегда совпадает с
  `versionName` / `versionCode` в `app/build.gradle.kts`, а в debug-сборке помечается `· debug`.
  `Defaults.AUTHOR` и `Defaults.GITHUB_URL` — автор и ссылка. Если браузера нет (частый случай на TV-приставках),
  ссылка показывается во всплывающем сообщении.
- **Новая настройка** = поле в `Settings` + дефолт в `Defaults` + чтение/запись в `Repository.settings` + строка в `SettingsScreen`
  (`SettingItem` с `trailing = { Switch(...) }` для переключателя).
- Источник логотипов меняется кнопкой «Применить», а не по каждому символу: смена источника перекачивает базу.

## Поток данных

```
SetupScreen ──Source──▶ Repository.source (SharedPreferences "iptv")
                              │
App: LaunchedEffect(source) ──▶ Repository.load(source)  [Dispatchers.IO]
                              ├─ M3u:    httpGet / ContentResolver ─▶ parseM3u
                              └─ Xtream: loadXtream ─▶ player_api.php
                              ▼
                        List<Channel> ──▶ ChannelsScreen
                                             │ фильтр (категория + поиск) [Dispatchers.Default, debounce 250 мс]
                                             ▼
                                         shown ──клик──▶ PlayerScreen(shown, index)
```

- **Загрузка** запускается в `App` вне веток `when`, поэтому открытие и отмена редактора источника не перезагружают плейлист.
- **Сначала кэш, потом сеть** (`App`, `LaunchedEffect(source, attempt)`):
  1. `Repository.cachedPlaylist(source)` — если есть сохранённый список этого источника, он сразу показывается,
     и параллельно запускается подбор логотипов;
  2. если `settings.playlistRefresh.isDue(savedAt)`, в фоне вызывается `refresh()` → `Repository.load()`.
     Успех: список заменяется (только если он изменился) и сохраняется в кэш. Ошибка при показанном кэше —
     только сообщение (Toast) и текст в настройках, кэш остаётся рабочим без интернета;
  3. кэша нет (первый запуск или новый источник) — экран загрузки с кнопкой «Настройки», при ошибке — экран ошибки.
  `ChannelsScreen` подхватывает новый список и не сбрасывает при этом категорию, поиск и прокрутку
  (прокрутка в начало — только когда пользователь сам меняет фильтр).
- **Кэш плейлиста** (`PlaylistCache.kt`): `filesDir/playlist.tsv`, а не `cacheDir`, который система может очистить.
  Первая строка — `#<ключ источника>\t<время сохранения, мс>`, дальше по строке на канал:
  `имя\tURL\tгруппа\tлоготип\ttvg-id` (табы и переводы строк внутри значений заменяются пробелами).
  Ключ — хэш `Source`: другой URL или аккаунт Xtream кэш не подхватит. Пустой ответ сервера считается ошибкой
  и не затирает рабочий кэш.
- **Автообновление** (`Settings.playlistRefresh`, по умолчанию `Defaults.PLAYLIST_REFRESH = ON_START`):
  при каждом запуске / каждые 6 часов / раз в день / раз в неделю / только вручную. Сохранённый список открывается сразу при любом варианте.
  «Обновить плейлист сейчас» в настройках запускает `refresh()` принудительно.
- **Плеер рисуется поверх списка**, а не вместо него. Поэтому фильтры, позиция прокрутки и фокус сохраняются без отдельного state holder. Плеер забирает фокус и поглощает все D-pad-клавиши, чтобы фокус не «провалился» в список под ним.
- **Плитки каналов**: `LazyVerticalGrid` с `GridCells.Adaptive` — 104 dp на узком экране (3 колонки на телефоне), 140 dp на широком.
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

- Снимает BOM, понимает `\r\n`, пропускает пустые строки и неизвестные теги (`#EXTVLCOPT`, `#KODIPROP` и т.п.).
- `#EXTINF`: атрибуты `key="value"` (используются `tvg-id`, `tvg-logo`, `group-title`, `tvg-name`).
  Имя канала — всё после **первой запятой вне кавычек**, поэтому `group-title="A, B",Канал, HD` разбирается корректно.
- Группа: `group-title` → `#EXTGRP` → «Без категории».
- Логотип: `tvg-logo` → нестандартный `logo`. Относительный путь достраивается от URL плейлиста
  (`parseM3u(text, baseUrl)`); для локального файла остаётся как есть.
- Строки-URL без `#EXTINF` тоже принимаются (имя берётся из последнего сегмента пути).

## Автоподгрузка логотипов

`LogoIndex.load(ctx, source, channels)` — в `App` через `LaunchedEffect(settings.autoLogos, settings.logoSource, channels)`.
Каналы показываются сразу, логотипы из базы появляются, когда она готова.

1. Скачивает `{source}/logos.json` (`channel → url`; основной логотип важнее вариантов для отдельных фидов)
   и `{source}/channels.json` (`id`, `name`, `alt_names`). Это ~14 МБ JSON (по сети ~2 МБ в gzip),
   поэтому разбор идёт потоково через `android.util.JsonReader`, без загрузки всего файла в память.
2. Строит полный индекс в памяти: `"id:<id в нижнем регистре>" → url` и `normName(имя) → url`
   для всех имён и альтернативных названий (~60 тыс. ключей).
3. **Оставляет только то, что используется:** логотипы, совпавшие с каналами текущего плейлиста
   (для ruiptv — около 10 КБ вместо 4 МБ). Полный индекс после этого отбрасывается, кэши других источников удаляются.
4. Пишет результат в `cacheDir/logos-<hash источника>.tsv`. Первая строка — отпечаток плейлиста
   (`#<hash отсортированных tvg-id|name>`), дальше строки `ключ\turl`.
   Пересборка (повторное скачивание) происходит, если плейлист изменился или кэшу больше 7 дней
   (`Defaults.LOGO_INDEX_MAX_AGE_MS`). Если пересобрать не удалось, используется старый кэш.

`normName`: нижний регистр, `ё→е`, убираются теги качества (`hd fhd uhd 4k sd hevc orig` — только как отдельные слова)
и всё, кроме букв и цифр. На тестовом плейлисте ruiptv совпало 190 из 215 каналов.

`ChannelLogo` перебирает кандидатов `[логотип из плейлиста, логотип из базы]` и переходит к следующему при ошибке загрузки.
Если не подошёл ни один — показывает инициалы.

## Xtream Codes

```
GET {server}/player_api.php?username=U&password=P                          → user_info.auth == 1
GET …&action=get_live_categories  → [{category_id, category_name}]
GET …&action=get_live_streams     → [{stream_id, name, category_id, stream_icon, epg_channel_id}]
URL потока: {server}/live/{U}/{P}/{stream_id}.ts   (или .m3u8, если включён флаг HLS)
```

`httpGet` сам обрабатывает редиректы (до 5): `HttpURLConnection` не переходит между http и https.

## Воспроизведение

`ExoPlayer` + `DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, DefaultHttpDataSource.Factory()))`:

- тип потока определяется по расширению URL: `.m3u8` → HLS, остальное → progressive (TS, MP4 и др.);
- `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` у элемента без MIME → повтор с `APPLICATION_M3U8`
  (ссылки на HLS без расширения);
- `ERROR_CODE_BEHIND_LIVE_WINDOW` → `seekToDefaultPosition()` + `prepare()`;
- остальные ошибки выводятся в оверлее, OK вызывает `prepare()` заново;
- при уходе приложения в фон (`ON_STOP`) воспроизведение ставится на паузу, при возврате продолжается (`LifecycleStartEffect`);
- User-Agent — константа `USER_AGENT` в `Repository.kt`, один и тот же для плейлистов и потоков.

## Ввод: пульт и сенсор

Главная идея: экраны не знают про keycode и pointer-события, они работают с `Action`.

```kotlin
enum class Action { Up, Down, Left, Right, Ok, ChannelUp, ChannelDown, PlayPause, Menu }
```

```
Пульт / клавиатура ── KeyEvent.toAction() ──┐
                                            ├──▶ handle(Action)   (PlayerScreen)
Сенсор ──────────── Modifier.touchActions ─┘
Back (пульт, кнопка, жест) ──▶ BackHandler
```

- **Списки, сетка и формы** не обрабатывают клавиши вручную. Элементы — обычные `clickable` (`FocusItem`, `ChannelTile`), поэтому навигация D-pad по фокусу (в том числе по сетке `LazyVerticalGrid`) и касания работают средствами Compose. Элемент в фокусе подсвечивается фоном и рамкой — без этого пультом пользоваться невозможно.
- **Плеер**: `onKeyEvent { it.toAction() }` на фокусируемом `Box` и прозрачный слой `touchActions` поверх `PlayerView` вызывают одну и ту же `handle(Action)`. `PlayerView` фокус не получает (`FOCUS_BLOCK_DESCENDANTS`).
- **TV-режим** (`Context.isTv()`, `UI_MODE_TYPE_TELEVISION`) только задаёт начальный фокус (`Modifier.initialFocus()`, фокус в `ChannelsScreen`). Раскладка выбирается по ширине экрана, а не по типу устройства.
- **Back** в `Action` не входит: `BackHandler` одинаково ловит кнопку пульта, кнопку телефона и жест «назад».

### Как добавить новый тип ввода

1. Геймпад или цифровые кнопки — добавить keycode в `KeyEvent.toAction()` (или новое значение в `Action`).
2. Новый жест — добавить детектор в `Modifier.touchActions`.
3. Новое действие в плеере — ветка в `handle` в `PlayerScreen.kt`. `when` по `Action` исчерпывающий, поэтому компилятор укажет все места, которые нужно обновить.

`Left`/`Right` в плеере пока ничего не делают — зарезервированы под список каналов поверх видео и аудиодорожки.

## Манифест

- `LEANBACK_LAUNCHER` + `android:banner` — приложение появляется в лаунчере Android TV.
- `android.software.leanback` и `android.hardware.touchscreen` с `required="false"` — один APK ставится и на TV, и на телефоны.
- `usesCleartextTraffic="true"` — IPTV почти всегда работает по http.

## Как расширять

| Задача | Где |
|---|---|
| VOD / сериалы Xtream | `Xtream.kt`: `get_vod_categories`, `get_vod_streams`, `get_series`, `get_series_info`; URL `/movie/U/P/{id}.{ext}`, `/series/U/P/{id}.{ext}` |
| EPG | `Xtream.kt`: `get_short_epg&stream_id=`; для M3U — XMLTV из `url-tvg` в `#EXTM3U` по `Channel.tvgId` |
| Несколько плейлистов / избранное | `Repository`: список `Source` вместо одного; избранное — множество URL в prefs |
| User-Agent/Referer из плейлиста | `M3uParser`: разбирать `#EXTVLCOPT:http-user-agent`, хранить в `Channel`, передавать в `DefaultHttpDataSource.Factory.setDefaultRequestProperties` |

## Тесты

`./gradlew testDebugUnitTest`:
- `M3uParserTest` — атрибуты, запятая в кавычках и в имени, `#EXTGRP`, CRLF/BOM/неизвестные теги, голый список URL,
  fallback на `tvg-name`, `logo`/`tvg-logo` и относительные пути;
- `LogoIndexTest` — `normName` и порядок поиска (сначала tvg-id, потом название).

Код Xtream, загрузка базы логотипов и UI unit-тестами не покрыты: `org.json` и Compose требуют Android-рантайма. Их проверяют на устройстве.
