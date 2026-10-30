import { GENERAL_EDUCATION_CATALOG } from "./general-education-catalog.js";

/** The catalogue is shared with score statistics; no term offerings or seats are inferred. */
export const ENROLLMENT_CATALOG_LOGIC = String.raw`
function enrollmentText(value){return String(value??'').normalize('NFKC').trim();}
function buildEnrollmentCatalog(catalog,overrides={}){
  if(!catalog||!Array.isArray(catalog.courses)||catalog.courses.length>10000)throw new Error('课程分类目录解析错误：课程列表格式不正确');
  const courses=new Map();
  for(const row of catalog.courses){
    const code=enrollmentText(row?.courseCode).toUpperCase(),name=String(row?.courseName??'').trim(),category=String(row?.category??'').trim();
    if(!/^[A-Z0-9]{8}$/.test(code)||!name||!category)throw new Error('课程分类目录解析错误：课程号、名称或分类缺失');
    if(!courses.has(code))courses.set(code,{courseCode:code,courseName:name,names:[],categories:[]});
    const item=courses.get(code);
    if(!item.names.includes(name))item.names.push(name);
    if(!item.categories.includes(category))item.categories.push(category);
  }
  return [...courses.values()].map(item=>{
    const raw=overrides?.['code:'+item.courseCode],corrected=typeof raw==='string'?raw.trim().slice(0,50):'';
    return {...item,officialCategories:[...item.categories],categories:corrected?[corrected]:item.categories,corrected:!!corrected,conflict:!corrected&&item.categories.length>1};
  });
}
function filterEnrollmentCatalog(items,keyword='',category=''){
  const tokens=enrollmentText(keyword).toLocaleLowerCase().split(/[\s,，;；]+/).filter(Boolean);
  return items.filter(item=>{
    if(category==='__conflict__'&&!item.conflict)return false;
    if(category&&category!=='__conflict__'&&!item.categories.includes(category))return false;
    const haystack=enrollmentText(item.courseCode+' '+item.names.join(' ')).toLocaleLowerCase();
    return tokens.every(token=>haystack.includes(token));
  });
}
`;

