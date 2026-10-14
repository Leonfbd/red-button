# Specyfikacja produktowa i techniczna — RedBUTTON

- **Nazwa wyświetlana:** Red Button
- **Identyfikator aplikacji:** `pl.leon.redbutton`
- **Wersja dokumentu:** 0.1 — projekt bazowy do uzgodnienia
- **Data:** 8 października 2026 r.
- **Status:** specyfikacja opisuje wymagany produkt i aktualne ograniczenia.

> **Najważniejsze ograniczenie:** żadna wersja Androida ani aplikacja nie może zagwarantować, że reset zostanie przyjęty, zakończony lub że usunie wszystkie dane z każdego nośnika. Red Button może w kwalifikowanej konfiguracji poprosić Androida o wykonanie oficjalnej operacji systemowej. Nie wolno opisywać tego jako gwarantowanego ani certyfikowanego wymazania.

---

## 1. Cel produktu

Red Button to lokalna, działająca bez sieci aplikacja Android do oceny, czy bieżąca instalacja ma udokumentowaną przez Androida ścieżkę do jednorazowego żądania resetu fabrycznego.

Po pierwszym uruchomieniu aplikacja przedstawia konsekwencje i zbiera zgodę. Pierwsze uruchomienie niczego nie kasuje. Jeżeli użytkownik uzbroi akcję, następne uruchomienie aplikacji automatycznie zużywa zapisane upoważnienie i — tylko jeśli spełnione są wymagania Androida oraz kontrole bezpieczeństwa — próbuje wysłać jedno natywne żądanie resetu. Nie ma drugiego potwierdzenia aplikacji na tym uruchomieniu.

Produkt nie wykrywa kradzieży, nie lokalizuje urządzenia i nie działa zdalnie ani w tle. Nie jest samodzielną ochroną przed dostępem do danych.

---

## 2. Zasady produktu

- **Zgoda przed działaniem:** pierwszy ekran jasno informuje o nieodwracalnych skutkach i o tym, że następnym uruchomieniem może być zwykłe uruchomienie przez właściciela.
- **Fail closed:** brak uprawnienia, nieznany stan szyfrowania lub blokady, niespójny profil, niekompletny zapis albo błąd systemu oznacza brak żądania resetu.
- **Tylko oficjalne API:** aplikacja korzysta z publicznych API Android `DevicePolicyManager`. Nie obchodzi polityk systemowych.
- **Brak gwarancji skuteczności:** „Android przyjął żądanie” nie oznacza „reset zakończony” ani „wszystkie komórki pamięci wymazane”.
- **Offline i minimalizacja danych:** aplikacja nie wysyła danych i nie zbiera treści użytkownika.
- **TEST MODE nie wykonuje operacji produkcyjnej:** symulacja nigdy nie wywołuje produkcyjnego dostawcy resetu.
- **Brak automatycznych ponowień:** akcja jest jednorazowa; odmowa lub błąd nie uruchamia słabszej metody ani kasowania plików.

---

## 3. Użytkownicy i scenariusze

### Główny użytkownik
Właściciel urządzenia lub administrator, który jest upoważniony do zarządzania nim i rozumie, że następne uruchomienie może spowodować nieodwracalny reset.

### Użytkownik/tester
Osoba sprawdzająca zachowanie interfejsu i bieżącą gotowość urządzenia w `TEST MODE` albo na kontrolowanym urządzeniu laboratoryjnym.

### Podstawowy scenariusz
1. Użytkownik instaluje i otwiera Red Button.
2. Aplikacja lokalnie odczytuje publicznie dostępne informacje o urządzeniu i uprawnieniach.
3. Użytkownik widzi stan, ograniczenia, ścieżkę zarządzania, warunki gotowości i konsekwencje.
4. Użytkownik może przejść do zgody administratora Androida, jeżeli dana wersja i konfiguracja na to pozwalają.
5. Użytkownik potwierdza, że rozumie automatyczne działanie przy kolejnym otwarciu, i uzbraja jedną akcję.
6. Aplikacja kończy pierwsze uruchomienie.
7. Przy następnym otwarciu aplikacja ponownie sprawdza urządzenie, zużywa zgodę przed działaniem i wykonuje symulację albo próbę natywnego żądania.

