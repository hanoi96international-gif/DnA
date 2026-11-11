package de.ayutv.data.remote

/** Fernbedienung als Webseite: für jedes Handy im Heimnetz, auch ohne DnA-TV-App. */
object RemotePage {
    fun html(pin: String): String = """<!doctype html><html lang=de><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1,user-scalable=no"><title>DnA-TV Fernbedienung</title>
<style>
body{font-family:system-ui,sans-serif;background:#0d0f14;color:#e8eaf0;margin:0 auto;max-width:420px;padding:16px;user-select:none;-webkit-user-select:none}
h1{font-size:1.1em;color:#8fb4ff;text-align:center;margin:6px 0 16px}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-bottom:14px}
button{padding:18px 0;border:0;border-radius:14px;background:#1c2130;color:#fff;font-size:20px;touch-action:manipulation}
button:active{background:#4f7cff}
.ok{background:#4f7cff;border-radius:50%;aspect-ratio:1}
.small{font-size:15px}
input{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #333a48;background:#171a22;color:#fff;font-size:17px}
p{color:#8a92a3;font-size:13px;text-align:center}
</style></head><body>
<h1>DnA-TV Fernbedienung</h1>
<div class=grid>
<button class=small data-k=menu>☰ Menü</button><button data-k=up>▲</button><button class=small data-k=info>ⓘ Info</button>
<button data-k=left>◀</button><button class=ok data-k=ok>OK</button><button data-k=right>▶</button>
<button class=small data-k=back>↩ Zurück</button><button data-k=down>▼</button><button class=small data-k=guide>TV-Guide</button>
</div>
<div class=grid>
<button data-k=rew>⏪</button><button data-k=play>⏯</button><button data-k=ffwd>⏩</button>
<button class=small data-k=chdown>Sender −</button><button class=small data-k=last>↺ Zuletzt</button><button class=small data-k=chup>Sender +</button>
<button class=small data-k=voldown>🔉 Leiser</button><button class=small data-k=mute>🔇</button><button class=small data-k=volup>🔊 Lauter</button>
</div>
<div class=grid>
${(1..9).joinToString("") { "<button data-k=$it>$it</button>" }}<span></span><button data-k=0>0</button><span></span>
</div>
<form id=s><input id=q placeholder="Suchen (Sender, Film, Serie) …" enterkeyhint=search></form>
<p>Adresse als Lesezeichen speichern – die PIN bleibt gleich.</p>
<script>
const base='/$pin';
document.querySelectorAll('button[data-k]').forEach(b=>b.addEventListener('click',()=>{
  fetch(base+'/key?k='+b.dataset.k,{method:'POST'});
  if(navigator.vibrate)navigator.vibrate(15);
}));
document.getElementById('s').addEventListener('submit',e=>{
  e.preventDefault();const q=document.getElementById('q');
  fetch(base+'/search',{method:'POST',body:q.value});q.blur();
});
</script></body></html>"""
}
