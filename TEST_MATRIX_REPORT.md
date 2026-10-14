# Raport Weryfikacji Macierzy API, Ról i Urządzeń — Red Button (`pl.leon.redbutton`)

Dokument przygotowany zgodnie z **Sekcją 10 (`Kryteria wydania — raport zawiera urządzenia i API`)** oraz **Sekcją 12 i 13** specyfikacji [`SPECIFICATION.md`](SPECIFICATION.md).

> **Ważne rozróżnienie:** Poniższy raport dokumentuje wyniki automatycznych testów jednostkowych JVM (`RedButtonUnitTest`) oraz testów interfejsu Jetpack Compose wykonywanych w środowisku Robolectric (`RedButtonComposeRobolectricTest`, SDK 34) z użyciem atrapy `FakeWipeExecutionProvider`. Testy automatyczne potwierdzają poprawność logiki reguł `DevicePolicyEvaluator`, jednorazowego zapisu zgody, ciągłości tożsamości, obsługi błędów i zachowania UI, ale **nie zastępują testów na fizycznych urządzeniach OEM ani w docelowym środowisku Android Enterprise MDM**.

---

## 1. Nieinwazyjna macierz API (Bez roli właściciela, `TEST MODE`)

| Poziom API | Wersja Androida | Profil urządzenia w teście | Rola zarządzania | Status cechy eSIM (`eUICC`) | Ścieżka DPM | Poziom sanitizacji | Wynik `REAL MODE` | Wynik `TEST MODE` (`FakeWipeExecutionProvider`) |
|---|---|---|---|---|---|---|---|---|
| **API 22** | Android 5.1 | `Google Pixel 8 (shiba)` | `NONE` | `UNKNOWN_PRE_API_28` | `NONE` | Poziom 0 | Zablokowany (`API_BELOW_23`) | — |
| **API 23** | Android 6.0 | `Google Pixel 8 (shiba)` | `NONE` | `UNKNOWN_PRE_API_28` | `NONE` | Poziom 0 | Zablokowany (`NO_ACTIVE_ADMIN_OR_POLICY`, `API23_REQUIRES_DEVICE_OWNER`) | `SIMULATED` (0 wywołań DPM) |
| **API 24** | Android 7.0 | `Google Pixel 8 (shiba)` | `NONE` | `UNKNOWN_PRE_API_28` | `NONE` | Poziom 0 | Zablokowany (`NO_ACTIVE_ADMIN_OR_POLICY`) | `SIMULATED` (0 wywołań DPM) |
| **API 27** | Android 8.1 | `Google Pixel 8 (shiba)` | `NONE` | `UNKNOWN_PRE_API_28` *(granica < 28)* | `NONE` | Poziom 0 | Zablokowany (`NO_ACTIVE_ADMIN_OR_POLICY`) | `SIMULATED` (0 wywołań DPM) |
| **API 28** | Android 9.0 | `Google Pixel 8 (shiba)` | `NONE` | `PRESENT` / `ABSENT` *(granica >= 28)* | `NONE` | Poziom 0 | Zablokowany (`NO_ACTIVE_ADMIN_OR_POLICY`) | `SIMULATED` (0 wywołań DPM) |
| **API 33** | Android 13 | `Google Pixel 8 (shiba)` | `NONE` | `PRESENT` | `NONE` | Poziom 0 | Zablokowany (`NO_ACTIVE_ADMIN_OR_POLICY`) | `SIMULATED` (0 wywołań DPM) |
| **API 34** | Android 14 | `Google Pixel 8 (shiba)` | `NONE` | `PRESENT` | `NONE` | Poziom 0 | Zablokowany (`API34_PRIVATE_DEVICE_NO_OWNER_ROLE`) | `SIMULATED` (0 wywołań DPM) |
| **API 35** | Android 15 | `Google Pixel 8 (shiba)` | `NONE` | `PRESENT` | `NONE` | Poziom 0 | Zablokowany (`API34_PRIVATE_DEVICE_NO_OWNER_ROLE`) | `SIMULATED` (0 wywołań DPM) |
| **API 36** | Android 16 | `Google Pixel 8 (shiba)` | `NONE` | `PRESENT` | `NONE` | Poziom 0 | Zablokowany (`API34_PRIVATE_DEVICE_NO_OWNER_ROLE`) | `SIMULATED` (0 wywołań DPM) |