---

## 4. Zakres produktu

### W zakresie
- Lokalna detekcja publicznych możliwości i stanu zarządzania Androidem.
- Ocena dostępności pełnego resetu urządzenia dla aktualnej roli, API i użytkownika.
- Sprawdzenie, czy Android zgłasza bezpieczną blokadę ekranu oraz wymagany stan szyfrowania.
- Widoczny `TEST MODE`.
- Jednorazowa zgoda zapisana lokalnie, z kontrolą integralności i wersji schematu.
- Natywne żądanie resetu wyłącznie przez obsługiwane publiczne API DPM.
- Opcjonalne żądanie usunięcia eSIM, tylko przy obsługiwanej konfiguracji i po świadomym wyborze.
- Lokalny profil techniczny oraz minimalny lokalny zapis informacji o próbie.
- Wynik komunikujący „żądanie przyjęte”, odmowę, błąd lub symulację — nigdy niepotwierdzający ukończenia resetu.

### Poza zakresem
- Wykrywanie kradzieży, lokalizacja, alarm, geofencing albo zdalny reset.
- Kontrola urządzenia, gdy aplikacja jest zamknięta.
- Gwarancja, że złodziej otworzy aplikację, lub ochrona danych już skopiowanych.
- Weryfikacja siły PIN-u/hasła, odczyt poświadczeń, monitorowanie ustawień użytkownika.
- Własna usługa kont, serwer, polecenia zdalne, telemetryka, reklamy, śledzenie lub analityka.
- Root, exploit, eskalacja uprawnień, obejście FRP, polecenia powłoki do kasowania, prywatne/ukryte API, bezpośredni dostęp do flash/NAND/UFS/eMMC.
- Kasowanie plików jako zastępstwo nieobsługiwanego resetu.
- Integracja z Find Hub, usługami producenta lub zewnętrznym MDM.
- Własne, producentowe API sanitizacji; obecnie nie ma wdrożonej takiej integracji.

---

## 5. Wymagania funkcjonalne

### FR-01 — Pierwsze uruchomienie bez resetu
- Pierwsze uruchomienie może wykrywać urządzenie, wyświetlać informacje i zapisywać lokalny profil.
- Nie może wywołać `wipeDevice()`, `wipeData()` ani innego dostawcy resetu.
- Zgoda administratora Androida, jeżeli jest dostępna, jest osobnym procesem systemowym. Samo jej udzielenie nie resetuje urządzenia.

### FR-02 — Świadoma zgoda
Przed uzbrojeniem `REAL MODE` interfejs musi wyjaśnić co najmniej, że:
- kolejny start Red Button — przez użytkownika albo, jeśli Android na to pozwoli, przez inną aplikację lub składnik systemu — może automatycznie uruchomić akcję;
- aplikacja nie rozpoznaje, kto zainicjował otwarcie, i nie wykrywa kradzieży;
- przyjęty reset może trwale usunąć lokalne dane;
- żądanie może zostać odrzucone i nie gwarantuje kompletnego wymazania;
- pamięć zewnętrzna jest zlecana do objęcia żądaniem, gdy obsługiwana, ale rzeczywisty zakres zależy od Androida/OEM;
- usunięcie eSIM jest opcjonalne i wymaga świadomego zaznaczenia;
- FRP nie jest przez aplikację czyszczone;
- po uzbrojeniu nie ma ekranu anulowania w aplikacji, ponieważ jej ponowne otwarcie może wykonać akcję.

`REAL MODE` wymaga osobnego potwierdzenia użytkownika. Brak potwierdzenia blokuje uzbrojenie.

