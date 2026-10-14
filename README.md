# Red Button (`pl.leon.redbutton`)

**Red Button** to lokalna, działająca całkowicie offline aplikacja na Androida, która pozwala ocenić gotowość urządzenia oraz — po świadomym uzbrojeniu — przy następnym uruchomieniu wysłać **jednorazowe** natywne żądanie przywrócenia ustawień fabrycznych przez oficjalne API Android `DevicePolicyManager` (albo wykonać bezpieczną symulację w `TEST MODE`).

Pełna specyfikacja produktowa i techniczna znajduje się w pliku **[SPECIFICATION.md](SPECIFICATION.md)**.

Dodatkowa dokumentacja projektu:
- **[PRIVACY_POLICY.md](PRIVACY_POLICY.md)** — Polityka prywatności (działanie 100% offline, brak `INTERNET`, wyłączony backup, zakres danych lokalnych).
- **[PRODUCT_CARD.md](PRODUCT_CARD.md)** — Uczciwa karta produktu, opis do sklepu/katalogu MDM (PL/EN) oraz procedura zgłoszeń i wsparcia.
- **[TESTING_AND_RELEASE.md](TESTING_AND_RELEASE.md)** — Macierz testowa API 23–36, testy ról `DevicePolicyManager` przez ADB oraz konfiguracja podpisu wydania (`keystore.properties.example`).
- **[TEST_MATRIX_REPORT.md](TEST_MATRIX_REPORT.md)** — Raport z automatycznej weryfikacji macierzy API 22–36, ról zarządzania, ciągłości tożsamości i testów UI Compose/Robolectric.

---

## Opis dla osób nietechnicznych

Red Button pozwala przygotować telefon lub tablet z Androidem do jednorazowego resetu fabrycznego przy kolejnym otwarciu aplikacji:

1. **Pierwsze uruchomienie niczego nie kasuje** — aplikacja sprawdza stan urządzenia, blokadę ekranu, szyfrowanie oraz uprawnienia administratora i wyświetla czytelne podsumowanie.
2. **Świadome uzbrojenie** — użytkownik może wybrać bezpieczny tryb symulacji (`TEST MODE`) albo tryb produkcyjny (`REAL MODE`), zapoznać się z konsekwencjami i uzbroić jedną akcję.
3. **Automatyczne działanie przy następnym otwarciu** — kolejne uruchomienie aplikacji natychmiast zużywa zapisaną zgodę (bez drugiego ekranu potwierdzenia) i wykonuje symulację albo wysyła jedno żądanie resetu do systemu Android.

### Przykłady użycia
- **Przygotowanie urządzenia służbowego:** administrator IT przygotowuje zarządzany telefon firmowy do kontrolowanego resetu przed przekazaniem kolejnemu pracownikowi.
- **Test procedury w `TEST MODE`:** zespół IT lub właściciel sprawdza na urządzeniu testowym, czy konfiguracja Androida kwalifikuje się do pełnego resetu, bez ryzyka skasowania danych.
- **Audyt gotowości polityk Android Enterprise:** weryfikacja ścieżki `wipeData()` (API 23–33) oraz `wipeDevice()` (API 34+) dla ról Device Owner i właściciela profilu organizacyjnego.

### Ważne ograniczenia
- **Brak gwarancji wymazania:** żadna aplikacja ani wersja Androida nie gwarantuje, że system przyjmie żądanie, ukończy reset lub fizycznie wymaże każdą komórkę pamięci flash.
- **Brak działania zdalnego i w tle:** Red Button nie wykrywa kradzieży, nie lokalizuje telefonu i nie działa zdalnie.
- **Brak przycisku „anuluj” po uzbrojeniu:** po uzbrojeniu ponowne otwarcie aplikacji uruchamia akcję (również wtedy, gdy otworzy ją właściciel). Jedyną ścieżką rozbrojenia przed kolejnym startem jest wyczyszczenie danych aplikacji w Ustawieniach Androida (o ile polityka administratora na to pozwala).
- **Wymagania Androida 14+ (API 34+):** zwykła prywatna instalacja nie może sama nadać sobie roli `Device Owner`; wymagane jest oficjalne provisionowanie Android Enterprise poza aplikacją.

---

## Pobieranie APK

Gotowy plik APK (zbudowany automatycznie przez GitHub Actions) jest dostępny pod adresem:

**https://github.com/Leonfbd/red-button/releases/download/v1.0/red-button.apk**

Strona wydania: https://github.com/Leonfbd/red-button/releases/tag/v1.0

---

## Parametry techniczne

- **Identyfikator aplikacji (`applicationId`):** `pl.leon.redbutton`
- **Zakres SDK:** `minSdk = 23` (Android 6.0), `targetSdk = 36`, `compileSdk = 36`
- **Technologia:** Kotlin, Jetpack Compose, Material 3
- **Ścieżki API:**
  - API 23: `DevicePolicyManager.wipeData(int)` (wyłącznie `Device Owner`)
  - API 24–33: `DevicePolicyManager.wipeData(int)` (`Device Owner` lub aktywny `Device Admin` w głównym/systemowym użytkowniku)
  - API 34+: `DevicePolicyManager.wipeDevice(int)` (`Device Owner` lub kwalifikowany właściciel profilu organizacyjnego przez instancję DPM profilu nadrzędnego)
- **Prywatność i bezpieczeństwo:**
  - Brak uprawnienia `android.permission.INTERNET`
  - Wyłączony Android Backup i transfer z urządzenia na urządzenie (`android:allowBackup="false"`)
  - Jednorazowy rekord zgody z kontrolą wersji schematu i integralności `SHA-256`, zużywany przed analizą wykonawczą i wywołaniem dostawcy
  - Ochrona przed ponownym wykonaniem przy rekreacji `Activity` (np. obrocie ekranu)

---

## Budowanie i testowanie lokalnie

Wymagania: JDK 17 oraz Android SDK.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Wynikowy plik debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
