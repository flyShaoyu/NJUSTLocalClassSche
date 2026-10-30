import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {TIMETABLE_COLOR_SCRIPT} from '../dist/timetable-colors.js';
import {renderTimetablePage} from '../dist/timetable-ui.js';
const palette=JSON.parse(renderTimetablePage([]).match(/const palette = (\[[^;]+);/)[1]);
const assign=vm.runInNewContext(TIMETABLE_COLOR_SCRIPT+';assignTimetableColors');
const course=(n,weeks=[1])=>({paletteKey:'课程'+String(n).padStart(3,'0'),weeks});
test('30色：同周30门课不重复，多条同名安排同色，打乱输入不影响配色',()=>{
 assert.equal(palette.length,30);assert.equal(new Set(palette.map(c=>c[0])).size,30);
 const input=Array.from({length:30},(_,i)=>course(i));input.push(course(0,[2]));
 const map=assign(input,palette);assert.equal(map.size,30);assert.equal(new Set([...map.values()].map(c=>c[0])).size,30);
 assert.deepEqual([...assign([...input].reverse(),palette)].sort(),[...map].sort());
});
test('超过颜色数时复用不同时周的颜色，31门同周只产生必要的一次重复',()=>{
 const input=Array.from({length:60},(_,i)=>course(i,[1+Math.floor(i/20)]));const map=assign(input,palette);
 for(let week=1;week<=3;week++)assert.equal(new Set(input.filter(c=>c.weeks.includes(week)).map(c=>map.get(c.paletteKey)[0])).size,20);
 const clique=Array.from({length:31},(_,i)=>course(i));assert.equal(new Set([...assign(clique,palette).values()].map(c=>c[0])).size,30);
});
test('少量同周课优先使用差异颜色，不直接按相邻数组项循环',()=>{
 const input=Array.from({length:5},(_,i)=>course(i));const map=assign(input,palette);
 const rgb=hex=>[1,3,5].map(i=>parseInt(hex.slice(i,i+2),16));const distance=(a,b)=>Math.hypot(...rgb(a).map((v,i)=>v-rgb(b)[i]));
 const chosen=[...map.values()].map(c=>c[0]);const min=xs=>Math.min(...xs.flatMap((a,i)=>xs.slice(i+1).map(b=>distance(a,b))));
 assert.ok(min(chosen)>min(palette.slice(0,5).map(c=>c[0])));assert.equal(assign([],palette).size,0);
});
