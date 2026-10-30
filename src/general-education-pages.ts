import { GENERAL_EDUCATION_DRAG_SCRIPT } from "./general-education-drag.js";

export const GENERAL_EDUCATION_PAGES_SCRIPT = String.raw`
// general-credit-pages-v4 inline editing; general-credit-drag-v1
(() => {
 const $=id=>document.getElementById(id),page=$('generalEducationPage'),overview=$('scoreOverview');if(!page||!overview)return;
 const key='classsche.general-education.categories.v1',native=!!generalCatalog.mobileUpdateAvailable;
 document.documentElement.classList.toggle('general-native',native);
 let overrides={},draft={},storageError='',view='scores',history=[],originalScroll=0,catalogPage=1;
 try{const raw=localStorage.getItem(key);if(raw){const v=JSON.parse(raw);if(!v||typeof v!=='object'||Array.isArray(v))throw Error();overrides=v;}}
 catch(_){storageError='本地分类读取失败，原数据未覆盖，暂时只能查看统计。';}
 const fmt=n=>String(Math.round(n*100)/100),esc=escapeHtml;
 const labels=()=>[...new Set([...(generalCatalog.categories||[]),'辅修',...Object.values(overrides).filter(x=>typeof x==='string'&&x),...Object.values(draft).filter(x=>typeof x==='string'&&x)])];
 const matches=(item,text)=>generalNormalize((item.courseName||'')+' '+(item.courseCode||'')).toLowerCase().includes(generalNormalize(text).toLowerCase());
 function notify(){
  const titles={scores:'成绩查询',credits:'学分统计',classification:'学分统计',catalog:'课程分类表'};document.title=titles[view];$('generalEducationTitle').textContent=titles[view];
  const action=$('generalToolbarAction');action.textContent=view==='classification'?'保存修改':view==='catalog'?'更新分类表':'修改分类';action.hidden=view==='scores'||view==='catalog'&&!native;
  if(native)location.href='classsche://score-section/'+view;
 }
 function options(){const select=$('generalCatalogFilter'),value=select.value;select.innerHTML='<option value="">全部分类</option><option value="__conflict__">分类待核对</option>'+labels().map(x=>'<option value="'+esc(x)+'">'+esc(x)+'</option>').join('');select.value=value;}
 const row=(item,editing=false)=>'<article class="general-course'+(editing?' general-draggable-course':'')+'"'+(editing?' data-course-key="'+esc(item.key)+'" data-course-category="'+esc(item.category)+'"':'')+'><div class="general-course-heading"><h3>'+esc(item.courseName||'未命名课程')+'</h3>'+(editing?'<button type="button" class="general-drag-handle" aria-label="拖动 '+esc(item.courseName)+' 到其他分类"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 6h14M5 12h14M5 18h14"/></svg></button>'+(item.category?'<button type="button" class="general-remove" data-general-remove="'+esc(item.key)+'" aria-label="移至待分类 '+esc(item.courseName)+'">×</button>':''):'')+'</div><p>'+esc(item.courseCode||'无课程代码')+' · '+esc(item.semester||'')+' · '+esc(item.score||'无成绩')+' · '+esc(item.credits===null?'学分格式无效':fmt(item.credits)+' 学分')+'</p>'+(editing&&!item.category?'<label>分类<select class="general-category-input" data-general-key="'+esc(item.key)+'"><option value="__pending__">待分类</option>'+labels().map(x=>'<option value="'+esc(x)+'">'+esc(x)+'</option>').join('')+'</select></label>':'')+'</article>';
 function renderStats(){
  const editing=view==='classification',data=computeGeneralEducation(rawScores,generalCatalog,editing?draft:overrides),query=$('generalSearch').value;
  $('generalTotals').innerHTML=[{category:'总学分',credits:data.totalCredits},...data.groups].map(g=>'<div><span>'+esc(g.category)+'</span><strong>'+fmt(g.credits)+'<small> 学分</small></strong></div>').join('');
  $('generalCreditHint').textContent='已获 '+fmt(data.totalCredits)+' 学分，其中 '+fmt(data.unmatchedCredits)+' 学分待分类。仅计已通过课程，按课程号去重，不影响 GPA。';
  $('generalCatalogNotice').textContent=storageError||generalCatalog.cacheWarning||((generalCatalog.cacheSource==='mobile'?'本机分类表 · '+generalCatalog.capturedOn:'校方课程分类表（2022版）')+' · '+generalCatalog.courses.length+' 条。点击查看与搜索课程分类 ›');
  $('generalCategoryList').hidden=editing;$('generalUnclassified').hidden=editing;$('generalUnearned').hidden=editing;$('generalEditRows').hidden=!editing;$('generalEditMessage').hidden=!editing;
  if(editing){
   const items=data.items.filter(x=>matches(x,query));
   $('generalEditRows').innerHTML=[...labels(),''].map(category=>{const courses=items.filter(x=>x.category===category);return '<section class="general-edit-group" data-category-drop="'+esc(category)+'" aria-label="'+esc(category||'待分类')+'分类栏"><h2><span>'+esc(category||'待分类')+'</span><small>'+courses.length+' 门</small></h2>'+courses.map(x=>row(x,true)).join('')+(!courses.length?'<p class="general-drop-empty">拖动课程到这里</p>':'')+'</section>';}).join('');
  }else{
   $('generalCategoryList').innerHTML=data.groups.map(g=>({...g,items:g.items.filter(x=>matches(x,query))})).filter(g=>g.items.length).map(g=>'<details class="general-group" open><summary><span>'+esc(g.category)+'</span><strong>'+fmt(g.credits)+' 学分</strong></summary>'+g.items.map(x=>row(x)).join('')+'</details>').join('');
   const pending=data.unclassified.filter(x=>matches(x,query)),unearned=data.unearned.filter(x=>matches(x,query));
   $('generalUnclassified').innerHTML=pending.length?'<h2>待分类</h2>'+pending.map(x=>row(x)).join(''):'';
   $('generalUnearned').innerHTML=unearned.length?'<details class="general-group"><summary>未计入学分 · '+unearned.length+' 门</summary>'+unearned.map(x=>row(x)).join('')+'</details>':'';
  }
  $('generalNoResults').hidden=data.items.some(x=>matches(x,query));
 }
 function renderEditor(){finishCourseDrag(false);renderStats();}
 function renderCatalog(){
  const items=filterEnrollmentCatalog(buildEnrollmentCatalog(generalCatalog,overrides),$('generalCatalogSearch').value,$('generalCatalogFilter').value),pages=Math.max(1,Math.ceil(items.length/30));catalogPage=Math.max(1,Math.min(pages,catalogPage));
  $('generalCatalogCount').textContent='找到 '+items.length+' 门课程 · 第 '+catalogPage+' / '+pages+' 页';
  $('generalCatalogRows').innerHTML=items.slice((catalogPage-1)*30,catalogPage*30).map(x=>'<article class="general-course"><h3>'+esc(x.courseName)+'</h3><p>'+esc(x.courseCode)+' · '+esc(x.categories.join(' / '))+'</p></article>').join('')||'<p class="general-empty">没有找到匹配课程</p>';
  $('generalCatalogPrev').disabled=catalogPage===1;$('generalCatalogNext').disabled=catalogPage===pages;
 }
 function render(){page.hidden=view==='scores';overview.hidden=view!=='scores';$('generalStatsView').hidden=!['credits','classification'].includes(view);$('generalCatalogView').hidden=view!=='catalog';if(['credits','classification'].includes(view))renderStats();if(view==='catalog')renderCatalog();}
 function edit(){if(view==='scores')navigate('credits');if(view!=='credits')return;draft={...overrides};view='classification';$('generalEditMessage').textContent=storageError||'拖动三条杠或长按课程调整分类，删除后移至待分类；保存修改后生效。';render();notify();}
 function discard(){if(view!=='classification')return true;if(JSON.stringify(draft)!==JSON.stringify(overrides)&&!confirm('分类修改尚未保存，放弃修改？'))return false;view='credits';draft={...overrides};return true;}
 function navigate(next){finishCourseDrag(false);if(next===view)return;if(next==='classification'){edit();return;}if(!discard())return;if(view==='scores')originalScroll=scrollY;history.push(view);view=next;if(view==='catalog')options();render();notify();window.scrollTo(0,0);}
 function back(){if(courseDrag){finishCourseDrag(false);return true;}if(view==='scores')return false;if(view==='classification'){if(discard()){render();notify();}return true;}view=history.pop()||'scores';render();notify();window.scrollTo(0,view==='scores'?originalScroll:0);return true;}
 function save(){finishCourseDrag(false);if(storageError){$('generalEditMessage').textContent=storageError;return;}try{localStorage.setItem(key,JSON.stringify(draft));overrides={...draft};view='credits';render();notify();}catch(_){$('generalEditMessage').textContent='分类保存失败，请重试；原数据未更改。';}}
 function action(){if(view==='classification')save();else if(view==='catalog'&&native)location.href='classsche://general-education/refresh';else if(view==='credits')edit();}
 $('openGeneralEducation').onclick=()=>navigate('credits');$('closeGeneralEducation').onclick=back;$('generalToolbarAction').onclick=action;$('openGeneralCatalog').onclick=()=>navigate('catalog');$('generalSearch').oninput=()=>{finishCourseDrag(false);renderStats();};
 $('generalEditRows').onchange=event=>{const select=event.target.closest('[data-general-key]');if(!select||view!=='classification')return;draft[select.dataset.generalKey]=select.value==='__pending__'?'':select.value;renderEditor();};
 $('generalEditRows').addEventListener('click',event=>{const button=event.target.closest('[data-general-remove]');if(!button||view!=='classification')return;draft[button.dataset.generalRemove]='';renderEditor();$('generalEditMessage').textContent='已移至待分类，保存修改后生效。';});
 $('generalCatalogSearch').oninput=$('generalCatalogFilter').onchange=()=>{catalogPage=1;renderCatalog();};$('generalCatalogPrev').onclick=()=>{catalogPage--;renderCatalog();};$('generalCatalogNext').onclick=()=>{catalogPage++;renderCatalog();};
 __GENERAL_EDUCATION_DRAG__
 document.addEventListener('keydown',event=>{if(event.key==='Escape')back();});
 window.GeneralEducationPage={back,open:()=>navigate('credits'),edit,catalog:()=>navigate('catalog'),toolbarAction:action,state:()=>view,updateCatalog:catalog=>{if(!catalog||!Array.isArray(catalog.courses)||!catalog.courses.length||!Array.isArray(catalog.categories))return false;generalCatalog={...catalog,mobileUpdateAvailable:generalCatalog.mobileUpdateAvailable};options();render();return true;}};
})();`.replace('__GENERAL_EDUCATION_DRAG__',GENERAL_EDUCATION_DRAG_SCRIPT);