---

## 2. Macierz ról zarządzania (`DevicePolicyManager`)

Wszystkie poniższe scenariusze testowane są przy spełnionych warunkach `LockScreenStatus.SECURE` oraz `EncryptionStatus.ACTIVE`:

| Zakres API | Rola zarządzania | Kwalifikacja użytkownika | Wybrana ścieżka publiczna DPM | Obsługa `WIPE_EXTERNAL_STORAGE` | Obsługa `WIPE_EUICC` | Poziom (bez flag / z flagą) | Decyzja `DevicePolicyEvaluator` |
|---|---|---|---|---|---|---|---|
| **API 23** | `DEVICE_OWNER` | `UNCONFIRMED_API23_USER` | `DevicePolicyManager.wipeData(int)` | Tak | Nie (`UNKNOWN_PRE_API_28`) | Poziom 1 / Poziom 3 | **GOTOWY (`READY`)** |
| **API 23** | `DEVICE_ADMIN` | `UNCONFIRMED_API23_USER` | `NONE` | Nie | Nie | Poziom 0 | **ODMOWA (`API23_REQUIRES_DEVICE_OWNER`)** |
| **API 24–33** | `DEVICE_OWNER` | `SYSTEM_OR_MAIN_USER` | `DevicePolicyManager.wipeData(int)` | Tak | Nie (wymaga API 34+) | Poziom 1 / Poziom 3 | **GOTOWY (`READY`)** |
| **API 24–33** | `DEVICE_ADMIN` | `SYSTEM_OR_MAIN_USER` | `DevicePolicyManager.wipeData(int)` | Tak | Nie (wymaga API 34+) | Poziom 1 / Poziom 3 | **GOTOWY (`READY`)** |
| **API 24–33** | `DEVICE_ADMIN` | `SECONDARY_OR_PROFILE_USER` | `NONE` | Nie | Nie | Poziom 0 | **ODMOWA (`SECONDARY_USER_NOT_ALLOWED`)** |
| **API 24–33** | `PROFILE_OWNER` | `SYSTEM_OR_MAIN_USER` | `NONE` | Nie | Nie | Poziom 0 | **ODMOWA (`PROFILE_OWNER_NOT_ALLOWED_API24_33`)** |
| **API 34–36** | `DEVICE_ADMIN` / `NONE` | `SYSTEM_OR_MAIN_USER` | `NONE` | Nie | Nie | Poziom 0 | **ODMOWA (`API34_PRIVATE_DEVICE_NO_OWNER_ROLE`)** |
| **API 34–36** | `PROFILE_OWNER` (zwykły) | `SYSTEM_OR_MAIN_USER` | `NONE` | Nie | Nie | Poziom 0 | **ODMOWA (`API34_PRIVATE_DEVICE_NO_OWNER_ROLE`)** |
| **API 34–36** | `DEVICE_OWNER` | `SYSTEM_OR_MAIN_USER` | `DevicePolicyManager.wipeDevice(int)` | Tak | Tak (opt-in przy `PRESENT`) | Poziom 2 / Poziom 3 | **GOTOWY (`READY`)** |
| **API 34–36** | `ORG_OWNED_PROFILE_OWNER` (z nadrzędnym DPM) | `SYSTEM_OR_MAIN_USER` | `ParentProfile.DevicePolicyManager.wipeDevice(int)` | Tak | Tak (opt-in przy `PRESENT`) | Poziom 2 / Poziom 3 | **GOTOWY (`READY`)** |
| **API 34–36** | `ORG_OWNED_PROFILE_OWNER` (brak nadrzędnego DPM) | `SYSTEM_OR_MAIN_USER` | `NONE` | Nie | Nie | Poziom 0 | **ODMOWA (`ORG_PROFILE_MISSING_PARENT_DPM`)** |

