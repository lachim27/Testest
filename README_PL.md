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

## Alarm terminu i rezerwacji (0.17)
W Ustawienia → Alarm dźwiękowy są dwa niezależne przełączniki (domyślnie włączone): po znalezieniu pasującego terminu oraz po potwierdzeniu rezerwacji. Alarm znalezienia działa również bez autorezerwacji, także przy pierwszym odczycie; respektuje zakres dat i godzin. Zapamiętana lista zapobiega dzwonieniu przy kolejnych identycznych odczytach i restarcie aplikacji. Termin, który zniknie i ponownie się pojawi, jest traktowany jako nowy.

Alarm trwa najwyżej 2 minuty, korzysta z dźwięku alarmów i wibracji. Powiadomienie ma przyciski „Wycisz” i „Otwórz portal” (otwarcie także wycisza). Znalezienie jest wyraźnie opisane jako dostępność, bez potwierdzenia rezerwacji. Potwierdzona rezerwacja ma własny komunikat i ma pierwszeństwo przed alarmem znalezienia. Niepewna lub odrzucona rezerwacja nie uruchamia alarmu sukcesu. Nadal trzeba opłacić zajęty egzamin w portalu.

Przycisk „Test alarmu” nie kontaktuje się z portalem i nie rezerwuje egzaminu. Wymagane są powiadomienia; Android może ograniczyć uruchamianie usługi w tle. Głośność alarmów, tryb Nie przeszkadzać i ustawienia urządzenia pozostają pod kontrolą użytkownika. Aplikacja nie zwiększa automatycznie głośności i nie omija DND. Powiadomienie może pojawić się na ekranie blokady zgodnie z jego ustawieniami; aplikacja nie wymusza pełnoekranowego połączenia. Przetestuj dźwięk i wyciszenie także przy zablokowanym ekranie.

## Aktualizacje bez reinstalacji (0.18)
Dodano podpisywanie release stałym kluczem z GitHub Secrets i rosnący numer wersji. Jednorazowa konfiguracja i ograniczenia przejścia ze starych wersji są opisane w [SIGNING_PL.md](SIGNING_PL.md). Bez skonfigurowanego klucza nie publikujemy APK z przypadkowym podpisem.

## Automatyczne przygotowanie formularza (0.19, funkcja eksperymentalna)

W **Opcje → Automatyczne przygotowanie formularza** można włączyć pomocnika, który działa **wyłącznie w zalogowanym WebView portalu** info-kierowca.pl. Próbuje przejść od „Zarezerwuj termin” do wyboru pojedynczego profilu PKK, ośrodka MORD Kraków, kategorii B i egzaminu praktycznego, a następnie do listy terminów. Nie loguje się przez zewnętrznego dostawcę tożsamości, nie wybiera godziny, nie zatwierdza rezerwacji i nie obsługuje płatności. Nie uruchamia sam dodatkowego odczytu API.

Automat jest domyślnie **wyłączony**. Jeśli portal pokazuje kilka PKK, niejednoznaczne elementy albo zmienił interfejs, pomaga tylko częściowo: sprawdź zakładkę **Portal** i wybierz pozostałe opcje ręcznie. Status działania jest widoczny w Opcjach. Nie gwarantujemy działania na każdej wersji portalu: nie mamy dostępu do prywatnego widoku zalogowanego użytkownika. Do czasu sprawdzenia w realnej sesji to wersja testowa.

To oddzielna opcja od **Autorezerwacji – zajęcie miejsca**. Nie zastępuje ustawień zakresu dat i godzin, limitów HTTP ani procesu opłacenia egzaminu.

## Uruchamianie monitora po logowaniu (0.20)

W zakładce **Opcje → Automatyczne przygotowanie formularza** włącz funkcję **raz**. Gdy monitor jest wyłączony, włączenie tej opcji włącza także monitor i jego usługę w tle. Otworzy się zakładka **Portal**. Zaloguj się samodzielnie przez wspieraną metodę uwierzytelniania.

