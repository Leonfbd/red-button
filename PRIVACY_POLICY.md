# Polityka Prywatności — Red Button (`pl.leon.redbutton`)

**Data aktualizacji:** 13 października 2026 r.  
**Aplikacja:** Red Button (`pl.leon.redbutton`)

---

## 1. Zasada działania całkowicie offline (Brak komunikacji sieciowej)

Aplikacja **Red Button** działa wyłącznie lokalnie na urządzeniu użytkownika:
- Aplikacja **nie posiada uprawnienia `android.permission.INTERNET`** (uprawnienie to jest jawnie usuwane na etapie budowania manifestu APK).
- Aplikacja nie łączy się z żadnym serwerem, nie wysyła danych poza urządzenie, nie odbiera poleceń zdalnych i nie korzysta z usług chmurowych.
- Aplikacja nie zawiera zewnętrznych bibliotek analitycznych, telemetrycznych, reklamowych ani śledzących (trackerów).

---

## 2. Dane, których aplikacja NIGDY nie zbiera ani nie odczytuje

Red Button **nie zbiera, nie odczytuje i nie przetwarza**:
- treści osobistych użytkownika (kontaktów, wiadomości, zdjęć, dokumentów, plików);
- kont użytkowników, adresów e-mail, numerów telefonów;
- haseł, kodów PIN, wzorów odblokowania ani danych biometrycznych (aplikacja sprawdza jedynie logiczny status `KeyguardManager.isDeviceSecure()`);
- unikalnych identyfikatorów sprzętowych i telekomunikacyjnych: **IMEI, MEID, numeru seryjnego urządzenia (`Build.getSerial()`), identyfikatorów IMSI/ICCID ani identyfikatorów woluminów pamięci**.

---

## 3. Dane przechowywane wyłącznie lokalnie na urządzeniu

W prywatnym katalogu danych aplikacji (`Context.MODE_PRIVATE`, niedostępnym dla innych zwykłych aplikacji) przechowywane są wyłącznie minimalne dane techniczne niezbędne do działania funkcji oceny gotowości i jednorazowego uzbrojenia:

1. **Lokalny profil techniczny urządzenia (`device_profile_v1`):**
   - publiczne pola modelu/rodziny sprzętu (`Build.MANUFACTURER`, `Build.BRAND`, `Build.MODEL`, `Build.DEVICE`, `Build.BOARD`, `Build.HARDWARE`, `Build.PRODUCT`);
   - wersja systemu Android (`Build.VERSION.RELEASE`, `Build.VERSION.SDK_INT`), `Build.FINGERPRINT` oraz data poprawki bezpieczeństwa (`Build.VERSION.SECURITY_PATCH`);
   - publiczny status cechy eSIM (`FEATURE_TELEPHONY_EUICC` na API 28+);
   - status roli zarządzania `DevicePolicyManager` (`NONE`, `DEVICE_ADMIN`, `PROFILE_OWNER`, `ORG_OWNED_PROFILE_OWNER`, `DEVICE_OWNER`), status blokady ekranu (`SECURE`/`INSECURE`/`UNKNOWN`) oraz status szyfrowania pamięci (`ACTIVE`/`ACTIVE_PER_USER` itd.);
   - wyliczony poziom klasyfikacji żądania (Poziom 0–3) oraz lista warunków blokujących.

2. **Jednorazowy rekord zgody (`consent_record_v1`):**
   - wersja schematu, losowy identyfikator tokenu, wybrany tryb (`TEST_MODE` lub `REAL_MODE`), wybrane flagi (`WIPE_EXTERNAL_STORAGE`, `WIPE_EUICC`), znacznik czasu uzbrojenia, migawka pól `hardware-family` i roli zarządzania, znacznik zużycia (`consumed`) oraz skrót integralności `SHA-256`.

3. **Minimalny raport ostatniej próby (`last_attempt_report_v1`):**
   - czas próby, producent/model, wersja Android/API, wybrana ścieżka `DevicePolicyManager`, żądane flagi oraz wynik (`WIPE REQUESTED`, `SIMULATED`, `REFUSED`, `ERROR`).
   - Raport jest **nadpisywany wyłącznie jako jedna ostatnia próba** — aplikacja nie prowadzi nieograniczonego dziennika historii.

4. **Ustawienie trybu testowego (`pref_test_mode`):**
   - flaga logiczna określająca, czy aktywny jest tryb symulacji (`TEST MODE`).

*Uwaga dotycząca checklisty gotowości:* widoczna w interfejsie „Checklista gotowości do kradzieży (pomoc ręczna)” istnieje wyłącznie tymczasowo w pamięci RAM na czas otwartego ekranu. Aplikacja nie odczytuje tych ustawień z systemu i nigdzie ich nie zapisuje.

---

## 4. Wyłączenie kopii zapasowych (Android Backup)

W celu ochrony bezpieczeństwa i zapobiegania przypadkowemu przeniesieniu uzbrojonej zgody na inne urządzenie:
- `android:allowBackup="false"` jest wymuszone w `AndroidManifest.xml`;
- reguły `backup_rules.xml` oraz `data_extraction_rules.xml` wykluczają transfer lokalnych preferencji do chmury oraz między urządzeniami (Device-to-Device transfer).

---

## 5. Usuwanie danych lokalnych

Wszystkie dane zapisane lokalnie przez aplikację można usunąć w dowolnym momencie poprzez:
- **Ustawienia Androida → Aplikacje → Red Button → Pamięć → Wyczyść dane** (o ile polityka administratora urządzenia na to pozwala), lub
- odinstalowanie aplikacji Red Button (po uprzednim odebraniu uprawnień administratora urządzenia, jeśli zostały nadane).

---

# Privacy Policy (English Summary)

**Red Button (`pl.leon.redbutton`)** operates 100% offline:
- **No Internet Access:** The app does not request `android.permission.INTERNET`, makes zero network connections, and includes no analytics, telemetry, ads, or third-party tracking SDKs.
- **No Personal or Hardware Serial Data Collected:** The app never reads contacts, photos, files, accounts, PIN/password credentials, IMEI, serial numbers, or storage volume IDs.
- **Strictly Local Private Storage:** Only technical readiness state (`Build` hardware-family fields, API level, `DevicePolicyManager` role, lock/encryption status), the one-time consent record, and a single overwritten last-attempt report are stored locally in `MODE_PRIVATE` storage with Android Backup disabled (`allowBackup="false"`).
