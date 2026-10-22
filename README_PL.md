# ZłapEgzamin Android TEST 0.1 (bez serwera)

To jest **natywny projekt Android Studio w Kotlinie**. To prototyp do testów na telefonie, **nie gotowy APK**. Użytkownik loguje się bezpośrednio na oficjalnej stronie otwartej wewnątrz aplikacji.

## Funkcje

- Przeglądarka WebView otwierająca `https://info-kierowca.pl/reservation`.
- Po wejściu na listę egzaminów w oficjalnym portalu aplikacja *próbuje* przechwycić odpowiedź `OneCenterExam` za pomocą `fetch`/`XMLHttpRequest` (bez dodatkowego zapytania).
- Lokalny filtr: `examType=Practice`, `category=B`, `organizationId=21`, `placePracticeAmount > 0`.
- Pokazuje **najwcześniejszy** termin, godzinę i liczbę wolnych miejsc.
- Powiadomienie lokalne i Telegram po wykryciu terminu **wcześniejszego niż poprzedni**, opcjonalnie także przy pierwszym rzeczywistym odczycie.
- Przycisk DEMO działa bez logowania, używając *wymyślonych* terminów. Nie wysyła fałszywych powiadomień.
- Ręczne odświeżanie portalu z lokalnym odstępem minimum 15 minut. Po HTTP 429 ręczne odświeżanie jest blokowane na czas przerwy.
- Informacje Telegram są zapisywane tylko lokalnie w prywatnych danych aplikacji. Nie wpisuj tokenu do kodu ani nie udostępniaj go innym.

## Czego ten prototyp NIE robi

- **Nie monitoruje w tle ani przy wyłączonej aplikacji**. Wiele systemów Android ogranicza pracę w tle, a ten projekt celowo nie zawiera usługi działającej stale.
- **Nie wysyła samodzielnie zapytań co 7 lub 15 minut**. Odczytuje odpowiedzi serwera dopiero, gdy oficjalna strona sama je pobierze podczas korzystania z przeglądarki aplikacji.
- **Nie zapewnia autorezerwacji, automatycznego logowania, płatności ani przedłużania sesji.**
- **Nie gwarantuje braku 429**. Interakcje użytkownika ze stroną nadal mogą powodować zapytania; aplikacja nie może kontrolować wszystkich wewnętrznych limitów portalu.
- **Nie ma jeszcze testów na zalogowanym info-kierowca.pl ani na fizycznym Androidzie.** Mechanizm przechwytywania może wymagać dostosowania do implementacji portalu albo uwierzytelniania WebView.

## Jak zbudować APK w Android Studio (Windows)

1. Zainstaluj Android Studio (https://developer.android.com/studio), a w nim Android SDK Platform 35 i JDK 17.
2. Rozpakuj to archiwum i w Android Studio kliknij **Open**, wskazując katalog `ZlapEgzamin_Android_TEST`.
3. Zaczekaj na synchronizację Gradle. W razie potrzeby wybierz w ustawieniach Gradle: Gradle 8.13 i JDK 17. Gdy IDE pyta o pobranie SDK / komponentów, zezwól.
4. W menu wybierz **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. Gotowy plik testowy znajdziesz pod `app/build/outputs/apk/debug/app-debug.apk`.
6. Skopiuj APK na swój telefon i zainstaluj. Android może poprosić o zezwolenie na instalację z tego źródła.

Projekt zawiera konfigurację Gradle Wrapper (`gradle-wrapper.properties`), ale **nie zawiera binarnego `gradle-wrapper.jar`**, ponieważ w środowisku przygotowania plików nie było narzędzi Android SDK/Gradle. Android Studio może pobrać Gradle w trakcie synchronizacji; jeżeli nie wykryje wrappera, ustaw Gradle lokalnie albo wygeneruj wrapper (`gradle wrapper --gradle-version 8.13`) z zainstalowanego Gradle.

## Pierwszy test

1. Uruchom APK, kliknij **DEMO**. Powinno się pojawić `03.11.2026 09:00 • miejsc: 1` (przykład, nie aktualny egzamin).
2. Kliknij **Portal**, zaloguj się i otwórz listę terminów MORD Kraków (Nowohucka 33a), kat. B, praktyczny. Jeśli portal sam pobierze `OneCenterExam`, program pokaże termin. Jeśli strona została załadowana zanim zadziałał nasłuch, przejdź w interfejsie portalu na listę ponownie, **nie odświeżaj wielokrotnie**.
3. Kliknij **Telegram** i wpisz NOWY token oraz Chat ID, następnie **Test Telegram**. Nigdy nie wysyłaj tokenu w wiadomości lub zrzucie ekranu.
4. Zachowaj ostrożność: jeśli portal zgłasza limit HTTP 429, przestań wykonywać kolejne zapytania i poczekaj na wskazany czas.

## Bezpieczeństwo

Skrypt działa tylko na domenie `info-kierowca.pl` i subdomenach, sprawdza pochodzenie wiadomości z WebView i przesyła do aplikacji wyłącznie minimalne dane terminów (identyfikator terminu, data/godzina, wolne miejsca, cena). Nie przechwytuje tokenów uwierzytelniania ani haseł. Dane Telegram są zapisane w prywatnej pamięci aplikacji bez dodatkowego szyfrowania; nie jest to jeszcze produkcyjny sejf na sekrety. Kopie zapasowe Android wyłączono.

Wdrożenie jest niezależnym prototypem użytkownika, a nie oficjalnym klientem info-kierowca.pl. Sprawdź regulamin portalu przed intensywną automatyzacją.

### Alternatywnie: zbuduj dwuklikiem

Po zainstalowaniu Android Studio i SDK Platform 35 możesz uruchomić `BUILD_APK_WINDOWS.bat`. Skrypt użyje wbudowanego JDK z Android Studio, pobierze Gradle 8.13 i wykona `assembleDebug`. Nie instaluj APK z nieznanych źródeł i nie używaj go do ważnej rezerwacji bez testów.

## Kompilacja bez Android Studio

Instrukcja budowania w GitHub Actions: [GITHUB_APK_INSTRUKCJA.md](GITHUB_APK_INSTRUKCJA.md).