export const GENERAL_EDUCATION_STYLE = `
  [hidden]{display:none!important}
  .general-entry,.general-back,.general-action,.general-source{border:0;border-radius:8px;background:var(--sky);color:white;padding:10px 14px;font:13px var(--font-cn);cursor:pointer;min-height:44px}
  .general-entry{width:100%;margin-top:12px;display:flex;justify-content:space-between;align-items:center}
  .general-page{padding:12px 12px 20px;font:13px var(--font-ui)}
  .general-web-toolbar{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:6px 0 12px}.general-web-toolbar h1{font:16px var(--font-cn);margin:0;flex:1;text-align:center}.general-back{font-size:25px;padding:0 14px}.general-action,.general-source{background:#e3edf9;color:#507da9}.general-native .general-web-toolbar{display:none}
  .general-panel,.general-group,.general-unclassified{border:1px solid var(--line);border-radius:8px;background:white;margin:0 0 10px;padding:12px}
  .general-totals{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.general-totals>div{background:#f5f8fc;border-radius:7px;padding:10px}.general-totals span{font:12px var(--font-cn);display:block;color:#748397;margin-bottom:5px}.general-totals strong{display:block;color:#5987b3;font-size:18px;font-weight:500}.general-totals small{font-size:11px;font-weight:400}
  .general-hint,.general-course-note,.general-empty{font-size:12px;line-height:1.7;color:#8e99aa}.general-hint{margin:10px 0}.general-notice{border:0;text-align:left;width:100%;cursor:pointer;border-radius:8px;background:#fff8e8;color:#936a29;font-size:12px;line-height:1.7;padding:10px 12px;margin:0 0 10px}.general-source{display:inline-flex;align-items:center}
  .general-group{padding:0;overflow:hidden}.general-group summary{cursor:pointer;padding:12px;font:14px var(--font-cn);color:#597795;display:flex;justify-content:space-between;gap:8px}.general-group summary strong{font-weight:500;white-space:nowrap}
  .general-course{padding:12px;border-top:1px solid var(--line);overflow-wrap:anywhere}.general-course h3{font:14px var(--font-cn);margin:0 0 7px}.general-course p{font-size:12px;line-height:1.7;margin:4px 0}.general-course label{display:flex;align-items:center;gap:12px;font-size:13px;margin-top:8px}
  .general-category-input,.general-search,.general-filter{min-width:0;border:1px solid #dce5ef;border-radius:7px;padding:10px;color:var(--ink);font:13px var(--font-ui);background:white;min-height:44px;box-sizing:border-box}.general-category-input{flex:1}.general-search{width:100%;margin:0 0 10px;border-radius:0}.general-filter{width:100%;margin-bottom:10px}.general-unclassified{padding:0}.general-unclassified:empty{display:none}.general-unclassified h2{font:15px var(--font-cn);padding:12px;margin:0}.general-empty{padding:12px;margin:0}.general-pagination{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:12px}.general-pagination button:disabled{opacity:.4}
  .general-page button:focus-visible,.general-page input:focus-visible,.general-page select:focus-visible{outline:2px solid #5987b3;outline-offset:2px}
  #generalEditRows{border:0;background:transparent;overflow:visible}.general-edit-group{background:white;border:1px solid var(--line);border-radius:8px;margin-bottom:12px;min-height:80px;transition:background .12s,border-color .12s}.general-edit-group>h2{display:flex;justify-content:space-between;align-items:center;gap:10px;font:14px var(--font-cn);color:#597795;margin:0;padding:12px;background:#edf4fb;border-radius:8px 8px 0 0}.general-edit-group>h2 small{font:12px var(--font-ui);color:#8e99aa}.general-drop-empty{padding:18px 12px;margin:0;color:#9aa9b9;font-size:12px}.general-course-heading{display:flex;gap:10px;align-items:center}.general-course-heading h3{flex:1;margin:0;min-width:0}.general-drag-handle{display:flex;align-items:center;justify-content:center;flex-shrink:0;min-width:44px;min-height:44px;padding:10px;border:0;border-radius:7px;background:transparent;color:#8199b2;cursor:grab;touch-action:none}.general-drag-handle svg{width:22px;height:22px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round}.general-draggable-course{user-select:none;-webkit-user-select:none;-webkit-touch-callout:none}.general-drag-source{opacity:.35}.general-drop-active{border-color:#72a8df;box-shadow:0 0 0 2px #72a8df;background:#f0f7ff}.general-drag-ghost{position:fixed;z-index:1000;pointer-events:none;box-sizing:border-box;border:1px solid #72a8df;border-radius:8px;background:#f4f9ff;color:#526b84;padding:14px;font:14px var(--font-cn);box-shadow:0 5px 18px #426d9c40;overflow-wrap:anywhere}.general-dragging,.general-dragging *{cursor:grabbing!important;user-select:none!important}
  .general-remove{min-width:44px;min-height:44px;background:transparent;border:0;color:#b96c65;font-size:22px;cursor:pointer}
  @media(min-width:600px){.general-totals{grid-template-columns:repeat(3,minmax(0,1fr))}}
`;


