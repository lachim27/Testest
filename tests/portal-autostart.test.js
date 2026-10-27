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
  const listeners = {};
  const buttons = labels.map(value => ({
    isConnected:true, disabled:false, parentElement:null, nodeType:1,
    innerText:value, textContent:value, getAttribute(){return null;},
    getClientRects(){return [1];}, click(){clicked.push(value);}
  }));
  const document = {
    body:{innerText:bodyText},
    documentElement:null,
    addEventListener(type,fn){
      (listeners[type]||(listeners[type]=[])).push(fn);
    },
    fire(type,event){
      (listeners[type]||[]).forEach(fn=>fn(event));
    },
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
  return {window,document,clicked,events};
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
  assert.match(field.value, /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/);
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


// A portal home screen may mention PKK in a sidebar, but the primary booking
// button still needs clicking. It is not itself proof that the form is open.
test('portal home opens the booking service even when navigation mentions PKK', () => {
  const f=fixture('Portal e-usług: numer profilu PKK, sprawy, informacje',
    ['Zarezerwuj termin egzaminu']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,['Zarezerwuj termin egzaminu']);
});

function selectFixture(role, values, current = '') {
  const events=[];
  const select={
    nodeType:1, isConnected:true, disabled:false, parentElement:null,
    id:'test-'+role, value:current, options:values.map((x,i)=>({
      text:x, value:String(i+1), disabled:false
    })),
    getAttribute(name){return name==='aria-label'?role:null;},
    getClientRects(){return [1];},
    closest(){return null;},
    dispatchEvent(e){events.push(e.type);}
  };
  const document={
    body:{innerText:'Wybierz profil PKK, kategorię B, ośrodek, rodzaj egzaminu'},
    documentElement:null, addEventListener(){},
    querySelectorAll(query){
      if (query==='select') return [select];
      if (query==='select, [role="combobox"], mat-select') return [select];
      return [];
    },
    querySelector(){return null;}
  };
  const window={
    setInterval(){}, MutationObserver:null,
    ZlapBridge:{postMessage(){}}
  };
  const setup=new Function('window','document','location',
    'getComputedStyle','CSS','Event','MutationObserver', source);
  setup(window,document,{
      protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'
    },()=>({display:'block',visibility:'visible'}),
    {escape:v=>v},class {constructor(t){this.type=t;}},class {});
  window.__zlapFormPrep.configure({enabled:true});
  return {select,events};
}

test('leaves category B chosen by portal untouched', () => {
  const f=selectFixture('Kategoria', ['Wybierz kategorię','A','B','C'], '3');
  f.select.selectedIndex=2;
  assert.equal(f.select.value,'3');
  assert.deepEqual(f.events,[]);
  const info=f.select && f.select.getAttribute('aria-label');
  assert.equal(info,'Kategoria');
});

test('selects only exactly named MORD Kraków headquarters', () => {
  const f=selectFixture('Ośrodek egzaminowania',
    ['MORD Kraków/O Kraków','MORD Kraków','MORD Kraków /O Oświęcim']);
  assert.equal(f.select.value,'2');
  assert.deepEqual(f.events,['input','change']);
});

test('does not guess if only different MORD branches are offered', () => {
  const f=selectFixture('Ośrodek egzaminowania',
    ['MORD Kraków/O Kraków','MORD Kraków /O Oświęcim']);
  assert.equal(f.select.value,'');
  assert.deepEqual(f.events,[]);
});


test('menu mention of payment does not block booking form launch',()=>{
  const f=fixture('Moje konto, menu: Opłać egzamin, Zarezerwuj termin',
    ['Zarezerwuj termin']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,['Zarezerwuj termin']);
});

test('manual retry resets a paused setup and returns anonymized controls',()=>{
  const f=fixture('Konto użytkownika. Dostępne usługi.',
    ['Zarezerwuj termin egzaminu']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,['Zarezerwuj termin egzaminu']);
  const diag=f.window.__zlapFormPrep.diagnose();
  assert.equal(diag.script,true);
  assert.equal(diag.enabled,true);
  assert.equal(diag.controls.start,1);
  assert.equal(diag.actions,1);
  const rerun=f.window.__zlapFormPrep.retry();
  assert.equal(rerun.script,true);
  assert.equal(rerun.actions,1);
  assert.deepEqual(f.clicked,[
    'Zarezerwuj termin egzaminu','Zarezerwuj termin egzaminu'
  ]);
});


test('opens the exact Info-Kierowca menu item Zarezerwuj termin egzaminu',()=>{
  const f=fixture('Usługi portalu po zalogowaniu',
    ['Zarezerwuj termin egzaminu']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,['Zarezerwuj termin egzaminu']);
  assert.equal(f.window.__zlapFormPrep.diagnose().controls.start,1);
});

test('does not press Dalej before selecting profile, category and center',()=>{
  let time=10000;
  const clicked=[];
  const radio={
    isConnected:true,disabled:false,parentElement:null,nodeType:1,
    value:'PKK',innerText:'PKK',getAttribute(){return null;},
    closest(){return null;},
    getClientRects(){return [1];},click(){clicked.push('PKK');}
  };
  const next={
    isConnected:true,disabled:false,parentElement:null,nodeType:1,
    innerText:'Dalej',getAttribute(){return null;},
    getClientRects(){return [1];},click(){clicked.push('Dalej');}
  };
  const document={
    body:{innerText:'Wybierz rodzaj profilu PKK, ośrodek MORD Kraków'},
    documentElement:null, addEventListener(){},
    querySelector(){return null;},
    querySelectorAll(q){
      if(q.startsWith('label, [role="radio"]'))return [radio];
      if(q==='input[type="radio"], input[type="date"]')return [radio];
      if(q.startsWith('button,'))return [next];
      return [];
    }
  };
  const window={setInterval(){},MutationObserver:null,
    ZlapBridge:{postMessage(){}}};
  class FakeDate extends Date{static now(){return time;}}
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver','Date',source)(
    window,document,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},
    class{},class{},FakeDate
  );
  window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(clicked,['PKK']);
  time+=2200;
  window.__zlapFormPrep.scan();
  assert.deepEqual(clicked,['PKK']); // incomplete form, stay on step one
});


