/** Term-stable colors: shared teaching weeks form a weighted conflict graph. */
export const TIMETABLE_PREFERRED_COLOR_COUNT = 31;
export const TIMETABLE_COLOR_SCRIPT = String.raw`
function assignTimetableColors(courses,palette){
 if(!palette.length)palette=[['#2167b0','#185590']];
 const preferredCount=Math.min(${TIMETABLE_PREFERRED_COLOR_COUNT},palette.length);
 const graph=new Map();
 for(const c of courses){if(!graph.has(c.paletteKey))graph.set(c.paletteKey,{weeks:new Set(),neighbors:new Map(),area:1});const node=graph.get(c.paletteKey);node.area=Math.max(node.area,Number(c.duration)||Number(c.endPeriod)-Number(c.startPeriod)+1||1);for(const w of c.weeks)node.weeks.add(w);}
 const keys=[...graph.keys()].sort((a,b)=>String(a).localeCompare(String(b),'zh-CN'));
 for(let i=0;i<keys.length;i++)for(let j=i+1;j<keys.length;j++){
  const a=graph.get(keys[i]),b=graph.get(keys[j]);let overlap=0;for(const w of a.weeks)if(b.weeks.has(w))overlap++;
  if(overlap){a.neighbors.set(keys[j],overlap);b.neighbors.set(keys[i],overlap);}
 }
 // Oklab distances distinguish perceived colors rather than raw hex channel differences.
 const labs=palette.map(([hex])=>{
  const rgb=[1,3,5].map(i=>parseInt(hex.slice(i,i+2),16)/255).map(v=>v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4));
  const [r,g,b]=rgb,l=Math.cbrt(.4122214708*r+.5363325363*g+.0514459929*b),m=Math.cbrt(.2119034982*r+.6806995451*g+.1073969566*b),s=Math.cbrt(.0883024619*r+.2817188376*g+.6299787005*b);
  return [.2104542553*l+.793617785*m-.0040720468*s,1.9779984951*l-2.428592205*m+.4505937099*s,.0259040371*l+.7827717662*m-.808675766*s];
 });
 const distance=(a,b)=>Math.hypot(...labs[a].map((v,i)=>v-labs[b][i]));
 const assigned=new Map(),usage=palette.map(()=>0);
 while(assigned.size<keys.length){
  const remaining=keys.filter(k=>!assigned.has(k));
  const saturation=k=>new Set([...graph.get(k).neighbors.keys()].filter(n=>assigned.has(n)).map(n=>assigned.get(n))).size;
  remaining.sort((a,b)=>saturation(b)-saturation(a)||graph.get(b).area-graph.get(a).area||graph.get(b).neighbors.size-graph.get(a).neighbors.size||keys.indexOf(a)-keys.indexOf(b));
  const key=remaining[0],neighbors=[...graph.get(key).neighbors].filter(([n])=>assigned.has(n));
  const candidates=palette.map((_,index)=>{
   const same=neighbors.reduce((sum,[n,weight])=>sum+(assigned.get(n)===index?weight:0),0);
   const compare=neighbors.length?neighbors.map(([n])=>assigned.get(n)):[...assigned.values()];
   const separation=compare.length?Math.min(...compare.map(n=>distance(index,n))):1;
   return {index,same,separation};
  });
  candidates.sort((a,b)=>a.same-b.same||(a.index<preferredCount?0:1)-(b.index<preferredCount?0:1)||(usage[a.index]===0?0:1)-(usage[b.index]===0?0:1)||b.separation-a.separation||usage[a.index]-usage[b.index]||a.index-b.index);
  const index=candidates[0].index;assigned.set(key,index);usage[index]++;
 }
 return new Map([...assigned].map(([key,index])=>[key,palette[index]]));
}
`;