Po powrocie z logowania aplikacja podejmie próbę: **Zarezerwuj termin egzaminu → rodzaj profilu PKK → pojedynczy profil PKK → MORD Kraków → kategoria B → praktyczny → data od dziś (jeśli pusta) → wyszukiwanie terminów**. Kiedy oficjalny portal wykona pierwsze zapytanie o terminy, istniejący moduł monitorowania przejmie je i będzie prowadził kolejne sprawdzenia zgodnie z harmonogramem i limitem HTTP 429. Pomocnik **nie** wykonuje równolegle własnych zapytań o terminy.

Automat nie loguje się za użytkownika, nie wybiera konkretnego terminu, nie zatwierdza rezerwacji i nie przechodzi do płatności. Przy kilku profilach PKK lub nieznanych polach wybór należy uzupełnić ręcznie. Portal jest aplikacją dynamiczną; automatyczny przebieg wymaga testu na rzeczywistej zalogowanej sesji i może przestać działać po zmianie portalu. Nie publikuj PKK ani danych logowania w diagnostyce.

## Poprawka 0.21 — rozruch po samym zalogowaniu

Wersja 0.20 mogła nie uruchamiać przygotowania formularza automatycznie (opcja była domyślnie wyłączona) i nie rozpoznawała niektórych wartości ośrodka/kategorii z powodu błędnych wyrażeń regularnych. W 0.21 przygotowanie jest domyślnie włączone, skrypt jest konfigurowany **od początku ładowania strony**, również po przekierowaniach do uwierzytelniania, a panel główny wyświetla status przygotowania. Po zalogowaniu skrypt próbuje przejść przez widoczne w oficjalnym portalu kroki formularza — **bez osobnego pobierania terminów ani obchodzenia limitów**. W Opcjach można funkcję wyłączyć.

**Komunikat `Wykryto ścieżkę API .../Reservations` nie oznacza gotowego monitorowania.** Nie pokazujemy go już jako aktywnego sprawdzania terminu; monitor będzie gotowy dopiero po przechwyceniu właściwego zapytania `OneCenterExam` i poprawnym odczycie.

**Instalacja jako aktualizacja:** release 0.21 używa tego samego sekretu podpisu i identyfikatora pakietu co stabilna 0.20 i 0.19. Android pozwala aktualizować je bez odinstalowywania. Jeżeli jednak na telefonie znajduje się starszy wariant podpisany innym kluczem debugowym, bez oryginalnego klucza Android **nie pozwoli** go nadpisać żadną poprawnie podpisaną aktualizacją. To wymaganie Androida, którego aplikacja nie może ominąć. Można zachować starą instalację osobno albo jednorazowo wykonać reinstalację po zabezpieczeniu ustawień i sprawdzeniu ewentualnych rezerwacji. Kolejne stabilne wydania aktualizują się normalnie.

Wybór formularza odbywa się poprzez kontrolki widoczne w portalu, więc przy nowym widoku/niejednoznacznym PKK konieczne może być dopasowanie na podstawie zrzutu ekranu *bez wrażliwych danych*. Obsługa prawdziwego portalu wymaga testu na zalogowanej sesji; lokalne testy nie potwierdzają całego przebiegu.

## Wersja 0.22 — diagnostyka niedziałającego automatu

Jeśli po zalogowaniu monitor nadal nie widzi listy terminów, otwórz **Opcje → Automatyczne przygotowanie formularza → Ponów wybór formularza automatycznie**, a następnie **Opcje → Diagnostyka → Kopiuj raport automatu formularza (bez PKK)**. Wklej raport do rozmowy. Pokazuje, czy skrypt został uruchomiony i czy rozpoznał przyciski/pola, bez ujawniania numeru PKK, sesji, ciasteczek ani danych osobowych. Nic nie wysyła do zewnętrznych serwerów.