export const GENERAL_EDUCATION_HTML = `
<main id="generalEducationPage" class="general-page" hidden>
<header class="general-web-toolbar"><button type="button" id="closeGeneralEducation" class="general-back" aria-label="返回">‹</button><h1 id="generalEducationTitle">学分统计</h1><button type="button" id="generalToolbarAction" class="general-action">修改分类</button></header>
<section id="generalStatsView"><section class="general-panel"><div id="generalTotals" class="general-totals"></div><p id="generalCreditHint" class="general-hint"></p></section><button type="button" id="openGeneralCatalog" class="general-notice"><span id="generalCatalogNotice"></span></button><input id="generalSearch" class="general-search" placeholder="搜索已修课程名称或课程号" aria-label="搜索已修课程"><p id="generalEditMessage" class="general-hint" role="status" hidden></p><section id="generalEditRows" hidden></section><section id="generalCategoryList"></section><section id="generalUnclassified" class="general-unclassified"></section><section id="generalUnearned"></section><p id="generalNoResults" class="general-empty" hidden>没有符合条件的课程</p></section>
<section id="generalCatalogView" hidden><input id="generalCatalogSearch" class="general-search" placeholder="搜索指定课程名称或课程号" aria-label="搜索分类表课程"><select id="generalCatalogFilter" class="general-filter" aria-label="筛选分类表类别"></select><p id="generalCatalogCount" class="general-hint"></p><section id="generalCatalogRows" class="general-group"></section><nav class="general-pagination"><button id="generalCatalogPrev" class="general-action">上一页</button><button id="generalCatalogNext" class="general-action">下一页</button></nav></section></main>`;
