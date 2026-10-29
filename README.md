# Fervo TV

[![CI](https://github.com/Divarion-D/FervoTV/actions/workflows/ci.yml/badge.svg)](https://github.com/Divarion-D/FervoTV/actions/workflows/ci.yml)

Нативный IPTV-плеер для Android: телефоны, планшеты, Android TV и приставки — один APK.
Управление и пальцами, и пультом.

## Возможности (v0.1)

- **Источники**
  - M3U / M3U8 плейлист по ссылке (http/https) или из локального файла;
  - Xtream Codes API (сервер, логин, пароль). Потоки берутся в TS, галочкой можно переключить на HLS (m3u8).
- **Форматы потоков:** HLS (m3u8), MPEG-TS, MP4 — через Media3 ExoPlayer.
  Если ссылка без расширения не открылась как обычный файл, плеер один раз пробует её как HLS.
- **Live TV:** категории (`group-title` / `#EXTGRP` / категории Xtream), поиск по названию, переключение каналов.
- **Каналы плитками:** логотип и название. Порядок поиска логотипа:
  1. из плейлиста — `tvg-logo` или `logo` (относительный путь достраивается от адреса плейлиста), из Xtream — `stream_icon`;
  2. **автоподгрузка**: если логотипа нет или он не открылся — поиск по `tvg-id`, затем по названию канала в базе
     [iptv-org](https://github.com/iptv-org/api) (~30 тыс. логотипов). Хранятся только совпавшие с каналами плейлиста,
     остальные отбрасываются. Подбор повторяется при смене плейлиста и раз в 7 дней;
  3. инициалы названия.
- **Настройки:** источник плейлиста, автоподгрузка логотипов (вкл/выкл), источник базы логотипов с кнопкой «По умолчанию».
- **О приложении** (внизу настроек): версия и номер сборки, автор, ссылка на GitHub, «Что нового» — журнал изменений.
- **Пульт и сенсор:**
  - на телефоне — категории полосой кнопок над списком; на широком экране (≥ 600 dp, TV, планшет) — колонкой слева;
  - на TV при старте фокус сразу на первом канале, после выхода из плеера — на последнем просмотренном.

## Спецификация и план развития

Полная функциональная спецификация — в [`docs/spec/`](docs/spec/): 222 функции с критериями приёмки,
целевая архитектура (`core` + `app-tv` + `app-mobile`) и дорожная карта
[`docs/spec/90-roadmap.md`](docs/spec/90-roadmap.md).
Что уже сделано — в строке «Статус» у каждой функции: ✅ есть · 🟡 частично (в скобках — чего не хватает или что не проверено) · ⬜ нет.
Облик, токены, компоненты и каркасы экранов — дизайн-система [`docs/spec/33-design.md`](docs/spec/33-design.md),
иллюстрация в палитре — [`docs/design/mockup.html`](docs/design/mockup.html) (открыть в браузере).

| Фаза | Что входит | Функций |
|---|---|---|
| P0 | Фундамент: модули, база данных, потоковые парсеры, интерфейс движка плеера | 28 |
| P1 | Эфир со списком и предпросмотром, телегид (EPG), боковое меню, часы, подсказки кнопок, избранное, файл настроек, русский и английский интерфейс | 39 |
| P2 | Фильмы и сериалы, главная, поиск, история | 33 |
| P3 | Плеер: дорожки, субтитры, FFmpeg-аудио, журнал ошибок, PiP, DRM | 28 |
| P4 | Несколько источников, Stalker, архив передач, напоминания | 22 |
| P5 | Профили и PIN, резервные копии, запись, загрузки, мультиэкран | 38 |
| P6 | Метаданные, спорт, оформление, ввод с телефона, обновления | 34 |

Спецификация составлена по принципу «белой комнаты»: ориентиром был набор функций открытого
плеера OwnTV (GPL-3.0), но описано только наблюдаемое поведение и своими словами, исходный код не
использовался. Правила для всех, кто пишет код по спецификации, — в
[`docs/spec/CLEANROOM.md`](docs/spec/CLEANROOM.md), журнал — в [`CLEANROOM_LOG.md`](docs/spec/CLEANROOM_LOG.md).

## Управление в плеере

| Действие | Пульт / клавиатура | Сенсор |
|---|---|---|
| Следующий канал | ▲, CH+, PageUp, ⏭ | свайп вверх |
| Предыдущий канал | ▼, CH−, PageDown, ⏮ | свайп вниз |
| Инфо о канале / повтор после ошибки | OK, Enter | тап |
| Пауза / продолжить | Play/Pause, Пробел | — |
| Инфо о канале | Menu, Info, Guide | — |
| Назад к списку | Back | жест/кнопка «назад» |

Переключение идёт по текущему отфильтрованному списку (выбранная категория и поиск) и закольцовано.

## Сборка

Нужны JDK 17 и Android SDK (platform 35, build-tools 35). Gradle ставить не нужно — в проекте есть wrapper.

Проще всего — через `make` (`make` без аргументов покажет все цели):

```bash
make check        # как CI на каждый коммит: тесты + lint + debug-сборка
make test         # все unit-тесты;  make test-fast — только ядро
make dist         # подписанные dist/FervoTV-<версия>.apk и .aab + проверка подписи
make ci-release   # как release.yml: тесты и lint release, затем dist
make install run  # собрать, поставить release на телефон по adb и запустить (APP=mobile — мобильное)
make install-debug run-debug  # debug — отдельным приложением «Fervo TV debug», release не трогает
```

Пути к JDK и SDK по умолчанию — `~/.local/share/jdk-17` и `~/Android/Sdk`, переопределяются переменными
`JAVA_HOME` и `ANDROID_HOME`. То же самое вручную через Gradle:

```bash
export JAVA_HOME=~/.local/share/jdk-17     # или системный JDK 17+
export ANDROID_HOME=~/Android/Sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties   # один раз

./gradlew testDebugUnitTest lintDebug   # то же, что CI на каждый коммит (все модули)
./gradlew assembleDebug       # app-tv/build/outputs/apk/debug/app-tv-debug.apk (~13 МБ) и app-mobile/…/app-mobile-debug.apk
./gradlew assembleRelease     # app-tv/build/outputs/apk/release/app-tv-release.apk (~2,5 МБ, R8) и app-mobile/…
./gradlew :app-tv:bundleRelease   # app-tv/build/outputs/bundle/release/app-tv-release.aab — для Google Play
```

Модули: `core` — всё, что не UI (модели, парсеры, Xtream, база данных, логотипы, HTTP, команды ввода, `Defaults`);
`app-tv` и `app-mobile` — приложения (пока с одинаковым UI). До конца P0 выпускается `app-tv`: он ставится и на TV,
и на телефоны. Подробнее — в [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Release подписывается автоматически, если настроен ключ (см. «Ключ подписи» выше); без ключа получается
`app-tv-release-unsigned.apk`.

Интерфейс сделан в стиле Material 3. Цветовая схема (тёмная, ярко-оранжевая, тона из material-color-utilities) —
в `app-tv/src/main/java/app/iptvplayer/ui/Theme.kt`, иконки Material Symbols — в `app-tv/src/main/res/drawable/ic_*.xml`
(в `app-mobile` — такие же копии).
Подробности — в [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#дизайн-material-3).

**Скорость оценивайте только по release-сборке.** Debug-сборка Compose идёт без оптимизаций и в разы медленнее.
Замер на TECNO CH6i: запуск 1,4–1,9 с (debug) против 0,6–0,7 с (release); логотипы появляются через 1,5–2,3 с
после окна (debug) против 0,35–0,4 с (release).

Debug ставится отдельным приложением «Fervo TV debug» (`io.github.divarion_d.fervotv.debug`) рядом с release:
у них разные ключи подписи и свои данные — плейлист в debug настраивается заново.

### Выпуск новой версии
1. `build.gradle.kts` (корневой, общий для обоих приложений): увеличить `versionCode` (на 1) и `versionName` (например `0.2`).
2. `core/src/main/assets/CHANGELOG.md`: раздел версии сверху — `## 0.2 — ДД.ММ.ГГГГ` со строками `- …`.
   Этот же файл показывается в приложении («Настройки → Что нового») и становится описанием релиза на GitHub.
3. Обновить строки «Статус» в [`docs/spec/`](docs/spec/), закоммитить, запушить и поставить тег:
   ```bash
   git tag v0.2 && git push origin v0.2
   ```
   GitHub Actions (`release.yml`) проверит, что тег совпадает с `versionName`, прогонит тесты и lint, соберёт
   подписанные `FervoTV-0.2.apk` и `FervoTV-0.2.aab` (из `app-tv`, до конца P0 — один APK для TV и телефонов) и создаст GitHub Release.

### Ключ подписи
- Ключ: `~/.android/fervotv-release.jks` (alias `fervotv`), пароли — в `~/.gradle/gradle.properties`
  (`fervo.keystore`, `fervo.keystorePassword`, `fervo.keyAlias`, `fervo.keyPassword`). В репозиторий не попадают.
- На CI те же значения лежат в секретах репозитория: `FERVO_KEYSTORE_BASE64` (файл .jks в base64),
  `FERVO_KEYSTORE_PASSWORD`, `FERVO_KEY_ALIAS`, `FERVO_KEY_PASSWORD`.
- **Сделайте резервную копию ключа и паролей** (например, в менеджер паролей). Если ключ потерять,
  обновления приложения больше не установятся поверх уже установленного — только удаление и установка заново.
- Отпечаток сертификата (SHA-256): `A6:5F:7A:24:25:FA:8F:9F:83:0C:4D:AE:CC:DD:6A:C7:DB:A7:39:C9:5B:19:7A:5F:20:35:B5:DB:62:EA:D0:A2`.

## Установка на устройство

1. Телефон: «Настройки → О телефоне», 7 раз нажать на «Номер сборки». Затем «Для разработчиков → Отладка по USB».
   Android TV: то же через «Настройки → Об устройстве → Сборка», затем «Отладка по сети» или ADB.
2. Подключить и проверить:
   ```bash
   ~/Android/Sdk/platform-tools/adb devices          # устройство в статусе "device"
   ~/Android/Sdk/platform-tools/adb install -r app-tv/build/outputs/apk/debug/app-tv-debug.apk
   ```
   Для TV по сети: `adb connect <ip-приставки>:5555`.
3. Логи плеера: `adb logcat | grep -iE "ExoPlayer|iptvplayer"`.

## Использование

1. При первом запуске выберите **M3U / M3U8** или **Xtream Codes**.
   - M3U: вставьте ссылку на плейлист или нажмите «Выбрать файл». Если не указать `http://`, он подставится сам.
   - Xtream: сервер в виде `http://host:port`, логин и пароль.
2. «Загрузить» — источник сохраняется и загружается автоматически при следующих запусках.
3. Сменить источник: «Настройки» (рядом с поиском) → «Источник плейлиста…».

Значения по умолчанию (источник логотипов, User-Agent, таймауты, задержки интерфейса) собраны в одном файле:
`core/src/main/java/app/iptvplayer/core/Defaults.kt`.

Тестовый публичный плейлист (~10 тыс. каналов): `https://iptv-org.github.io/iptv/index.m3u`.

## Документация

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — устройство приложения, слой ввода (пульт/сенсор), как расширять.
- [CHANGELOG.md](core/src/main/assets/CHANGELOG.md) — журнал изменений (тот же файл показывается в приложении).

## Стек

Kotlin 2.1, Jetpack Compose (Material3), Media3 ExoPlayer 1.5 (+HLS), Coil 2.7 (логотипы), minSdk 21 (Android 5.0), targetSdk 35.
Сеть — `HttpURLConnection`, JSON — `JsonReader` / `org.json` из платформы, без сторонних HTTP/JSON-библиотек.
Данные — Room 2.6 (SQLite, FTS4) + Paging 3.3. Тесты — JUnit 4 + Robolectric 4.14.