Naprawiono zatrzymywanie automatu przez sam link „Opłać egzamin” w menu portalu (nie jest to jeszcze ekran płatności). Przy aktualizacji z 0.19–0.21 jednorazowo migracja uruchamia automatyczne przygotowanie formularza, ponieważ wcześniejsza opcja była zapisywana jako wyłączona nawet po zmianie jej domyślnej wartości. Później można ją wyłączyć na stałe.

Automat nadal nie gwarantuje działania przy nieznanym układzie interfejsu zalogowanego portalu. Bez diagnostyki z realnego ekranu nie należy twierdzić, że wybór PKK/ośrodka działa u każdego. Jeśli formularz ma kilka profili PKK, automat powinien wymagać decyzji użytkownika. Podpis wersji 0.22 pozostaje ten sam co podpis 0.19–0.21.

## 0.23 — rzeczywista nazwa zakładki

Użytkownik potwierdził, że zakładka prowadząca do listy dostępnych terminów to **„Zarezerwuj termin egzaminu”**, a nie „Zarezerwuj egzamin”. Ta nazwa była rozpoznawana w starszym kodzie; problemu nie można przypisać wyłącznie nazwie menu.

Wersja 0.23 doprecyzowuje sekwencję w automatycznym przygotowaniu: **menu „Zarezerwuj termin egzaminu” → MORD Kraków i pojedynczy PKK → „Dalej” → egzamin praktyczny → lista terminów**. Poprawiono warunek przejścia „Dalej” dla pierwszego kroku z PKK i ośrodkiem, który wcześniej wymagał innych etykiet. Nie uruchamia to funkcji autorezerwacji ani nie wybiera dostępnej godziny czy płatności. Dodano testy na dokładną nazwę menu i etap PKK → Dalej.

Działania na żywej, zalogowanej stronie nadal wymagają sprawdzenia. Jeśli nie przechodzi prawidłowo, użyj **Opcje → Diagnostyka → Kopiuj raport automatu formularza (bez PKK)** w aplikacji 0.22 lub nowszej.

## 0.24 — brak rozpoznanych przycisków w menu

W raporcie z rzeczywistego telefonu skrypt był uruchomiony, ale wykrył **0 kliknięć**, **0 przycisków startowych**, **0 rozpoznanych pól** i **1 nieznaną kontrolkę**, przy ekranie `start/inne`. Nie dowodzi to, że formularz PKK jest już otwarty. Poprawiono rozpoznawanie dokładnej etykiety **„Zarezerwuj termin egzaminu”** także dla elementów nawigacji Angular, list i kafelków, które nie są zwykłymi linkami/przyciskami. Klikana jest tylko pojedyncza jednoznaczna kontrolka, bez wybierania godziny ani tworzenia rezerwacji. Automat nie naciska tej samej zakładki wielokrotnie, jeśli strona nie pokazała jeszcze dalszych pól.

Raport diagnostyczny 0.24 rozszerzono o **typy nierozpoznanych kontrolek**, liczbę ramek `iframe`, widoczność tekstu zakładki w nawigacji oraz liczbę hostów Shadow DOM. Raport nie udostępnia wartości pól, PKK, hasła, ciasteczek ani tekstu strony.

Jeśli menu nadal nie zostanie rozpoznane, wklej nowy raport po zalogowaniu w Portalu, a opcjonalnie prześlij zrzut ekranu zakładki z zasłoniętymi danymi osobowymi. Bez wyglądu rzeczywistej zalogowanej strony nie można zagwarantować pełnego przejścia formularza, nawet przy pomyślnych testach automatu na atrapach DOM. 

## 0.25 — przejście według prawdziwych ekranów (26.10.2026)

