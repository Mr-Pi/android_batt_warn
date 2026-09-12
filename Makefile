SHELL := bash

APK         := app/build/outputs/apk/debug/app-debug.apk
RELEASE_APK := app/build/outputs/apk/release/app-release-unsigned.apk
PKG         := com.example.battwarn

.PHONY: help shell build release install run clean uninstall devices

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

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

clean: ## Clean build outputs
	nix-shell --run 'gradle clean'

uninstall: ## Uninstall the app from device
	nix-shell --run 'adb uninstall $(PKG)'

devices: ## List connected Android devices
	nix-shell --run 'adb devices'