---

## 3. Weryfikacja bezpieczeństwa, ciągłości i interfejsu Compose (Robolectric SDK 34)

| Obszar wymagań | Scenariusz testowy | Test automatyczny | Wynik |
|---|---|---|---|
| **FR-01** | Pierwsze uruchomienie nigdy nie wywołuje dostawcy resetu | `RedButtonUnitTest`, `RedButtonComposeRobolectricTest` | **PASS** |
| **FR-02** | Wymagane potwierdzenie dwóch oświadczeń zgody przed uzbrojeniem `REAL MODE` | `composeUi_realModeConsentAndSecondLaunchOutcomeAndRecreation_callsFakeProviderOnce` | **PASS** |
| **FR-03** | Domyślny `TEST MODE = false`; automatyczny fallback do `TEST MODE = true` przy błędzie odczytu pamięci | `testMode_defaultIsFalse_andStorageReadErrorFallsBackToTrue`, `composeUi_storageReadErrorForcesTestModeFallbackAndDisplaysWarningBanner` | **PASS** |
| **FR-04** | Walidacja schematu, brakujących pól i skrótu `SHA-256`; blokada przy błędzie zapisu; atomowe zużycie przy 8 wątkach współbieżnych | `consentStorage_rejectsIncompleteSchemaMismatchOrTamperedRecord`, `concurrentConsumption_executesProviderAtMostOnce`, `composeUi_writeFailureAndUncertainWriteBanners_blockTransitionToArmedScreen` | **PASS** |
| **FR-05** | Ciągłość tożsamości `Google Pixel 8 (shiba)` po aktualizacji `SECURITY_PATCH` / `FINGERPRINT` kontra odrzucenie przy zmianie na `Galaxy S24 (e1s)` lub `< 3` polach | `hardwareIdentity_toleratesOsPatchUpdate_rejectsDifferentHardwareOrTooFewFields` | **PASS** |
| **FR-06** | Blokada dla `LockScreenStatus.INSECURE` / `UNKNOWN` oraz `EncryptionStatus.ACTIVE_DEFAULT_KEY` / `INACTIVE` / `ACTIVATING` / `UNSUPPORTED` | `lockAndEncryption_blocksInsecureOrDefaultKeyOrInactive` | **PASS** |
| **FR-08** | Bezwzględne usunięcie/odrzucenie flag `WIPE_RESET_PROTECTION_DATA` i `WIPE_SILENTLY`; wyłączenie `checkbox_wipe_euicc` na API 33 | `forbiddenFlags_frpAndSilentAreNeverIncluded`, `composeUi_euiccCheckboxDisabledOnApi33_andEnabledOnQualifiedApi34` | **PASS** |
| **FR-09** | Ponowna weryfikacja stanu przed wywołaniem dostawcy, wymóg zapisu raportu lokalnego przed wywołaniem oraz brak ponowień po `SecurityException` | `preCallReinspectionChange_orReportWriteFailure_blocksRealModeWithoutProviderCall`, `stateChangeOrProviderException_refusesWithNoFallbackAndNoRetry` | **PASS** |
| **FR-10 / Sekcja 10** | Brak przycisku anulowania po uzbrojeniu oraz brak ponownego wykonania przy rekreacji `Activity` | `activityRecreation_doesNotReExecuteArmedLaunch`, `composeUi_realModeConsentAndSecondLaunchOutcomeAndRecreation_callsFakeProviderOnce` | **PASS** |
| **Sekcja 8** | Brak `android.permission.INTERNET` oraz `android:allowBackup="false"` w zmergowanym manifeście APK | Krok CI `Verify merged AndroidManifest privacy & security constraints` | **PASS** |
