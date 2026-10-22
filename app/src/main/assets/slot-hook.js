/* ZlapEgzamin: read-only monitoring of an authenticated info-kierowca.pl tab.
 * Only sends filtered dates and counts to Android. Credentials, PKK and
 * request bodies stay inside this page's JavaScript context. */
(function () {
  'use strict';
  if (window.__zlapHookV3) return;
  window.__zlapHookV3 = true;
  var saved = null, running = false;
  var endpoint = /\/Schedules\/user\/OneCenterExam(?:[?#]|$)/i;
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
      return parsed.origin === location.origin && endpoint.test(parsed.pathname);
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
    if (!isTarget(url) || typeof body !== 'string' || !selected(body)) return false;
    var resolved = new URL(String(url), location.href).href;
    var wasReady = !!saved;
    saved = {url: resolved, body: body};
    if (!wasReady) send({kind: 'ready'});
    return true;
  }
  function process(url, status, raw, retryAfter) {
    if (!isTarget(url)) return;
    if (status === 429) {
      send({kind:'rate', retryAfter: String(retryAfter || '').slice(0, 120)});
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
        send({kind:'format'});
        return;
      }
      var slots = [];
      data.examCollectionForDay.forEach(function (day) {
        if (!day || !Array.isArray(day.examCollections)) return;
        day.examCollections.forEach(function (exam) {
          if (!exam) return;
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
      var body = init && init.body;
      var watchThis = typeof body === 'string' && remember(url, body);
      var requestBody = null;
      if (!watchThis && isTarget(url) && input && typeof input.clone === 'function') {
        try { requestBody = input.clone().text(); } catch (_) {}
      }
      return fetchOriginal.apply(this, arguments).then(function (res) {
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
    return sendOriginal.apply(this, arguments);
  };
  window.__zlapCheckNow = function () {
    if (!saved || running) return false;
    running = true;
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