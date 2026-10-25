/* Deterministic, network-free regression tests for real-reservation safeguards.
 * The production script is evaluated against a fake authenticated portal.
 */
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = fs.readFileSync(path.join(__dirname,
  '../app/src/main/assets/slot-hook.js'), 'utf8');
const origin = 'https://info-kierowca.pl';
const date = '2026-11-05T10:30:00';
const bookingId = 'reservation_123';

function response(status, payload, headers = {}) {
  const raw = typeof payload === 'string' ? payload : JSON.stringify(payload);
  return {
    status, ok: status >= 200 && status < 300,
    headers: {get(k) {return headers[k] || null;}},
    text: async () => raw,
    json: async () => JSON.parse(raw),
    clone() {return response(status, raw, headers);},
    body: {cancel: async () => {}}
  };
}

function fixture(onCreate) {
  const events = [];
  const calls = [];
  const fetchImpl = async (url, options) => {
    const address = String(url);
    calls.push(address);
    if (/onecenterexam/i.test(address)) {
      return response(200, {examCollectionForDay:[]});
    }
    if (/\/Reservations\/create$/i.test(address)) {
      return onCreate();
    }
    if (/\/Reservations\/confirm\//i.test(address)) {
      return response(200, {status:'Processing'});
    }
    if (/\/Reservations\/reservation_123$/i.test(address)) {
      return response(200, {status:'PlaceReserved'});
    }
    throw new Error('Unexpected request: ' + address);
  };
  function FakeXHR() {}
  FakeXHR.prototype.open = function () {};
  FakeXHR.prototype.send = function () {};
  FakeXHR.prototype.setRequestHeader = function () {};
  FakeXHR.prototype.addEventListener = function () {};

  const window = {
    fetch: fetchImpl,
    ZlapBridge: {postMessage(data) {events.push(JSON.parse(data));}},
    setInterval() {},
    setTimeout,
    clearTimeout,
    getComputedStyle() {return {display:'block', visibility:'visible'};}
  };
  const document = {
    readyState: 'loading',
    addEventListener() {},
    body: null,
    documentElement: null
  };
  const context = {window, document,
    location:{href:origin+'/reservation',origin,pathname:'/reservation'},
    URL, Headers, XMLHttpRequest:FakeXHR, AbortController, setTimeout,
    clearTimeout, console, MutationObserver:class {}};
  vm.runInNewContext(script, context, {timeout:2000});

  async function loginByOpeningExamList() {
    await window.fetch(origin+'/bknd/exam/api/v1/OneCenterExam',{
      method:'POST', credentials:'same-origin',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({organizationId:21,category:'B',
        profileNumber:'1234567890'})
    });
    assert.ok(events.some(x=>x.kind === 'ready'), 'Authenticated exam hook must be ready');
  }
  async function until(kind, waitMs=1500) {
    const end = Date.now() + waitMs;
    while(Date.now() < end) {
      const match = events.find(x=>x.kind === kind);
      if(match) return match;
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    throw new Error('Missing event: ' + kind +' / '+JSON.stringify(events));
  }
  return {window, calls, events, until, loginByOpeningExamList};
}

test('A reservation is created once and confirmed while an exam scan finishes', async () => {
  const f=fixture(async()=>response(201,{id:bookingId}));
  await f.loginByOpeningExamList();
  assert.equal(f.window.__zlapCheckNow('manual'),true);
  // The scan's "running" flag is still true here.
  assert.equal(f.window.__zlapBook('practice_42',date),true);
  assert.equal(f.window.__zlapBook('practice_42',date),false);
  const result=await f.until('booking_success');
  assert.equal(result.reservationId,bookingId);
  assert.equal(f.calls.filter(x=>/\/Reservations\/create$/.test(x)).length,1);
  assert.equal(f.events.filter(x=>x.kind==='booking_pending').length,1);
});

test('Lost response to create is UNKNOWN, never an automatic duplicate attempt',async()=>{
  const f=fixture(async()=>{throw new Error('Network response lost after create');});
  await f.loginByOpeningExamList();
  assert.equal(f.window.__zlapBook('practice_42',date),true);
  const failed=await f.until('booking_failed');
  assert.equal(failed.uncertain,true);
  assert.equal(f.window.__zlapBook('practice_42',date),false);
  assert.equal(f.calls.filter(x=>/\/Reservations\/create$/.test(x)).length,1);
});

test('Definite HTTP 429 creates no hold and reports its server lock',async()=>{
  const f=fixture(async()=>response(429,'busy',{'Retry-After':'1800'}));
  await f.loginByOpeningExamList();
  assert.equal(f.window.__zlapBook('practice_42',date),true);
  const failed=await f.until('booking_failed');
  assert.equal(failed.uncertain,false);
  const rate=f.events.find(x=>x.kind==='rate');
  assert.ok(rate);
  assert.equal(rate.retryAfter,'1800');
  assert.equal(f.calls.filter(x=>/\/Reservations\/create$/.test(x)).length,1);
});

test('Captured exam request is handed to native reader before readiness', async () => {
  const f = fixture(async () => response(201, {id: bookingId}));
  await f.loginByOpeningExamList();
  const captured = f.events.find(x => x.kind === 'native_request_ready');
  assert.ok(captured);
  assert.equal(captured.url, origin + '/bknd/exam/api/v1/OneCenterExam');
  assert.equal(JSON.parse(captured.body).organizationId, 21);
  assert.equal(captured.headers['content-type'], 'application/json');
  assert.ok(f.events.indexOf(captured) < f.events.findIndex(x => x.kind === 'ready'));
});

test('Native query can be exported again without an HTTP read or quota request', async () => {
  const f = fixture(async () => response(201, {id: bookingId}));
  assert.equal(f.window.__zlapExportNativeRequest(), false);
  await f.loginByOpeningExamList();
  const callsBefore = f.calls.length;
  const requestsBefore = f.events.filter(x => x.kind === 'request').length;
  const exportsBefore = f.events.filter(x => x.kind === 'native_request_ready').length;
  assert.equal(f.window.__zlapExportNativeRequest(), true);
  assert.equal(f.calls.length, callsBefore);
  assert.equal(f.events.filter(x => x.kind === 'request').length, requestsBefore);
  assert.equal(f.events.filter(x => x.kind === 'native_request_ready').length, exportsBefore + 1);
});
