
window.__seek=(s)=>{pause();t=s;render();burnCap();};
// Video render: burn the current caption into the canvas (the page shows it in the DOM instead).
function burnCap(){
  const i=sceneAt(t), sc=scenes[i], lt=t-offs[i]; let cap='';
  for(const c of sc.caps){ if(lt>=c.t0&&lt<c.t1){cap=c.text;break;} }
  cap=cap.replace(/<[^>]+>/g,'').replace(/&amp;/g,'&'); if(!cap) return;
  const fs=Math.round(h*.034); ctx.font='500 '+fs+'px '+SANS;
  const words=cap.split(' '), lines=[]; let line='';
  for(const wd of words){ const tr=line?line+' '+wd:wd; if(ctx.measureText(tr).width>w*.78&&line){lines.push(line);line=wd;} else line=tr; }
  if(line) lines.push(line);
  const lh=fs*1.35, bh=lines.length*lh+fs*.8, y0=h-bh-h*.035;
  let bw=0; for(const l of lines) bw=Math.max(bw,ctx.measureText(l).width); bw+=fs*1.6;
  ctx.globalAlpha=.78; ctx.fillStyle='#05070b'; ctx.beginPath(); ctx.roundRect(w/2-bw/2,y0,bw,bh,fs*.4); ctx.fill(); ctx.globalAlpha=1;
  ctx.fillStyle='#f4f6fb'; ctx.textAlign='center'; ctx.textBaseline='middle';
  lines.forEach((l,k)=>ctx.fillText(l,w/2,y0+fs*.4+lh*(k+.5)));
}
window.__size=(W,H)=>{window.__forceDpr=1;const st=document.querySelector('.stage');document.querySelector('.wrap').style.maxWidth='none';document.querySelector('.wrap').style.padding='0';st.style.width=W+'px';st.style.height=H+'px';st.style.border='0';st.style.borderRadius='0';document.querySelector('.chapter').style.display='none';fit();render();};
