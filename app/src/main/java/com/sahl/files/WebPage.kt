package com.sahl.files

/** The page a PC, TV or any phone opens at http://<phone>:53317 — enter the code, then send or download files. */
object WebPage {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun html(device: String) = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sahl Share · ${esc(device)}</title>
<style>
:root{--g:#0E6B4E;--gold:#C9A227;--bg:#F6F4EE;--card:#fff;--t:#1B2420;--s:#6B746F}
@media(prefers-color-scheme:dark){:root{--bg:#0B1512;--card:#15241F;--t:#EDEBE3;--s:#9BA89F}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font:16px system-ui,-apple-system,"Segoe UI",Tahoma,sans-serif}
main{max-width:640px;margin:0 auto;padding:24px 16px}
h1{font-size:24px;margin:0 0 4px}h1 b{color:var(--gold)}.s{color:var(--s);font-size:14px}
.card{background:var(--card);border-radius:18px;padding:18px;margin:16px 0}
input[type=text]{font-size:28px;letter-spacing:8px;text-align:center;width:100%;padding:12px;border-radius:12px;border:1px solid var(--s);background:transparent;color:var(--t)}
button,label.btn{display:inline-block;background:var(--g);color:#fff;border:0;border-radius:24px;padding:12px 22px;font-size:16px;cursor:pointer;margin-top:12px}
a{color:var(--g);text-decoration:none}.row{display:flex;justify-content:space-between;padding:10px 0;border-bottom:1px solid #8883}
.bar{height:6px;background:#8883;border-radius:3px;overflow:hidden;margin-top:6px}.bar i{display:block;height:100%;width:0;background:var(--g)}
.hide{display:none}#drop.over{outline:2px dashed var(--gold)}
</style></head><body><main>
<h1>📡 Sahl <b>Share</b></h1><div class="s">${esc(device)} · سهل للمشاركة</div>

<div class="card" id="login">
 <div>Enter the 6-digit code shown on the phone · أدخل الرمز الظاهر على الهاتف</div><br>
 <input id="code" type="text" inputmode="numeric" maxlength="6" autocomplete="off">
 <button onclick="go()">Open · فتح</button><div id="err" class="s"></div>
</div>

<div id="app" class="hide">
 <div class="card" id="drop">
  <b>Send to the phone · إرسال إلى الهاتف</b>
  <div class="s">Choose files or drop them here · اختر ملفات أو اسحبها هنا</div>
  <label class="btn">Choose files · اختيار<input id="pick" type="file" multiple class="hide"></label>
  <div id="ups"></div>
 </div>
 <div class="card"><b>From the phone · من الهاتف</b><div id="files" class="s">…</div></div>
</div>
</main>
<script>
let C=sessionStorage.getItem('c')||'';
const $=id=>document.getElementById(id);
function size(n){const u=['B','KB','MB','GB'];let i=0;while(n>=1024&&i<3){n/=1024;i++}return n.toFixed(i?1:0)+' '+u[i]}
async function go(){C=$('code').value.trim();const r=await fetch('/web/list?c='+C);
 if(r.status!=200){$('err').textContent=r.status==429?'Too many tries · محاولات كثيرة':'Wrong code · رمز خاطئ';return}
 sessionStorage.setItem('c',C);$('login').classList.add('hide');$('app').classList.remove('hide');show(await r.json())}
function show(j){const f=$('files');if(!j.files.length){f.textContent='Nothing shared yet · لا توجد ملفات مشاركة';return}
 f.innerHTML='';for(const x of j.files){const d=document.createElement('div');d.className='row';
 const a=document.createElement('a');a.href='/web/dl?c='+C+'&i='+x.i;a.textContent='⬇ '+x.name;d.append(a);
 const s=document.createElement('span');s.className='s';s.textContent=size(x.size);d.append(s);f.append(d)}}
function up(file){return new Promise(res=>{const row=document.createElement('div');row.innerHTML='<div class="row"><span></span><span class="s"></span></div><div class="bar"><i></i></div>';
 row.querySelector('span').textContent=file.name;$('ups').append(row);const x=new XMLHttpRequest();
 x.open('POST','/web/up?c='+C+'&n='+encodeURIComponent(file.name));
 x.upload.onprogress=e=>{row.querySelector('i').style.width=(e.loaded*100/e.total)+'%';row.querySelector('.s').textContent=size(e.loaded)};
 x.onload=()=>{row.querySelector('.s').textContent=x.status==200?'✓':'✗';res()};x.onerror=()=>{row.querySelector('.s').textContent='✗';res()};x.send(file)})}
async function many(fs){for(const f of fs)await up(f)}
$('pick').onchange=e=>many([...e.target.files]);
const d=$('drop');d.ondragover=e=>{e.preventDefault();d.classList.add('over')};d.ondragleave=()=>d.classList.remove('over');
d.ondrop=e=>{e.preventDefault();d.classList.remove('over');many([...e.dataTransfer.files])};
$('code').onkeyup=e=>{if(e.key=='Enter')go()};
if(C){$('code').value=C;go()}
</script></body></html>"""
}
