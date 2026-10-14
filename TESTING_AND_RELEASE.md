# Przewodnik Testowania i Wydania — Red Button (`pl.leon.redbutton`)

Dokument opisuje sposób uruchamiania testów automatycznych, procedurę testowania macierzy urządzeń i ról `DevicePolicyManager` oraz konfigurację podpisu wydania zgodnie z Sekcją 10 i 11 `SPECIFICATION.md`.

---

## 1. Testy automatyczne (JVM, Robolectric Compose UI oraz Lint)

Wszystkie testy automatyczne korzystają wyłącznie z atrapy `FakeWipeExecutionProvider` i **nigdy nie wywołują produkcyjnego `wipeData()` ani `wipeDevice()`**.

Uruchomienie pełnego zestawu weryfikacyjnego:
```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin --no-daemon
```

Zakres pokryty w `RedButtonUnitTest` oraz `RedButtonComposeRobolectricTest`:
- Nieinwazyjna macierz poziomów **API 23, 24, 27, 28, 33, 34, 35 i 36** bez roli właściciela w `TEST MODE`.
- Granica odczytu cechy eSIM (`FEATURE_TELEPHONY_EUICC`): **API 27 (`UNKNOWN_PRE_API_28`) kontra API 28 (`PRESENT` / `ABSENT`)** oraz opt-in `WIPE_EUICC` wyłącznie na **API 34+**.
- Reguły ról zarządzania:
  - API 23: `DEVICE_OWNER` (akceptowany) kontra zwykły `DEVICE_ADMIN` (odrzucany).
  - API 24–33: `DEVICE_OWNER` i `DEVICE_ADMIN` w głównym użytkowniku (akceptowani) kontra `PROFILE_OWNER` i `SECONDARY_OR_PROFILE_USER` (odrzucani).
  - API 34–36: instalacja prywatna / `DEVICE_ADMIN` (odrzucani) kontra `DEVICE_OWNER` oraz `ORG_OWNED_PROFILE_OWNER` z nadrzędną instancją DPM (akceptowani).
- Klasyfikacja poziomów sanitizacji (Poziomy 0–3; brak możliwości zwrócenia Poziomu 4; brak podnoszenia poziomu przez samo szyfrowanie).
- Ciągłość tożsamości sprzętu (`>= 3` znane pola `hardware-family`, odporność na aktualizację poprawek bezpieczeństwa).
- Bezpieczny fallback do `TEST MODE` włączonego przy błędzie odczytu pamięci (`FR-03`).
- Walidacja schematu, typów i skrótu `SHA-256` jednorazowego zapisu zgody, blokada zamknięcia ekranu przy błędzie zapisu oraz atomowe zużycie przed analizą wykonawczą przy wywołaniach współbieżnych (`FR-04`).
- Brak ponownego wykonania akcji przy rekreacji `Activity` / odtworzeniu ekranu wyniku (`Sekcja 9 i 10`).

---

## 2. Testy laboratoryjne ról zarządzania (Emulatory / Urządzenia testowe)

> **Ostrzeżenie:** Testy laboratoryjne należy zaczynać wyłącznie w `TEST MODE` na dedykowanych emulatorach lub urządzeniach testowych bez danych produkcyjnych.

### Nadanie roli `Device Owner` na czystym emulatorze/urządzeniu laboratoryjnym (przez ADB)
Na urządzeniu bez dodanych kont użytkowników po instalacji APK:
```bash
adb shell dpm set-device-owner pl.leon.redbutton/.admin.RedButtonDeviceAdminReceiver
```

Po nadaniu roli otwórz aplikację Red Button i kliknij **Odśwież odczyt urządzenia**:
- Pole **Rola zarządzania** pokaże `DEVICE_OWNER`.
- Na API 23–33 wybrana zostanie ścieżka `DevicePolicyManager.wipeData(int)`.
- Na API 34+ wybrana zostanie ścieżka `DevicePolicyManager.wipeDevice(int)`.
- Aby urządzenie uzyskało status `GOTOWOŚĆ DO WYSŁANIA ŻĄDANIA`, w ustawieniach systemu musi być ponadto włączona bezpieczna blokada ekranu (PIN/hasło/wzór) oraz aktywne szyfrowanie pamięci (`ACTIVE` lub `ACTIVE_PER_USER`).

### Usunięcie roli `Device Admin` (gdy nadano zwykłego administratora w Ustawieniach)
Przejdź do: **Ustawienia Androida → Bezpieczeństwo → Aplikacje administrujące urządzeniem → Red Button → Dezaktywuj**.

---

## 3. Protokół testu destrukcyjnego (`REAL MODE`)

Zgodnie z Sekcją 10 specyfikacji test w `REAL MODE` wolno przeprowadzić wyłącznie:
1. Na osobnym urządzeniu lub emulatorze przeznaczonym do całkowitego skasowania.
2. Po przejściu wszystkich testów automatycznych i uprzednim sprawdzeniu zachowania w `TEST MODE`.
3. Z przygotowaną procedurą odzyskania urządzenia (znane poświadczenia konta, brak blokady FRP lub znane dane odblokowania FRP).
4. Dokumentując wynik jako **„Android przyjął/odmówił żądania (`WIPE REQUESTED` / `REFUSED`)”**, nigdy jako „wymazanie 100%”.

---

## 4. Bezpieczne podpisywanie wydania (Release Signing — Sekcja 11)

Projekt obsługuje podpis wydania (`release`) konfigurowany poza repozytorium:
1. Wygeneruj klucz wydania i zabezpiecz jego kopię zapasową w bezpiecznym magazynie:
   ```bash
   keytool -genkeypair -v -keystore red-button-release.jks -keyalg RSA -keysize 4096 -validity 9125 -alias redbutton
   ```
2. Utwórz plik `keystore.properties` (ignorowany przez `.gitignore`) na podstawie `keystore.properties.example` lub przekaż zmienne środowiskowe `RED_BUTTON_KEYSTORE_PATH`, `RED_BUTTON_KEYSTORE_PASSWORD`, `RED_BUTTON_KEY_ALIAS`, `RED_BUTTON_KEY_PASSWORD`.
3. Zbuduj podpisany pakiet release:
   ```bash
   gradle :app:assembleRelease
   ```