Po zalogowaniu w telefonie widoczna jest **strona „Moje wnioski i rezerwacje”**, a nie od razu lista usług. Na górze oficjalnego portalu jest przycisk **menu ☰**. Po jego otwarciu pojawia się kafelek **„Zarezerwuj termin egzaminu”**. Pierwszy krok formularza to **„Profil i WORD”**, w którym pokazany jest wariant **PKK – Profil kandydata na kierowcę**, następnie pole rozwijane **„Numer profilu”** oraz **„Kategoria”**. Dopiero po ukończeniu pierwszego kroku należy wybrać egzamin praktyczny i otworzyć listę dat.

W 0.25 automat próbuje najpierw bezpiecznie rozpoznać przycisk ☰ i otworzyć menu usług, następnie kliknąć jednoznaczny kafelek „Zarezerwuj termin egzaminu”. Rozpoznaje również zaznaczoną już opcję PKK oraz rozwijane listy (Angular Material / MUI / PrimeNG i natywne). Jeśli na liście profili jest więcej niż jeden kandydat lub portal zmieni widok, **nie zgaduje numeru**. Nie klika dostępnej godziny, nie składa rezerwacji i nie płaci.

Zaktualizowano raport diagnostyczny o informację, czy automat otworzył menu usług. Uruchomiono 25 testów symulowanego DOM — **to nie zastępuje próby w zalogowanym Portalu**. Jeśli helper nadal stoi w miejscu, należy przesłać raport bez PKK lub zrzut interfejsu z ukrytymi danymi osobowymi. Screenshots, PKK i ciasteczka sesji nie są przechowywane w publicznym repozytorium.

## 0.26 — brak wykrycia ikony ☰ po zalogowaniu

Raport z rzeczywistego Androida dla 0.25 potwierdził: skrypt aktywny, **0 kliknięć**, 0 elementów „Zarezerwuj termin egzaminu”, 1 nierozpoznana kontrolka typu combobox (prawdopodobnie filtr „Status” na ekranie „Moje wnioski i rezerwacje”), brak iframes i Shadow DOM, nie otwarto menu. To nie jest dowód awarii modułu autorezerwacji — problem powstaje wcześniej, podczas przejścia do wyszukiwarki terminów.

W 0.26 rozszerzono wykrywanie ikony menu ☰:
- Warunek aktywności ograniczony do **rzeczywiście rozpoznanej strony głównej „Moje wnioski i rezerwacje”**, a nie samego stałego nagłówka „Portal E-usług”.
- Wykrywanie przycisków z tekstowym oznaczeniem Menu i SVG z trzema poziomymi kreskami.
- Zapasowe rozpoznawanie kompaktowego elementu z obsługą kliknięcia w **lewym górnym rogu WebView** na dokładnie tej stronie. Jest to zgodne z przesłanym widokiem; na innych ekranach metoda nie jest wykonywana.
- Raport anonimowy podaje **Rozpoznano stronę główną: true/false** oraz **Ikony menu ☰ w lewym górnym rogu: liczba**. Nie zbiera numeru PKK, nazwiska, cookies ani wartości pól.

Zachowany stały podpis release, ten sam identyfikator aplikacji, limit zapytań i zakaz potwierdzania terminu lub opłaty przez moduł przygotowania. Testy 29/29 w symulowanym DOM. Ostateczny test wymaga zalogowanego telefonu.

## 0.27 — poprawione pola Profil i WORD na podstawie zrzutów ekranu

Rzeczywisty ekran Info-Kierowca ma radio **PKK – Profil kandydata na kierowcę (PKK)** (na zdjęciu niezaznaczone), pole **Numer profilu**, zależną **Kategorię**, a niżej przełącznik między **Pokaż tylko najbliższe terminy dla ośrodków WORD w okolicy** (domyślny, niezgodny z konkretnym MORD Kraków) a **Wybierz ośrodek WORD i pokaż wszystkie terminy egzaminów**. Na liście ośrodków widoczny jest Kraków, ul. **Nowohucka 33a**, oraz osobna lokalizacja **Kraków/O Kraków Balicka**. Pierwszy krok kończy się przyciskiem **Następny**, nie Dalej.

