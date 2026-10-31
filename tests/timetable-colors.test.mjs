import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {TIMETABLE_COLOR_SCRIPT,TIMETABLE_PREFERRED_COLOR_COUNT} from '../dist/timetable-colors.js';
import {renderTimetablePage} from '../dist/timetable-ui.js';
const palette=JSON.parse(renderTimetablePage([]).match(/const palette = (\[[^;]+);/)[1]);
const assign=vm.runInNewContext(TIMETABLE_COLOR_SCRIPT+';assignTimetableColors');
const course=(n,weeks=[1])=>({paletteKey:'课程'+String(n).padStart(3,'0'),weeks});
test('46色：同周不重复，多条同名安排同色，打乱输入不影响配色',()=>{
 assert.equal(palette.length,46);assert.equal(new Set(palette.map(c=>c[0])).size,46);
 const input=Array.from({length:46},(_,i)=>course(i));input.push(course(0,[2]));
 const map=assign(input,palette);assert.equal(map.size,46);assert.equal(new Set([...map.values()].map(c=>c[0])).size,46);
 assert.deepEqual([...assign([...input].reverse(),palette)].sort(),[...map].sort());
});
test('超过颜色数时复用不同时周的颜色，47门同周只产生必要的一次重复',()=>{
 const input=Array.from({length:90},(_,i)=>course(i,[1+Math.floor(i/30)]));const map=assign(input,palette);
 for(let week=1;week<=3;week++)assert.equal(new Set(input.filter(c=>c.weeks.includes(week)).map(c=>map.get(c.paletteKey)[0])).size,30);
 const clique=Array.from({length:47},(_,i)=>course(i));assert.equal(new Set([...assign(clique,palette).values()].map(c=>c[0])).size,46);
});
test('少量同周课优先使用差异颜色，不直接按相邻数组项循环',()=>{
 const input=Array.from({length:5},(_,i)=>course(i));const map=assign(input,palette);
 const rgb=hex=>[1,3,5].map(i=>parseInt(hex.slice(i,i+2),16));const distance=(a,b)=>Math.hypot(...rgb(a).map((v,i)=>v-rgb(b)[i]));
 const chosen=[...map.values()].map(c=>c[0]);const min=xs=>Math.min(...xs.flatMap((a,i)=>xs.slice(i+1).map(b=>distance(a,b))));
 assert.ok(min(chosen)>min(palette.slice(0,5).map(c=>c[0])));assert.equal(assign([],palette).size,0);
});
test('原15色与截图16色共同优先，课程保持白字，大块先取首选色',()=>{
 assert.deepEqual(palette.slice(0,15).map(([hex])=>hex),['#f39ac3','#ffd36f','#7ed2aa','#78b7ee','#628fe2','#96cf6e','#e25555','#6e7fd8','#ffb287','#7fd7d0','#b799f2','#f2c66d','#84c3a4','#f08ca4','#85aef7']);
 const html=renderTimetablePage([]);assert.match(html,/color: #ffffff/);assert.ok(!html.includes('--course-ink'));
 const big={...course(99),duration:6},small={...course(1),duration:1};
 assert.equal(assign([small,big],palette).get(big.paletteKey)[0],assign([big],palette).get(big.paletteKey)[0]);
 assert.deepEqual(palette.slice(15,31).map(([hex])=>hex),['#e286aa','#fecb62','#78cfa7','#abd289','#85b763','#72b4ef','#4781d4','#536baf','#9f8bea','#7158d1','#ce7abf','#e07875','#d04543','#ce533b','#bc9e84','#d0c670']);
 assert.equal(TIMETABLE_PREFERRED_COLOR_COUNT,31);
 const preferred=new Set(palette.slice(0,31).map(([hex])=>hex));
 for(let count=1;count<=31;count++)assert.ok([...assign(Array.from({length:count},(_,i)=>course(i)),palette).values()].every(([hex])=>preferred.has(hex)));
 const thirtyTwo=[...assign(Array.from({length:32},(_,i)=>course(i)),palette).values()];
 assert.equal(thirtyTwo.filter(([hex])=>!preferred.has(hex)).length,1);
});

test('不同周复用首选色，不提前启用补充色；空调色板正常回退',()=>{
 const preferred=new Set(palette.slice(0,31).map(([hex])=>hex));
 const input=Array.from({length:62},(_,i)=>course(i,[1+Math.floor(i/31)]));
 assert.ok([...assign(input,palette).values()].every(([hex])=>preferred.has(hex)));
 assert.equal(assign([course(1)],[]).get(course(1).paletteKey)[0],'#2167b0');
});
