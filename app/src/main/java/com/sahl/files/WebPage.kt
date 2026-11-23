package com.sahl.files

/**
 * The page a PC, TV or any phone opens at http://<phone>:53317.
 * After the 6-digit code: a file manager for the phone (browse, view, download, zip a folder,
 * upload, new folder, rename, delete to the Recycle bin) and the files picked in "To send".
 */
object WebPage {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun html(device: String) = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sahl Files · ${esc(device)}</title>
<style>
:root{--g:#0E6B4E;--gold:#C9A227;--bg:#F6F4EE;--card:#fff;--t:#1B2420;--s:#6B746F;--line:#E8E4DA;--sel:#E3F0EA}
@media(prefers-color-scheme:dark){:root{--bg:#0B1512;--card:#15241F;--t:#EDEBE3;--s:#9BA89F;--line:#22332D;--sel:#1F3B31}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font:15px system-ui,-apple-system,"Segoe UI",Tahoma,sans-serif}
header{position:sticky;top:0;z-index:5;background:var(--bg);padding:14px 18px 8px;border-bottom:1px solid var(--line)}
h1{font-size:20px;margin:0}h1 b{color:var(--gold)}.s{color:var(--s);font-size:13px}
main{max-width:1100px;margin:0 auto;padding:10px 16px 60px}
.card{background:var(--card);border-radius:16px;padding:16px;margin:12px 0}
#code{font-size:26px;letter-spacing:8px;text-align:center;width:100%;padding:10px;border-radius:12px;border:1px solid var(--s);background:transparent;color:var(--t)}
button,.btn{display:inline-block;background:var(--g);color:#fff;border:0;border-radius:20px;padding:8px 16px;font-size:14px;cursor:pointer;margin:4px 4px 4px 0;text-decoration:none}
.btn.o{background:transparent;color:var(--g);border:1px solid var(--g)}
.bar-ctl{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-top:8px}
.ctl{background:var(--bg);color:var(--t);border:1px solid var(--line);border-radius:20px;padding:7px 12px;font-size:13px;outline:0}
.ctl:focus{border-color:var(--g)}
input.ctl{flex:1;min-width:160px}
select.ctl{cursor:pointer}
a{color:inherit;text-decoration:none}
.tabs button{background:transparent;color:var(--s);border-radius:0;border-bottom:2px solid transparent;padding:8px 12px}
.tabs button.on{color:var(--g);border-color:var(--g);font-weight:600}
.crumb a{color:var(--g)}.crumb span{color:var(--s);margin:0 6px}
table{width:100%;border-collapse:collapse}td{padding:8px 6px;border-bottom:1px solid var(--line);vertical-align:middle}
tr:hover{background:var(--sel)}td.n{word-break:break-all}td.r{text-align:right;white-space:nowrap;color:var(--s);font-size:13px}
.ic{width:44px;height:44px;border-radius:10px;background:var(--bg);display:flex;align-items:center;justify-content:center;font-size:22px;overflow:hidden}
.ic img{width:100%;height:100%;object-fit:cover}
.act a{display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;margin-left:6px;border-radius:10px;cursor:pointer;font-size:17px;background:var(--bg)}
.act a:hover{background:var(--g);filter:none}
td.ck{width:34px}input[type=checkbox]{width:18px;height:18px;accent-color:var(--g);cursor:pointer}
tr.on{background:var(--sel)}
#selbar{position:sticky;top:64px;z-index:4;display:flex;align-items:center;gap:6px;flex-wrap:wrap;background:var(--g);color:#fff;border-radius:14px;padding:8px 12px;margin:10px 0}
#selbar b{margin-right:auto}#selbar button,#selbar a{background:#fff2;color:#fff;border:0}
.hide{display:none!important}#drop.over{outline:2px dashed var(--gold)}
.bar{height:5px;background:var(--line);border-radius:3px;overflow:hidden;margin-top:4px}.bar i{display:block;height:100%;width:0;background:var(--g)}
#view{position:fixed;inset:0;background:#000d;z-index:9;display:flex;align-items:center;justify-content:center}
#view img,#view video{max-width:96vw;max-height:90vh}#view iframe{width:92vw;height:88vh;border:0;background:#fff;border-radius:8px}
#view .x{position:absolute;top:12px;right:18px;color:#fff;font-size:30px;cursor:pointer}
</style></head><body>
<header><h1>📁 Sahl <b>Files</b></h1><div class="s" id="dev">${esc(device)}</div></header>
<main>
<div class="card" id="login">
 <div>Enter the 6-digit code shown on the phone (Sahl Share) · أدخل الرمز الظاهر على الهاتف</div><br>
 <input id="code" type="password" inputmode="numeric" maxlength="6" autocomplete="off">
 <button onclick="go()">Open · فتح</button><div id="err" class="s"></div>
</div>

<div id="app" class="hide">
 <div class="tabs"><button id="t1" class="on" onclick="tab(1)">📁 Phone files · ملفات الهاتف</button><button id="t2" onclick="tab(2)">📡 Shared · المُشارَكة</button></div>

 <div id="p1">
  <div class="card" id="drop">
   <div class="crumb" id="crumb"></div>
   <div style="margin-top:8px">
    <button class="btn o" onclick="ls(P)" title="Refresh">↻ Refresh · تحديث</button>
    <label class="btn">⬆ Upload files · رفع ملفات<input id="pick" type="file" multiple class="hide"></label>
    <label class="btn">📂 Upload folder · رفع مجلد<input id="pickdir" type="file" webkitdirectory directory multiple class="hide"></label>
    <button class="btn o" onclick="mk()">＋ Folder · مجلد</button>
    <a class="btn o" id="zipdl">🗜 Download folder (zip)</a>
    <label class="s"><input type="checkbox" id="hid" onchange="ls(P)"> hidden files</label>
   </div>
   <div class="bar-ctl">
    <input type="search" id="pf" class="ctl" placeholder="Filter by name · تصفية بالاسم" oninput="renderList()">
    <select id="pt" class="ctl" onchange="renderList()">
     <option value="">All types · كل الأنواع</option>
     <option value="dir">📁 Folders · مجلدات</option>
     <option value="img">🖼 Images · صور</option>
     <option value="vid">🎬 Videos · فيديو</option>
     <option value="aud">🎵 Audio · صوت</option>
     <option value="doc">📄 Documents · مستندات</option>
     <option value="arc">🗜 Archives · مضغوطة</option>
     <option value="apk">📦 APK</option>
     <option value="other">📄 Other · أخرى</option>
    </select>
    <select id="ps" class="ctl" onchange="renderList()">
     <option value="name-asc">Name A→Z · الاسم أ-ي</option>
     <option value="name-desc">Name Z→A · الاسم ي-أ</option>
     <option value="date-desc">Newest · الأحدث</option>
     <option value="date-asc">Oldest · الأقدم</option>
     <option value="size-desc">Largest · الأكبر</option>
     <option value="size-asc">Smallest · الأصغر</option>
    </select>
   </div>
   <div id="ups"></div>
   <div class="s" id="note"></div>
  </div>
  <div id="selbar" class="hide"><b id="selcount"></b>
   <button onclick="selAll(true)">✓ Select all · الكل</button>
   <button onclick="dlSel()">⬇ Download (zip) · تنزيل</button>
   <button onclick="rmSel()">🗑 Delete · حذف</button>
   <button onclick="selAll(false)">✕</button></div>
  <div class="card"><table id="list"></table></div>
 </div>

 <div id="p2" class="hide">
  <div class="card">
   <b>Files the phone is sharing · ملفات يشاركها الهاتف</b>
   <div class="bar-ctl">
    <button class="btn o" onclick="shared()" title="Refresh">↻ Refresh · تحديث</button>
    <input type="search" id="sf" class="ctl" placeholder="Filter by name · تصفية بالاسم" oninput="renderShared()">
    <select id="st" class="ctl" onchange="renderShared()">
     <option value="">All types · كل الأنواع</option>
     <option value="img">🖼 Images · صور</option>
     <option value="vid">🎬 Videos · فيديو</option>
     <option value="aud">🎵 Audio · صوت</option>
     <option value="doc">📄 Documents · مستندات</option>
     <option value="arc">🗜 Archives · مضغوطة</option>
     <option value="apk">📦 APK</option>
     <option value="other">📄 Other · أخرى</option>
    </select>
    <select id="ss" class="ctl" onchange="renderShared()">
     <option value="orig">Order · الترتيب</option>
     <option value="name-asc">Name A→Z · الاسم أ-ي</option>
     <option value="name-desc">Name Z→A · الاسم ي-أ</option>
     <option value="size-desc">Largest · الأكبر</option>
     <option value="size-asc">Smallest · الأصغر</option>
     <option value="type">Type · النوع</option>
    </select>
   </div>
  </div>
  <div class="card"><div id="files" class="s">…</div></div>
 </div>
</div>
<div id="view" class="hide" onclick="if(event.target===this)closeV()"><span class="x" onclick="closeV()">✕</span><div id="vbox"></div></div>
</main>
<script>
let C=sessionStorage.getItem('c')||'',P='/',ITEMS=[],VIS=[],SHARED=[];const SEL=new Set();
const ${'$'}=id=>document.getElementById(id);
const q=(path,extra)=>'?c='+C+'&p='+encodeURIComponent(path)+(extra||'');
const join=(a,b)=>(a=='/'?'':a)+'/'+b;
function size(n){const u=['B','KB','MB','GB','TB'];let i=0;while(n>=1024&&i<4){n/=1024;i++}return n.toFixed(i?1:0)+' '+u[i]}
function icon(n,d){if(d)return'📁';const e=n.split('.').pop().toLowerCase();
 if(['jpg','jpeg','png','webp','gif','heic','bmp'].includes(e))return'🖼';if(['mp4','mkv','webm','mov','3gp','avi'].includes(e))return'🎬';
 if(['mp3','m4a','ogg','wav','flac','aac','opus'].includes(e))return'🎵';if(e=='pdf')return'📕';if(e=='apk')return'📦';
 if(['zip','rar','7z','tar','gz'].includes(e))return'🗜';return'📄'}
function kind(n,d){if(d)return'dir';const e=n.split('.').pop().toLowerCase();
 if(['jpg','jpeg','png','webp','gif','heic','bmp','svg'].includes(e))return'img';if(['mp4','mkv','webm','mov','3gp','avi','m4v'].includes(e))return'vid';
 if(['mp3','m4a','ogg','wav','flac','aac','opus'].includes(e))return'aud';if(['pdf','txt','log','md','json','xml','csv','html','htm','doc','docx','xls','xlsx','ppt','pptx'].includes(e))return'doc';
 if(['zip','rar','7z','tar','gz'].includes(e))return'arc';if(e=='apk')return'apk';return'other'}
const isImg=n=>/\.(jpe?g|png|webp|gif|bmp|heic)${'$'}/i.test(n),isVid=n=>/\.(mp4|webm|mkv|mov|3gp|m4v)${'$'}/i.test(n),
      isAud=n=>/\.(mp3|m4a|ogg|wav|flac|aac|opus)${'$'}/i.test(n),isDoc=n=>/\.(pdf|txt|log|md|json|xml|csv|html?|svg)${'$'}/i.test(n);
async function api(u,opt){const r=await fetch(u,opt);if(r.status==401||r.status==429){sessionStorage.removeItem('c');location.reload()}return r}
async function go(){C=${'$'}('code').value.trim();const r=await fetch('/fs/info?c='+C);
 if(r.status!=200){${'$'}('err').textContent=r.status==429?'Too many tries · محاولات كثيرة':'Wrong code · رمز خاطئ';return}
 sessionStorage.setItem('c',C);const j=await r.json();
 ${'$'}('dev').textContent=j.device+' · free '+size(j.free)+' of '+size(j.total);
 ${'$'}('note').textContent=j.full?'':'Showing the Received folder only. To see all phone files, turn on “Let the browser see all my files” in Sahl Share. · يعرض مجلد المستلمة فقط.';
 ${'$'}('login').classList.add('hide');${'$'}('app').classList.remove('hide');
 P=decodeURIComponent(location.hash.slice(1))||'/';ls(P);shared()}
function tab(n){${'$'}('p1').classList.toggle('hide',n!=1);${'$'}('p2').classList.toggle('hide',n!=2);${'$'}('t1').classList.toggle('on',n==1);${'$'}('t2').classList.toggle('on',n==2);if(n==2)shared()}
function selUI(){const n=SEL.size;${'$'}('selbar').classList.toggle('hide',!n);${'$'}('selcount').textContent=n+' selected · محدد';
 const all=${'$'}('ckall');if(all){const vn=VIS.filter(i=>SEL.has(i.n)).length;all.checked=VIS.length&&vn==VIS.length;all.indeterminate=vn>0&&vn<VIS.length}
 document.querySelectorAll('#list tr[data-n]').forEach(tr=>{const on=SEL.has(tr.dataset.n);tr.classList.toggle('on',on);tr.querySelector('input').checked=on})}
function selAll(on){if(on)VIS.forEach(i=>SEL.add(i.n));else SEL.clear();selUI()}
function dlSel(){if(!SEL.size)return;location.href='/fs/zipsel'+q(P,'&names='+encodeURIComponent(JSON.stringify([...SEL])))}
async function rmSel(){if(!SEL.size||!confirm('Move '+SEL.size+' items to the Recycle bin on the phone?'))return;
 let bad=0;for(const n of [...SEL]){const r=await api('/fs/delete'+q(join(P,n)));if(r.status!=200)bad++}if(bad)alert(bad+' could not be deleted');SEL.clear();ls(P)}
async function ls(path){const r=await api('/fs/ls'+q(path,${'$'}('hid').checked?'&h=1':''));if(r.status!=200){if(path!='/')ls('/');return}
 const j=await r.json();if(j.path!=P)SEL.clear();P=j.path;ITEMS=j.items;location.hash=encodeURIComponent(P);
 const parts=P.split('/').filter(x=>x);let acc='',h='<a href="#" onclick="ls(\'/\');return false">📱 Phone</a>';
 for(const p of parts){acc+='/'+p;const a=acc;h+='<span>›</span><a href="#" data-p="'+encodeURIComponent(a)+'" onclick="ls(decodeURIComponent(this.dataset.p));return false"></a>'}
 ${'$'}('crumb').innerHTML=h;const links=${'$'}('crumb').querySelectorAll('a[data-p]');parts.forEach((p,i)=>links[i].textContent=p);
 ${'$'}('zipdl').href='/fs/zip'+q(P);
 renderList()}
function renderList(){
 const f=(${'$'}('pf').value||'').trim().toLowerCase(),tp=${'$'}('pt').value,sr=${'$'}('ps').value;
 VIS=ITEMS.filter(it=>(!f||it.n.toLowerCase().includes(f))&&(!tp||kind(it.n,it.d)==tp));
 VIS.sort((a,b)=>{if(a.d!=b.d)return a.d?-1:1;
  if(sr=='name-desc')return b.n.localeCompare(a.n,undefined,{sensitivity:'base'});
  if(sr=='date-desc')return b.m-a.m;
  if(sr=='date-asc')return a.m-b.m;
  if(sr=='size-desc')return b.s-a.s;
  if(sr=='size-asc')return a.s-b.s;
  return a.n.localeCompare(b.n,undefined,{sensitivity:'base'})});
 const t=${'$'}('list');t.innerHTML='';
 if(!ITEMS.length){t.innerHTML='<tr><td class="s">Empty folder · مجلد فارغ</td></tr>';selUI();return}
 if(!VIS.length){t.innerHTML='<tr><td class="s">No matching items · لا توجد عناصر مطابقة</td></tr>';selUI();return}
 const cnt=VIS.length==ITEMS.length?ITEMS.length+' items · عنصر':VIS.length+' / '+ITEMS.length+' items · عنصر';
 const h=document.createElement('tr');h.innerHTML='<td class="ck"><input type="checkbox" id="ckall" title="Select all"></td><td colspan="3" class="s">'+cnt+'</td>';
 t.append(h);${'$'}('ckall').onchange=e=>selAll(e.target.checked);
 for(const it of VIS){const full=join(P,it.n);const tr=document.createElement('tr');tr.dataset.n=it.n;
  const ck=document.createElement('td');ck.className='ck';const cb=document.createElement('input');cb.type='checkbox';
  cb.onchange=()=>{cb.checked?SEL.add(it.n):SEL.delete(it.n);selUI()};ck.append(cb);tr.append(ck);
  const ic=document.createElement('td');ic.style.width='54px';const box=document.createElement('div');box.className='ic';box.textContent=icon(it.n,it.d);
  if(!it.d&&(isImg(it.n)||isVid(it.n))){const im=new Image();im.loading='lazy';im.onload=()=>{box.textContent='';box.append(im)};im.src='/fs/thumb'+q(full)}
  ic.append(box);tr.append(ic);
  const n=document.createElement('td');n.className='n';const a=document.createElement('a');a.href='#';a.textContent=it.n;
  a.onclick=e=>{e.preventDefault();it.d?ls(full):openF(full,it.n)};n.append(a);
  const sub=document.createElement('div');sub.className='s';sub.textContent=(it.d?it.s+' items':size(it.s))+' · '+new Date(it.m).toLocaleString();n.append(sub);tr.append(n);
  const act=document.createElement('td');act.className='r act';
  const dl=document.createElement('a');dl.textContent=it.d?'🗜':'⬇';dl.title='Download';dl.href=it.d?'/fs/zip'+q(full):'/fs/get'+q(full,'&dl=1');act.append(dl);
  const rn=document.createElement('a');rn.textContent='✎';rn.title='Rename';rn.onclick=()=>ren(full,it.n);act.append(rn);
  const del=document.createElement('a');del.textContent='🗑';del.title='Delete';del.onclick=()=>rm(full,it.n);act.append(del);
  tr.append(act);t.append(tr)}
 selUI()}
function openF(full,name){const u='/fs/get'+q(full);const v=${'$'}('vbox');v.innerHTML='';
 let el;if(isImg(name)){el=new Image();el.src=u}else if(isVid(name)){el=document.createElement('video');el.controls=true;el.autoplay=true;el.src=u}
 else if(isAud(name)){el=document.createElement('audio');el.controls=true;el.autoplay=true;el.src=u}
 else if(isDoc(name)){el=document.createElement('iframe');el.src=u;el.setAttribute('sandbox','')}
 else{location.href='/fs/get'+q(full,'&dl=1');return}
 v.append(el);${'$'}('view').classList.remove('hide')}
function closeV(){${'$'}('vbox').innerHTML='';${'$'}('view').classList.add('hide')}
async function mk(){const n=prompt('New folder name · اسم المجلد');if(!n)return;await api('/fs/mkdir'+q(P,'&n='+encodeURIComponent(n)));ls(P)}
async function ren(full,old){const n=prompt('New name · الاسم الجديد',old);if(!n||n==old)return;const r=await api('/fs/rename'+q(full,'&n='+encodeURIComponent(n)));if(r.status!=200)alert('Could not rename');ls(P)}
async function rm(full,name){if(!confirm('Move “'+name+'” to the Recycle bin on the phone?'))return;const r=await api('/fs/delete'+q(full));if(r.status!=200)alert('Could not delete');ls(P)}
function up(file,rel,last,count){return new Promise(res=>{const row=document.createElement('div');row.innerHTML='<div class="s"><span></span> · <b></b></div><div class="bar"><i></i></div>';
 row.querySelector('span').textContent=rel||file.name;${'$'}('ups').append(row);const x=new XMLHttpRequest();
 x.open('POST','/fs/put'+q(P,'&n='+encodeURIComponent(rel||file.name)+(last?'&last=1&count='+count:'')));
 x.upload.onprogress=e=>{row.querySelector('i').style.width=(e.loaded*100/e.total)+'%';row.querySelector('b').textContent=size(e.loaded)};
 x.onload=()=>{row.querySelector('b').textContent=x.status==200?'✓':'✗';res()};x.onerror=()=>{row.querySelector('b').textContent='✗';res()};x.send(file)})}
// list = [[File, relativePath], ...]
async function many(list){for(let i=0;i<list.length;i++)await up(list[i][0],list[i][1],i==list.length-1,list.length);ls(P)}
${'$'}('pick').onchange=e=>{many([...e.target.files].map(f=>[f,f.name]));e.target.value=''};
${'$'}('pickdir').onchange=e=>{many([...e.target.files].map(f=>[f,f.webkitRelativePath||f.name]));e.target.value=''};
function readEntry(en,path,out){return new Promise(res=>{
 if(en.isFile)en.file(f=>{out.push([f,path+f.name]);res()},()=>res());
 else{const rd=en.createReader();const all=[];const next=()=>rd.readEntries(async es=>{if(!es.length){for(const c of all)await readEntry(c,path+en.name+'/',out);res()}else{all.push(...es);next()}},()=>res());next()}})}
const d=${'$'}('drop');d.ondragover=e=>{e.preventDefault();d.classList.add('over')};d.ondragleave=()=>d.classList.remove('over');
d.ondrop=async e=>{e.preventDefault();d.classList.remove('over');const out=[];
 const items=[...e.dataTransfer.items].map(i=>i.webkitGetAsEntry&&i.webkitGetAsEntry()).filter(x=>x);
 if(items.length){for(const en of items)await readEntry(en,'',out)}else[...e.dataTransfer.files].forEach(f=>out.push([f,f.name]));
 many(out)};
document.addEventListener('keydown',e=>{if(e.key=='F5'&&!e.ctrlKey){e.preventDefault();${'$'}('p2').classList.contains('hide')?ls(P):shared()}});
async function shared(){const f=${'$'}('files');f.textContent='Loading… · جارٍ التحديث…';
 const r=await api('/web/list?c='+C);if(r.status!=200){f.textContent='Could not load · تعذر التحميل';return}
 const j=await r.json();SHARED=j.files||[];renderShared()}
function renderShared(){const f=${'$'}('files');
 if(!SHARED.length){f.textContent='Nothing picked in “To send” on the phone · لا توجد ملفات';return}
 const qv=(${'$'}('sf').value||'').trim().toLowerCase(),tp=${'$'}('st').value,sr=${'$'}('ss').value;
 const list=SHARED.filter(x=>(!qv||x.name.toLowerCase().includes(qv))&&(!tp||kind(x.name,false)==tp));
 list.sort((a,b)=>{
  if(sr=='name-asc')return a.name.localeCompare(b.name,undefined,{sensitivity:'base'});
  if(sr=='name-desc')return b.name.localeCompare(a.name,undefined,{sensitivity:'base'});
  if(sr=='size-desc')return b.size-a.size;
  if(sr=='size-asc')return a.size-b.size;
  if(sr=='type')return kind(a.name,false).localeCompare(kind(b.name,false))||a.name.localeCompare(b.name,undefined,{sensitivity:'base'});
  return a.i-b.i});
 if(!list.length){f.textContent='No matching files · لا توجد ملفات مطابقة';return}
 f.innerHTML='';
 const cnt=document.createElement('div');cnt.className='s';cnt.style.marginBottom='6px';
 cnt.textContent=list.length==SHARED.length?SHARED.length+' files · ملف':list.length+' / '+SHARED.length+' files · ملف';
 f.append(cnt);
 const tbl=document.createElement('table');
 for(const x of list){const tr=document.createElement('tr');
  const ic=document.createElement('td');ic.style.width='42px';ic.textContent=icon(x.name,false);tr.append(ic);
  const nm=document.createElement('td');nm.className='n';
  const a=document.createElement('a');a.href='/web/dl?c='+C+'&i='+x.i;a.textContent=x.name;nm.append(a);
  const sub=document.createElement('div');sub.className='s';sub.textContent=size(x.size);nm.append(sub);tr.append(nm);
  const act=document.createElement('td');act.className='r act';
  const dl=document.createElement('a');dl.textContent='⬇';dl.title='Download';dl.href='/web/dl?c='+C+'&i='+x.i;act.append(dl);
  tr.append(act);tbl.append(tr)}
 f.append(tbl)}
document.addEventListener('keydown',e=>{if(e.key=='Escape')closeV()});
${'$'}('code').onkeyup=e=>{if(e.key=='Enter')go()};
if(C){${'$'}('code').value=C;go()}
</script></body></html>"""
}
