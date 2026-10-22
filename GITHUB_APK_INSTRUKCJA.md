# ZłapEgzamin Android — jak uzyskać APK przez GitHub Actions (bez Android Studio)

Ta paczka zawiera projekt Android i konfigurację GitHub Actions. **Nie jest to gotowe APK.** GitHub skompiluje aplikację w chmurze, o ile zależności i kod przejdą kompilację. Nie jest to jeszcze przetestowane na fizycznym telefonie.

## Przez stronę GitHub na komputerze

1. Wejdź na https://github.com i zaloguj się lub utwórz konto.
2. Naciśnij **+ → New repository**. Nazwij je `ZlapEgzamin-Android-Test`. Ustaw **Private**, ponieważ to Twój projekt. Możesz zostawić bez README. Kliknij **Create repository**.
3. Rozpakuj tę paczkę ZIP na komputerze. W repozytorium kliknij **uploading an existing file** lub **Add file → Upload files**.
4. Przeciągnij **ZAWARTOŚĆ folderu projektu**, nie sam plik ZIP ani całego folderu nadrzędnego. W głównym katalogu repozytorium powinny znaleźć się `app/`, `build.gradle.kts`, `settings.gradle.kts` i `gradle.properties`. Zatwierdź **Commit changes**.
5. Gdy GitHub nie prześle ukrytego katalogu `.github`, utwórz workflow ręcznie: **Add file → Create new file**, nazwij `.github/workflows/build-apk.yml`. Skopiuj całą zawartość pliku `WORKFLOW_DO_SKOPIOWANIA.yml` z paczki i kliknij **Commit changes**. Jeśli `.github/workflows/build-apk.yml` już jest w repozytorium, pomiń ten krok.
6. Otwórz kartę **Actions** → **ZlapEgzamin Android APK**. Jeśli nie uruchomiło się po zapisaniu plików, kliknij **Run workflow** → **Run workflow**.
7. Kliknij nowe uruchomienie. Gdy status będzie zielony, przewiń do sekcji **Artifacts** i pobierz `ZlapEgzamin-Android-TEST-APK` (GitHub pobierze archiwum ZIP).
8. Rozpakuj pobrane z GitHub archiwum: w środku będzie plik `app-debug.apk`. Przenieś go na telefon i otwórz w **Moje pliki**. W razie potrzeby pozwól na instalowanie z tego źródła.

## Jeśli GitHub pokaże czerwony krzyżyk

Wciśnij uruchomienie w **Actions**, otwórz krok **Kompiluj aplikacje** i skopiuj **pierwszy właściwy błąd kompilacji** (usuń prywatne dane). Nie można zagwarantować poprawnej kompilacji bez uruchomienia workflow.

## Ważne

- Nie wrzucaj do GitHub tokenu Telegram, hasła, cookies, PKK, folderu profilu przeglądarki ani plików `.env` z sekretami.
- APK to testowa wersja: DEMO i próba odczytu odpowiedzi `OneCenterExam` w WebView. Nie jest to aplikacja do pracy w tle ani autorezerwacji.
- Jeśli po wejściu na portal pojawi się błąd 429, nie ponawiaj zapytań przed wskazanym terminem.
- Plik `.github/workflows/build-apk.yml` zawiera przepis dla GitHub: JDK 17, Gradle 8.13 i Android SDK 35.
