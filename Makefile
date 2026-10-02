# SPDX-FileCopyrightText: 2026 Batt Warn authors
# SPDX-License-Identifier: MIT

SHELL := bash

APK         := app/build/outputs/apk/debug/app-debug.apk
RELEASE_APK := app/build/outputs/apk/release/app-release-unsigned.apk
PKG         := com.example.battwarn
AVD         := test
SYS_IMG     := system-images;android-35;google_apis_playstore;x86_64
DEVICE      := pixel_6

# Passed to the scripts, which also carry these as defaults.
AVD_ENV     := AVD="$(AVD)" SYS_IMG="$(SYS_IMG)" DEVICE="$(DEVICE)"

# One instrumented-test run plus the per-test PASS/FAIL summary, preserving
# gradle's exit status. Shared by `test` and `test-boot` so they stay in sync.
RUN_TESTS   := gradle connectedDebugAndroidTest; status=$$?; bash scripts/test-report.sh; exit $$status

.PHONY: help shell build release install run clean lint unit avd-clean emulator-clean clean-all uninstall devices avd emulator test test-boot screenshots

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'

shell: ## Enter the nix-shell dev environment
	nix-shell

build: ## Build debug APK
	nix-shell --run 'gradle assembleDebug'

release: ## Build release APK (unsigned)
	nix-shell --run 'gradle assembleRelease'

install: build ## Build and install debug APK to connected device
	nix-shell --run 'adb install -r $(APK)'

run: install ## Install and launch the app on device
	nix-shell --run 'adb shell am start -n $(PKG)/.MainActivity'

clean: ## Clean build outputs (gradle clean)
	nix-shell --run 'gradle clean'

lint: ## Android Lint (debug variant)
	nix-shell --run 'gradle lintDebug'

unit: ## JVM unit tests (no emulator needed)
	nix-shell --run 'gradle testDebugUnitTest'

avd-clean: ## Stop the emulator and delete the AVD "$(AVD)"
	nix-shell --run '$(AVD_ENV) bash scripts/remove-avd.sh'

emulator-clean: ## Uninstall app + test APK, stop emulator, delete AVD image data
	nix-shell --run '$(AVD_ENV) PKG="$(PKG)" bash scripts/clean-emulator.sh'

clean-all: clean emulator-clean ## Clean build outputs and full emulator/AVD teardown

uninstall: ## Uninstall the app and the instrumented test APK from device
	nix-shell --run 'adb uninstall $(PKG).test >/dev/null 2>&1 && echo "removed $(PKG).test" || echo "$(PKG).test was not installed"; adb uninstall $(PKG)'

devices: ## List connected Android devices
	nix-shell --run 'adb devices'

avd: ## Create the API 35 AVD "$(AVD)" if it does not exist
	nix-shell --run '$(AVD_ENV) bash scripts/ensure-avd.sh'

emulator: ## Boot the API 35 headless emulator (AVD "$(AVD)") if not running
	nix-shell --run '$(AVD_ENV) bash scripts/emulator-up.sh'

test: ## Run instrumented tests (requires a running emulator)
	nix-shell --run '$(RUN_TESTS)'

test-boot: ## Reboot the emulator (boot it if needed) then run instrumented tests
	nix-shell --run 'REBOOT=1 $(AVD_ENV) bash scripts/emulator-up.sh || exit 1; $(RUN_TESTS)'

screenshots: ## Capture/refresh docs/screenshots from a running emulator
	nix-shell --run '$(AVD_ENV) bash scripts/emulator-up.sh || exit 1; bash scripts/screenshots.sh'

