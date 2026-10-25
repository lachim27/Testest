# ZłapEgzamin Android 0.5 beta

Niezależna aplikacja do **podglądu wolnych terminów egzaminu praktycznego kategorii B w MORD Kraków** przez własną, zalogowaną sesję portalu info-kierowca.pl.

## Co nowego

- Jasny, mobilny interfejs inspirowany sposobem nawigacji aplikacji do terminów egzaminacyjnych. Własne logo i własny kod.
- Trzy zakładki: **Start**, **Portal**, **Opcje**.
- **Kalendarz miesięczny** — niebieskie dni pochodzą z ostatniego poprawnego odczytu. Kliknięcie pokazuje godziny; to nie jest gwarancja aktualnej dostępności.
- Lista ostatnio widzianych wolnych terminów.
- Zakres dat **Od–Do**, wybór 7 / 14 / 30 dni, własny interwał i gotowe ustawienia.
- Status połączenia, liczba prób w godzinie, najwcześniejszy czas następnego odczytu, diagnostyka kopiowana bez danych logowania.
- Lokalne powiadomienia o nowo pojawiających się terminach oraz opcjonalne powiadomienia Telegram.
- Ostrzeżenia o **HTTP 429**, braku odczytów i wygaśnięciu sesji.

## Obsługa limitów

**Ustawienie interwału 30 sekund NIE oznacza pobierania danych co 30 sekund.**

Wersja 0.5 ma **trwały licznik faktycznych zapytań** do obserwowanego API, również tych ręcznie wywołanych w WebView. Dla automatycznego monitorowania przyjmuje zachowawczy limit lokalny:
- co najmniej **15 minut** pomiędzy zapytaniami,
- maksymalnie **4 zapytania na godzinę**,
- blokada po HTTP 429 na czas wskazany w nagłówku `Retry-After` lub na rosnący czas awaryjny,
- czas blokady i godziny żądań zostają zachowane po restarcie aplikacji.

To **nie omija limitów** portalu. Portal może mieć także inne, niższe albo zmienne ograniczenia; ręczne sprawdzanie w innych urządzeniach również może mieć znaczenie.

## Uruchomienie

1. Pobierz APK ze strony **Releases** projektu lub artefakt `ZlapEgzamin-APK` z GitHub Actions.
2. Zainstaluj na Androidzie i pozwól na powiadomienia.
3. W zakładce **Portal** zaloguj się do info-kierowca.pl wewnątrz aplikacji.
4. Wybierz MORD Kraków, kategorię B i egzamin praktyczny, następnie wyświetl dostępne terminy.
5. Na **Start** sprawdź stan połączenia i ostatni odczyt; w **Opcjach** ustaw daty, interwał i Telegram.

Logowanie przez **zewnętrzną przeglądarkę** nie przekazuje sesji do WebView. mObywatel może wymagać ręcznego potwierdzenia logowania.

## Ważne ograniczenia wersji beta

- **Brak autorezerwacji** — aplikacja wyłącznie obserwuje terminy. Nie pobiera opłat i nie tworzy rezerwacji.
- Usługa pierwszoplanowa może utrzymywać proces podczas minimalizacji, ale monitor wciąż używa WebView powiązanego z ekranem aplikacji. **Po usunięciu procesu lub zamknięciu Activity sprawdzanie się zatrzymuje**; Android może też ograniczać timery w tle. Nie jest to jeszcze niezawodne działanie 24/7.
- Automatyczne kliknięcie „Przedłuż sesję” jest wykonywane tylko przy widocznym komunikacie portalu. Jeśli portal wymaga mObywatela/ponownego logowania, aplikacja nie może odnowić tożsamości samodzielnie.
- Struktura API portalu może się zmieniać. Nie przeprowadzono kompleksowych testów na rzeczywiście zalogowanym koncie i konkretnym telefonie.

## Bezpieczeństwo