### FR-03 — TEST MODE
- Domyślnie `TEST MODE` jest wyłączony, zgodnie z przyjętym wymaganiem.
- Ustawienie jest zapisywane lokalnie; przy błędzie odczytu aplikacja ma przyjąć bezpieczną wartość `TEST MODE` włączony.
- W `TEST MODE` następne uruchomienie wykonuje analizę i prezentuje symulację.
- `TEST MODE` nigdy nie może wywołać `requestWipe()` ani żadnego produkcyjnego API resetu.
- Testy jednostkowe i instrumentacyjne używają atrap dostawców, a nie produkcyjnych metod resetu.

### FR-04 — Jednorazowy zapis zgody
- Upoważnienie obejmuje tryb wykonania i wybrane opcje.
- Wszystkie wymagane pola muszą być obecne, poprawnego typu i zgodne z wersją schematu.
- Nie wolno uzupełniać brakujących pól wartością, która rozszerza skutki akcji.
- Zgodę należy zużyć i trwale oznaczyć jako zużytą przed analizą wykonawczą lub wywołaniem dostawcy.
- Niekompletny, nieznany albo przestarzały zapis nie może uruchomić resetu. Wadliwy zapis jest zużywany/unieważniany; przy zmianie semantyki wymagane jest ponowne wyrażenie zgody.
- Błąd trwałego zapisu blokuje zamknięcie ekranu jako „uzbrojonego”. Jeżeli wynik zapisu jest niepewny, komunikat ma ostrzec, że akcja może nadal być oczekująca.
- Błąd zużycia powoduje odmowę i próbę lokalnego unieważnienia. Jeśli stan pamięci jest niepewny, użytkownik jest ostrzegany przed ponownym otwarciem aplikacji.

### FR-05 — Sprawdzenie tożsamości urządzenia
- Aplikacja przechowuje tylko lokalne dane techniczne potrzebne do profilu i ciągłości zgody.
- Zwykła aktualizacja systemu lub poprawki zabezpieczeń nie powinna sama w sobie unieważnić zgody.
- Zmiana rodziny sprzętu lub zbyt mała liczba wiarygodnych pól identyfikacyjnych powoduje odmowę.
- Obecny próg implementacyjny: co najmniej trzy znane, zgodne pola hardware-family; nie jest to dowód unikalnej tożsamości fizycznego egzemplarza.
- Dostawca ma ponownie wykryć bieżący stan i sprawdzić zmianę urządzenia/uprawnień bezpośrednio przed żądaniem.

### FR-06 — Blokada i szyfrowanie
- Brak potwierdzonej bezpiecznej blokady (`KeyguardManager.isDeviceSecure() = false` lub `unknown`) blokuje `REAL MODE`.
- Aplikacja nie odczytuje ani nie ocenia PIN-u, wzoru lub hasła.
- `REAL MODE` wymaga raportowanego aktywnego stanu szyfrowania (`ACTIVE` albo `ACTIVE_PER_USER` według mapowania projektu).
- `UNKNOWN`, `INACTIVE`, `ACTIVATING` oraz stan domyślnego klucza nie mogą być przedstawiane jako potwierdzone kryptograficzne wymazanie.
- Raport aktywnego szyfrowania potwierdza wyłącznie odczyt stanu szyfrowania. Nie potwierdza unieważnienia kluczy po resecie.

### FR-07 — Ścieżki Androida i zakres resetu

| Android API | Publiczna ścieżka | Wymagana rola/polityka w obecnym zakresie |
|---|---|---|
| Poniżej 23 | Brak | Aplikacji nie można zainstalować. |
| API 23 | `DevicePolicyManager.wipeData(int)` | W obecnej implementacji pełny zakres jest akceptowany tylko dla Device Owner z aktywną polityką resetu; systemowego użytkownika nie można tu wiarygodnie potwierdzić używanym publicznym odczytem. |
| API 24–33 | `DevicePolicyManager.wipeData(int)` | Device Owner albo kwalifikowana aktywna polityką resetu w systemowym/głównym użytkowniku. Zwykły profile owner i użytkownik dodatkowy są odrzucani. |
| API 34+ | `DevicePolicyManager.wipeDevice(int)` | Device Owner lub kwalifikowany właściciel profilu organizacyjnego przez instancję DPM profilu nadrzędnego; aktywna polityka resetu. |