test('opens exact booking-service title inside a nav-list li (not a button)',()=>{
  const clicks=[];
  const nav={
    nodeType:1, isConnected:true, disabled:false, parentElement:null,
    getClientRects(){return [1];},
    innerText:'Zarezerwuj termin egzaminu',
    textContent:'Zarezerwuj termin egzaminu',
    click(){clicks.push('menu');},
    getAttribute(){return null;},
    contains(other){return other===this;},
    closest(selector){
      if(selector.indexOf('li')>=0) return this;
      return null;
    }
  };
  const span=Object.assign({},nav,{
    parentElement:nav,
    click(){clicks.push('span');},
    contains(other){return other===this;},
    closest(selector){
      if(selector.indexOf('li')>=0) return nav;
      return null;
    }
  });
  const doc={
    body:{innerText:'Moje konto, wybierz usługę'},
    documentElement:null,
    addEventListener(){}, querySelector(){return null;},
    querySelectorAll(query){
      if(query.startsWith('nav span, nav div, nav li'))return [span];
      if(query.indexOf('select, [role="combobox"]')===0)return [];
      if(query.indexOf('input[type="radio"], input[type="date"]')===0)return [];
      return [];
    }
  };
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class {},class {}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(clicks,['menu']);
});

test('diagnostics classify unknown control by type without exposing its value',()=>{
  const input={
    nodeType:1,isConnected:true,disabled:false,parentElement:null,
    tagName:'INPUT',value:'SECRET-NUMBER-MUST-NOT-LEAK',
    getClientRects(){return [1];},
    getAttribute(k){return k==='type'?'radio':null;},
    closest(){return null;},id:''
  };
  const doc={
    body:{innerText:'Moje konto'},documentElement:null,
    addEventListener(){},querySelector(){return null;},
    querySelectorAll(q){
      if(q.indexOf('select, [role="combobox"]')===0)return [input];
      return [];
    }
  };
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class {},class {}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  const data=wnd.__zlapFormPrep.diagnose();
  assert.equal(data.fields.other,1);
  assert.equal(data.otherTags.radio,1);
  assert.ok(!JSON.stringify(data).includes('SECRET-NUMBER-MUST-NOT-LEAK'));
});

