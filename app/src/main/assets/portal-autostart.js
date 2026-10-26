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
      'button, a, [role="button"], [role="link"], [role="menuitem"],' +
      ' [role="option"], mat-option, mat-list-item, .mat-list-item,' +
      ' [tabindex], input[type="button"]'))
      .filter(visible);
  }
  function name(el) {
    // Prefer visible labels. In portal navigation aria-label may be generic.
    return norm(el.innerText || el.textContent ||
      el.getAttribute('aria-label') || el.value || el.getAttribute('title') || '');
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
    // Some navigation components expose both an outer <a> and an inner
    // role=button with identical text. Treat nested matches as one action.
    matches = matches.filter(function (el) {
      return !matches.some(function (other) {
        return other !== el && el.contains && el.contains(other);
      });
    });
    return matches.length === 1 && click(matches[0], reason);
  }
  // This is the exact menu service leading TO the exam search form.
  // It is not a reservation confirmation or an available exam slot.
  function examMenuAction(label) {
    // User-confirmed name of the booking service in the Info-Kierowca menu.
    return label === 'zarezerwuj termin egzaminu' ||
      label === 'zarezerwuj termin' ||
      label === 'rezerwacja terminu egzaminu';
  }
  function openExamMenuLeaf() {
    // Angular menus sometimes attach handlers to an <li>, <div> or a
    // mat-list-item rather than rendering a literal <button>/<a>.
    // Only accept the exact user-confirmed text on a single visible leaf
    // within a menu/navigation or a clearly clickable parent.
    var possible = [];
    try {
      possible = Array.from(document.querySelectorAll(
        'nav span, nav div, nav li, aside span, aside div, aside li,' +
        ' [role="navigation"] span, [role="navigation"] div,' +
        ' [role="menu"] span, [role="menuitem"], mat-list-item,' +
        ' .mat-list-item, [class*="menu"] span, [class*="nav"] span,' +
        ' [class*="tile"] span, [class*="card"] span'))
        .filter(visible).filter(function (el) {
          return examMenuAction(norm(el.textContent || ''));
        });
    } catch (_) { return false; }
    if (!possible.length) return false;
    var targets = [];
    possible.forEach(function (el) {
      var target = el.closest(
        'a, button, [role="button"], [role="link"], [role="menuitem"],' +
        ' [onclick], [tabindex], mat-list-item, .mat-list-item');
      if (!target) {
        var li = el.closest('li');
        var nav = el.closest('nav, aside, [role="navigation"], [role="menu"]');
        if (li && nav) target = li;
      }
      if (target && visible(target) && targets.indexOf(target) < 0)
        targets.push(target);
    });
    targets = targets.filter(function (el) {
      return !targets.some(function (other) {
        return other !== el && el.contains && el.contains(other);
      });
    });
    return targets.length === 1 &&
      click(targets[0], 'otwarcie zakładki Zarezerwuj termin egzaminu');
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
    var setter = window.HTMLSelectElement && Object.getOwnPropertyDescriptor(
      window.HTMLSelectElement.prototype, 'value');
    if (setter && setter.set) setter.set.call(select, option.value);
    else select.value = option.value;
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
  // A single uniquely identified PKK may be selected; never guess between profiles.
  // Some builds of the portal use a native select, others use a styled combobox.
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
    if (pkk.length === 0 && options.length === 1) pkk = options;
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
      '[role="combobox"], mat-select, .mat-mdc-select, .ng-select-container, .mat-select-trigger, button[aria-haspopup="listbox"]'))
      .filter(visible).filter(function (el) { return label.test(fieldLabel(el)); });
    if (els.length !== 1) return false;
    if (clicks >= maxClicks) return false;
    pending = reason;
    return click(els[0], 'lista ' + reason);
  }
  function selectOpenOption() {
    if (!pending) return false;
    var options = Array.from(document.querySelectorAll(
      '[role="option"], mat-option, .mat-mdc-option, .ng-option')).filter(visible);
    if (!options.length) { pending = null; return false; }
    var regex = pending === 'osrodek' ?
      /^(mord krakow|malopolski osrodek ruchu drogowego w krakowie)$/ :
      pending === 'kategoria' ? /^(kategoria\s+)?b$/ :
      pending === 'praktyczny' ? /^(egzamin\s+)?(praktyka|praktyczny)$/ :
      pending === 'profil PKK' ? /^pkk$/ : null;
    var matches = pending === 'numer PKK'
      ? options.filter(function (el) {
          var value = name(el);
          return value && !/^(wybierz|wybierz profil|brak profilu)/.test(value) &&
            !/\bpkz\b/.test(value);
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
  function chooseProfileType() {
    if (changed['profil PKK']) return false;
    var body = norm(document.body && document.body.innerText || '');
    if (!/rodzaj profilu|profil pkk|profil pkz|wybierz profil/.test(body)) return false;
    if (uniqueClick(function (s) { return /^pkk$/.test(s); }, 'profil PKK')) return true;
    // Native radio inputs and custom radio-group controls are not buttons.
    var candidates = Array.from(document.querySelectorAll(
      'input[type="radio"], [role="radio"], label')).filter(visible)
      .filter(function (el) {
        var val = norm(el.value || el.getAttribute('aria-label') || el.innerText ||
          el.textContent || '');
        return val === 'pkk' || val === 'profil pkk';
      });
    // Nested label + input count as different candidates: do not guess.
    return candidates.length === 1 && click(candidates[0], 'profil PKK');
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
    // Without a page heading, the text may include every global navigation
    // link (including "Opłać egzamin") even on the homepage.
    // Never interpret such a menu link as an active payment screen.
    var relevant = heading ? norm(heading.textContent || heading.innerText) : '';
    // Strong payment/confirmation indicators remain blocking even without h1.
    // "Opłać egzamin" alone can be a global menu item.
    var strong = /podsumowanie rezerwacji|metoda platnosci|potwierdz rezerwacje|zaplac|numer rezerwacji/;
    return strong.test(body) || /oplac egzamin/.test(relevant);
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
    if (chooseProfileType()) return;
    if (chooseSingleProfile()) return;
    // The first step in the official form has center + PKK. The following
    // step has practical exam choice. Do not confuse these with booking.
    if (selectNative('osrodek', /(osrodek|word|mord|centrum egzamin)/,
        /^(mord krakow|malopolski osrodek ruchu drogowego w krakowie)$/)) return;
    if (selectNative('kategoria', /kategoria/, /^(kategoria\s+)?b$/)) return;
    if (selectNative('praktyczny', /(rodzaj|typ egzamin)/,
        /^(egzamin\s+)?(praktyka|praktyczny)$/)) return;
    if (fillDateFrom()) return;

    // Only exact, labelled UI targets. Never click generic "Wybierz" or a slot.
    if (openCombobox('numer PKK', /(numer profilu|numer pkk|profil kandydata)/)) return;
    if (openCombobox('osrodek', /(osrodek|word|mord|centrum egzamin)/)) return;
    if (openCombobox('kategoria', /kategoria/)) return;
    if (openCombobox('praktyczny', /(rodzaj|typ egzamin)/)) return;

    var selectors = Array.from(document.querySelectorAll(
      'select, [role="combobox"], mat-select')).filter(visible);
    var hasWizard = selectors.some(function (el) {
      return /profil|pkk|osrodek|kategoria|rodzaj egzamin/.test(fieldLabel(el));
    }) || Array.from(document.querySelectorAll('input[type="radio"], input[type="date"]'))
      .filter(visible).some(function (el) {
        return /profil|pkk|osrodek|kategoria|rodzaj egzamin|data egzaminu/.test(fieldLabel(el));
      });
    // The portal's navigation may mention PKK even on its homepage. The presence
    // of a label alone is not proof the reservation wizard is already open.
    if (!hasWizard) {
      if (uniqueClick(examMenuAction,
        'otwarcie zakładki Zarezerwuj termin egzaminu')) return;
      if (openExamMenuLeaf()) return;
    }

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
          return /profil|pkk|osrodek|kategoria|praktyczny/.test(norm(k));
        }) && /wybierz|profil|pkk|kategoria|osrodek|mord|egzamin praktyczny/.test(body) &&
        !/dostepne terminy|godzina egzaminu|platnosc/.test(body)) {
      var stage = norm((document.querySelector('h1,h2,h3') || {}).textContent || '') +
        '|' + norm(location.pathname) + '|' + Object.keys(changed)
          .filter(function (k) { return !/^next:/.test(k); }).sort().join(',');
      if (!changed['next:' + stage] && uniqueClick(function (s) {
        return s === 'dalej' || s === 'przejdz dalej';
      }, 'next:' + stage)) return;
    }
    var d = diagnose();
    report('Brak kolejnego bezpiecznego kroku. ' +
      (d.bookingAction ? 'Widzę przycisk rezerwacji, ale nie jest jednoznaczny. ' :
       d.wizard ? 'Formularz jest widoczny, nie rozpoznano kolejnej kontrolki. ' :
       'Nie widzę jednoznacznej zakładki „Zarezerwuj termin egzaminu”. ') +
      'Opcje → Diagnostyka → Kopiuj raport automatu.');
  }
  function diagnose() {
    var a = [];
    try { a = buttons(); } catch (_) {}
    var names = a.map(name);
    var kinds = [
      ['start', /^(zarezerwuj termin egzaminu|zarezerwuj termin|rezerwacja terminu egzaminu)$/],
      ['pkk', /^(profil )?pkk$/],
      ['next', /^(dalej|przejdz dalej)$/],
      ['search', /^(pokaz|wyszukaj|sprawdz|szukaj) termin/],
      ['payment', /^(zaplac|oplac|potwierdz rezerwacje)$/]
    ];
    var controls = {};
    kinds.forEach(function (v) {
      controls[v[0]] = names.filter(function (n) {return v[1].test(n);}).length;
    });
    var elements = [];
    try {
      elements = Array.from(document.querySelectorAll(
        'select, [role="combobox"], mat-select, .mat-mdc-select, input[type="radio"], input[type="date"]'))
        .filter(visible);
    } catch (_) {}
    var fields = {profile:0,center:0,category:0,type:0,date:0,other:0};
    var otherTags = {select:0,combobox:0,radio:0,date:0,other:0};
    elements.forEach(function (el) {
      var label = '';
      try {label = fieldLabel(el);} catch (_) {}
      var key = /profil|pkk/.test(label) ? 'profile' :
        /osrodek|word|mord/.test(label) ? 'center' :
        /kategoria/.test(label) ? 'category' :
        /rodzaj|typ egzamin/.test(label) ? 'type' :
        /data|date|termin/.test(label) ? 'date' : 'other';
      fields[key]++;
      if (key === 'other') {
        var tag = norm(el.tagName || '');
        var type = norm(el.getAttribute && el.getAttribute('type') || '');
        var role = norm(el.getAttribute && el.getAttribute('role') || '');
        var kind = tag === 'select' ? 'select' :
          role === 'combobox' ? 'combobox' :
          type === 'radio' ? 'radio' :
          type === 'date' ? 'date' : 'other';
        otherTags[kind]++;
      }
    });
    var embedded = 0, shadowHosts = 0, menuLabelVisible = false;
    try {
      embedded = document.querySelectorAll('iframe').length;
      shadowHosts = Array.from(document.querySelectorAll('*'))
        .slice(0,2000).filter(function(el){return !!el.shadowRoot;}).length;
      menuLabelVisible = Array.from(document.querySelectorAll(
        'nav, aside, [role="navigation"], [role="menu"], mat-list-item'))
        .filter(visible).some(function (el) {
          return norm(el.textContent || '').indexOf('zarezerwuj termin egzaminu')>=0;
        });
    } catch (_) {}
    var body = norm((document.body && document.body.innerText || '').slice(0,20000));
    return {
      script:true, enabled:!!settings.enabled, stopped:!!stopped,
      pending:pending ? (pending==='numer PKK'?'profil':pending) : '',
      actions:clicks, last:lastReport, wizard:elements.length>0,
      controls:controls, fields:fields,
      otherTags:otherTags, embeddedFrames:embedded, shadowHosts:shadowHosts,
      menuTextVisible:menuLabelVisible,
      screen: /login|signin|authorize|logowanie|identity/.test(norm(location.pathname)) ? 'logowanie' :
        /checkout|payment|platnosc|podsumowanie/.test(norm(location.pathname)) ? 'platnosc' :
        /wybierz godzine|dostepne terminy egzaminow|lista terminow/.test(body) ? 'terminy' :
        /rodzaj profilu|numer profilu|kategoria|osrodek/.test(body) ? 'formularz lub menu' : 'start/inne'
    };
  }
  function retry() {
    stopped = false; pending = null; clicks = 0; lastAction = 0;
    changed = {}; awaitingResults = false; lastReport = '';
    report('Ponowna próba przygotowania formularza.');
    scan();
    return diagnose();
  }
  function configure(cfg) {
    settings.enabled = !!(cfg && cfg.enabled);
    if (!settings.enabled) {
      pending = null;
      report('Automatyczne przygotowanie wyłączone.');
      return true;
    }
    // Android may call configure again after a page loads. Do not reset a
    // successfully completed or explicitly stopped flow on the same document.
    if (stopped) return true;
    report('Przygotowuję formularz po zalogowaniu.');
    scan();
    return true;
  }
  window.__zlapFormPrep = {configure:configure, scan:scan,
    diagnose:diagnose, retry:retry, stop:function () {
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