- Zwykła instalacja prywatna na API 34+ nie może sama uzyskać roli Device Owner.
- Rola Device Owner lub właściciela profilu organizacyjnego musi być nadana przez oficjalne provisionowanie Android Enterprise poza aplikacją.
- Zmiana API, roli, polityki, użytkownika lub tożsamości między analizą a wykonaniem blokuje żądanie.
- Nie ma słabszego fallbacku do resetu profilu, kasowania plików ani do ukrytego API.

### FR-08 — Opcje i flagi
- `WIPE_EXTERNAL_STORAGE` jest domyślnie żądane, gdy bieżący API je wspiera.
- W UI należy jasno zaznaczyć, że publiczne API nie potwierdza, które nośniki wymienne/adoptowane faktycznie obejmie system.
- `WIPE_EUICC` jest dostępne wyłącznie w obsługiwanej konfiguracji API 34+ i tylko po opt-in użytkownika. Poniżej API 28 cecha eSIM jest „nieznana”, a nie „brak”.
- `WIPE_RESET_PROTECTION_DATA` nigdy nie jest żądane.
- `WIPE_SILENTLY` nigdy nie jest żądane.

### FR-09 — Wynik i ponawianie
- `WIPE REQUESTED` może być pokazane tylko, gdy wywołanie publicznego API wróci bez zgłoszonej odmowy.
- Ten status nie oznacza ukończonego resetu ani zweryfikowanego sanitizowania nośników.
- Odmowa, wyjątek, brak profilu, brak gotowości, niezgodna tożsamość i błąd lokalnego raportu powodują brak fallbacku i brak automatycznego ponawiania.
- Po każdej nieudanej próbie jednorazowa zgoda pozostaje zużyta. Nowa próba wymaga ponownego uzbrojenia po spełnieniu warunków.

### FR-10 — Uruchomienia spoza aplikacji i anulowanie
- Aktywność startowa jest wyeksportowana, aby Androidowy launcher mógł ją uruchomić. Inna aplikacja lub komponent systemu może również zainicjować jej otwarcie, jeżeli Android na to pozwoli.
- Red Button nie może wiarygodnie uwierzytelnić każdego źródła uruchomienia. To zachowanie musi być jawnie opisane w zgodzie.
- Po uzbrojeniu nie ma wewnątrz Red Button ekranu anulowania, ponieważ otwarcie aplikacji uruchamia akcję.
- Jedyna opisana ścieżka anulowania przed kolejnym startem to próba wyczyszczenia danych aplikacji z ustawień Androida. Polityka administratora może to zablokować; aplikacja nie gwarantuje, że anulowanie będzie możliwe.

---

## 6. Lokalizacja i interfejs

- Język aplikacji podąża za językiem Androida.
- Krytyczne zgody, ostrzeżenia, ograniczenia administracyjne, `TEST MODE` i wyniki są dostępne po polsku; angielski jest fallbackiem dla pozostałych języków.
- Nazwa wyświetlana to `Red Button`; identyfikatory API i wartości raportowane bezpośrednio przez Androida mogą pozostać techniczne.
- Checklista gotowości do kradzieży jest wyłącznie ręczną, tymczasową pomocą użytkownika. Aplikacja nie odczytuje tych ustawień, nie zapisuje ich i nie używa ich jako warunku resetu.
- Wszystkie ekrany muszą jasno odróżniać `TEST MODE`, `REAL MODE`, gotowość do wysłania żądania i wynik żądania.

---

## 7. Klasyfikacja poziomów sanitizacji

