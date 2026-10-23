/* ZlapEgzamin: read-only monitoring of an authenticated info-kierowca.pl tab.
 * Only sends filtered dates and counts to Android. Credentials, PKK and
 * request bodies stay inside this page's JavaScript context. */
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
  function remember(url, body) {
    if (!isTarget(url)) return false;
    send({kind:'endpoint'});
    if (typeof body !== 'string') { send({kind:'body', detail:'not-text'}); return false; }
    if (!body.trim()) {
      var fields = [];
      try { fields = Object.keys(JSON.parse(body)).filter(function(k){ return /organization|category|exam|center|type/i.test(k); }).slice(0,12); } catch (_) {}
      send({kind:'filter', fields:fields});
      return false;
    }
    var resolved = new URL(String(url), location.href).href;
    var wasReady = !!saved;
    saved = {url: resolved, body: body};
    if (!wasReady) send({kind: 'ready'});
    return true;
  }
  function process(url, status, raw, retryAfter) {
    if (!isTarget(url)) return;
    send({kind:'response', status:status});
    if (status === 429) {
      cooldownUntil = Date.now() + 30 * 60 * 1000;
      send({kind:'rate', retryAfter: String(retryAfter || '').slice(0, 120)});
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
      var watchThis = typeof body === 'string' && remember(url, body);
      var requestBody = null;
      if (!watchThis && isTarget(url) && input && typeof input.clone === 'function') {
        try { requestBody = input.clone().text(); } catch (_) {}
      }
      if (isTarget(url)) { lastRequest = Date.now(); requestInFlight = true; }
      return fetchOriginal.apply(this, arguments).then(function (res) {
        if (isTarget(url)) requestInFlight = false;
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
            if (remember(url, txt)) inspect();
          }).catch(function () {});
        }
        return res;
      });
    };
  }
  var openOriginal = XMLHttpRequest.prototype.open;
  var sendOriginal = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__zlapURL = String(url || '');
    observe(url);
    this.__zlapMethod = String(method || '');
    return openOriginal.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    if (isTarget(this.__zlapURL) && this.__zlapMethod.toUpperCase() === 'POST' &&
        remember(this.__zlapURL, body)) {
      this.addEventListener('loadend', function () {
        try {
          var result = this.responseType === 'json' ? this.response : this.responseText;
          process(this.__zlapURL, this.status, result, this.getResponseHeader('Retry-After'));
        } catch (_) {}
      });
    }
    if (isTarget(this.__zlapURL)) {
      lastRequest = Date.now(); requestInFlight = true;
      this.addEventListener('loadend', function () { requestInFlight = false; }, {once:true});
    }
    return sendOriginal.apply(this, arguments);
  };
  window.__zlapCheckNow = function () {
    if (!saved || running || requestInFlight || Date.now() < cooldownUntil ||
        Date.now() - lastRequest < 15000) return false;
    running = true;
    lastRequest = Date.now();
    fetchOriginal.call(window, saved.url, {
      method: 'POST',
      body: saved.body,
      headers: {'Content-Type': 'application/json'},
      credentials: 'same-origin',
      cache: 'no-store'
    }).then(function (res) {
      return res.text().then(function (txt) {
        process(saved.url, res.status, txt, res.headers.get('Retry-After'));
      });
    }).catch(function () {
      send({kind:'network'});
    }).then(function () { running = false; });
    return true;
  };
})();