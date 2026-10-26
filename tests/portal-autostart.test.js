'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(
  path.join(__dirname, '../app/src/main/assets/portal-autostart.js'), 'utf8');

function fixture(bodyText, labels, changes = {}) {
  const clicked = [];
  const events = [];
  const buttons = labels.map(value => ({
    isConnected:true, disabled:false, parentElement:null, nodeType:1,
    innerText:value, textContent:value, getAttribute(){return null;},
    getClientRects(){return [1];}, click(){clicked.push(value);}
  }));
  const document = {
    body:{innerText:bodyText},
    documentElement:null,
    addEventListener(){},
    querySelectorAll(selector) {
      if (selector.startsWith('button,')) return buttons;
      return [];
    },
    querySelector(){return null;}
  };
  const window = {
    setInterval(){},
    MutationObserver:null,
    ZlapBridge:{postMessage(raw){events.push(JSON.parse(raw));}}
  };
  const location = Object.assign({
    protocol:'https:', hostname:'info-kierowca.pl',
    pathname:'/reservation'
  }, changes);
  const setup = new Function('window','document','location',
    'getComputedStyle','CSS','Event','MutationObserver', source);
  setup(window,document,location,
    () => ({display:'block',visibility:'visible'}),
    {escape:value=>value},class {},class {});
  return {window,clicked,events};
}

test('off by default, booking-start UI is untouched',()=>{
  const f=fixture('Konto użytkownika. Usługi:', ['Zarezerwuj termin']);
  assert.deepEqual(f.clicked,[]);
});

test('after opt-in, opens one exact booking-start action',()=>{
  const f=fixture('Konto użytkownika. Usługi:', ['Zarezerwuj termin']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,['Zarezerwuj termin']);
  assert.ok(f.events.some(e=>e.kind==='form_prep'));
});

test('never proceeds on payment or confirmation summary',()=>{
  const f=fixture('Podsumowanie rezerwacji. Zapłać egzamin',
    ['Zarezerwuj termin','Potwierdź rezerwację']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
});

test('login is owned by user, not preparer',()=>{
  const f=fixture('Zaloguj się do portalu', ['Zarezerwuj termin'],
    {pathname:'/auth/login'});
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
});

test('never automates a different website',()=>{
  const f=fixture('Konto użytkownika', ['Zarezerwuj termin'],
    {hostname:'attacker.example'});
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
});

test('ambiguous action does not click either candidate',()=>{
  const f=fixture('Konto użytkownika', ['Zarezerwuj termin','Zarezerwuj termin']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
});

test('visible list of appointment times is not a setup screen',()=>{
  const f=fixture('Dostępne terminy egzaminów. 11:30.',
    ['Zarezerwuj termin','Wybierz','Potwierdź']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
});

test('disabling preparation makes the form untouched',()=>{
  const f=fixture('Konto użytkownika', ['Zarezerwuj termin']);
  f.window.__zlapFormPrep.configure({enabled:false});
  assert.deepEqual(f.clicked,[]);
});

test('after consent a blank start-date field is filled without booking a slot',()=>{
  const clicks=[];
  const field = {
    isConnected:true, disabled:false, nodeType:1, parentElement:null,
    id:'dateFrom', value:'', min:'2026-10-01', max:'', readOnly:false,
    getClientRects(){return [1];},
    getAttribute(key){return key==='aria-label'?'Data od':null;},
    closest(){return null;},
    dispatchEvent(e){clicks.push(e.type);}
  };
  const document = {
    body:{innerText:'Rodzaj egzaminu: praktyczny, Data od'},
    documentElement:null,
    addEventListener(){},
    querySelectorAll(selector) {
      return selector==='input[type="date"]'?[field]:[];
    },
    querySelector(){return null;}
  };
  const window = {setInterval(){},MutationObserver:null,
    ZlapBridge:{postMessage(){}}};
  const location={protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'};
  const setup=new Function('window','document','location','getComputedStyle',
    'CSS','Event','MutationObserver',source);
  setup(window,document,location,()=>({display:'block',visibility:'visible'}),
    {escape:x=>x},class {constructor(type){this.type=type;}},class {});
  window.__zlapFormPrep.configure({enabled:true});
  assert.match(field.value,/^\\d{4}-\\d{2}-\\d{2}$/);
  assert.deepEqual(clicks,['input','change']);
});

test('date is not overwritten if user has already selected one',()=>{
  const field = {
    isConnected:true, disabled:false, nodeType:1, parentElement:null,
    id:'dateFrom', value:'2026-11-02', readOnly:false,
    getClientRects(){return [1];},
    getAttribute(k){return k==='aria-label'?'Data od':null;},
    closest(){return null;},
    dispatchEvent(){throw Error('must not mutate existing date');}
  };
  const doc = {
    body:{innerText:'Data od, egzamin praktyczny'},documentElement:null,
    addEventListener(){},querySelectorAll(s){return s==='input[type="date"]'?[field]:[];},
    querySelector(){return null;}
  };
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},
    class {constructor(t){this.type=t;}},class {});
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.equal(field.value,'2026-11-02');
});