Poziomy są wewnętrzną klasyfikacją dostępnego żądania, a nie certyfikacją NIST, IEEE ani potwierdzeniem rezultatu:

- **Poziom 0 — brak obsługi:** brak uprawnionego dostawcy albo dowolny blokujący/nieznany warunek.
- **Poziom 1 — natywny reset starszego Androida:** kwalifikowane żądanie `wipeData()` bez wybranych flag rozszerzających.
- **Poziom 2 — natywny reset DPM:** kwalifikowane `wipeDevice()` bez wybranych flag rozszerzających.
- **Poziom 3 — natywne żądanie z obsługiwaną flagą rozszerzającą:** np. żądanie objęcia pamięci zewnętrznej; eSIM tylko po świadomym opt-in.
- **Poziom 4 — sanitizacja producenta:** zarezerwowany dla udokumentowanej, publicznej i wdrożonej integracji producenta. Obecnie takiej integracji nie ma; aplikacja nie może raportować Poziomu 4.

Szyfrowanie samo w sobie nie podnosi poziomu. Żaden poziom nie oznacza fizycznego nadpisania każdej komórki flash.

---

## 8. Prywatność i bezpieczeństwo danych

### Dane lokalne
Implementacja przechowuje w prywatnym magazynie aplikacji lokalny profil techniczny, w tym m.in. producenta/markę/model, build fingerprint, poprawkę zabezpieczeń, publiczne cechy urządzenia, stan DPM/właściciela, status szyfrowania i blokady, dostępne API/flagi, ostrzeżenia i gotowość. Minimalny raport próby zawiera czas, make/model, Android/API, wybranego dostawcę i żądane flagi.

### Dane, których aplikacja nie zbiera
Treści użytkownika, kontakty, zdjęcia, konta, hasła/PIN, IMEI, numer seryjny i identyfikatory woluminów. Aplikacja nie wysyła danych do serwera.

### Wymagania
- Brak `INTERNET` i brak komunikacji sieciowej w runtime.
- Brak kont, telemetryki, analityki, reklam, trackerów i zdalnych poleceń.
- Wyłączone backupy aplikacji i transfer lokalnych preferencji przez Android backup.
- Dane są przechowywane lokalnie w przestrzeni aplikacji; raport jest nadpisywany jako ostatnia próba, nie jako nieograniczony dziennik.
- Nie dodawać SDK zewnętrznych bez osobnej oceny prywatności i zgody właściciela produktu.

---

## 9. Niefunkcjonalne wymagania jakościowe

- **Bezpieczeństwo:** odmowa przy braku uprawnień, niepewnym stanie, niespójnym zapisie, niezgodnej tożsamości lub błędzie.
- **Jednorazowość:** akcja jest zużywana przed działaniem i nie może zostać ponowiona przez rekreację Activity, ponowne wejście ani błąd dostawcy.
- **Uczciwość statusu:** nie twierdzić, że reset zakończono lub pamięć została fizycznie wymazana.
- **Offline:** aplikacja działa bez konta, serwera, sieci i zdalnych usług.
- **Dostępność:** kluczowe zgody i ostrzeżenia muszą być czytelne, dostępne dla TalkBack i zrozumiałe bez znajomości DPM.
- **Odporność:** błędy DPM, Storage, Settings, analizy, profilu i dostawcy nie mogą prowadzić do fallbacku kasującego pliki.
- **Kompatybilność:** minimalne API i rzeczywiste ścieżki muszą być komunikowane oddzielnie; numer API nie oznacza gwarancji działania.

---

## 10. Strategia testów i kryteria akceptacji

### Testy automatyczne
- Testy JVM mapowania statusów, reguł API/właściciela, planowania, flag, identity match, profilu, wersji zapisu zgody, współbieżnego zużycia, niekompletnych rekordów, błędów Storage, wyjątków detekcji/dostawcy i `TEST MODE`.
- Testy Compose/instrumentacyjne zgody i dostępności wyłącznie z fake providerem.
- Żaden test automatyczny nie może wywołać produkcyjnego `wipeData()` ani `wipeDevice()`.
- Test rekreacji Activity/odtworzenia ekranu wyniku potwierdzający brak ponownego wykonania akcji.

