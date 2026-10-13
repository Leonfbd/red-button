# red-button

Prosta aplikacja na Androida: duży czerwony przycisk, licznik naciśnięć i krótka wibracja.

## Pobieranie

Gotowy plik APK znajdziesz w sekcji Releases:

**https://github.com/Leonfbd/red-button/releases/download/v1.0/red-button.apk**

Aby zainstalować na telefonie: pobierz plik, zezwól na instalację z tego źródła
(Ustawienia → Bezpieczeństwo / Instalacja nieznanych aplikacji) i otwórz plik APK.

## Budowanie lokalnie

Wymagania: JDK 17 oraz Android SDK (compileSdk 34).

```
gradle :app:assembleDebug
```

Wynik: `app/build/outputs/apk/debug/app-debug.apk`.

## Wymagania

Android 7.0 (API 24) lub nowszy.
