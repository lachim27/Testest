/* ZlapEgzamin authenticated portal helper. Auto-hold is disabled by default
 * and started ONLY after an explicit opt-in in the Android settings.
 * Captured query/auth headers may be handed to the native reader in memory only. */
(function () {
  'use strict';
  if (window.__zlapHookV3) return;
  window.__zlapHookV3 = true;
  send({kind:'hook'});
  var saved = null, running = false, lastRequest = 0, cooldownUntil = 0;
  var requestInFlight = false;
  var endpoint = /onecenterexam/i;
  var observed = {};
  function observe(url) {
    try {
      var u = new URL(String(url || ''), location.href);
      if (u.protocol !== 'https:' || !/(^|\.)info-kierowca\.pl$/i.test(u.hostname)) return;
      var path = u.pathname.slice(0, 100);
      if (/exam|reservation|slot|termin|available/i.test(path) && !observed[path]) {
        observed[path] = true;
        send({kind:'candidate', path:path});
      }
    } catch (_) {}
  }
  function send(obj) {
    try {
      if (window.ZlapBridge && window.ZlapBridge.postMessage) {
        window.ZlapBridge.postMessage(JSON.stringify(obj));
      }
    } catch (_) {}
  }
  function isTarget(url) {
    try {
      var parsed = new URL(String(url || ''), location.href);
      return parsed.protocol === 'https:' && /(^|\.)info-kierowca\.pl$/i.test(parsed.hostname) && endpoint.test(parsed.pathname);
    } catch (_) { return false; }
  }
  function selected(body) {
    try {
      var b = JSON.parse(body);
      var organizations = Array.isArray(b.organizationId) ? b.organizationId : [b.organizationId];
      var orgOK = organizations.some(function (id) { return Number(id) === 21; });
      return orgOK && (Number(b.category) === 5 || String(b.category).toUpperCase() === 'B');
    } catch (_) { return false; }
  }
  // Local replay only: no extra HTTP request and no quota consumption.
  window.__zlapExportNativeRequest = function () {
    if (!saved) return false;
    var nativeHeaders = {};
    try { new Headers(saved.headers).forEach(function(value, key) { nativeHeaders[key] = value; }); } catch (_) {}
    send({kind:'native_request_ready', url:saved.url, body:saved.body, headers:nativeHeaders});
    return true;
  };
  function remember(url, body, originalHeaders, originalCredentials) {
    if (!isTarget(url)) return false;
    send({kind:'endpoint'});
    if (typeof body !== 'string') { send({kind:'body', detail:'not-text'}); return false; }
    if (!selected(body)) {
      var fields = [];
      try {
        fields = Object.keys(JSON.parse(body)).filter(function(k) {
          return /organization|category|exam|center|type/i.test(k);
        }).slice(0, 12);
      } catch (_) {}
      send({kind:'filter', fields:fields});
      return false;
    }
    var resolved = new URL(String(url), location.href).href;
    var wasReady = !!saved;
    var headers;
    try {
      headers = new Headers(originalHeaders || {});
      if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    } catch (_) { headers = {'Content-Type':'application/json'}; }
    saved = {
      url: resolved, body: body, headers: headers,
      credentials: originalCredentials || 'same-origin'
    };
    // In-memory handoff: Android performs automatic reads without waiting for
    // background WebView fetch promises or JavaScript timeout callbacks.
    window.__zlapExportNativeRequest();
    hadAuthenticatedRequest = true;
    if (!wasReady) send({kind: 'ready'});
    return true;
  }
  function process(url, status, raw, retryAfter) {
    if (!isTarget(url)) return;
    send({kind:'response', status:status});
    if (status === 429) {
      cooldownUntil = Date.now() + 30 * 60 * 1000;
      send({kind:'rate', retryAfter: String(retryAfter || raw || '').slice(0, 200)});
      return;
    }
    if (status === 200) cooldownUntil = 0;
    if (status === 200 && typeof raw === 'string' && /<html[\s>]/i.test(raw) && /logowanie|zaloguj|sign.in|login/i.test(raw)) {
      send({kind:'auth', status:401});
      return;
    }
    if (status === 401 || status === 403) {
      send({kind:'auth', status:status});
      return;
    }
    if (status !== 200) {
      send({kind:'http', status:status});
      return;
    }
    try {
      var data = typeof raw === 'string' ? JSON.parse(raw) : raw;
      if (!data || !Array.isArray(data.examCollectionForDay)) {
        send({kind:'format', fields: data && typeof data === 'object' ? Object.keys(data).slice(0,12) : []});
        return;
      }
      var slots = [];
      data.examCollectionForDay.forEach(function (day) {
        if (!day || !Array.isArray(day.examCollections)) return;
        day.examCollections.forEach(function (exam) {
          if (!exam || String(exam.examType) !== 'Practice' || String(exam.category) !== 'B' || Number(exam.organizationId) !== 21) return;
          var places = Number(exam.placePracticeAmount || 0);
          if (places < 1 || !exam.practiceId || !exam.practiceDateTime) return;
          var type = String(exam.examType || '');
          if (type && type !== 'Practical' && type !== 'Practice' &&
              type !== 'TheoreticalAndPractical') return;
          if (exam.organizationId != null && Number(exam.organizationId) !== 21) return;
          if (exam.category != null &&
              String(exam.category).toUpperCase() !== 'B' &&
              Number(exam.category) !== 5) return;
          slots.push({id: String(exam.practiceId).slice(0, 90),
            date: String(exam.practiceDateTime).slice(0, 32), places: places});
        });
      });
      send({kind:'terms', terms:slots.slice(0, 300)});
    } catch (_) {
      send({kind:'format'});
    }
  }
  if (typeof window.fetch === 'function') {
    var fetchOriginal = window.fetch;
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      observe(url);
      var body = init && init.body;
      var headers = (init && init.headers) || (input && input.headers) || {};
      var credentials = (init && init.credentials) || (input && input.credentials) || 'same-origin';
      var watchThis = typeof body === 'string' && remember(url, body, headers, credentials);
      var requestBody = null;
      if (!watchThis && isTarget(url) && input && typeof input.clone === 'function') {
        try { requestBody = input.clone().text(); } catch (_) {}
      }
      if (isTarget(url)) { lastRequest = Date.now(); requestInFlight = true; send({kind:'request', source:'portal'}); }
      var portalFetch;
      try {
        portalFetch = fetchOriginal.apply(this, arguments);
      } catch (err) {
        if (isTarget(url)) requestInFlight = false;
        throw err;
      }
      return portalFetch.then(function (res) {
        if (isTarget(url)) requestInFlight = false;
        if (hadAuthenticatedRequest && /\/jwt\/refresh/i.test(String(url))) {
          if (res.status === 401 || res.status === 403) {
            send({kind:'auth', status:res.status});
          } else if (res.status === 204 || (res.status === 200 &&
                     /json/i.test(res.headers.get('Content-Type') || ''))) {
            send({kind:'auth_alive'}); // Actual portal refresh response
          }
        }
        function inspect() {
          try {
            res.clone().text().then(function (txt) {
              process(url, res.status, txt, res.headers.get('Retry-After'));
            }).catch(function () {});
          } catch (_) {}
        }
        if (watchThis) inspect();
        else if (requestBody) {
          requestBody.then(function (txt) {
            if (remember(url, txt, headers, credentials)) inspect();
          }).catch(function () {});
        }
        return res;
      }, function (err) {
        // A rejected portal fetch must not leave the monitor permanently
        // believing an HTTP request is still in progress.
        if (isTarget(url)) {
          requestInFlight = false;
          send({kind:'network', reason:'portal'});
        }
        throw err;
      });
    };
  }
  var openOriginal = XMLHttpRequest.prototype.open;
  var sendOriginal = XMLHttpRequest.prototype.send;
  var setHeaderOriginal = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
    if (!this.__zlapHeaders) this.__zlapHeaders = {};
    this.__zlapHeaders[String(name)] = String(value);
    return setHeaderOriginal.apply(this, arguments);
  };
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__zlapURL = String(url || '');
    observe(url);
    this.__zlapMethod = String(method || '');
    this.__zlapHeaders = {};
    return openOriginal.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    if (isTarget(this.__zlapURL) && this.__zlapMethod.toUpperCase() === 'POST' &&
        remember(this.__zlapURL, body, this.__zlapHeaders, 'same-origin')) {
      this.addEventListener('loadend', function () {
        try {
          var result = this.responseType === 'json' ? this.response : this.responseText;
          process(this.__zlapURL, this.status, result, this.getResponseHeader('Retry-After'));
        } catch (_) {}
      });
    }
    if (isTarget(this.__zlapURL)) {
      lastRequest = Date.now(); requestInFlight = true;
      send({kind:'request', source:'portal'});
      this.addEventListener('loadend', function () { requestInFlight = false; }, {once:true});
    }
    try {
      return sendOriginal.apply(this, arguments);
    } catch (err) {
      if (isTarget(this.__zlapURL)) requestInFlight = false;
      throw err;
    }
  };
  // Extend the user's session via the portal's OWN visible control.
  // No undocumented authentication endpoint or identity-provider bypass.
  var lastExtensionClick = 0;
  var lastAuthSignal = 0;
  var lastSessionHeartbeat = 0;
  var hadAuthenticatedRequest = false;
  var lastSessionScan = 0;
  var lastSoftActivity = Date.now();
  function normalizeLabel(value) {
    return String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[łŁ]/g, 'l')
      .toLowerCase().replace(/\s+/g, ' ').trim();
  }
  function visible(el) {
    if (!el || !el.isConnected || el.disabled) return false;
    for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
      var st = window.getComputedStyle(n);
      if (st.display === 'none' || st.visibility === 'hidden' ||
          st.visibility === 'collapse') return false;
    }
    return el.getClientRects().length > 0;
  }
  function sessionButtons(root, results, depth) {
    if (!root || depth > 3 || results.length >= 1500) return;
    try {
      var nodes = root.querySelectorAll('button, [role="button"], input[type="button"], input[type="submit"], a');
      for (var i = 0; i < nodes.length && results.length < 1500; i++) {
        results.push(nodes[i]);
        if (nodes[i].shadowRoot) sessionButtons(nodes[i].shadowRoot, results, depth + 1);
      }
    } catch (_) {}
  }
  function extendWhenPrompted() {
    try {
      if (!document.body || Date.now() - lastExtensionClick < 60000) return false;
      var nodes = [];
      sessionButtons(document, nodes, 0);
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        var label = normalizeLabel(
          el.innerText || el.textContent || el.value || el.getAttribute('aria-label') ||
          el.getAttribute('title'));
        var exactExtension = /\b(przedluz|odnow|wydluz)\b.*\b(sesj\w*|logowan\w*)\b/.test(label) ||
          /\b(extend|renew)\b.*\bsession\b/.test(label) ||
          /pozostan.*zalogowan/.test(label);
        var dialog = el.closest('[role="dialog"], [aria-modal="true"], .modal, .dialog');
        var context = dialog || (el.parentElement && el.parentElement.parentElement);
        var warning = normalizeLabel(context && context.innerText);
        var shortButton = /^(przedluz|odnow|wydluz)$/.test(label) &&
          /\b(sesj\w*|wylogowan\w*|logowan\w*)\b/.test(warning);
        if (!exactExtension && !shortButton) continue;
        if (!visible(el)) continue;
        lastExtensionClick = Date.now();
        el.click();
        send({kind:'extended'}); // attempted click, not server confirmation
        return true;
      }
    } catch (_) {}
    return false;
  }
  // Some portals implement the 10-minute idle timer in JavaScript.
  // Dispatching ordinary *synthetic* activity events may reset such a timer,
  // without generating extra requests to the exam API. It does NOT refresh
  // the server session or bypass any reauthentication requirement.
  function maintainPageActivity() {
    try {
      if (!hadAuthenticatedRequest || !saved || !document.body) return;
      var now = Date.now();
      if (now - lastSoftActivity < 3 * 60 * 1000) return;
      lastSoftActivity = now;
      var target = document.body;
      target.dispatchEvent(new MouseEvent('mousemove', {bubbles:true}));
      target.dispatchEvent(new Event('scroll', {bubbles:true}));
      if (typeof PointerEvent === 'function') {
        target.dispatchEvent(new PointerEvent('pointermove', {bubbles:true}));
      }
    } catch (_) {}
  }
  function detectExpiredSession() {
    try {
      if (!document.body || Date.now() - lastAuthSignal < 60000) return;
      var path = normalizeLabel(location.pathname || '');
      var text = normalizeLabel((document.body.innerText || '').slice(0, 12000));
      // Exact public portal logout screen: "Wylogowano z powodu nieaktywności.
      // Sesja wygasła po braku aktywności."  Works after full page
      // navigation, when in-page "hadAuthenticatedRequest" has been reset.
      var idleLogout = /wylogowano z powodu nieaktywnosci/.test(text) ||
        /sesja wygasla po braku aktywnosci/.test(text);
      var genericExpired = /sesja (wygasla|zakonczona)|session expired|zaloguj sie ponownie|ponowne logowanie|zostales wylogowany/.test(text);
      var onLoginRoute = /login|signin|sign-in|logowanie|authorize|authentication/.test(path);
      if (idleLogout || (hadAuthenticatedRequest && (onLoginRoute || genericExpired))) {
        lastAuthSignal = Date.now();
        saved = null;
        send({kind:'auth', status:401, reason:idleLogout ? 'idle' : 'login'});
      }
    } catch (_) {}
  }
  window.__zlapExtendNow = function () {
    return extendWhenPrompted();
  };
  window.__zlapSessionTick = function () {
    detectExpiredSession();
    extendWhenPrompted();
    maintainPageActivity();
    return true;
  };
  // Mutation observer responds when the warning appears even if timers are throttled.
  function observeSessionDialog() {
    if (!document.documentElement || window.__zlapSessionObserver) return;
    window.__zlapSessionObserver = new MutationObserver(function () {
      var now = Date.now();
      if (now - lastSessionScan < 1000) return;
      lastSessionScan = now;
      window.__zlapSessionTick();
    });
    window.__zlapSessionObserver.observe(document.documentElement,
      {childList:true, subtree:true, characterData:true});
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', observeSessionDialog, {once:true});
  } else {
    observeSessionDialog();
  }
  window.setInterval(window.__zlapSessionTick, 3000);
  // Report why an automatic search could not start, instead of silently
  // leaving the planned time in the past. This never increases request volume.
  window.__zlapLastCheckReason = '';
  window.__zlapCheckNow = function (mode) {
    var reason = !saved ? 'missing_request' :
      running ? 'previous_check_running' :
      requestInFlight ? 'portal_request_running' :
      Date.now() < cooldownUntil ? 'cooldown_429' :
      (mode !== 'manual' && Date.now() - lastRequest < 15000) ? 'portal_recent_request' : '';
    window.__zlapLastCheckReason = reason;
    if (reason) return false;

    var controller = new AbortController();
    var timeout = 0;
    var operation;
    running = true;
    lastRequest = Date.now();
    try {
      operation = fetchOriginal.call(window, saved.url, {
        method: 'POST',
        body: saved.body,
        headers: saved.headers || {'Content-Type': 'application/json'},
        credentials: saved.credentials || 'same-origin',
        cache: 'no-store',
        signal: controller.signal
      });
    } catch (_) {
      running = false;
      window.__zlapLastCheckReason = 'request_start_failed';
      send({kind:'network', reason:'start'});
      return false;
    }
    send({kind:'request', source: mode === 'manual' ? 'manual' : 'automatic'});
    timeout = window.setTimeout(function () { controller.abort(); }, 45000);
    Promise.resolve(operation).then(function (res) {
      return res.text().then(function (txt) {
        process(saved.url, res.status, txt, res.headers.get('Retry-After'));
      });
    }).catch(function (err) {
      send({kind:'network', reason:err && err.name === 'AbortError' ? 'timeout' : 'connection'});
    }).then(function () {
      window.clearTimeout(timeout);
      running = false;
    });
    return true;
  };

  // Optional temporary hold, only after explicit Android user consent.
  // A POST that times out can still have created a real reservation.
  // Treat ambiguous outcomes as a reason to STOP, not to POST again.
  var zlapBookingBusy = false;
  var zlapBookingCompleted = false;
  var zlapBookingKeys = {};
  function zlapSafeId(value) {
    return typeof value === 'string' && /^[a-zA-Z0-9_-]{1,90}$/.test(value);
  }
  function zlapBookingHeaders(accept) {
    var headers = new Headers(saved && saved.headers ? saved.headers : {});
    headers.set('Accept', accept || 'application/json');
    if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    return headers;
  }
  function zlapBookingError(reason, date, reservationId, uncertain) {
    send({kind:'booking_failed', reason:String(reason || 'Brak potwierdzenia').slice(0,180),
      date:date, reservationId:reservationId || '', uncertain:!!uncertain});
  }
  // Short network timeouts limit how long an operation can be stuck while
  // Android throttles its WebView. A timeout never proves server rejection.
  function zlapFetchWithTimeout(url, opts, timeoutMs) {
    var controller = new AbortController();
    var timeout = setTimeout(function () { controller.abort(); }, timeoutMs);
    var request = Object.assign({}, opts, {signal:controller.signal});
    return Promise.resolve().then(function () {
      return fetchOriginal.call(window, url, request);
    }).finally(function () {
      clearTimeout(timeout);
    });
  }

  window.__zlapBook = function(practiceId, datetime) {
    // 'running' can still be true for a fraction of a second AFTER the
    // exam-result message. It is safe to start this distinct reservation
    // request; all other concurrency guards still apply.
    if (!saved || zlapBookingBusy || zlapBookingCompleted ||
        requestInFlight || Date.now() < cooldownUntil) return false;
    if (!zlapSafeId(practiceId) || typeof datetime !== 'string' ||
        !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(datetime)) return false;
    var key = practiceId + '|' + datetime;
    if (zlapBookingKeys[key]) return false;
    var profile;
    try {
      profile = String(JSON.parse(saved.body).profileNumber || '').trim();
    } catch (_) { return false; }
    if (!/^[0-9]{8,30}$/.test(profile)) {
      send({kind:'booking_failed', reason:'Brak poprawnego numeru PKK w sesji',
        date:datetime, uncertain:false});
      return false;
    }
    zlapBookingBusy = true;
    zlapBookingKeys[key] = true;
    var dateOnly = datetime.slice(0, 10);
    var reservationId = '';
    var creating = false;
    var ambiguous = false;
    send({kind:'booking_started', date:datetime});
    (async function () {
      var base = location.origin + '/bknd/exam/api/v1/Reservations/';
      try {
        creating = true;
        var createResponse = await zlapFetchWithTimeout(base + 'create', {
          method:'POST', credentials:saved.credentials || 'same-origin',
          headers:zlapBookingHeaders('application/json'),
          body:JSON.stringify({
            profileNumber:profile,
            organizationId:21,
            examDate:dateOnly,
            practiceExamId:practiceId,
            profileType:'Pkk',
            examType:'Practice',
            language:'Polish',
            category:'B'
          }), cache:'no-store'
        }, 15000);
        if (createResponse.status === 429) {
          cooldownUntil = Date.now() + 30 * 60 * 1000;
          send({kind:'rate', retryAfter:createResponse.headers.get('Retry-After') || ''});
          creating = false;
          zlapBookingError('HTTP 429 – limit portalu', datetime, '', false);
          return;
        }
        if (createResponse.status === 401 || createResponse.status === 403) {
          send({kind:'auth', status:createResponse.status});
          creating = false;
          zlapBookingError('Sesja wygasła – zaloguj się ponownie', datetime, '', false);
          return;
        }
        if (!createResponse.ok) {
          // HTTP 5xx/409 can mean the server accepted the operation but failed
          // to return its ID. Never automatically retry such a response.
          ambiguous = createResponse.status >= 500 || createResponse.status === 409;
          creating = false;
          zlapBookingError('Tworzenie rezerwacji: HTTP ' + createResponse.status,
            datetime, '', ambiguous);
          return;
        }
        var createBody = await createResponse.json();
        reservationId = String(createBody && createBody.id || '');
        if (!zlapSafeId(reservationId)) {
          ambiguous = true;
          zlapBookingError('Serwer zaakceptował tworzenie, ale nie zwrócił poprawnego ID',
            datetime, '', true);
          return;
        }
        send({kind:'booking_pending', date:datetime, reservationId:reservationId});
        // Confirm the existing ID: never send /create again to repair timeouts.
        try {
          var confirmResponse = await zlapFetchWithTimeout(
            base + 'confirm/' + encodeURIComponent(reservationId), {
              method:'GET', credentials:saved.credentials || 'same-origin',
              headers:zlapBookingHeaders('text/event-stream'),
              cache:'no-store'
            }, 9000);
          if (confirmResponse.body && confirmResponse.body.cancel) {
            try { await confirmResponse.body.cancel(); } catch (_) {}
          }
        } catch (_) {
          // A broken streaming confirmation is not proof of failure.
        }
        for (var attempt = 0; attempt < 4; attempt++) {
          if (attempt > 0) {
            await new Promise(function (resolve) { setTimeout(resolve, 3000); });
          }
          var stateResponse;
          try {
            stateResponse = await zlapFetchWithTimeout(
              base + encodeURIComponent(reservationId), {
                method:'GET', credentials:saved.credentials || 'same-origin',
                headers:zlapBookingHeaders('application/json'),
                cache:'no-store'
              }, 10000);
          } catch (_) { continue; }
          if (stateResponse.status === 429) {
            send({kind:'rate', retryAfter:stateResponse.headers.get('Retry-After') || ''});
            zlapBookingError('HTTP 429 podczas potwierdzania – sprawdź rezerwację',
              datetime, reservationId, true);
            return;
          }
          if (stateResponse.status === 401 || stateResponse.status === 403) {
            send({kind:'auth', status:stateResponse.status});
            zlapBookingError('Sesja wygasła podczas potwierdzania',
              datetime, reservationId, true);
            return;
          }
          if (!stateResponse.ok) continue;
          var data;
          try { data = await stateResponse.json(); } catch (_) { continue; }
          var status = String(data && data.status || '');
          if (status === 'PlaceReserved' || status === 'Accepted') {
            zlapBookingCompleted = true;
            send({kind:'booking_success', date:datetime,
              reservationId:reservationId, status:status});
            return;
          }
          if (/^(Failed|Rejected|Cancelled|Canceled|Expired)$/i.test(status)) {
            zlapBookingError('Portal wskazuje stan: ' + status,
              datetime, reservationId, true);
            return;
          }
        }
        zlapBookingError('Stan rezerwacji nie został potwierdzony. Sprawdź portal.',
          datetime, reservationId, true);
      } catch (err) {
        zlapBookingError('Błąd sieci lub przekroczony czas podczas rezerwacji',
          datetime, reservationId, creating || ambiguous || !!reservationId);
      } finally {
        // Only a definitive rejection *before* a reservation was created can
        // be safely retried at a later fresh scan.
        if (!reservationId && !creating && !ambiguous) delete zlapBookingKeys[key];
        zlapBookingBusy = false;
      }
    })();
    return true;
  };
})();