---

## 11. Dystrybucja i komercjalizacja — decyzje przed sprzedażą

Model sprzedaży i dystrybucji nie jest jeszcze określony. Przed publikacją trzeba:
- zdecydować, czy produkt jest przeznaczony dla konsumentów, administratorów organizacji, czy wyłącznie urządzeń testowych;
- wybrać kanał: sklep, prywatne wdrożenie MDM czy dystrybucję APK;
- sprawdzić aktualne zasady wybranego sklepu dotyczące Device Admin, DPM, funkcji destrukcyjnych, formularzy prywatności i opisu uprawnień;
- przygotować politykę prywatności, warunki sprzedaży, procedurę zgłoszeń i wsparcia oraz uczciwą kartę produktu;
- skonfigurować bezpieczne podpisywanie wydania.

---

## 12. Ryzyka i jawne ograniczenia

- Zwykła aplikacja nie może sama nadać sobie Device Owner.
- Na API 34+ prywatna instalacja bez oficjalnego zarządzania może nie mieć żadnej ścieżki pełnego resetu.
- Aktywność startowa może zostać uruchomiona z zewnątrz, jeśli Android na to pozwoli; źródła nie da się wiarygodnie rozpoznać.
- Po uzbrojeniu nie ma anulowania w aplikacji; czyszczenie danych z Ustawień może być niedostępne lub ograniczone przez administratora.
- `WIPE_EXTERNAL_STORAGE` nie gwarantuje pokrycia wszystkich kart/nośników/adopted storage.
- Reset może nie usunąć eSIM, wymagać ponownej aktywacji lub zachować FRP.
- Żądanie przyjęte przez DPM nie dowodzi resetu ani usunięcia każdej komórki flash.
- Urządzenia OEM, stan zarządzania, szyfrowanie i polityki różnią się; brak testu na danej konfiguracji oznacza brak potwierdzenia.

---

## 13. Bieżący stan projektu

- `applicationId`: `pl.leon.redbutton`
- `minSdk`: 23, `targetSdk`: 36, `compileSdk`: 36
- Kotlin, Jetpack Compose, Material 3
- Ścieżki kodu: `wipeData()` API 23–33 oraz `wipeDevice()` API 34+ z kontrolą ról
- Język krytycznych komunikatów: polski i angielski
- `TEST MODE` domyślnie wyłączony (z bezpiecznym fallbackiem do `TEST MODE` włączonego przy błędzie odczytu pamięci)
- Konfiguracja podpisu wydania (`signingConfigs.release`) przez `keystore.properties` lub zmienne środowiskowe `RED_BUTTON_KEYSTORE_*` (szablon: [`keystore.properties.example`](keystore.properties.example))
- Dokumentacja towarzysząca:
  - [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) — Polityka prywatności zgodna z Sekcją 8
  - [`PRODUCT_CARD.md`](PRODUCT_CARD.md) — Uczciwa karta produktu, opis do katalogu/sklepu oraz procedura zgłoszeń (Sekcja 10–12)
  - [`TESTING_AND_RELEASE.md`](TESTING_AND_RELEASE.md) — Instrukcja testów macierzy API, ról zarządzania oraz procedury wydania
- Kompilacja (`assembleDebug`, `assembleRelease`), analiza `lintDebug`, testy jednostkowe JVM i testy Compose/Robolectric (`testDebugUnitTest`) oraz kontrola zmergowanego manifestu (`brak INTERNET`, `allowBackup="false"`) są automatycznie weryfikowane w GitHub Actions (`.github/workflows/build-apk.yml`).

Nie wolno utożsamiać tej konfiguracji kodu z gwarancją działania na każdym urządzeniu fizycznym ani z certyfikowanym wymazaniem nośników.
