# Karta Produktu, Opis Sklepowy i Warunki Użycia — Red Button (`pl.leon.redbutton`)

Dokument przygotowany zgodnie z Sekcją 10, 11 i 12 specyfikacji (`SPECIFICATION.md`). Nie obiecuje wykrywania kradzieży, zdalnego kasowania ani gwarantowanego wymazania danych.

---

## 1. Krótki opis produktu (PL — dla użytkowników i katalogu aplikacji)

**Red Button** to lokalna, działająca bez internetu aplikacja na Androida do oceny gotowości urządzenia oraz jednorazowego żądania przywrócenia ustawień fabrycznych przez oficjalne API systemu Android (`DevicePolicyManager`).

Pierwsze uruchomienie niczego nie kasuje — sprawdza stan urządzenia, blokady ekranu, szyfrowania i uprawnień administracyjnych. Jeśli użytkownik świadomie uzbroi akcję, **następne uruchomienie aplikacji** automatycznie zużyje zapisaną zgodę i wykona bezpieczną symulację (`TEST MODE`) albo podejmie jedną próbę wysłania natywnego żądania resetu (`REAL MODE`).

---

## 2. Pełny opis i przykłady użycia (PL)

### Jak działa Red Button?
1. **Ocena gotowości (bez kasowania danych):** Przy pierwszym otwarciu aplikacja odczytuje publiczne informacje systemowe i informuje, czy bieżąca konfiguracja Androida kwalifikuje się do pełnego resetu urządzenia (`wipeData` na API 23–33 lub `wipeDevice` na API 34+).
2. **Tryb symulacji (`TEST MODE`):** Pozwala bezpiecznie przetestować cały przepływ uzbrojenia i kolejnego uruchomienia bez wywoływania produkcyjnego API resetu.
3. **Jednorazowe uzbrojenie (`REAL MODE`):** Po zaznaczeniu wymaganych oświadczeń świadomej zgody użytkownik może uzbroić jedną akcję na kolejne uruchomienie aplikacji.
4. **Wykonanie przy następnym uruchomieniu:** Kolejne otwarcie aplikacji natychmiast zużywa zgodę przed analizą wykonawczą i — wyłącznie gdy wszystkie kontrole bezpieczeństwa (`fail-closed`) są spełnione — wysyła jedno żądanie do `DevicePolicyManager`.

### Przykłady użycia
- **Przygotowanie telefonu służbowego (MDM / Android Enterprise):** Administrator przygotowuje zarządzane urządzenie do kontrolowanego resetu przy kolejnym uruchomieniu przed przekazaniem sprzętu innemu pracownikowi.
- **Testy laboratoryjne i audyt polityk IT:** Zespół techniczny weryfikuje w `TEST MODE`, czy urządzenia organizacji spełniają wymagania dotyczące szyfrowania, blokady ekranu oraz roli `Device Owner` / właściciela profilu organizacyjnego.

### Jawne ograniczenia i ostrzeżenia (Wymagane przed użyciem)
- **Brak gwarancji skuteczności:** Żadna aplikacja ani wersja Androida nie gwarantuje, że żądanie resetu zostanie przyjęte, ukończone lub że fizycznie usunie wszystkie dane z każdej komórki pamięci flash, karty SD czy profilu eSIM.
- **Brak ochrony przed kradzieżą i brak działania zdalnego:** Aplikacja nie wykrywa kradzieży, nie lokalizuje urządzenia, nie działa w tle i nie przyjmuje poleceń zdalnych.
- **Brak przycisku anulowania po uzbrojeniu:** Po uzbrojeniu ponowne otwarcie aplikacji (również przez właściciela lub inną aplikację/składnik systemu) uruchamia akcję bez drugiego ekranu potwierdzenia. Jedynym sposobem rozbrojenia przed kolejnym otwarciem jest wyczyszczenie danych aplikacji w Ustawieniach Androida (o ile polityka administratora na to pozwala).
- **Wymagania Android 14+ (API 34+):** Zwykła prywatna instalacja na Androidzie 14+ nie może sama uzyskać roli `Device Owner`. Wymagane jest oficjalne provisionowanie Android Enterprise poza aplikacją.

---

## 3. Product Description (EN — Store / MDM Catalog Listing)

**Red Button (`pl.leon.redbutton`)** is an offline Android utility that evaluates device policy readiness and—after explicit informed consent and arming—can issue a single native factory reset request via Android's public `DevicePolicyManager` API on the next application launch (or run a non-destructive simulation in `TEST MODE`).

### Key Characteristics & Explicit Limitations
- **First launch never wipes:** Inspects screen lock (`KeyguardManager.isDeviceSecure()`), storage encryption (`ACTIVE` / `ACTIVE_PER_USER`), and management role (`Device Owner`, `Organization-Owned Profile Owner`, or qualified `Device Admin` on API 24–33).
- **Fail-closed & one-time execution:** Any missing permission, insecure lock, inactive/default-key encryption, hardware-family mismatch, or storage error blocks execution without fallback.
- **Not a guaranteed wipe or anti-theft tool:** Does not detect theft, does not operate remotely or in the background, and does not certify physical flash sanitization (NIST/IEEE). Status `"WIPE REQUESTED"` means only that the Android API call returned without refusal.

---

## 4. Procedura zgłoszeń i wsparcia (Support & Issue Reporting)

Zgłoszenia błędów, pytania techniczne oraz raporty z testów kompatybilności urządzeń należy kierować przez zakładkę **Issues** w repozytorium GitHub projektu:
- **Repozytorium:** https://github.com/Leonfbd/red-button/issues

Przy zgłaszaniu problemu prosimy o podanie (bez przesyłania jakichkolwiek danych osobowych, numerów IMEI czy numerów seryjnych):
1. Producenta i modelu urządzenia oraz wersji Androida (poziomu API).
2. Roli zarządzania (`NONE`, `DEVICE_ADMIN`, `PROFILE_OWNER`, `ORG_OWNED_PROFILE_OWNER`, `DEVICE_OWNER`).
3. Trybu pracy (`TEST_MODE` lub `REAL_MODE`) oraz kodu szczegółowego wyniku z ekranu *Ostatni lokalny raport próby*.
