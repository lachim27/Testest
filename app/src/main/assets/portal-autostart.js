/* ZłapEgzamin: conservative DOM-only preparation of the official booking form.
 * Operates in the authenticated WebView, never submits/creates a reservation.
 * Does not call the exam API itself and never handles payment/login credentials.
 */
(function () {
  'use strict';
  if (window.__zlapFormPrep) return;
  var settings = {enabled:false, center:'MORD Kraków'};
  var lastAction = 0;
  var stopped = false;
  var pending = null;
  var changed = {};
  var awaitingResults = false;
  var clicks = 0;
  var lastReport = '';
  var maxClicks = 14;

  function norm(s) {
    return String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
      .replace(/ł/g, 'l').replace(/Ł/g, 'l').toLowerCase()
      .replace(/\s+/g, ' ').trim();
  }
  function allowed() {
    return location.protocol === 'https:' &&
      /(^|\.)info-kierowca\.pl$/i.test(location.hostname);
  }
  function report(status) {
    if (status === lastReport) return;
    lastReport = status;
    try {
      if (window.ZlapBridge && window.ZlapBridge.postMessage)
        window.ZlapBridge.postMessage(JSON.stringify({kind:'form_prep', status:status}));
    } catch (_) {}
  }
  function visible(el) {
    if (!el || !el.isConnected || el.disabled) return false;
    for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
      var style = getComputedStyle(n);
      if (style.display === 'none' || style.visibility === 'hidden') return false;
    }
    return el.getClientRects().length > 0;
  }
  function buttons() {
    return Array.from(document.querySelectorAll(
      'button, a, [role="button"], [role="option"], mat-option, input[type="button"]'))
      .filter(visible);
  }
  function name(el) {
    return norm(el.getAttribute('aria-label') || el.innerText ||
      el.textContent || el.value || el.getAttribute('title') || '');
  }
  function click(el, reason) {
    if (!visible(el) || Date.now() - lastAction < 1700 || clicks >= maxClicks) return false;
    lastAction = Date.now();
    clicks++;
    changed[reason] = true;
    el.click();
    report('Wybrano: ' + reason + '. Czekam na kolejny krok.');
    return true;
  }
  function uniqueClick(test, reason) {
    var matches = buttons().filter(function (el) { return test(name(el), el); });
    return matches.length === 1 && click(matches[0], reason);
  }
  function fieldLabel(el) {
    var labels = [el.getAttribute('aria-label'), el.getAttribute('placeholder'),
      el.getAttribute('name'), el.getAttribute('id')];
    if (el.id) {
      var label = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
      if (label) labels.push(label.textContent);
    }
    var parent = el.closest('mat-form-field, .mat-mdc-form-field, label, .form-group, fieldset') ||
      el.parentElement;
    if (parent) labels.push(String(parent.innerText || '').slice(0, 120));
    return norm(labels.join(' '));
  }
  function updateSelect(select, option, reason) {
    if (!option || select.value === option.value) return false;
    select.value = option.value;
    select.dispatchEvent(new Event('input', {bubbles:true}));
    select.dispatchEvent(new Event('change', {bubbles:true}));
    changed[reason] = true;
    lastAction = Date.now();
    report('Wybrano: ' + reason + '.');
    return true;
  }
  function selectNative(reason, label, match) {
    if (changed[reason]) return false;
    var fields = Array.from(document.querySelectorAll('select')).filter(visible)
      .filter(function (el) { return label.test(fieldLabel(el)); });
    if (fields.length !== 1) return false;
    var options = Array.from(fields[0].options).filter(function (o) {
      return !o.disabled && !!o.value && !/^(wybierz|select|--)/.test(norm(o.text));
    });
    var matches = options.filter(function (o) { return match.test(norm(o.text)); });
    if (matches.length !== 1) return false;
    if (fields[0].value === matches[0].value) {
      changed[reason] = true;
      return false;
    }
    return updateSelect(fields[0], matches[0], reason);
  }
  function chooseSingleProfile() {
    if (changed['jedyny profil PKK']) return false;
    var fields = Array.from(document.querySelectorAll('select')).filter(visible)
      .filter(function (el) { return /(profil|pkk)/.test(fieldLabel(el)); });
    if (fields.length !== 1) return false;
    if (!changed['profil PKK'] && !/pkk/.test(fieldLabel(fields[0]))) return false;
    var options = Array.from(fields[0].options).filter(function (o) {
      return !o.disabled && !!o.value && !/^(wybierz|select|--)/.test(norm(o.text));
    });
    var pkk = options.filter(function (o) { return /pkk|\d{8,}/.test(norm(o.text)); });
    if (pkk.length === 1) {
      if (fields[0].value === pkk[0].value) {
        changed['jedyny profil PKK'] = true;
        return false;
      }
      return updateSelect(fields[0], pkk[0], 'jedyny profil PKK');
    }
    if (pkk.length > 1) report('Kilka profili PKK — wybierz właściwy raz w Portalu.');
    return false;
  }
  function openCombobox(reason, label) {
    if (changed[reason]) return false;
    var els = Array.from(document.querySelectorAll(
      '[role="combobox"], mat-select, .mat-mdc-select, .ng-select-container, button[aria-haspopup="listbox"]'))
      .filter(visible).filter(function (el) { return label.test(fieldLabel(el)); });
    if (els.length !== 1) return false;
    if (clicks >= maxClicks) return false;
    pending = reason;
    return click(els[0], 'lista ' + reason);
  }
  function selectOpenOption() {
    if (!pending) return false;
    var options = Array.from(document.querySelectorAll(
      '[role="option"], mat-option, .mat-mdc-option')).filter(visible);
    if (!options.length) { pending = null; return false; }
    var regex = pending === 'osrodek' ? /^mord krakow(\\b|$)/ :
      pending === 'kategoria' ? /^(kategoria\\s+)?b$/ :
      pending === 'praktyczny' ? /^(egzamin\\s+)?(praktyka|praktyczny)$/ :
      pending === 'profil PKK' ? /^pkk$/ : null;
    var matches = pending === 'numer PKK'
      ? options.filter(function (el) {
          var value = name(el);
          return value && !/^(wybierz|wybierz profil|brak profilu)/.test(value) &&
            !/\\bpkz\\b/.test(value);
        })
      : regex ? options.filter(function (el) { return regex.test(name(el)); }) : [];
    if (matches.length === 1) {
      var which = pending;
      pending = null;
      return click(matches[0], which);
    }
    report('Nie rozpoznano jednoznacznie opcji ' + pending + ' — wybierz ręcznie.');
    pending = null;
    return false;
  }
  function fillDateFrom() {
    if (changed['data od']) return false;
    var fields = Array.from(document.querySelectorAll('input[type="date"]'))
      .filter(visible)
      .filter(function (el) {
        return !el.readOnly && /(data|termin|od kiedy|od dnia)/.test(fieldLabel(el));
      });
    if (fields.length !== 1) return false;
    var field = fields[0];
    if (field.value) {
      changed['data od'] = true;
      return false;
    }
    var now = new Date();
    var date = [now.getFullYear(), ('0' + (now.getMonth()+1)).slice(-2),
      ('0' + now.getDate()).slice(-2)].join('-');
    if (field.min && date < field.min) date = field.min;
    if (field.max && date > field.max) return false;
    field.value = date;
    field.dispatchEvent(new Event('input', {bubbles:true}));
    field.dispatchEvent(new Event('change', {bubbles:true}));
    changed['data od'] = true;
    lastAction = Date.now();
    report('Ustawiono datę wyszukiwania na ' + date + '.');
    return true;
  }
  function resultsShown() {
    var body = norm((document.body && document.body.innerText || '').slice(0, 16000));
    return /wybierz godzine|dostepne terminy egzaminow|lista terminow/.test(body) &&
      /\d{1,2}:\d{2}/.test(body);
  }
  function unsafeToProceed(body) {
    var route = norm(location.pathname || '');
    if (/checkout|payment|platnosc|podsumowanie|potwierdzenie/.test(route)) return true;
    // Inspect the active heading, not the entire site's navigation/footer:
    // menu links like "Opłać egzamin" may exist on every page.
    var heading = document.querySelector(
      'main h1, [role="main"] h1, main h2, [role="main"] h2, h1, h2');
    var relevant = heading ? norm(heading.textContent || heading.innerText) : body;
    return /podsumowanie rezerwacji|metoda platnosci|oplac egzamin|potwierdz rezerwacje|zaplac|numer rezerwacji/.test(relevant);
  }
  function scan() {
    if (!settings.enabled || stopped || !allowed() || !document.body) return;
    if (clicks >= maxClicks) { report('Zatrzymano: za dużo kroków. Sprawdź Portal.'); return; }
    if (Date.now() - lastAction < 1700) return;
    var pathname = norm(location.pathname);
    if (/login|signin|authorize|logowanie|identity/.test(pathname)) {
      report('Zaloguj się w portalu; logowanie nie jest automatyzowane.');
      return;
    }
    var body = norm((document.body.innerText || '').slice(0, 20000));
    if (unsafeToProceed(body)) {
      stopped = true;
      report('Przerwano na etapie rezerwacji lub płatności.');
      return;
    }
    if (resultsShown()) {
      stopped = true;
      report('Lista terminów otwarta. Monitor może przejąć odczyt.');
      return;
    }
    if (selectOpenOption()) return;
    if (chooseSingleProfile()) return;
    if (selectNative('osrodek', /(osrodek|word|mord|centrum egzamin)/,
        /^mord krakow(\\b|$)/)) return;
    if (selectNative('kategoria', /kategoria/, /^(kategoria\\s+)?b$/)) return;
    if (selectNative('praktyczny', /(rodzaj|typ egzamin)/,
        /^(egzamin\\s+)?(praktyka|praktyczny)$/)) return;
    if (fillDateFrom()) return;

    // Only exact, labelled UI targets. Never click generic "Wybierz" or a slot.
    if (!changed['profil PKK'] && /rodzaj profilu|profil pkk|profil pkz/.test(body) &&
        uniqueClick(function (s) { return s === 'pkk'; }, 'profil PKK')) return;
    if (changed['profil PKK'] &&
        openCombobox('numer PKK', /(numer profilu|numer pkk|moje profile)/)) return;
    if (openCombobox('osrodek', /(osrodek|word|mord|centrum egzamin)/)) return;
    if (openCombobox('kategoria', /kategoria/)) return;
    if (openCombobox('praktyczny', /(rodzaj|typ egzamin)/)) return;

    var selectors = Array.from(document.querySelectorAll(
      'select, [role="combobox"], mat-select')).filter(visible);
    var hasWizard = /wybierz (profil|rodzaj profilu|osrodek|kategorie)|numer profilu/.test(body) ||
      selectors.some(function (el) {
        return /profil|pkk|osrodek|kategoria|rodzaj egzamin/.test(fieldLabel(el));
      });
    if (!hasWizard && uniqueClick(function (s) {
      return s === 'zarezerwuj termin' || s === 'zarezerwuj termin egzaminu';
    }, 'otwarcie formularza')) return;

    // Search action is only used once criteria are visible, never on a list of slots.
    if (!awaitingResults && /rodzaj egzaminu|egzamin praktyczny|praktyka/.test(body) &&
        uniqueClick(function (s) {
          return /^(pokaz terminy|wyszukaj terminy|sprawdz terminy|szukaj terminu|szukaj terminow|wyszukaj)$/.test(s);
        }, 'wyszukiwanie terminow')) {
      awaitingResults = true;
      return;
    }

    // A generic Next button is allowed only after a local, non-booking selection.
    if (Object.keys(changed).some(function (k) {
          return /profil|osrodek|kategoria|praktyczny/.test(k);
        }) && /wybierz|profil|kategoria/.test(body) &&
        !/dostepne terminy|godzina egzaminu|platnosc/.test(body)) {
      var stage = norm((document.querySelector('h1,h2,h3') || {}).textContent || '') +
        '|' + norm(location.pathname);
      if (!changed['next:' + stage] && uniqueClick(function (s) {
        return s === 'dalej' || s === 'przejdz dalej';
      }, 'next:' + stage)) return;
    }
    report('Formularz wymaga wyboru lub zmienił układ. Możesz dokończyć ręcznie.');
  }
  function configure(cfg) {
    settings.enabled = !!(cfg && cfg.enabled);
    if (!settings.enabled) {
      pending = null;
      report('Automatyczne przygotowanie wyłączone.');
      return true;
    }
    stopped = false;
    report('Przygotowuję formularz po zalogowaniu.');
    scan();
    return true;
  }
  window.__zlapFormPrep = {configure:configure, scan:scan, stop:function () {
    stopped = true;
    report('Lista terminów przejęta przez monitor.');
  }};
  document.addEventListener('DOMContentLoaded', scan);
  window.setInterval(scan, 2000);
  if (window.MutationObserver) {
    var observer = new MutationObserver(function () {
      if (Date.now() - lastAction > 1700) scan();
    });
    if (document.documentElement) observer.observe(document.documentElement,
      {childList:true, subtree:true});
  }
})();