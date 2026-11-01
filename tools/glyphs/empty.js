const fs=require('fs'),path=require('path');const out=process.argv[2];
function varint(n){const b=[];while(n>127){b.push((n&127)|128);n=Math.floor(n/128);}b.push(n);return b;}
function fieldB(f,bytes){return [...varint((f<<3)|2),...varint(bytes.length),...bytes];}
for(const name of ['WayfinderMedium','WayfinderBold']){for(let s=0;s<65536;s+=256){const f=path.join(out,name,`${s}-${s+255}.pbf`);if(fs.existsSync(f))continue;
fs.writeFileSync(f,Buffer.from(fieldB(1,[...fieldB(1,[...Buffer.from(name)]),...fieldB(2,[...Buffer.from(`${s}-${s+255}`)])])));}}
