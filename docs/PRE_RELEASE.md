# Предрелизная подготовка Fervo TV

Этот чеклист описывает выпуск публичной версии для Android TV, телефона и планшета. В GitHub Release публикуется только подписанный универсальный APK. AAB можно собрать локально для магазина, но он не должен попадать в публичный релиз.

## 1. Подготовить рабочее дерево

Перед началом:

```bash
git status --short
git diff --check
git switch main
git pull --ff-only origin main
```

Рабочее дерево должно содержать только намеренные изменения. Проверьте [`docs/spec/90-roadmap.md`](spec/90-roadmap.md): выполненные пункты отмечены `✅`, частичные — `🟡`, незавершённые не включайте в обещания релиза.

## 2. Обновить версию и журнал

1. В корневом [`build.gradle.kts`](../build.gradle.kts) увеличьте `versionCode`. Он никогда не уменьшается: Android использует его для обновления установленного приложения.
2. Задайте `versionName` строго в формате `major.minor.patch`, например `0.2.0`.
3. В начало [`core/src/main/assets/CHANGELOG.md`](../core/src/main/assets/CHANGELOG.md) добавьте раздел с той же версией:

   ```text
   ## 0.2.0 — 31.10.2026
   - Короткое описание изменения
   ```

4. Обновите статусы затронутых пунктов в `docs/spec/`.
5. Проверьте совпадение версии:

   ```bash
   make version
   grep -n 'versionName' build.gradle.kts
   grep -n '^## ' core/src/main/assets/CHANGELOG.md | head
   ```

## 3. Проверить подпись

Подпись берётся из `signing/` или переменных `FERVO_*`. Файлы ключа и пароли нельзя добавлять в git.

```bash
make release
make verify
```

Сохраните отпечаток сертификата и убедитесь, что он совпадает с предыдущими версиями. Без того же ключа Android не установит обновление поверх текущей версии.

## 4. Автоматические проверки

До создания тега выполните полный локальный gate:

```bash
make check          # debug-тесты, lint и debug-сборка
make test           # unit-тесты всех модулей
make ci-release     # release-тесты, lint, подписанный APK и проверка подписи
```

После push проверьте CI для `main`. Релизный workflow дополнительно проверяет формат тега, запускает release-тесты и lint, собирает `app-tv` и проверяет APK через `apksigner`.

## 5. Проверить на устройствах

Подключите ТВ или телефон по USB/ADB over Wi‑Fi и выполните:

```bash
make devices
make install run
```

Минимальный smoke-тест:

- запуск и возврат после обновления без потери настроек;
- навигация крестовиной: `Home`, `Эфир`, поиск, история, настройки;
- `OK`, `Back`, двойной `Back`, полноэкранный плеер и возврат к списку;
- включение/выключение превью и проверка, что список не исчезает;
- запуск канала и обработка ошибки воспроизведения;
- смена языка, файла настроек и источника;
- проверка версии в «О приложении».

Для Wi‑Fi ADB сначала подключите устройство командой `adb connect <адрес>:<порт>`, затем повторите `make devices`.

## 6. Создать публичный релиз

Закоммитьте и отправьте изменения в `main`, затем создайте аннотированный тег, совпадающий с `versionName`:

```bash
git add build.gradle.kts core/src/main/assets/CHANGELOG.md docs/spec
git commit -m "Prepare Fervo TV 0.2.0 release"
git push origin main
git tag -a v0.2.0 -m "Fervo TV 0.2.0"
git push origin v0.2.0
```

GitHub Actions создаст [GitHub Release](https://github.com/Divarion-D/FervoTV/releases) и прикрепит только `FervoTV-0.2.0.apk`.

Проверка после завершения workflow:

```bash
gh run list --workflow Release --limit 3
gh release view v0.2.0 --repo Divarion-D/FervoTV --json tagName,isDraft,isPrerelease,assets,url
```

В `assets` должен быть APK и не должно быть AAB. Если workflow упал, релиз не распространяйте: исправьте причину новым коммитом и используйте следующий patch-тег.

## 7. Что не публиковать

- ключ подписи, `signing.properties`, пароли и тестовые учётные данные;
- внутренние CI-артефакты, если в них есть секреты или неподготовленная сборка;
- AAB в GitHub Release;
- debug APK как публичный релиз;
- релизный тег до прохождения CI.

`make bundle` остаётся локальной командой для ручной подготовки Google Play и не является частью публичного GitHub-релиза.

## 8. После публикации

Скачайте APK из релиза, установите его поверх предыдущей версии и проверьте:

```bash
adb install -r FervoTV-0.2.0.apk
adb shell dumpsys package io.github.divarion_d.fervotv | grep -E 'version(Name|Code)'
```

Если релиз уже раздан, не переиспользуйте тот же `versionCode`: исправление выпускайте новой patch-версией.