export const buildEnrollmentCatalogScript = (): string => String.raw`
__CATALOG_LOGIC__
let generalCatalog = __PUBLIC_CATALOG__;
// enrollment-catalog-v1: the page is available, all actual enrollment operations remain disabled.
let enrollmentItems=[],enrollmentMatches=[],enrollmentPage=1;
const enrollmentPageSize=30;
function showEnrollment(){
  page='enrollment';clearError();root.className='page';
  let overrides={},storageWarning='';
  try{const raw=localStorage.getItem('classsche.general-education.categories.v1');if(raw){const value=JSON.parse(raw);if(!value||typeof value!=='object'||Array.isArray(value))throw new Error('格式错误');overrides=value;}}
  catch(_){storageWarning='本机分类更正读取失败，暂用校方原始分类，原数据未覆盖。';}
  try{enrollmentItems=buildEnrollmentCatalog(generalCatalog,overrides);}catch(err){error(err);root.innerHTML=empty('search','课程分类目录无法读取','请返回后重试，或在成绩页更新分类表');return;}
  const labels=[...new Set([...(generalCatalog.categories||[]),...enrollmentItems.flatMap(item=>item.categories)])].filter(category=>enrollmentItems.some(item=>item.categories.includes(category)));
  const source=generalCatalog.cacheSource==='mobile'?'本机更新的校方分类表 · '+(generalCatalog.capturedOn||''):'校方通识课程分类表（2022版）';
  root.innerHTML='<section class="card"><div class="row between"><h2>通识课程分类</h2><span class="badge warm">选课 / 抢课未开放</span></div><p class="hint" style="padding-left:0">'+e(source)+' · '+enrollmentItems.length+' 门课程</p><p class="hint" style="padding-left:0">当前显示课程分类目录。实时可选课程、上课安排和余量，待教务开放后提供。</p></section><form class="searchbar" id="enrollmentSearch"><input id="enrollmentKeyword" maxlength="120" placeholder="搜索课程名称或课程号" aria-label="课程名称或课程号"><button class="primary">搜索</button></form><div class="toolbar-note"><label for="enrollmentCategory" class="cn">分类</label><select id="enrollmentCategory"><option value="">全部分类</option>'+labels.map(label=>'<option value="'+e(label)+'">'+e(label)+'（'+enrollmentItems.filter(item=>item.categories.includes(label)).length+'）</option>').join('')+'<option value="__conflict__">分类待核对</option></select><button type="button" class="link" id="clearEnrollment">重置</button></div><p class="summary" id="enrollmentSummary" aria-live="polite"></p><div id="enrollmentResults"></div><nav class="pagination" aria-label="课程目录翻页"><button class="secondary" id="enrollmentPrevious">上一页</button><span id="enrollmentPageLabel"></span><button class="secondary" id="enrollmentNext">下一页</button></nav>';
  const warnings=[generalCatalog.cacheWarning,storageWarning].filter(Boolean);if(warnings.length)error(new Error(warnings.join(' ')));
  const apply=()=>{enrollmentPage=1;enrollmentMatches=filterEnrollmentCatalog(enrollmentItems,document.getElementById('enrollmentKeyword').value,document.getElementById('enrollmentCategory').value);renderEnrollmentResults();};
  document.getElementById('enrollmentSearch').onsubmit=event=>{event.preventDefault();apply();};
  document.getElementById('enrollmentKeyword').oninput=apply;
  document.getElementById('enrollmentCategory').onchange=apply;
  document.getElementById('clearEnrollment').onclick=()=>{document.getElementById('enrollmentKeyword').value='';document.getElementById('enrollmentCategory').value='';apply();};
  document.getElementById('enrollmentPrevious').onclick=()=>{enrollmentPage--;renderEnrollmentResults();};
  document.getElementById('enrollmentNext').onclick=()=>{enrollmentPage++;renderEnrollmentResults();};
  apply();
}
function renderEnrollmentResults(){
  const total=enrollmentMatches.length,pages=Math.max(1,Math.ceil(total/enrollmentPageSize));
  enrollmentPage=Math.min(pages,Math.max(1,enrollmentPage));
  document.getElementById('enrollmentSummary').textContent='找到 '+total+' 门课程'+(document.getElementById('enrollmentCategory').value==='__conflict__'?' · 原表同课程号存在多个分类，需核对':'');
  document.getElementById('enrollmentResults').innerHTML=total?enrollmentMatches.slice((enrollmentPage-1)*enrollmentPageSize,enrollmentPage*enrollmentPageSize).map(item=>'<article class="card enrollment-course"><div class="result-head"><h3>'+e(item.courseName)+'</h3><button class="secondary" disabled aria-label="'+e(item.courseName)+'：选课暂未开放">选课</button></div>'+row('课程号',item.courseCode)+'<div class="enrollment-labels">'+item.categories.map(category=>'<span class="badge">'+e(category)+'</span>').join('')+'</div>'+(item.corrected?'<p class="hint" style="padding-left:0">本机分类更正 · 校方原表：'+e(item.officialCategories.join(' / '))+'</p>':'')+(item.names.length>1?'<p class="hint" style="padding-left:0">原表名称：'+e(item.names.join(' / '))+'</p>':'')+(item.conflict?'<p class="hint" style="padding-left:0;color:#a77a39">分类待核对：校方原表包含多个分类，已保留全部标注。</p>':'')+'</article>').join(''):empty('search','没有匹配的课程','调整课程关键词或分类后再试试');
  document.getElementById('enrollmentPrevious').disabled=enrollmentPage===1;
  document.getElementById('enrollmentNext').disabled=enrollmentPage===pages;
  document.getElementById('enrollmentPageLabel').textContent=enrollmentPage+' / '+pages;
}
`.replace("__CATALOG_LOGIC__", ENROLLMENT_CATALOG_LOGIC)
  .replace("__PUBLIC_CATALOG__", JSON.stringify(GENERAL_EDUCATION_CATALOG).replace(/</g, "\\u003c").replace(/\u2028/g, "\\u2028").replace(/\u2029/g, "\\u2029"));