Hasła pozostają w portalu. Zapytanie o terminy (w tym PKK, jeśli portal je zawiera) i jego nagłówki są przekazywane do natywnego odczytu wyłącznie w pamięci procesu. Nie są zapisywane w plikach, ustawieniach ani logach; służą tylko powtórzeniu zapytania do API info-kierowca.pl. Ciasteczka są pobierane z istniejącej sesji WebView. Telegram jest opcjonalny; jego token i Chat ID są przechowywane lokalnie w ustawieniach aplikacji. Aplikacja ma `allowBackup=false`.

## Budowanie

Workflow `.github/workflows/build-apk.yml` kompiluje poleceniem `gradle assembleDebug --no-daemon` i publikuje `app-debug.apk` w artefakcie `ZlapEgzamin-APK`. Commit oznaczony `[apk]` publikuje dodatkowo plik `ZlapEgzamin-Android.apk` jako zasób GitHub Release umożliwiający bezpośrednie pobranie (bez ZIP).

Projekt nie jest związany z właścicielem ZłapTermin ani portalem info-kierowca.pl.


## Godziny autorezerwacji (0.13)
W Ustawienia → Autorezerwacja ustaw „Godzina od” i „Godzina do”. Zakres dotyczy godziny rozpoczęcia egzaminu każdego dnia, w polskiej strefie czasowej, z uwzględnieniem obu granic. Domyślnie 00:00–23:59. Ustawienia są zapamiętywane po zamknięciu aplikacji. Wybranie identycznych godzin dopuszcza tylko tę minutę; zakres przez północ jest odrzucany.

Filtr dotyczy wyłącznie autorezerwacji: odczyty, lista i powiadomienia zachowują dotychczasowe działanie. Automat wybiera najwcześniejszy termin spełniający zakres dat i godzin, pomijając wcześniejsze niedopasowane terminy. Zmiana godzin nie zmienia już wysłanej rezerwacji.

## Reset całego limitu (0.14)
Limit lokalny działa w stałych cyklach: pierwsze zapytanie rozpoczyna 60 minut, a po ich upływie cały licznik wraca do zera. Przykład: zapytania o 10:00, 10:15, 10:30 i 10:45 zostają wyzerowane razem o 11:00. Pierwsze zapytanie po resecie rozpoczyna kolejny cykl. Restart aplikacji, kolejne zapytania i zmiana limitu nie przesuwają końca bieżącego cyklu.

Odstęp pomiędzy automatycznymi odczytami i ewentualna blokada HTTP 429 nadal obowiązują niezależnie od odnowienia lokalnego limitu. Odczyty ręczne nadal są uwzględniane w liczniku.

## Odczyty przy zablokowanym ekranie (0.15)
Automatyczne zapytania o terminy i analiza odpowiedzi działają natywnie na wątku Androida, bez oczekiwania na zakończenie JavaScript fetch w WebView. Po aktualizacji otwórz listę terminów w Portalu raz, aby przygotować zapytanie do powtarzania. Timeouty połączenia i odpowiedzi wynoszą po 15 sekund; osobny natywny deadline rozłącza odczyt po 45 sekundach. Jednocześnie może działać jeden odczyt automatyczny; limity i HTTP 429 nadal obowiązują.

Sama rezerwacja pozostaje w WebView, z dotychczasową ochroną przed ponawianiem niepewnych operacji. Odczyty nie oznaczają gwarancji działania po zabiciu procesu przez Androida. Zachowanie konkretnego telefonu przy zablokowanym ekranie wymaga sprawdzenia na urządzeniu.

## Przywrócenie automatu po v0.15 (0.16)
Walidacja odczytu natywnego przyjmuje również zagnieżdżone i mnogie nazwy endpointu OneCenterExams. Zapytania do rezerwacji i operacji zapisu pozostają wykluczone. Jeśli przekazanie zapytania nie dotarło do odczytu natywnego, aplikacja ponawia je lokalnie (bez kolejnego żądania HTTP i bez zużycia limitu). Status rozróżnia gotowość Portalu od gotowości odczytu w tle. Przy odrzuceniu formatu wyświetla samą ścieżkę API bez parametrów ani danych osobowych.
