const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const out = process.argv[2];
const cps = JSON.parse(fs.readFileSync(path.join(__dirname,'cps.json')));
function varint(n){const b=[];while(n>127){b.push((n&127)|128);n=Math.floor(n/128);}b.push(n);return b;}
function zz(n){return n>=0?n*2:(-n*2)-1;}
function fieldV(f,v){return [...varint(f<<3),...varint(v)];}
function fieldB(f,bytes){return [...varint((f<<3)|2),...varint(bytes.length),...bytes];}
(async()=>{
  const b = await chromium.launch({args:['--allow-file-access-from-files']}); const p = await b.newPage();
  await p.goto('file://'+path.join(__dirname,'page.html')); await p.waitForFunction(()=>window.ready);
  await p.evaluate(()=>document.fonts.ready.then(()=>Promise.all([...document.fonts].map(f=>f.load()))));
  const stacks=[["WayfinderMedium","GSFM, NOTOM"],["WayfinderBold","GSFB, NOTOB"]];
  const byRange={}; for(const c of cps){(byRange[c>>8] ||= []).push(c);}
  for(const [name,family] of stacks){
    for(const r of Object.keys(byRange).map(Number)){
      const glyphs = await p.evaluate(({family,list})=>{
        const sdf=new TinySDF({fontSize:24,buffer:3,radius:8,cutoff:0.25,fontFamily:family,fontWeight:'normal'});
        return list.map(id=>{const g=sdf.draw(String.fromCodePoint(id));return {id,w:g.glyphWidth,h:g.glyphHeight,top:g.glyphTop,left:g.glyphLeft,adv:g.glyphAdvance,data:(g.glyphWidth&&g.glyphHeight)?Array.from(g.data):null};});
      },{family,list:byRange[r]});
      const start=r*256,end=start+255; let gbytes=[];
      for(const g of glyphs){
        let m=[...fieldV(1,g.id)];
        if(g.data) m.push(...fieldB(2,g.data));
        m.push(...fieldV(3,g.data?g.w:0),...fieldV(4,g.data?g.h:0),...fieldV(5,zz(Math.round(g.left))),...fieldV(6,zz(g.top-27)),...fieldV(7,Math.round(g.adv)));
        gbytes.push(...fieldB(3,m));
      }
      const fs_=[...fieldB(1,[...Buffer.from(name)]),...fieldB(2,[...Buffer.from(`${start}-${end}`)]),...gbytes];
      const all=Buffer.from(fieldB(1,fs_));
      fs.mkdirSync(path.join(out,name),{recursive:true}); fs.writeFileSync(path.join(out,name,`${start}-${end}.pbf`),all);
    }
  }
  await b.close();
})();
