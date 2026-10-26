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
  var pendingSince = 0;
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
    if (!el || !el.isConnected || el.disabled ||
        (el.getAttribute && el.getAttribute('aria-disabled') === 'true')) return false;
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
  // Real screenshot shows "Następny →", not the plain "Następny".
  // Match only the wizard-navigation action and an optional arrow/icon label.
  // NEVER accept labels containing "zarezerwuj", "potwierdź", etc.
  function nextWizardLabel(raw) {
    var label = norm(raw || '');
    label = label.replace(/\s*(→|➜|➔|➝|❯|›|»|⟶|⟹|➡|⇢)\s*$/g, '').trim();
    label = label.replace(/\s*(arrow_forward|arrow_right_alt|chevron_right|east)\s*$/g, '').trim();
    return /^(nastepny|dalej|przejdz dalej)$/.test(label);
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

  function menuOpened() {
    return changed['menu usług'] || changed['otwarcie zakładki Zarezerwuj termin egzaminu'];
  }

  function isHomePage(body) {
    // Exact words from the logged-in Info-Kierowca homepage.
    // A lone "PORTAL E-USŁUG" is a persistent header and is NOT enough.
    return /moje wnioski i rezerwacje|lista moich spraw|rezerwacje zostaly anulowane|rezerwacja anulowana przez word/.test(body);
  }
  function inHamburgerArea(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var box = el.getBoundingClientRect();
    var width = (window.innerWidth || document.documentElement &&
      document.documentElement.clientWidth || 400);
    return box.width > 7 && box.width <= 105 &&
      box.height > 7 && box.height <= 105 &&
      box.left >= -8 && box.left < Math.min(110, width*0.28) &&
      box.right <= Math.min(135, width*0.36) &&
      box.top >= -12 && box.top < 100 && box.bottom < 145;
  }
  function hamburgerGlyph(el) {
    if (!el) return false;
    var words = norm((el.getAttribute &&
      ((el.getAttribute('aria-label') || '') + ' ' +
       (el.getAttribute('title') || '') + ' ' +
       (el.getAttribute('data-testid') || ''))) || '');
    var cls = norm(typeof el.className === 'string' ? el.className : '');
    if (/^(menu|otworz menu|pokaz menu|menu glowne|menu portalu|menu nawigacji|otworz nawigacje)/.test(words) ||
        /hamburger|navbar-toggler|menu-toggle|menu-button|menu-icon|sidenav-toggle|nav-toggle/.test(cls))
      return true;
    var icon = el.querySelector && el.querySelector('mat-icon, .material-icons');
    if (icon && norm(icon.textContent) === 'menu') return true;
    var svg = (el.tagName && norm(el.tagName) === 'svg') ? el :
      el.querySelector && el.querySelector('svg');
    if (!svg) return false;
    var lines = svg.querySelectorAll ? Array.from(svg.querySelectorAll('line')) : [];
    if (lines.filter(function(l){return l.getAttribute('y1') === l.getAttribute('y2');}).length >= 3)
      return true;
    var path = svg.querySelectorAll ? Array.from(svg.querySelectorAll('path')) : [];
    var d = path.map(function(p){return (p.getAttribute('d') || '').replace(/\s/g,'').toLowerCase();}).join('');
    // Three horizontal Material/Lucide/SVG path segments.
    if ((d.match(/[mh][0-9.,-]+h[0-9.,-]+/g) || []).length >= 3 ||
        (d.includes('m3,18h18') && d.includes('m3,12h18') && d.includes('m3,6h18')))
      return true;
    return false;
  }
  function menuButtonTarget(el) {
    if (!el) return null;
    var target = el.closest && el.closest(
      'button, [role="button"], [aria-label*="menu"], [title*="menu"],' +
      ' .hamburger, .navbar-toggler, .menu-toggle, .menu-button,' +
      ' .menu-icon, .sidenav-toggle');
    if (target && inHamburgerArea(target)) return target;
    // Web frameworks often bind click to a wrapping div with no role.
    var node = el;
    for (var i=0; node && i<4; node=node.parentElement, i++) {
      if (!inHamburgerArea(node)) continue;
      var pointer = false;
      try { pointer = getComputedStyle(node).cursor === 'pointer'; } catch(_){}
      if (hamburgerGlyph(node) && (pointer || node.onclick ||
          (node.tagName && /^(button|a)$/i.test(node.tagName)) ||
          (node.querySelector && !!node.querySelector('svg, mat-icon'))))
        return node;
    }
    return null;
  }
  function hamburgerCandidates() {
    var found = [];
    function add(el) {
      if (!el || !visible(el) || !inHamburgerArea(el) ||
          found.indexOf(el)>=0) return;
      found.push(el);
    }
    try {
      Array.from(document.querySelectorAll(
        'button, [role="button"], [aria-label], [title], mat-icon, svg,' +
        ' [class*="hamburger"], [class*="menu"], [class*="navbar-toggler"],' +
        ' [class*="sidenav-toggle"], [class*="nav-toggle"]'))
        .filter(visible).forEach(function (el) {
          if (!hamburgerGlyph(el)) return;
          add(menuButtonTarget(el) || el);
        });
      // Screenshot-based probe, constrained to the menu area of the authenticated
      // homepage. Never click an arbitrary point: require a clickable target
      // AND a matching hamburger glyph or menu identity.
      if (document.elementFromPoint) {
        var width = window.innerWidth || 400;
        [20,32,44,56].forEach(function(y) {
          [Math.min(26,width*.07),Math.min(40,width*.10),
           Math.min(55,width*.14)].forEach(function(x) {
            var el = document.elementFromPoint(x,y);
            var target = menuButtonTarget(el);
            if (target && (hamburgerGlyph(target) || hamburgerGlyph(el)))
              add(target);
          });
        });
      }
    } catch (_) {}
    var out = found.filter(function(el) {
      return !found.some(function(other) {
        return other !== el && el.contains && el.contains(other);
      });
    });
    if (out.length) return out;
    // Screenshot-verified fallback: on "Moje wnioski i rezerwacje" the
    // hamburger is the one compact control in the top-left 90 px of the
    // WebView. Some portals render the three bars with CSS pseudo-elements,
    // with no SVG, aria-label, title, button, or accessible text.
    // Only try bounded elementFromPoint on that EXACT home screen.
    var body = norm((document.body && document.body.innerText || '').slice(0,15000));
    if (!isHomePage(body) || !document.elementFromPoint) return out;
    var width = window.innerWidth || 400, hits = [];
    [26,35,44].forEach(function(y) {
      [Math.min(30,width*.08),Math.min(39,width*.10)].forEach(function(x) {
        try {
          var hit = document.elementFromPoint(x,y);
          var candidate = null;
          for (var n=hit, depth=0; n && depth<5; n=n.parentElement,depth++) {
            if (!inHamburgerArea(n)) continue;
            var rect=n.getBoundingClientRect();
            if (rect.left>x || rect.right<x || rect.top>y || rect.bottom<y)
              continue;
            var isIcon = /^(svg|mat-icon|i)$/i.test(n.tagName || '');
            var pointer = false;
            try { pointer = getComputedStyle(n).cursor === 'pointer'; } catch (_) {}
            if ((rect.width >= 34 && rect.height >= 32) &&
                (pointer || n.onclick || /^(button|a)$/i.test(n.tagName || ''))) {
              candidate = n; break;
            }
            if (isIcon && rect.width >= 16 && rect.height >= 12)
              candidate = n;
          }
          if (candidate && hits.indexOf(candidate)<0) hits.push(candidate);
        } catch (_) {}
      });
    });
    hits = hits.filter(function(el) {
      return !hits.some(function(other) {
        return other!==el && el.contains && el.contains(other);
      });
    });
    // Require one unambiguous compact interactive target in the corner.
    return hits.length===1 ? hits : [];
  }
  function openServicesMenu(body) {
    if (menuOpened() || !isHomePage(body)) return false;
    var candidates = hamburgerCandidates();
    if (candidates.length !== 1) {
      report(candidates.length ?
        'Wykryto kilka ikon menu. Nie klikam niejednoznacznej kontrolki.' :
        'Widzę stronę główną, ale nie rozpoznaję ikony menu ☰. Skopiuj raport automatu.');
      return false;
    }
    return click(candidates[0], 'menu usług');
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
        ' [class*="tile"] span, [class*="card"] span,' +
        ' span, p, li, label, [class*="service"] div'))
        .filter(visible).filter(function (el) {
          return examMenuAction(norm(el.textContent || ''));
        });
    } catch (_) { return false; }
    if (!possible.length) return false;
    // Prefer deepest exact-text leaf, so nesting does not count as ambiguity.
    possible = possible.filter(function (el) {
      return !possible.some(function (other) {
        return other !== el && el.contains && el.contains(other);
      });
    });
    var targets = [];
    possible.forEach(function (el) {
      var target = el.closest(
        'a, button, [role="button"], [role="link"], [role="menuitem"],' +
        ' [onclick], [tabindex], mat-list-item, .mat-list-item');
      // Clicking the text itself bubbles to the parent Angular click handler
      // even when the service tile is a plain div rather than a link/button.
      if (!target) target = el;
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
    var parent = el.closest(
      'mat-form-field, .mat-mdc-form-field, .MuiFormControl-root,' +
      ' .MuiTextField-root, .form-group, .form-field, .p-dropdown,' +
      ' .p-select, .ant-select, .ng-select, label, fieldset') || el.parentElement;
    if (parent) labels.push(String(parent.innerText || '').slice(0, 180));
    // Floating "Numer profilu" and "Kategoria" labels may be two wrapper
    // levels above the actual custom select. Stop before the whole form.
    if (!/(numer profilu|kategoria|osrodek|rodzaj egzamin|pkk|word)/.test(norm(labels.join(' ')))) {
      for (var n = 0, node = el.parentElement; node && n < 3; n++, node=node.parentElement) {
        var t = norm(node.innerText || '');
        if (t.length < 160 && t) labels.push(t);
      }
    }
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

  // Category B is already selected by the user's portal/profile.
  // Read it where possible, without changing it or opening the dropdown.
  function categoryStatus() {
    var nodes = [];
    try {
      nodes = Array.from(document.querySelectorAll(
        'select, input, [role="combobox"], mat-select, .mat-mdc-select,' +
        ' .MuiSelect-select, .p-dropdown-label, .p-select, .ant-select-selection-item'
      )).filter(visible).filter(function(el) {
        return /kategoria/.test(fieldLabel(el));
      });
    } catch (_) { return 'unknown'; }
    var values = [];
    nodes.forEach(function(el) {
      try {
        var val = '';
        if (norm(el.tagName) === 'select' && el.options && el.selectedIndex >= 0) {
          val = el.options[el.selectedIndex].text || '';
        } else {
          val = el.value || el.getAttribute('data-value') ||
            el.getAttribute('aria-valuetext') ||
            el.innerText || el.textContent || '';
        }
        var selected = norm(val).replace(/^kategoria\s*:?\s*/, '').replace(/\*$/, '').trim();
        if (selected && selected.length < 20 &&
            !/^(wybierz|select|--)/.test(selected)) values.push(selected);
      } catch (_) {}
    });
    if (values.some(function(v){return v==='b' || v==='kat b';})) return 'B';
    if (values.some(function(v){
      return /^(a|a1|a2|am|b1|be|b\+e|c|c1|ce|d|d1|de|t)$/.test(v);
    })) return 'other';
    return 'unknown';
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
    if (changed[reason] || changed['lista ' + reason]) return false;
    var els = Array.from(document.querySelectorAll(
      '[role="combobox"], mat-select, .mat-mdc-select, .ng-select-container,' +
      ' .mat-select-trigger, .MuiSelect-select, .MuiAutocomplete-inputRoot,' +
      ' .p-dropdown, .p-dropdown-label, .p-select, .ant-select-selector,' +
      ' input[readonly], button[aria-haspopup="listbox"]'))
      .filter(visible).filter(function (el) { return label.test(fieldLabel(el)); });
    // Prefer the inner interactive control if wrapper and inner input match.
    els = els.filter(function(el) {
      return !els.some(function(other) {
        return other !== el && el.contains && el.contains(other);
      });
    });
    if (els.length !== 1) return false;
    if (clicks >= maxClicks) return false;
    pending = reason;
    pendingSince = Date.now();
    return click(els[0], 'lista ' + reason);
  }
  function selectOpenOption() {
    if (!pending) return false;
    var options = Array.from(document.querySelectorAll(
      '[role="option"], mat-option, .mat-mdc-option, .ng-option,' +
      ' .MuiMenuItem-root, .p-dropdown-item, .p-select-option,' +
      ' [role="listbox"] li, li[aria-selected]')).filter(visible);
    if (!options.length) {
      if (Date.now() - pendingSince < 6500) return true;
      report('Otworzono listę ' + pending +
        ', ale nie wykryto jej opcji. Potrzebny raport lub zdjęcie listy.');
      pending = null;
      return true;
    }
    var regex = pending === 'osrodek' ?
      /^(mord krakow|krakow|malopolski osrodek ruchu drogowego w krakowie)$/ :
      pending === 'kategoria' ? /^(kategoria\s+)?b$/ :
      pending === 'praktyczny' ? /^(egzamin\s+)?(praktyka|praktyczny)$/ :
      pending === 'profil PKK' ? /^pkk$/ : null;
    var matches = pending === 'numer PKK'
      ? options.filter(function (el) {
          var value = name(el);
          return value && !/^(wybierz|wybierz profil|brak profilu|dodaj|utworz)/.test(value) &&
            !/\bpkz\b/.test(value);
        })
      : pending === 'osrodek' ? options.filter(function (el) {
          var val=name(el);
          return regex.test(val) || (val.length < 130 &&
            /krakow/.test(val) && /nowohucka\s*33\s*a/.test(val) &&
            !/balicka/.test(val));
        }) : regex ? options.filter(function (el) { return regex.test(name(el)); }) : [];
    if (matches.length === 1) {
      var which = pending;
      pending = null;
      return click(matches[0], which);
    }
    report('Nie rozpoznano jednoznacznie opcji ' + pending + ' — wybierz ręcznie.');
    pending = null;
    return true;
  }

  // Text on the real first screen (from user's Android screenshot):
  // "PKK - Profil kandydata na kierowcę (PKK)"
  // and "Wybierz ośrodek WORD i pokaż wszystkie terminy egzaminów".
  function radioChoices(test) {
    var elements = [];
    try {
      elements = Array.from(document.querySelectorAll(
        'label, [role="radio"], .mat-radio-button, mat-radio-button,' +
        ' .mat-mdc-radio-button, .MuiFormControlLabel-root,' +
        ' input[type="radio"]')).filter(visible);
    } catch (_) {}
    var matched = elements.filter(function(el) {
      var labels = [];
      try {
        labels.push(el.innerText || el.textContent || '');
        if (el.getAttribute) labels.push(el.getAttribute('aria-label') || '');
        if (el.value) labels.push(String(el.value));
        // Some MUI/Angular inputs have value=on; their label has the text.
        var wrap = el.closest && el.closest(
          'label, mat-radio-button, .mat-mdc-radio-button, .MuiFormControlLabel-root');
        if (wrap && wrap!==el) labels.push(wrap.innerText || wrap.textContent || '');
        if (el.id && document.querySelector) {
          var lab = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
          if (lab) labels.push(lab.textContent || '');
        }
      } catch (_) {}
      return labels.some(function(txt){return test(norm(txt));});
    });
    // Prefer one top-level radio option, not two nested input/label matches.
    matched = matched.filter(function(el) {
      return !matched.some(function(other) {
        return other!==el && other.contains && other.contains(el);
      });
    });
    return matched;
  }
  function checkedRadio(el) {
    if (!el) return false;
    if (el.checked || (el.control && el.control.checked)) return true;
    if (el.getAttribute && el.getAttribute('aria-checked')==='true') return true;
    try {
      return !!(el.querySelector && el.querySelector(
        'input[type="radio"]:checked, [role="radio"][aria-checked="true"]'));
    } catch (_) {return false;}
  }
  function chooseProfileType() {
    if (changed['profil PKK']) return false;
    var body = norm(document.body && document.body.innerText || '');
    if (!/wybor rodzaju profilu|wybierz rodzaj profilu|profil kandydata na kierowce|profil pkz|wybierz profil/.test(body))
      return false;
    var candidates = radioChoices(function(txt) {
      return txt==='pkk' || txt==='profil pkk' ||
        /^pkk\s*-\s*profil kandydata na kierowce(?:\s*\(pkk\))?$/.test(txt);
    });
    if (candidates.length!==1) {
      if (candidates.length>1) report('Niejednoznaczny wybór PKK — zatrzymano.');
      return false;
    }
    if (checkedRadio(candidates[0])) {
      changed['profil PKK']=true;
      return false;
    }
    return click(candidates[0], 'profil PKK');
  }

  // On the second wizard screen Info-Kierowca renders four RADIO inputs.
  // In 0.31 a textual label's click was treated as success even if the portal
  // actually selected "Egzamin łączony". Never infer a selection from a click.
  function practicalRadioScreen(body) {
    return /wybor rodzaju egzaminu/.test(body) &&
      /wyswietl wszystkie typy egzaminu/.test(body) &&
      /wybor terminu egzaminu dla/.test(body);
  }
  function practicalRadioLabel(input) {
    var names = [];
    try {
      if (input.getAttribute) {
        names.push(input.getAttribute('aria-label') || '');
        var ids = String(input.getAttribute('aria-labelledby') || '').split(/\s+/);
        ids.forEach(function(id) {
          if (id && document.getElementById) {
            var labelled = document.getElementById(id);
            if (labelled) names.push(labelled.innerText || labelled.textContent || '');
          }
        });
      }
      if (input.id && document.querySelector) {
        var htmlLabel=document.querySelector(
          'label[for="' + CSS.escape(input.id) + '"]');
        if (htmlLabel) names.push(htmlLabel.innerText || htmlLabel.textContent || '');
      }
      if (input.closest) {
        var wrapper=input.closest(
          'label, mat-radio-button, .mat-radio-button, .mat-mdc-radio-button,' +
          ' .MuiFormControlLabel-root, [role="radio"]');
        if (wrapper && wrapper!==input) {
          var label=wrapper.querySelector &&
            wrapper.querySelector('label, .mdc-label');
          names.push(label ? label.innerText || label.textContent || '' :
            wrapper.innerText || wrapper.textContent || '');
        }
      }
    } catch (_) {}
    return names.map(norm).filter(Boolean);
  }
  function practicalRadioMatch() {
    var candidates = [], all = [];
    try {
      all=Array.from(document.querySelectorAll('input[type="radio"], [role="radio"]'));
    } catch (_) {}
    all.forEach(function(input) {
      // Explicitly address a single control, never a broad label/card.
      var labels=practicalRadioLabel(input);
      if (!labels.some(function(n){return n==='egzamin praktyczny';})) return;
      var clicked=input;
      try {
        if (!visible(input)) {
          // Input can be visually hidden under a custom radio; click the
          // uniquely associated visible LABEL, never its neighbouring card.
          var outer=input.closest && input.closest(
            'label, mat-radio-button, .mat-radio-button, .mat-mdc-radio-button,' +
            ' .MuiFormControlLabel-root, [role="radio"]');
          var explicit=input.id && document.querySelector &&
            document.querySelector('label[for="' + CSS.escape(input.id) + '"]');
          clicked=visible(explicit) ? explicit :
            visible(outer) ? outer : null;
        }
      } catch (_) {clicked=null;}
      if (clicked) candidates.push({radio:input, clickTarget:clicked});
    });
    return candidates.length===1 ? candidates[0] : null;
  }
  function practicalRadioVerified() {
    var match=practicalRadioMatch();
    if (!match) return false;
    var radio=match.radio;
    if (radio.checked === true ||
        (radio.getAttribute && radio.getAttribute('aria-checked')==='true')) return true;
    try {
      var outer=radio.closest && radio.closest(
        'mat-radio-button, .mat-radio-button, .mat-mdc-radio-button, .MuiFormControlLabel-root');
      if (outer && outer.getAttribute &&
          outer.getAttribute('aria-checked')==='true') return true;
    } catch (_) {}
    return false;
  }
  function choosePracticalExamRadio() {
    if (practicalRadioVerified()) {
      changed['rodzaj: egzamin praktyczny'] = true;
      report('Potwierdzono zaznaczenie egzaminu praktycznego.');
      return false;
    }
    if (changed['proba: egzamin praktyczny']) {
      // Verification is performed after the action cooldown; do not make
      // another click if the portal ignored the first or selected a different option.
      stopped=true;
      report('BŁĄD: po kliknięciu nie jest zaznaczony egzamin praktyczny. ' +
        'Zatrzymano automat przed wyszukiwaniem, aby nie wybrać łączonego.');
      return true;
    }
    var match=practicalRadioMatch();
    if (!match) {
      report('Nie znaleziono jednej, bezpiecznie powiązanej kontrolki radio „Egzamin praktyczny”. ' +
        'Nie klikam innych typów ani nie przechodzę dalej.');
      return true;
    }
    if (click(match.clickTarget, 'proba: egzamin praktyczny')) {
      report('Kliknięto radio egzamin praktyczny — oczekuję na potwierdzenie zaznaczenia.');
      return true;
    }
    return true;
  }
  function chooseWordMode() {
    if (changed['wybór WORD']) return false;
    var body = norm(document.body && document.body.innerText || '');
    if (!/wybor osrodka egzaminacyjnego word/.test(body)) return false;
    var matches = radioChoices(function(txt) {
      return /^wybierz osrodek word i pokaz wszystkie terminy egzaminow$/.test(txt);
    });
    if (matches.length!==1) {
      if (matches.length>1) report('Wybór trybu WORD niejednoznaczny.');
      return false;
    }
    if (checkedRadio(matches[0])) {
      changed['wybór WORD']=true;
      return false;
    }
    return click(matches[0], 'wybór WORD');
  }
  function chooseKrakowWordCard() {
    if (changed['ośrodek Kraków'] || !changed['wybór WORD']) return false;
    // Screenshots show the main Kraków card at Nowohucka 33a, distinct
    // from Kraków/O Kraków Balicka. Only accept the exact headquarters.
    var cards=[];
    try {
      cards=Array.from(document.querySelectorAll(
        'label, li, [role="radio"], [role="button"], button,' +
        ' [class*="card"], [class*="word"], [class*="center"],' +
        ' [class*="osrodek"], [class*="location"], div'))
        .filter(visible).filter(function(el) {
          var text=norm(el.innerText || el.textContent || '');
          return /krakow/.test(text) && /nowohucka\s*33\s*a/.test(text) &&
            !/balicka/.test(text) && text.length<220;
        });
    } catch (_) {}
    // Match the smallest visible card that mentions the specific address.
    cards=cards.filter(function(el) {
      return !cards.some(function(other) {
        return other!==el && el.contains && el.contains(other);
      });
    });
    if (cards.length!==1) {
      if (cards.length>1)
        report('Kilka kart Kraków/Nowohucka — nie wybieram losowej.');
      return false;
    }
    if (checkedRadio(cards[0]) ||
        (cards[0].getAttribute && cards[0].getAttribute('aria-selected')==='true')) {
      changed['ośrodek Kraków']=true;
      return false;
    }
    return click(cards[0], 'ośrodek Kraków');
  }
  function onProfileWordStep(body) {
    return /wybor rodzaju profilu/.test(body) &&
      /wybor osrodka egzaminacyjnego word/.test(body);
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
    // PKK first: category can be disabled until a profile is selected.
    if ((changed['profil PKK'] || /numer profilu/.test(body)) &&
        openCombobox('numer PKK', /(numer profilu|numer pkk|profil kandydata)/))
      return;
    // Category B is preselected by the portal; do not click this field.
    // Detect an explicitly different category before advancing.
    if (categoryStatus() === 'other') {
      report('Wybrano inną kategorię niż B. Sprawdź formularz ręcznie.');
      return;
    }

    // The actual screen initially checks "nearest WORDs", which is NOT
    // specific to MORD Kraków. Choose the explicitly named mode, then the
    // main card at Nowohucka 33a (never Kraków Balicka).
    if (chooseWordMode()) return;
    if (chooseKrakowWordCard()) return;
    if ((changed['wybór WORD'] ||
         !/wybor osrodka egzaminacyjnego word/.test(body)) &&
        selectNative('osrodek',
        /(osrodek|word|mord|centrum egzamin)/,
        /^(mord krakow|krakow|malopolski osrodek ruchu drogowego w krakowie)$/))
      return;
    if ((changed['wybór WORD'] ||
         !/wybor osrodka egzaminacyjnego word/.test(body)) &&
        openCombobox('osrodek', /(osrodek|word|mord|centrum egzamin)/)) return;

    // The real exam-type screen has FOUR radio inputs. No fallback to
    // a select/combobox or search action until the PRACTICAL input is
    // visibly/semantically checked. This also stops a misselected combined exam.
    if (practicalRadioScreen(body)) {
      if (choosePracticalExamRadio()) return;
      if (!practicalRadioVerified()) return;
    }
    // Other portal layouts may have explicit selects; preserve them only
    // outside the four-radio screen.
    if (selectNative('praktyczny', /(rodzaj|typ egzamin)/,
        /^(egzamin\s+)?(praktyka|praktyczny)$/)) return;
    if (openCombobox('praktyczny', /(rodzaj|typ egzamin)/)) return;
    if (fillDateFrom()) return;

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
    if (!hasWizard && !changed['otwarcie zakładki Zarezerwuj termin egzaminu']) {
      if (uniqueClick(examMenuAction,
        'otwarcie zakładki Zarezerwuj termin egzaminu')) return;
      if (openExamMenuLeaf()) return;
      if (openServicesMenu(body)) return;
    }

    // Search action is only used once criteria are visible, never on a list of slots.
    if (!awaitingResults && /rodzaj egzaminu|egzamin praktyczny|praktyka/.test(body) &&
        uniqueClick(function (s) {
          return /^(pokaz terminy|wyszukaj terminy|sprawdz terminy|szukaj terminu|szukaj terminow|wyszukaj)$/.test(s);
        }, 'wyszukiwanie terminow')) {
      awaitingResults = true;
      return;
    }

    // On the screenshot "Następny" is a NEXT-WIZARD button, not a
    // reservation. Click it only on first "Profil i WORD" step after the
    // exact PKK + WORD preference and headquarters have been selected.
    if (onProfileWordStep(body) &&
        changed['profil PKK'] && changed['wybór WORD'] &&
        changed['ośrodek Kraków'] &&
        (changed['jedyny profil PKK'] || changed['numer PKK']) &&
        categoryStatus() !== 'other') {
      if (!changed['przejście Profil i WORD'] &&
          uniqueClick(function(txt){
            return nextWizardLabel(txt);
          }, 'przejście Profil i WORD')) return;
    }
    var d = diagnose();
    report('Brak kolejnego bezpiecznego kroku. ' +
      (d.wizard ? 'Formularz jest widoczny, nie rozpoznano kolejnej kontrolki. ' :
       changed['otwarcie zakładki Zarezerwuj termin egzaminu'] ?
       'Otworzono zakładkę, ale nie rozpoznano kolejnego pola. ' :
       'Nie rozpoznano jednoznacznej zakładki „Zarezerwuj termin egzaminu”. ') +
      'Opcje → Diagnostyka → Kopiuj raport automatu.');
  }

  // Guided reservation through the same user-visible cards as the portal.
  // Only the already-matched date from Android settings may be selected.
  // No direct /Reservations/create POST, no payment, and no silent retries.
  var bookingUi = null;
  function bookingUiReport(kind, detail) {
    try {
      if (window.ZlapBridge && window.ZlapBridge.postMessage) {
        window.ZlapBridge.postMessage(JSON.stringify({
          kind:kind, date:bookingUi ? bookingUi.iso : '',
          detail:String(detail || '').slice(0,100)
        }));
      }
    } catch (_) {}
  }
  function bookingUiDate(iso) {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(iso)) return null;
    var day=iso.slice(0,10), hour=iso.slice(11,16);
    if (/[zZ]$|[+-]\d{2}:\d{2}$/.test(iso)) {
      // Match local MORD Kraków clock time even when API date carries offset.
      try {
        var parts=new Intl.DateTimeFormat('en-GB', {
          timeZone:'Europe/Warsaw',year:'numeric',month:'2-digit',day:'2-digit',
          hour:'2-digit',minute:'2-digit',hourCycle:'h23'
        }).formatToParts(new Date(iso));
        function part(kind) {return parts.filter(function(p){return p.type===kind;})[0].value;}
        day=part('year')+'-'+part('month')+'-'+part('day');
        hour=part('hour')+':'+part('minute');
      } catch (_) {return null;}
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || hour > '23:59') return null;
    return {
      day:day,
      label:day.slice(8,10)+'/'+day.slice(5,7)+'/'+day.slice(0,4),
      hour:hour
    };
  }
  function bookingUiSafeText(el) {
    return norm((el && (el.innerText || el.textContent)) || '');
  }
  function bookingUiCardMatches(txt,hour) {
    if (!/egzamin praktyczny/.test(txt) || !/\bkat(?:egoria)?\s*b\b/.test(txt)) return false;
    if (!new RegExp('(?:^|[^0-9])'+hour.replace(':','\\:')+'(?![0-9])').test(txt)) return false;
    var places = txt.match(/\b([0-9]+)\s+wolne?\s+miejsc(?:a)?\b/);
    return !!places && Number(places[1])>0;
  }
  function bookingUiFindCard(target) {
    var all=[];
    try { all=Array.from(document.querySelectorAll('*')); } catch (_) {}
    // Identify the date heading preceding the candidate card. A matching
    // time on another day must never be clicked.
    var headings = all.map(function(el, i) {
      var txt = bookingUiSafeText(el);
      return /^\d{2}\/\d{2}\/\d{4}$/.test(txt) ?
        {index:i,date:txt} : null;
    }).filter(Boolean);
    var candidates = all.filter(function(el) {
      if (!visible(el)) return false;
      var txt=bookingUiSafeText(el);
      return txt.length>25 && txt.length<450 &&
        bookingUiCardMatches(txt,target.hour);
    });
    candidates = candidates.filter(function(el) {
      return !candidates.some(function(other) {
        return other!==el && el.contains && el.contains(other);
      });
    });
    candidates = candidates.filter(function(el) {
      var index=all.indexOf(el);
      var before=headings.filter(function(h){return h.index<index;});
      return before.length && before[before.length-1].date===target.label;
    });
    if (candidates.length!==1) return null;
    var card=candidates[0];
    // The entire card may receive the Angular click, or its radio input.
    // Prefer the uniquely identified radio control if available.
    var radios=[];
    try {radios=Array.from(card.querySelectorAll('input[type="radio"], [role="radio"]'));}catch(_){}
    if (radios.length===1 && visible(radios[0])) {
      if (radios[0].checked || radios[0].getAttribute('aria-checked')==='true')
        return {node:card,already:true};
      return {node:radios[0],already:false};
    }
    if (/^(button|label)$/i.test(card.tagName||'') || card.onclick ||
        (card.getAttribute && (card.getAttribute('role')==='radio' ||
          card.getAttribute('role')==='button'))) return {node:card,already:false};
    // A div card with a click listener bound by Angular lacks onclick in DOM.
    // Clicking the exact uniquely-matched card still bubbles to its handler.
    if (/^div$/i.test(card.tagName||'')) return {node:card,already:false};
    return null;
  }
  function bookingUiFindDateHeading(target) {
    var matches=[];
    try {
      matches=Array.from(document.querySelectorAll('*')).filter(visible)
        .filter(function(el){return bookingUiSafeText(el)===norm(target.label);});
    } catch (_) {}
    matches=matches.filter(function(el){
      return !matches.some(function(other){
        return other!==el && el.contains && el.contains(other);
      });
    });
    if (matches.length!==1) return null;
    var heading=matches[0];
    // A heading with an already-visible card must not be clicked again,
    // as that would collapse it rather than reveal the requested slot.
    var all=Array.from(document.querySelectorAll('*'));
    var at=all.indexOf(heading);
    if (all.slice(at+1).some(function(el){
      var label=bookingUiSafeText(el);
      if (/^\d{2}\/\d{2}\/\d{4}$/.test(label)) return false;
      return visible(el) && label.length>25 && label.length<450 &&
        /egzamin praktyczny/.test(label);
    })) {
      var nextDate=all.slice(at+1).findIndex(function(el){
        return /^\d{2}\/\d{2}\/\d{4}$/.test(bookingUiSafeText(el));
      });
      var currentBlock=all.slice(at+1,
        nextDate<0 ? all.length : at+1+nextDate);
      if (currentBlock.some(function(el) {
        var txt=bookingUiSafeText(el);
        return visible(el) && txt.length>25 && txt.length<450 &&
          /egzamin praktyczny/.test(txt);
      })) return null;
    }
    var btn=heading.closest && heading.closest(
      'button, [role="button"], summary, [aria-expanded]');
    // A date bar may have a JavaScript click handler on a plain div.
    return btn && visible(btn) ? btn : heading;
  }
  function bookingUiFindModal(target) {
    var dialogs=[];
    try {
      dialogs=Array.from(document.querySelectorAll(
        '[role="dialog"], [aria-modal="true"], .MuiDialog-paper,' +
        ' .mat-dialog-container, .modal-dialog'));
    } catch (_) {}
    dialogs=dialogs.filter(visible).filter(function(el) {
      var txt=bookingUiSafeText(el);
      return txt.length<2000 && /potwierdz wybrany egzamin/.test(txt);
    });
    dialogs=dialogs.filter(function(el){
      return !dialogs.some(function(other) {
        return other!==el && el.contains && el.contains(other);
      });
    });
    if (dialogs.length!==1) return null;
    var modal=dialogs[0], txt=bookingUiSafeText(modal);
    if (!/egzamin praktyczny/.test(txt) ||
        !/kategoria\s+b\b/.test(txt) ||
        txt.indexOf(target.label)<0 || txt.indexOf(target.hour)<0)
      return {mismatch:true};
    var controls=[];
    try {controls=Array.from(modal.querySelectorAll('button, [role="button"]'))
      .filter(visible).filter(function(el) {
        return /^potwierdz i przejdz dalej$/.test(name(el));
      });} catch (_) {}
    if (controls.length!==1) return {mismatch:true};
    return {button:controls[0],mismatch:false};
  }
  function bookingUiTick() {
    if (!bookingUi || bookingUi.done) return;
    var target=bookingUi;
    if (Date.now()-target.started>25000) {
      target.done=true;
      bookingUiReport(target.stage==='expanding' ?
        'booking_ui_no_card' : 'booking_ui_problem',
        target.stage==='expanding' ?
        'Po rozwinięciu daty nie widzę pasującej karty.' :
        'Nie odnaleziono bezpiecznego potwierdzenia. Sprawdź Portal.');
      return;
    }
    if (target.stage==='expanding') {
      var chosen=bookingUiFindCard(target);
      if (!chosen) return;
      target.stage='selected';
      bookingUiReport('booking_ui_selected','Rozwinięto datę i wybrano pasujący egzamin.');
      if (!chosen.already) chosen.node.click();
    }
    var result=bookingUiFindModal(target);
    if (!result) return;
    if (result.mismatch) {
      target.done=true;
      bookingUiReport('booking_ui_problem','Nie zgadza się data/godzina/kategoria w oknie.');
      return;
    }
    if (!result.button || !visible(result.button)) return;
    // Exactly the requested exam is displayed in "Potwierdź wybrany egzamin".
    // This is a single transition to the next portal screen, NOT payment.
    target.done=true;
    bookingUiReport('booking_ui_confirming','Potwierdzam dokładnie pasujący egzamin w oknie portalu.');
    result.button.click();
    bookingUiReport('booking_ui_advanced','Kliknięto Potwierdź i przejdź dalej; stan rezerwacji wymaga sprawdzenia.');
  }
  function bookUi(practiceId,iso,dateFrom,dateTo,hourFrom,hourTo) {
    // Explicit native autoReserve opt-in controls this call; the optional
    // form-preparation toggle is independent.
    if (!allowed() || bookingUi || !document.body) return false;
    if (typeof practiceId!=='string' || !/^[A-Za-z0-9_-]{1,90}$/.test(practiceId))
      return false;
    var target=bookingUiDate(iso);
    if (!target || typeof dateFrom!=='string' || typeof dateTo!=='string' ||
        target.day<dateFrom || target.day>dateTo ||
        !/^\d{2}:\d{2}$/.test(hourFrom) || !/^\d{2}:\d{2}$/.test(hourTo) ||
        target.hour<hourFrom || target.hour>hourTo) return false;
    if (/platnosc|podsumowanie|zaplac|oplat/.test(norm(document.body.innerText||'')))
      return false;
    var match=bookingUiFindCard(target);
    var heading=match ? null : bookingUiFindDateHeading(target);
    if (!match && !heading) return false;
    bookingUi={iso:iso,day:target.day,label:target.label,
      hour:target.hour,started:Date.now(),done:false,
      stage:match ? 'selected' : 'expanding'};
    if (match) {
      bookingUiReport('booking_ui_selected','Znaleziono pasującą kartę terminu.');
      if (!match.already) match.node.click();
    } else {
      bookingUiReport('booking_ui_expanding','Rozwijam dokładnie wskazaną datę.');
      heading.click();
    }
    // Angular dialog is asynchronous. A single interval observes it without
    // re-clicking the card or issuing HTTP requests.
    if (window.setInterval) {
      var ticks=0;
      var timer=window.setInterval(function(){
        bookingUiTick();
        if (!bookingUi || bookingUi.done || ++ticks>35) {
          if (window.clearInterval) window.clearInterval(timer);
        }
      },700);
    }
    bookingUiTick();
    return true;
  }

  function diagnose() {
    var a = [];
    try { a = buttons(); } catch (_) {}
    var names = a.map(name);
    var kinds = [
      ['start', /^(zarezerwuj termin egzaminu|zarezerwuj termin|rezerwacja terminu egzaminu)$/],
      ['pkk', /^(profil )?pkk$/],
      ['next', /^(nastepny|dalej|przejdz dalej)(?:\s*(?:→|➜|➔|➝|❯|›|»|⟶|⟹|➡|⇢|arrow_forward|arrow_right_alt|chevron_right|east))?$/],
      ['search', /^(pokaz|wyszukaj|sprawdz|szukaj) termin/],
      ['payment', /^(zaplac|oplac|potwierdz rezerwacje)$/]
    ];
    var controls = {};
    kinds.forEach(function (v) {
      controls[v[0]] = names.filter(function (n) {return v[1].test(n);}).length;
    });
    // Count navigation actions outside buttons(), too: a disabled or styled
    // action may be present on the page yet not safely clickable.
    var nextRaw = [], nextEnabled = [], nextDisabled = 0;
    try {
      nextRaw = Array.from(document.querySelectorAll(
        'button, a, [role="button"], input[type="submit"], input[type="button"]'
      )).filter(function(el) {
        return nextWizardLabel(name(el));
      });
      nextEnabled = nextRaw.filter(visible);
      nextDisabled = nextRaw.filter(function(el) {
        return !!el.disabled || (el.getAttribute &&
          el.getAttribute('aria-disabled') === 'true');
      }).length;
    } catch (_) {}
    var firstStage = onProfileWordStep(norm(
      (document.body && document.body.innerText || '').slice(0, 20000)));
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
    var home = isHomePage(body);
    var hamburgerCount = home ? hamburgerCandidates().length : 0;
    return {
      onHomePage:home, hamburgerCount:hamburgerCount,
      script:true, enabled:!!settings.enabled, stopped:!!stopped,
      pending:pending ? (pending==='numer PKK'?'profil':pending) : '',
      actions:clicks, last:lastReport,
      bookingUiStage:bookingUi ? (bookingUi.done ? 'zakończony' : bookingUi.stage) : 'nieuruchomiony',
      nextDiagnostics:{
        firstStage:firstStage,
        rawCandidates:nextRaw.length,
        enabledCandidates:nextEnabled.length,
        disabledCandidates:nextDisabled,
        profileAndWordReady:!!changed['profil PKK'] &&
          !!changed['wybór WORD'] && !!changed['ośrodek Kraków'] &&
          !!(changed['jedyny profil PKK'] || changed['numer PKK']) &&
          categoryStatus() !== 'other'
      },
      setupSteps:{
        pkk:!!changed['profil PKK'],
        profile:!!(changed['numer PKK'] || changed['jedyny profil PKK']),
        categoryB:categoryStatus()==='B',
        categoryPreset:categoryStatus()!=='other',
        categoryReadable:categoryStatus()!=='unknown',
        specificWord:!!changed['wybór WORD'],
        centerNowohucka:!!changed['ośrodek Kraków'],
        nextStep:!!changed['przejście Profil i WORD'],
        practicalRadio:practicalRadioScreen(body) ?
          practicalRadioVerified() : !!changed['rodzaj: egzamin praktyczny'],
        practicalRadioScreen:practicalRadioScreen(body),
        practicalRadioAttempted:!!changed['proba: egzamin praktyczny'],
        practicalRadioVerified:practicalRadioVerified()
      },
      wizard:elements.length>fields.other,
      controls:controls, fields:fields,
      otherTags:otherTags, embeddedFrames:embedded, shadowHosts:shadowHosts,
      menuTextVisible:menuLabelVisible,
      menuOpened:!!changed['menu usług'],
      screen: /login|signin|authorize|logowanie|identity/.test(norm(location.pathname)) ? 'logowanie' :
        /checkout|payment|platnosc|podsumowanie/.test(norm(location.pathname)) ? 'platnosc' :
        /wybierz godzine|dostepne terminy egzaminow|lista terminow/.test(body) ? 'terminy' :
        /rodzaj profilu|numer profilu|kategoria|osrodek/.test(body) ? 'formularz lub menu' : 'start/inne'
    };
  }
  function retry() {
    stopped = false; pending = null; pendingSince = 0; clicks = 0; lastAction = 0;
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
  window.__zlapFormPrep = {configure:configure, scan:scan, bookUi:bookUi,
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