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

function selectFixture(role, values) {
  const events=[];
  const select={
    nodeType:1, isConnected:true, disabled:false, parentElement:null,
    id:'test-'+role, value:'', options:values.map((x,i)=>({
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

test('selects category B without choosing theory or other categories', () => {
  const f=selectFixture('Kategoria', ['Wybierz kategorię','A','B','C']);
  assert.equal(f.select.value,'3');
  assert.deepEqual(f.events,['input','change']);
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

test('after choosing PKK the first wizard step can advance with Dalej',()=>{
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
      if(q==='input[type="radio"], [role="radio"], label')return [radio];
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
  assert.deepEqual(clicked,['PKK','Dalej']);
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