0.27 rozpoznaje pełne etykiety radiobuttonów (także MUI/Angular label z inputem typu radio), wybiera profil typu PKK, pojedynczy PKK, kategorię B, **konkretny WORD** i tylko jednoznacznie zidentyfikowaną centralę Nowohucka 33a, nie filię Balicka. Kliknięcie Następny jest dozwolone tylko, gdy automat potwierdzi wykonanie poprzednich bezpiecznych wyborów. Nie rezerwuje daty, nie naciska przycisków płatności, nie dodaje zapytań o terminy.

Raport Kopiuj raport automatu formularza (bez PKK) w nowej wersji pokazuje osobno anonimowe stany PKK, profilu, kategorii B, trybu WORD, Nowohuckiej i przycisku Następny. **Nie eksportuje wartości PKK ani danych osobowych**. Testy JavaScript: 32/32 przeszły w symulowanym DOM; rzeczywista interakcja po zalogowaniu musi zostać sprawdzona na telefonie.

## 0.28 — kategoria B jest już wybrana, nie należy jej klikać

Użytkownik potwierdził, że po wyborze PKK portal sam ustawia kategorię B. W 0.27 moduł przygotowania mimo to wymagał odnotowania własnej zmiany kategorii B i przez to zatrzymywał się przed przyciskiem Następny. Raport pokazywał kategoria B false mimo że na ekranie była wybrana.

Wersja 0.28 **nie zmienia kategorii w formularzu ani nie otwiera ponownie jej listy**. Jeśli wartość jest dostępna w DOM, odczytuje ją wyłącznie kontrolnie i blokuje przejście tylko wtedy, gdy wykryta jest jawnie inna kategoria niż B. Jeśli portal nie ujawnia jej wartości w kontrolce, ufa istniejącemu ustawieniu portalu (zgodnie z zachowaniem zgłoszonym przez użytkownika) i nie wymaga już samodzielnego wybrania kategorii przed Następny. Raport rozróżnia B odczytana, wartość nieodczytana (bez zmiany) oraz inna kategoria. W dalszym ciągu przed Następny wymagane są wybrany PKK, numer profilu, tryb konkretnego WORD-u oraz ośrodek Kraków Nowohucka.

Wszystkie 35 testów JS w symulowanym DOM przeszło. Odczyt nie przesyła numeru PKK ani innej wartości profilu poza WebView. Nie rezerwuje terminu ani nie dokonuje płatności. Realny przebieg formularza po logowaniu wymaga sprawdzenia na telefonie.

## 0.29 — przycisk „Następny →” z ikoną strzałki

W 0.28 raport użytkownika pokazywał: wybrany PKK, numer profilu, tryb konkretnego WORD-u i Kraków Nowohucka, ale „Następny: false” i „dalej=0”. Na przesłanym wcześniej zrzucie przycisk nosi etykietę **„Następny →”**, natomiast skrypt rozpoznawał tylko dokładny tekst „Następny”. W 0.29 rozpoznaje również opcjonalny symbol strzałki lub tekst ikony Material po właściwej nazwie. Nie rozpoznaje podobnych poleceń rezerwacji i nie uruchamia się na ekranach płatności ani potwierdzenia.

Przed kliknięciem nadal konieczne jest wybranie PKK, numeru profilu, konkretnego WORD-u i centrali Nowohucka oraz brak jawnie odmiennej kategorii niż B. W diagnostyce są nowe pola: **Pierwszy etap formularza rozpoznany**, **Wymagane wybory gotowe**, oraz liczba **przycisków Następny wykrytych / aktywnych / nieaktywnych**, bez wartości danych osobowych lub PKK. Testy JS: 39/39, w tym symulowane przejście z tekstem „Następny →”.

Testy symulowane nie zastępują testowania rzeczywistego widoku po zalogowaniu. Jeżeli nadal stoi w miejscu, poproś o raport tych nowych trzech wskaźników lub screenshot z zasłoniętymi danymi.
