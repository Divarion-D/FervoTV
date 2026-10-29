# Fervo TV — сборка, тесты и выпуск. `make` или `make help` — список целей.
# Обёртка над ./gradlew. Ключ подписи release — из ~/.gradle/gradle.properties (fervo.*) или переменных FERVO_* (см. README).

JAVA_HOME    ?= $(HOME)/.local/share/jdk-17
ANDROID_HOME ?= $(HOME)/Android/Sdk
export JAVA_HOME ANDROID_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

GRADLE    := ./gradlew --console=plain
ADB       := $(ANDROID_HOME)/platform-tools/adb
APKSIGNER  = $(lastword $(sort $(wildcard $(ANDROID_HOME)/build-tools/*/apksigner)))
PKG       := io.github.divarion_d.fervotv
PKG_DEBUG := $(PKG).debug
ACTIVITY  := app.iptvplayer.MainActivity
VERSION   := $(shell sed -n 's/.*versionName = "\(.*\)".*/\1/p' build.gradle.kts)
DIST      := dist

# Какое приложение ставить/выпускать: tv (выпускается сейчас, работает и на телефонах) или mobile.
APP ?= tv
APK_RELEASE := app-$(APP)/build/outputs/apk/release/app-$(APP)-release.apk
APK_DEBUG   := app-$(APP)/build/outputs/apk/debug/app-$(APP)-debug.apk

.DEFAULT_GOAL := help
.PHONY: help version test test-fast lint check debug release release-mobile bundle dist ci-release \
        verify install install-debug uninstall uninstall-debug run run-debug devices clean

help: ## Список целей
	@echo "Fervo TV $(VERSION). Использование: make <цель> [APP=tv|mobile]"
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*## "}{printf "  %-15s %s\n",$$1,$$2}'

version: ## Текущая версия (versionName из build.gradle.kts)
	@echo $(VERSION)

local.properties:
	@echo "sdk.dir=$(ANDROID_HOME)" > $@

# --- Тесты и проверки ---

test: local.properties ## Все unit-тесты (debug и release, все модули)
	$(GRADLE) test

test-fast: local.properties ## Unit-тесты только ядра, debug — быстрый прогон
	$(GRADLE) :core:testDebugUnitTest

lint: local.properties ## Lint всех модулей
	$(GRADLE) lint

check: local.properties ## То же, что CI на каждый коммит: тесты + lint + debug-сборка
	$(GRADLE) testDebugUnitTest lintDebug assembleDebug

# --- Сборки ---

debug: local.properties ## Debug APK обоих приложений
	$(GRADLE) assembleDebug
	@ls -lh app-*/build/outputs/apk/debug/*.apk

release: local.properties ## Подписанный release APK (APP=tv|mobile) → dist/
	$(GRADLE) :app-$(APP):assembleRelease
	@test -f $(APK_RELEASE) || { echo "Нет подписанного APK: настройте ключ (README → «Ключ подписи»)"; exit 1; }
	@mkdir -p $(DIST)
	cp $(APK_RELEASE) $(DIST)/FervoTV-$(if $(filter mobile,$(APP)),mobile-)$(VERSION).apk
	@ls -lh $(DIST)/*.apk

release-mobile: ## Подписанный release APK мобильного приложения → dist/
	$(MAKE) release APP=mobile

bundle: local.properties ## AAB для Google Play (app-tv) → dist/
	$(GRADLE) :app-tv:bundleRelease
	@mkdir -p $(DIST)
	cp app-tv/build/outputs/bundle/release/app-tv-release.aab $(DIST)/FervoTV-$(VERSION).aab
	@ls -lh $(DIST)/*.aab

dist: release bundle verify ## APK + AAB + проверка подписи → dist/

ci-release: local.properties ## Как release.yml: тесты, lint, APK, AAB, проверка подписи → dist/
	$(GRADLE) testReleaseUnitTest lintRelease
	$(MAKE) dist

verify: ## Проверить подпись APK в dist/
	@ls $(DIST)/*.apk >/dev/null 2>&1 || { echo "В $(DIST)/ нет APK: сначала make release"; exit 1; }
	@for f in $(DIST)/*.apk; do echo "== $$f"; "$(APKSIGNER)" verify --print-certs "$$f" | grep -E "DN|SHA-256"; done

# --- Устройство (adb) ---

devices: ## Подключённые устройства
	$(ADB) devices -l

install: release ## Собрать и поставить release на устройство (данные сохраняются)
	$(ADB) install -r $(APK_RELEASE)

install-debug: local.properties ## Собрать и поставить debug — отдельное приложение «Fervo TV debug» рядом с release
	$(GRADLE) :app-$(APP):assembleDebug
	$(ADB) install -r $(APK_DEBUG)

uninstall: ## Удалить приложение с устройства (вместе с данными!)
	$(ADB) uninstall $(PKG)

uninstall-debug: ## Удалить debug-приложение с устройства
	$(ADB) uninstall $(PKG_DEBUG)

run: ## Запустить приложение на устройстве
	$(ADB) shell am start -n $(PKG)/$(ACTIVITY)

run-debug: ## Запустить debug-приложение на устройстве
	$(ADB) shell am start -n $(PKG_DEBUG)/$(ACTIVITY)

# --- Уборка ---

clean: ## Удалить результаты сборки и dist/ (на NTFS gradle clean иногда не может удалить lint-cache — это не страшно)
	-$(GRADLE) clean
	rm -rf $(DIST)