test('payment confirmation text keeps automatic opening disabled',()=>{
  const f=fixture('Podsumowanie rezerwacji, płatność', ['Zarezerwuj termin egzaminu']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
});


// Screens from the real portal: after login the user sees "Moje wnioski i
// rezerwacje"; the exam service is hidden behind a hamburger menu.
test('opens the hamburger on the logged-in requests homepage',()=>{
  const clicks=[];
  const menu={
    isConnected:true,disabled:false,nodeType:1,parentElement:null,
    innerText:'',textContent:'',
    getAttribute(k){return k==='aria-label'?'Menu':null;},
    getClientRects(){return [1];},
    getBoundingClientRect(){return {left:18,right:72,top:13,bottom:67,width:54,height:54};},
    closest(){return null;}, querySelector(){return null;},
    click(){clicks.push('hamburger');}
  };
  const doc={
    body:{innerText:'PORTAL E-USŁUG. Moje wnioski i rezerwacje'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(q){
      if(q.startsWith('button, [role="button"], [aria-label]'))return [menu];
      if(q.startsWith('button, a, [role="button"]'))return [menu];
      return [];
    }
  };
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(clicks,['hamburger']);
  wnd.__zlapFormPrep.scan();
  assert.deepEqual(clicks,['hamburger']); // not immediately toggled shut
  assert.equal(wnd.__zlapFormPrep.diagnose().menuOpened,true);
});

test('opens a plain service tile containing exact visible exam-menu text',()=>{
  const clicked=[];
  const leaf={
    isConnected:true,disabled:false,nodeType:1,parentElement:null,
    innerText:'Zarezerwuj termin egzaminu',
    textContent:'Zarezerwuj termin egzaminu',
    getAttribute(){return null;},getClientRects(){return [1];},
    contains(other){return this===other;},
    closest(){return null;},
    click(){clicked.push('service');}
  };
  const doc={
    body:{innerText:'USŁUGI: Lista moich spraw, Zarezerwuj termin egzaminu'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(q){
      if(q.startsWith('nav span, nav div, nav li'))return [leaf];
      return [];
    }
  };
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(clicked,['service']);
});

test('recognizes already selected PKK candidate profile radio on first step',()=>{
  const inner={checked:true};
  const option={
    nodeType:1,isConnected:true,disabled:false,parentElement:null,
    innerText:'PKK - Profil kandydata na kierowcę (PKK)',
    textContent:'PKK - Profil kandydata na kierowcę (PKK)',
    getAttribute(){return null;},getClientRects(){return [1];},
    contains(other){return other===this;},
    querySelector(){return inner;},
    click(){throw Error('already selected PKK must not be clicked');}
  };
  const doc={
    body:{innerText:'Wybór rodzaju profilu. PKK - Profil kandydata na kierowcę (PKK)'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(q){
      if(q==='input[type="radio"], [role="radio"], label')return [option];
      return [];
    }
  };
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.equal(wnd.__zlapFormPrep.diagnose().actions,0);
});

test('opens profile number custom combobox from floating field label',()=>{
  const clicked=[];
  const parent={innerText:'Numer profilu*\nWybierz z listy',parentElement:null};
  const control={
    nodeType:1,isConnected:true,disabled:false,parentElement:parent,
    innerText:'',textContent:'',id:'',
    getAttribute(k){return k==='aria-label'?'Wybierz z listy':null;},
    closest(q){return q.indexOf('.MuiFormControl-root')>=0?parent:null;},
    getClientRects(){return [1];},contains(other){return other===this;},
    click(){clicked.push('profile dropdown');}
  };
  const doc={
    body:{innerText:'Wybór rodzaju profilu. Numer profilu, Kategoria'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(q){
      if(q.startsWith('[role="combobox"], mat-select'))return [control];
      return [];
    }
  };
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(clicked,['profile dropdown']);
});


test('a genuine three-bar SVG under the home header opens the menu',()=>{
  const clicks=[];
  const lines=[9,14,19].map(y=>({getAttribute(k){return k==='y1'||k==='y2'?String(y):null;}}));
  const svg={
    tagName:'svg',isConnected:true,nodeType:1,disabled:false,
    parentElement:null,className:'',getAttribute(){return null;},
    getClientRects(){return [1];},
    getBoundingClientRect(){return {left:35,right:57,top:25,bottom:47,width:22,height:22};},
    querySelector(){return null;},
    querySelectorAll(q){return q==='line'?lines:[];},
    closest(){return null;},contains(){return false;},
    click(){clicks.push('svg menu');}
  };
  const doc={
    body:{innerText:'Witaj, Moje wnioski i rezerwacje, Status'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    elementFromPoint(){return svg;},
    querySelectorAll(q){
      if(q.includes('[class*="hamburger"]') && q.startsWith('button,'))return [svg];
      if(q==='select, [role="combobox"], mat-select')return [];
      return [];
    }
  };
  const wnd={innerWidth:393,setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(clicks,['svg menu']);
  const diag=wnd.__zlapFormPrep.diagnose();
  assert.equal(diag.onHomePage,true);
  assert.equal(diag.menuOpened,true);
  assert.equal(diag.hamburgerCount,1);
});

test('profile drop-down on homepage never looks like a hamburger button',()=>{
  const clicks=[];
  const status={
    isConnected:true,nodeType:1,disabled:false,className:'',
    innerText:'Status',textContent:'Status',tagName:'DIV',
    getAttribute(){return null;},getClientRects(){return [1];},
    getBoundingClientRect(){return {left:26,right:360,top:220,bottom:270,width:334,height:50};},
    querySelector(){return null;},closest(){return null;},
    click(){clicks.push('status');}
  };
  const doc={
    body:{innerText:'Moje wnioski i rezerwacje. Rezerwacje. Status'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    elementFromPoint(){return status;},
    querySelectorAll(q){
      if(q.includes('[class*="hamburger"]') && q.startsWith('button,'))return [status];
      return [];
    }
  };
  const wnd={innerWidth:393,setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(clicks,[]);
  assert.equal(wnd.__zlapFormPrep.diagnose().hamburgerCount,0);
});


test('CSS-only hamburger in verified top-left menu position can be opened',()=>{
  const clicked=[];
  const corner={
    tagName:'DIV',isConnected:true,disabled:false,nodeType:1,
    className:'left-control',parentElement:null,
    innerText:'',textContent:'',
    getAttribute(){return null;},getClientRects(){return [1];},
    getBoundingClientRect(){return {left:10,top:10,right:66,bottom:66,width:56,height:56};},
    closest(){return null;},querySelector(){return null;},
    click(){clicked.push('hamburger');}
  };
  const doc={
    body:{innerText:'Witaj. Moje wnioski i rezerwacje. Status'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(){return [];},
    elementFromPoint(){return corner;}
  };
  const wnd={innerWidth:393,setInterval(){},MutationObserver:null,
    ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible',cursor:'pointer'}),
    {escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(clicked,['hamburger']);
  assert.equal(wnd.__zlapFormPrep.diagnose().hamburgerCount,1);
});

test('CSS-only top-left probing is disabled outside verified homepage',()=>{
  const clicked=[];
  const corner={
    tagName:'DIV',isConnected:true,disabled:false,nodeType:1,
    className:'',parentElement:null,
    getClientRects(){return [1];},
    getBoundingClientRect(){return {left:10,top:10,right:66,bottom:66,width:56,height:56};},
    getAttribute(){return null;},closest(){return null;},querySelector(){return null;},
    click(){clicked.push('unsafe');}
  };
  const doc={
    body:{innerText:'Szczegóły wniosku. Zapraszamy.'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(){return [];},elementFromPoint(){return corner;}
  };
  const wnd={innerWidth:393,setInterval(){},MutationObserver:null,
    ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible',cursor:'pointer'}),
    {escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(clicked,[]);
});


// End-to-end reproduction of the user's screenshots of "Profil i WORD".
// No personal profile number is transmitted; only fake values in mocks.
test('follows PKK -> single profile -> B -> explicit WORD -> Krakow Nowohucka -> Nastepny',()=>{
  const clicked=[]; let tick=10000; let radioChecked=false; let wordChecked=false;
  let profile={tagName:'SELECT',nodeType:1,isConnected:true,disabled:false,
    parentElement:null,id:'profile',value:'',options:[
      {text:'Wybierz z listy',value:'',disabled:false},
      {text:'PKK 12345678901',value:'one',disabled:false}],
    getAttribute(k){return k==='aria-label'?'Numer profilu':null;},
    getClientRects(){return [1];},closest(){return null;},
    dispatchEvent(){}};
  let category={tagName:'SELECT',nodeType:1,isConnected:true,disabled:true,
    parentElement:null,id:'category',value:'B',selectedIndex:1,options:[
      {text:'Wybierz z listy',value:'',disabled:false},
      {text:'B',value:'B',disabled:false}],
    getAttribute(k){return k==='aria-label'?'Kategoria':null;},
    getClientRects(){return [1];},closest(){return null;},
    dispatchEvent(){throw Error('preselected category must not be changed');},};
  const originalProfileDispatch=profile.dispatchEvent;
  profile.dispatchEvent=function(e){if(e.type==='change')category.disabled=false;};
  const makeRadio=(title,kind,onClick,checked)=>({
    tagName:'LABEL',nodeType:1,isConnected:true,disabled:false,
    parentElement:null,innerText:title,textContent:title,
    getAttribute(){return null;},getClientRects(){return [1];},
    querySelector(q){return q.includes(':checked')&&checked()?{}:null;},
    contains(other){return other===this;},click:onClick,closest(){return null;}
  });
  const pkk=makeRadio('PKK - Profil kandydata na kierowcę (PKK)','pkk',()=>{
    clicked.push('PKK');radioChecked=true;},()=>radioChecked);
  const pkz=makeRadio('PKZ - Profil kierowcy zawodowego (PKZ)','pkz',()=>{
    throw Error('must never pick PKZ');},()=>false);
  const nearest=makeRadio('Pokaż tylko najbliższe terminy dla ośrodków WORD w okolicy',
    'nearest',()=>{throw Error('nearest is not the requested center');},()=>!wordChecked);
  const all=makeRadio('Wybierz ośrodek WORD i pokaż wszystkie terminy egzaminów',
    'all',()=>{clicked.push('WORD mode');wordChecked=true;},()=>wordChecked);
  const card={
    tagName:'DIV',nodeType:1,isConnected:true,disabled:false,parentElement:null,
    innerText:'Kraków - 18,5 km Nowohucka 33a, Kraków',
    textContent:'Kraków - 18,5 km Nowohucka 33a, Kraków',
    getAttribute(){return null;},getClientRects(){return [1];},
    contains(other){return other===this;},
    click(){clicked.push('Kraków Nowohucka');}
  };
  const balicka={
    ...card,innerText:'Kraków/O Kraków Balicka 127, Kraków',
    textContent:'Kraków/O Kraków Balicka 127, Kraków',
    click(){throw Error('wrong location');}
  };
  const next={
    tagName:'BUTTON',nodeType:1,isConnected:true,disabled:false,parentElement:null,
    innerText:'Następny →',textContent:'Następny →',
    getAttribute(){return null;},getClientRects(){return [1];},
    click(){clicked.push('Następny →');}
  };
  const body={innerText:'Wybór rodzaju profilu. PKK - Profil kandydata na kierowcę (PKK). '+
    'Wybór numeru profilu i kategorii. Numer profilu. Kategoria. '+
    'Wybór ośrodka egzaminacyjnego WORD. '+
    'Pokaż tylko najbliższe terminy dla ośrodków WORD w okolicy. '+
    'Wybierz ośrodek WORD i pokaż wszystkie terminy egzaminów. '+
    'Kraków - 18,5 km Nowohucka 33a, Kraków'};
  const doc={
    body,documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(q){
      if(q.startsWith('label, [role="radio"]'))return [pkk,pkz,nearest,all];
      if(q==='select')return [profile,category];
      if(q==='input[type="radio"], input[type="date"]')return [];
      if(q.startsWith('button, a, [role="button"]'))return [next];
      if(q.startsWith('label, li, [role="radio"]'))return [card,balicka];
      return [];
    }
  };
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  class FakeDate extends Date{static now(){return tick;}}
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver','Date',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},
    class {constructor(t){this.type=t;}},class{},FakeDate
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  for(let i=0;i<9;i++){tick+=1900;wnd.__zlapFormPrep.scan();}
  assert.deepEqual(clicked,['PKK','WORD mode','Kraków Nowohucka','Następny →']);
  assert.equal(profile.value,'one');
  assert.equal(category.value,'B');
});

test('ambiguous Krakow Nowohucka center cards are never chosen at random',()=>{
  // Center selection must uniquely identify Nowohucka 33a, not an alternate
  // branch or two elements with the same address.
  const f=fixture('Wybór ośrodka egzaminacyjnego WORD',
    ['Kraków/O Kraków Balicka']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
});

test('no automatic reservation or payment buttons are pressed',()=>{
  const f=fixture('Podsumowanie rezerwacji, wybór metody płatności',
    ['Następny','Potwierdź rezerwację','Zapłać']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
});


test('preselected category B is reported from native field without clicking',()=>{
  const events=[];
  const category={
    tagName:'SELECT', nodeType:1, isConnected:true, disabled:false,
    parentElement:null, id:'category',value:'B',selectedIndex:1,
    options:[{text:'Wybierz z listy',value:''},{text:'B',value:'B'}],
    getAttribute(k){return k==='aria-label'?'Kategoria':null;},
    getClientRects(){return [1];},closest(){return null;},
    dispatchEvent(){events.push('changed');}
  };
  const doc={body:{innerText:'Profil i WORD. Kategoria: B'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(q){
      if(q==='select')return [category];
      if(q.startsWith('select, input, [role="combobox"]'))return [category];
      return [];
    }};
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  const report=wnd.__zlapFormPrep.diagnose();
  assert.equal(report.setupSteps.categoryB,true);
  assert.equal(report.setupSteps.categoryReadable,true);
  assert.deepEqual(events,[]);
});

test('explicitly selected category A blocks wizard advancement',()=>{
  const clicked=[];
  const category={
    tagName:'SELECT',nodeType:1,isConnected:true,disabled:false,
    parentElement:null,id:'category',value:'A',selectedIndex:1,
    options:[{text:'Wybierz z listy',value:''},{text:'A',value:'A'}],
    getAttribute(k){return k==='aria-label'?'Kategoria':null;},
    getClientRects(){return [1];},closest(){return null;},
    dispatchEvent(){throw Error('should never overwrite an explicit different category');}
  };
  const next={
    nodeType:1,isConnected:true,disabled:false,parentElement:null,
    innerText:'Następny',textContent:'Następny',
    getAttribute(){return null;},getClientRects(){return [1];},
    click(){clicked.push('Następny');}
  };
  const doc={
    body:{innerText:'Wybór rodzaju profilu. Kategoria. Wybór ośrodka egzaminacyjnego WORD'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(q){
      if(q.startsWith('select, input, [role="combobox"]'))return [category];
      if(q.startsWith('button, a, [role="button"]'))return [next];
      return [];
    }
  };
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(clicked,[]);
  assert.equal(wnd.__zlapFormPrep.diagnose().setupSteps.categoryPreset,false);
});

test('category B preset on a custom combobox is read without a click',()=>{
  const combo={
    tagName:'DIV',nodeType:1,isConnected:true,disabled:false,
    parentElement:null,id:'c',innerText:'B',textContent:'B',
    getAttribute(k){return k==='aria-label'?'Kategoria':null;},
    getClientRects(){return [1];},closest(){return null;},
    click(){throw Error('category combobox must not be reopened');}
  };
  const doc={
    body:{innerText:'Profil i WORD. Kategoria B'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(q){
      if(q.startsWith('select, input, [role="combobox"]'))return [combo];
      return [];
    }
  };
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  assert.equal(wnd.__zlapFormPrep.diagnose().setupSteps.categoryB,true);
});


test('diagnostics recognize screenshot Następny → as wizard next',()=>{
  const f=fixture('Wybór rodzaju profilu. Wybór ośrodka egzaminacyjnego WORD',
    ['Następny →']);
  f.window.__zlapFormPrep.configure({enabled:true});
  const report=f.window.__zlapFormPrep.diagnose();
  assert.equal(report.controls.next,1);
  assert.equal(report.nextDiagnostics.firstStage,true);
  assert.equal(report.nextDiagnostics.rawCandidates,1);
  assert.equal(report.nextDiagnostics.enabledCandidates,1);
  assert.equal(report.nextDiagnostics.disabledCandidates,0);
  // Incomplete profile and center selections forbid navigating away.
  assert.deepEqual(f.clicked,[]);
});

test('do not click Następny → without previous field selections',()=>{
  const f=fixture('Wybór rodzaju profilu. Wybór ośrodka egzaminacyjnego WORD',
    ['Następny →']);
  // Fixture has no current selections and must never click automatically.
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
});

test('do not confuse Następny reservation confirmation with wizard navigation',()=>{
  const f=fixture('Podsumowanie rezerwacji. Potwierdzenie rezerwacji',
    ['Następny →','Potwierdź rezerwację']);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
});


test('diagnostics expose disabled Następny without clicking it',()=>{
  const next={
    nodeType:1,tagName:'BUTTON',isConnected:true,disabled:true,
    parentElement:null,innerText:'Następny →',textContent:'Następny →',
    getAttribute(k){return k==='aria-disabled'?'true':null;},
    getClientRects(){return [1];},click(){throw Error('disabled button clicked');}
  };
  const doc={
    body:{innerText:'Wybór rodzaju profilu. Wybór ośrodka egzaminacyjnego WORD'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(q){
      if(q==='button, a, [role="button"], input[type="submit"], input[type="button"]')
        return [next];
      if(q.startsWith('button, a, [role="button"]'))return [next];
      return [];
    }
  };
  const wnd={setInterval(){},MutationObserver:null,ZlapBridge:{postMessage(){}}};
  new Function('window','document','location','getComputedStyle','CSS','Event','MutationObserver',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class{},class{}
  );
  wnd.__zlapFormPrep.configure({enabled:true});
  const diag=wnd.__zlapFormPrep.diagnose();
  assert.equal(diag.nextDiagnostics.rawCandidates,1);
  assert.equal(diag.nextDiagnostics.enabledCandidates,0);
  assert.equal(diag.nextDiagnostics.disabledCandidates,1);
});


// 0.30: matching the screenshots: 17/12/2026 06:00, 4 free places,
// followed by the "Potwierdź wybrany egzamin" dialog.
function reservationScreen({wrongDay=false,wrongHour=false,wrongCategory=false,
  noPlaces=false,wrongModal=false,extraCard=false,collapsed=false}={}) {
  const events=[],clicks=[];
  let modalVisible=false;
  function item(tag,txt) {
    return {tagName:tag,nodeType:1,isConnected:true,disabled:false,
      parentElement:null,innerText:txt,textContent:txt,
      getAttribute(){return null;},
      getClientRects(){return [1];},
      contains(other){return other===this;}};
  }
  const previous=item('DIV','16/12/2026');
  const otherCard=item('DIV','Egzamin praktyczny KAT B P: 06:00 4 wolne miejsca 229,00 zł');
  const targetDay=item('DIV',wrongDay?'18/12/2026':'17/12/2026');
  const actualHour=wrongHour?'07:00':'06:00';
  const cat=wrongCategory?'C':'B';
  const places=noPlaces?'0 wolne miejsca':'4 wolne miejsca';
  const card=item('DIV','Egzamin praktyczny KAT '+cat+' P: '+actualHour+' '+places+' 229,00 zł');
  const radio={...item('INPUT',''),getAttribute(k){return k==='type'?'radio':null;},
    click(){clicks.push('card radio');modalVisible=true;}};
  card.querySelectorAll=function(q){
    return q==='input[type="radio"], [role="radio"]'?[radio]:[];
  };
  otherCard.querySelectorAll=function(){return [];};
  const modal=item('DIV',wrongModal ?
    'Potwierdź wybrany egzamin. Egzamin praktyczny Kategoria B Data i godzina 18/12/2026, 06:00 Cena 229,00 zł':
    'Potwierdź wybrany egzamin. Rodzaj egzaminu Egzamin praktyczny. Kategoria B. Data i godzina 17/12/2026, 06:00 Cena 229,00 zł.');
  const confirm=item('BUTTON','Potwierdź i przejdź dalej');
  confirm.click=function(){clicks.push('modal confirm');};
  modal.querySelectorAll=function(q){return q==='button, [role="button"]'?[confirm]:[];};
  let expanded=!collapsed;
  targetDay.click=function(){clicks.push('date section');expanded=true;};
  const all=[previous,otherCard,targetDay];
  if(expanded) all.push(card);
  if(extraCard) {
    const duplicate=item('DIV',card.innerText);
    duplicate.querySelectorAll=function(){return [radio];};
    all.push(duplicate);
  }
  const doc={body:{innerText:'Dostępne terminy egzaminów - 17/12/2026'},
    documentElement:null,addEventListener(){},querySelector(){return null;},
    querySelectorAll(q){
      if(q==='*') return expanded && !all.includes(card) ? all.concat([card]) : all;
      if(q==='[role="dialog"], [aria-modal="true"], .MuiDialog-paper, .mat-dialog-container, .modal-dialog')
        return modalVisible ? [modal] : [];
      return [];
    }
  };
  const wnd={setInterval(){return 1;},clearInterval(){},MutationObserver:null,
    ZlapBridge:{postMessage(m){events.push(JSON.parse(m));}}};
  const setup=new Function('window','document','location','getComputedStyle',
    'CSS','Event','MutationObserver',source);
  setup(wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class {},class {});
  wnd.__zlapFormPrep.configure({enabled:true});
  return {wnd,events,clicks};
}

test('booking UI clicks only 17 December 06:00 and matching confirmation',()=>{
  const f=reservationScreen();
  assert.equal(f.wnd.__zlapFormPrep.bookUi('practice_01','2026-12-17T06:00:00',
    '2026-12-01','2026-12-30','06:00','12:00'),true);
  assert.deepEqual(f.clicks,['card radio','modal confirm']);
  assert.ok(f.events.some(e=>e.kind==='booking_ui_selected'));
  assert.ok(f.events.some(e=>e.kind==='booking_ui_advanced'));
  assert.equal(f.wnd.__zlapFormPrep.bookUi('practice_01','2026-12-17T06:00:00',
    '2026-12-01','2026-12-30','06:00','12:00'),false);
});

test('booking UI refuses a term outside configured dates or hours',()=>{
  const f=reservationScreen();
  assert.equal(f.wnd.__zlapFormPrep.bookUi('practice_01','2026-12-17T06:00:00',
    '2026-12-18','2026-12-30','06:00','12:00'),false);
  assert.equal(f.wnd.__zlapFormPrep.bookUi('practice_01','2026-12-17T06:00:00',
    '2026-12-01','2026-12-30','08:00','12:00'),false);
  assert.deepEqual(f.clicks,[]);
});

test('booking UI never chooses a same-time card on a different day',()=>{
  const f=reservationScreen({wrongDay:true});
  assert.equal(f.wnd.__zlapFormPrep.bookUi('practice_01','2026-12-17T06:00:00',
    '2026-12-01','2026-12-30','06:00','12:00'),false);
  assert.deepEqual(f.clicks,[]);
});

test('booking UI refuses incorrect time, category or zero availability',()=>{
  for(const config of [{wrongHour:true},{wrongCategory:true},{noPlaces:true}]) {
    const f=reservationScreen(config);
    assert.equal(f.wnd.__zlapFormPrep.bookUi('practice_01','2026-12-17T06:00:00',
      '2026-12-01','2026-12-30','06:00','12:00'),false);
    assert.deepEqual(f.clicks,[]);
  }
});

test('booking UI does not confirm mismatched date in portal dialog',()=>{
  const f=reservationScreen({wrongModal:true});
  assert.equal(f.wnd.__zlapFormPrep.bookUi('practice_01','2026-12-17T06:00:00',
    '2026-12-01','2026-12-30','06:00','12:00'),true);
  assert.deepEqual(f.clicks,['card radio']);
  assert.ok(f.events.some(e=>e.kind==='booking_ui_problem'));
  assert.ok(!f.events.some(e=>e.kind==='booking_ui_advanced'));
});

test('booking UI refuses ambiguous matching exam cards',()=>{
  const f=reservationScreen({extraCard:true});
  assert.equal(f.wnd.__zlapFormPrep.bookUi('practice_01','2026-12-17T06:00:00',
    '2026-12-01','2026-12-30','06:00','12:00'),false);
  assert.deepEqual(f.clicks,[]);
});

test('booking UI translates ISO Z offset into local Warsaw time',()=>{
  const f=reservationScreen();
  assert.equal(f.wnd.__zlapFormPrep.bookUi('practice_01','2026-12-17T05:00:00Z',
    '2026-12-01','2026-12-30','06:00','12:00'),true);
  assert.deepEqual(f.clicks,['card radio','modal confirm']);
});


test('booking UI expands exact December 17 heading before selecting 06:00',()=>{
  const f=reservationScreen({collapsed:true});
  assert.equal(f.wnd.__zlapFormPrep.bookUi('practice_01','2026-12-17T06:00:00',
    '2026-12-01','2026-12-30','06:00','12:00'),true);
  assert.deepEqual(f.clicks,['date section','card radio','modal confirm']);
  assert.ok(f.events.some(x=>x.kind==='booking_ui_expanding'));
  assert.ok(f.events.some(x=>x.kind==='booking_ui_advanced'));
});


// 0.31: the user's actual second-step screenshot displays FOUR radio choices,
// not a select or combobox. Default is "Wyświetl wszystkie typy egzaminu".
function practicalScreen(opts={}) {
  const clicked=[],events=[];
  let selected=opts.practicalSelected?'practical':'all';
  let clock=10000;
  const bodyText=opts.bodyText||(
    'Wybór terminu egzaminu dla: MORD Kraków, ul. Nowohucka 33a. '+
    'Wybór rodzaju egzaminu. Egzamin teoretyczny. Egzamin praktyczny. '+
    'Egzamin łączony (Teoria + Praktyka). Wyświetl wszystkie typy egzaminu. '+
    'Wybór daty początkowej dla dostępnych egzaminów'
  );
  const option=(txt,id)=>{
    const item={
      nodeType:1,tagName:'LABEL',disabled:false,isConnected:true,
      parentElement:null,innerText:txt,textContent:txt,
      getAttribute(){return null;},
      getClientRects(){return [1];},
      closest(){return null;},
      contains(other){return other===item;},
      querySelector(q) {
        if(q.includes(':checked')) return selected===id?item.radio:null;
        if(q.includes('label')) return item;
        if(q.includes('input[type="radio"]')) return item.radio;
        return null;
      },
      click(){item.radio.click();}
    };
    const radio={
      tagName:'INPUT',type:'radio',nodeType:1,disabled:false,isConnected:true,
      parentElement:item,
      get checked(){return selected===id;},
      getAttribute(k){
        if(k==='aria-label')return txt;
        if(k==='aria-checked')return selected===id?'true':'false';
        return null;
      },
      getClientRects(){return [1];},
      closest(){return item;},
      click(){
        clicked.push(id);
        selected=opts.misroute && id==='practical'?'combined':id;
      }
    };
    item.radio=radio;
    return item;
  };
  const items=[
    option('Egzamin teoretyczny','theory'),
    option('Egzamin praktyczny','practical'),
    option('Egzamin łączony (Teoria + Praktyka)','combined'),
    option('Wyświetl wszystkie typy egzaminu','all')
  ];
  if(opts.duplicated)items.push(option('Egzamin praktyczny','practical-duplicate'));
  const doc={
    body:{innerText:bodyText},documentElement:null,addEventListener(){},
    querySelector(){return null;},
    querySelectorAll(q) {
      if(q==='input[type="radio"], [role="radio"]')return items.map(i=>i.radio);
      if(q.startsWith('label, [role="radio"]'))return items;
      if(q==='input[type="radio"], input[type="date"]')return items.map(i=>i.radio);
      return [];
    }
  };
  const wnd={setInterval(){},MutationObserver:null,
    ZlapBridge:{postMessage(raw){events.push(JSON.parse(raw));}}};
  class FakeDate extends Date{static now(){return clock;}}
  new Function('window','document','location','getComputedStyle','CSS',
    'Event','MutationObserver','Date',source)(
    wnd,doc,{protocol:'https:',hostname:'info-kierowca.pl',pathname:'/reservation'},
    ()=>({display:'block',visibility:'visible'}),{escape:x=>x},class{},class{},FakeDate
  );
  return {wnd,clicked,events,get selected(){return selected;},
    tick(){clock+=2000;}};
}
test('second wizard step selects only practical B exam radio instead of all types',()=>{
  const f=practicalScreen();
  f.wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,['practical']);
  assert.equal(f.selected,'practical');
  assert.equal(f.wnd.__zlapFormPrep.diagnose().setupSteps.practicalRadio,true);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().setupSteps.practicalRadioSelected,'praktyczny');
  assert.equal(f.wnd.__zlapFormPrep.diagnose().setupSteps.practicalRadioScreen,true);
  f.wnd.__zlapFormPrep.scan();
  assert.deepEqual(f.clicked,['practical']);
});

test('already checked practical radio is not toggled',()=>{
  const f=practicalScreen({practicalSelected:true});
  f.wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().setupSteps.practicalRadio,true);
});

test('ambiguous or duplicated practical radio cannot cause a blind selection',()=>{
  const f=practicalScreen({duplicated:true});
  f.wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
});

test('practical radio is not clicked on results, booking summary or unrelated page',()=>{
  for(const bodyText of [
    'Dostępne terminy egzaminów: Egzamin praktyczny KAT B',
    'Podsumowanie rezerwacji. Potwierdź wybrany egzamin. Egzamin praktyczny',
    'Wybór rodzaju egzaminu. Egzamin praktyczny. Wyświetl wszystkie typy egzaminu'
  ]){
    const f=practicalScreen({bodyText});
    f.wnd.__zlapFormPrep.configure({enabled:true});
    assert.deepEqual(f.clicked,[]);
  }
});


test('if portal selects combined despite practical click, automation stops before search',()=>{
  const f=practicalScreen({misroute:true});
  f.wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,['practical']);
  assert.equal(f.selected,'combined');
  assert.equal(f.wnd.__zlapFormPrep.diagnose().setupSteps.practicalRadio,false);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().setupSteps.practicalRadioSelected,'łączony');
  assert.equal(f.wnd.__zlapFormPrep.diagnose().setupSteps.practicalRadioAttempted,true);
  f.tick();
  f.wnd.__zlapFormPrep.scan();
  assert.equal(f.wnd.__zlapFormPrep.diagnose().stopped,true);
  assert.match(f.wnd.__zlapFormPrep.diagnose().last,/nie jest zaznaczony egzamin praktyczny/);
  assert.deepEqual(f.clicked,['practical']);
});

test('when four radio options are visible but practical has no readable label, never click others',()=>{
  const f=practicalScreen({bodyText:'Wybór terminu egzaminu dla: MORD Kraków. '+
    'Wybór rodzaju egzaminu. Wyświetl wszystkie typy egzaminu.'});
  f.wnd.__zlapFormPrep.configure({enabled:true});
  // With actual radio labels present in the DOM, the exact input still succeeds
  // even if the entire page's text has not settled yet.
  assert.deepEqual(f.clicked,['practical']);
});

test('no search when four radio inputs exist but none is safely label-associated',()=>{
  const f=practicalScreen({duplicated:true});
  f.wnd.__zlapFormPrep.configure({enabled:true});
  f.tick();
  f.wnd.__zlapFormPrep.scan();
  assert.equal(f.wnd.__zlapFormPrep.diagnose().stopped,false);
  assert.deepEqual(f.clicked,[]);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().setupSteps.practicalRadio,false);
});


test('prefetched term API must not stop practical exam radio screen before selection',()=>{
  const bodyText = 'Wybór terminu egzaminu dla: MORD Kraków, ul. Nowohucka 33a. '+
    'Wybór rodzaju egzaminu. Egzamin teoretyczny. Egzamin praktyczny. '+
    'Egzamin łączony (Teoria + Praktyka). Wyświetl wszystkie typy egzaminu. '+
    'Dostępne terminy egzaminów 28/10/2026 08:00';
  const f=practicalScreen({bodyText});
  // The native hook may see a OneCenterExam response even before user-facing
  // "Egzamin praktyczny" is selected: do not allow its old premature stop.
  assert.equal(f.wnd.__zlapFormPrep.stop(),false);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().stopped,false);
  f.wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,['practical']);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().setupSteps.practicalRadioVerified,true);
});

test('manual stop is rejected before real result list is visible',()=>{
  const f=practicalScreen();
  assert.equal(f.wnd.__zlapFormPrep.stop(),false);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().stopped,false);
  assert.deepEqual(f.clicked,[]);
});

test('prefetched slots do not override combined-exam safety guard',()=>{
  const bodyText='Wybór terminu egzaminu dla: MORD Kraków ul. Nowohucka 33a. '+
    'Wybór rodzaju egzaminu. Egzamin teoretyczny. Egzamin praktyczny. '+
    'Egzamin łączony (Teoria + Praktyka). Wyświetl wszystkie typy egzaminu. '+
    'Dostępne terminy egzaminów 28/10/2026 08:00';
  const f=practicalScreen({bodyText,misroute:true});
  f.wnd.__zlapFormPrep.configure({enabled:true});
  assert.equal(f.selected,'combined');
  assert.equal(f.wnd.__zlapFormPrep.diagnose().stopped,false);
  assert.equal(f.wnd.__zlapFormPrep.stop(),false);
  f.tick();
  f.wnd.__zlapFormPrep.scan();
  assert.equal(f.wnd.__zlapFormPrep.diagnose().stopped,true);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().setupSteps.practicalRadioVerified,false);
});


test('trusted phone swipe pauses menu auto-navigation without disabling option',()=>{
  const f=fixture('Moje wnioski i rezerwacje. USŁUGI',
    ['Zarezerwuj termin egzaminu']);
  f.document.fire('touchmove',{isTrusted:true});
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
  const diag=f.window.__zlapFormPrep.diagnose();
  assert.equal(diag.enabled,true);
  assert.equal(diag.manualBrowsingPaused,true);
  f.window.__zlapFormPrep.scan();
  assert.deepEqual(f.clicked,[]);
});

test('mouse-wheel scrolling also yields manual menu control',()=>{
  const f=fixture('Moje wnioski i rezerwacje. USŁUGI',
    ['Zarezerwuj termin egzaminu']);
  f.document.fire('wheel',{isTrusted:true,deltaY:105});
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
  assert.equal(f.window.__zlapFormPrep.diagnose().manualBrowsingPaused,true);
});

test('synthetic scroll is not treated as manual user browsing',()=>{
  const f=fixture('Konto użytkownika. Usługi:',
    ['Zarezerwuj termin']);
  f.document.fire('touchmove',{isTrusted:false});
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,['Zarezerwuj termin']);
  assert.equal(f.window.__zlapFormPrep.diagnose().manualBrowsingPaused,false);
});

test('entering the wizard must not cancel a user scroll pause on same document',()=>{
  const f=fixture('Moje wnioski i rezerwacje. USŁUGI',[]);
  f.document.fire('touchmove',{isTrusted:true});
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.equal(f.window.__zlapFormPrep.diagnose().manualBrowsingPaused,true);
  f.document.body.innerText='Wybór rodzaju profilu. Wybór osrodka egzaminacyjnego WORD';
  f.window.__zlapFormPrep.scan();
  assert.equal(f.window.__zlapFormPrep.diagnose().manualBrowsingPaused,true);
  assert.equal(f.window.__zlapFormPrep.diagnose().enabled,true);
});

test('explicit Retry releases manual scrolling pause and restarts autofill',()=>{
  const f=fixture('Moje wnioski i rezerwacje. USŁUGI',[]);
  f.document.fire('touchmove',{isTrusted:true});
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.equal(f.window.__zlapFormPrep.diagnose().manualBrowsingPaused,true);
  f.window.__zlapFormPrep.retry();
  assert.equal(f.window.__zlapFormPrep.diagnose().manualBrowsingPaused,false);
});

test('touch scrolling inside booking wizard pauses automatic clicks',()=>{
  const f=fixture('Wybór rodzaju profilu. Wybór osrodka egzaminacyjnego WORD',[]);
  f.document.fire('touchmove',{isTrusted:true});
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.equal(f.window.__zlapFormPrep.diagnose().manualBrowsingPaused,true);
});


test('native Android bridge can pause even if WebView does not deliver trusted touchmove',()=>{
  const f=fixture('Wybór rodzaju profilu. Wybór osrodka egzaminacyjnego WORD',[]);
  const value=f.window.__zlapFormPrep.pauseForManualScroll();
  assert.equal(value,true);
  f.window.__zlapFormPrep.configure({enabled:true});
  assert.equal(f.window.__zlapFormPrep.diagnose().enabled,true);
  assert.equal(f.window.__zlapFormPrep.diagnose().manualBrowsingPaused,true);
});

test('page configure call cannot unpause manual scrolling while still on exam screen',()=>{
  const f=practicalScreen();
  f.wnd.__zlapFormPrep.pauseForManualScroll();
  f.wnd.__zlapFormPrep.configure({enabled:true});
  f.wnd.__zlapFormPrep.scan();
  assert.deepEqual(f.clicked,[]);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().manualBrowsingPaused,true);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().setupSteps.practicalRadioVerified,false);
});

test('manual gesture after practical selection prevents further automatic clicks',()=>{
  const f=practicalScreen();
  f.wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,['practical']);
  f.wnd.__zlapFormPrep.pauseForManualScroll();
  f.tick();
  f.wnd.__zlapFormPrep.scan();
  f.wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,['practical']);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().manualBrowsingPaused,true);
});

test('explicit retry resumes form processing following native pause',()=>{
  const f=practicalScreen();
  f.wnd.__zlapFormPrep.pauseForManualScroll();
  f.wnd.__zlapFormPrep.configure({enabled:true});
  assert.deepEqual(f.clicked,[]);
  f.wnd.__zlapFormPrep.retry();
  assert.deepEqual(f.clicked,['practical']);
  assert.equal(f.wnd.__zlapFormPrep.diagnose().manualBrowsingPaused,false);
});
