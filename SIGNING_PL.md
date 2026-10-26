# Aktualizacje bez odinstalowywania

Android wymaga tego samego identyfikatora aplikacji oraz zgodnego podpisu. Każda nowa publikacja musi również mieć wyższy versionCode. Projekt zachowuje `pl.zlapegzamin.test`, a GitHub Actions nadaje numer `1000 + GITHUB_RUN_NUMBER`.

## Jednorazowe ustawienie stałego klucza

1. Zainstaluj JDK 17 (polecenie `keytool`) i GitHub CLI z https://cli.github.com/.
2. Zaloguj GitHub CLI do konta mającego dostęp do repozytorium: `gh auth login`.
3. Pobierz repozytorium i w jego katalogu wykonaj w PowerShell: `powershell -ExecutionPolicy Bypass -File .\scripts\setup-signing.ps1`.

Skrypt tworzy prywatny klucz poza repozytorium, w folderze `ZlapEgzamin-private-signing` Twojego profilu Windows. Wysyła jego konfigurację do **GitHub Actions Secrets** repozytorium `lachim27/Testest` pod nazwą `ANDROID_SIGNING_BUNDLE`, a następnie uruchamia budowanie. Pliki nie trafiają do kodu ani publicznych wydań. Jeśli sekret już istnieje, skrypt go nie zastępuje.

Zachowaj bezpieczną prywatną kopię tego folderu. Nie wysyłaj klucza ani jego hasła w wiadomościach, nie dodawaj ich do Git i nie twórz nowego klucza przy aktualizacji. Utrata klucza może uniemożliwić podpisanie kolejnej zgodnej aktualizacji.

Alternatywnie istniejący klucz PKCS12 można wprowadzić jako sekret JSON zawierający pola `keystore` (base64), `password` oraz `alias`; hasło klucza i magazynu musi być jednakowe. Nie zastępuj sekretu innym kluczem, jeśli była już publikowana wersja ze stałym podpisem.

## Co dzieje się przy budowaniu

Bez sekretu wykonywane są testy i kompilacja, ale APK nie jest udostępniany. Nie wracamy automatycznie do losowego podpisu debugowego. Gdy sekret jest ustawiony, publikowany jest podpisany wariant release. Workflow sprawdza podpis APK i zgodność jego odcisku z kluczem; publiczny plik `signing-certificate.sha256` to odcisk certyfikatu, a nie klucz prywatny.

## Przejście ze starych wersji

Starsze APK powstawały z domyślnym kluczem debugowym tworzonym na maszynie GitHub Actions. Nie zachowano tego klucza jako sekretu. Klucza prywatnego nie da się odzyskać z APK. Dopóki nie zostanie odnaleziony konkretny stary klucz, nie można zagwarantować aktualizacji obecnej instalacji.

**Nie odinstalowuj starej aplikacji, zanim masz nowe, prawidłowo podpisane APK.** Przed ewentualną jednorazową reinstalacją zanotuj ustawienia, zabezpiecz konfigurację Telegrama i sprawdź istniejące rezerwacje w portalu. Reinstalacja usuwa lokalne dane i wymaga ponownego logowania. Kolejne wydania ze stałym kluczem instalujesz już bez odinstalowywania, przez otwarcie nowego APK i przycisk „Aktualizuj”.

To nie jest jeszcze aktualizator wewnątrz aplikacji: APK nadal pobierasz z GitHub Releases. Nie musisz jednak usuwać aplikacji między zgodnie podpisanymi wersjami.
