# ZłapEgzamin Android 0.3 — monitoring i powiadomienia (test)

Aplikacja Android z własnym panelem, portalem info-kierowca.pl i **automatycznym, tylko odczytowym** sprawdzaniem wolnych terminów egzaminu praktycznego kategorii B w MORD Kraków (identyfikator ośrodka 21).

## Jak uruchomić
1. Zainstaluj najnowsze APK z **GitHub Actions → ZlapEgzamin-APK**.
2. Zezwól na **powiadomienia** na Androidzie 13+.
3. Otwórz zakładkę **Portal** i zaloguj się **wewnątrz aplikacji**.
4. Wybierz **MORD Kraków → B → praktyczny** i przejdź do listy egzaminów, żeby portal pobrał `OneCenterExam`.
5. Wróć do zakładki **Start**. Jeśli monitor pokazuje ostatni odczyt, został połączony z listą terminów. Gdy wyświetla prośbę o wybranie egzaminu, powtórz ten krok.
6. **Pozostaw aplikację otwartą na ekranie**. Podczas korzystania z niej ponownie sprawdzi termin nie częściej niż co 5 minut. Po zamknięciu lub zminimalizowaniu aplikacji monitorowanie jest wstrzymywane.
7. Nowy termin (którego brakowało w poprzedniej poprawnej odpowiedzi) wywoła powiadomienie Android. Przycisk **Wyślij test powiadomienia** w Opcjach pozwala sprawdzić uprawnienia bez udawania dostępności.

## Jak działa
- Skrypt w WebView obserwuje rzeczywistą odpowiedź endpointu `/Schedules/user/OneCenterExam`.
- Zapamiętuje wyłącznie ostatnie **wybrane przez Ciebie** zapytanie o ośrodek 21 i kategorię B. Kolejne odczyty używają sesji portalu w obrębie zalogowanego WebView, **bez wpisywania numeru PKK, loginu ani hasła w kodzie**.
- Ze zwróconej struktury `examCollectionForDay[].examCollections[]` wybiera tylko praktyczne egzaminy z `practiceId`, `practiceDateTime` i `placePracticeAmount > 0`.
- Pierwszy odczyt buduje punkt odniesienia. Nowe identyfikatory terminów powodują alert, a znane nie wywołują powtarzanych alertów. W **Opcjach** możesz też włączyć alarm przy pierwszym odczycie.
- Odczyt ma limit 5 minut. Po HTTP 429 monitor blokuje dodatkowe sprawdzanie przynajmniej na 30 minut lub na czas wskazany przez portal. Po wygaśnięciu sesji użytkownik musi zalogować się ponownie.

## Ograniczenia
- **To wciąż wersja testowa.** Mechanizm jest zaimplementowany, ale nie został przetestowany na rzeczywiście zalogowanym koncie i fizycznym telefonie; portal może zmienić API, sposób uwierzytelniania, strukturę odpowiedzi i limity.
- **Nie działa w tle, przy wyłączonym ekranie ani po zamknięciu aplikacji.** Brak stałego serwera i nieobecna usługa systemowa.
- **Nie rezerwuje egzaminu** ani nie wykonuje płatności.
- Otworzenie portalu w **zewnętrznej przeglądarce** nie przenosi jej zalogowanej sesji do monitorowania w WebView.
- Nie ma Telegrama w tej wersji — powiadomienia są **lokalne, na telefonie**.
- Jedno odczytanie odpowiada zakresowi dat wybranemu w portalu. Jeżeli zakres w portalu zmieni się, ponownie wybierz go, aby monitor przechwycił nowe zapytanie.
- Aplikacja działa tylko jeśli Android System WebView obsługuje `WEB_MESSAGE_LISTENER` i `DOCUMENT_START_SCRIPT`.

## Bezpieczeństwo
Skrypt nie wysyła do kodu Androida haseł, ciasteczek, numerów PKK ani treści żądań. Do aplikacji trafiają tylko identyfikatory, daty i liczba wolnych miejsc. Aplikacja nie wysyła żądań tworzących lub potwierdzających rezerwację.

Używaj zgodnie z regulaminem portalu i nie próbuj obchodzić limitów HTTP 429.
