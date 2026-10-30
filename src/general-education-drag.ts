/** Touch long-press keeps ordinary list scrolling; handles start dragging immediately. */
export const GENERAL_EDUCATION_DRAG_SCRIPT = String.raw`
  let courseDrag=null,dragFrame=0,suppressDragClickUntil=0;
  function dropZone(x,y){return document.elementFromPoint(x,y)?.closest('#generalEditRows [data-category-drop]');}
  function highlightDrop(){
    document.querySelectorAll('.general-drop-active').forEach(node=>node.classList.remove('general-drop-active'));
    const zone=dropZone(courseDrag.x,courseDrag.y);if(zone)zone.classList.add('general-drop-active');
    courseDrag.target=zone;
  }
  function dragScroll(){
    if(!courseDrag?.active)return;
    const top=native?0:Math.max(0,$('generalEducationPage').querySelector('.general-web-toolbar').getBoundingClientRect().bottom);
    const edge=64,y=courseDrag.y,h=window.innerHeight;
    const amount=y<top+edge?-Math.min(12,(top+edge-y)/4):y>h-edge?Math.min(12,(y-h+edge)/4):0;
    if(amount){window.scrollBy(0,amount);highlightDrop();}
    dragFrame=requestAnimationFrame(dragScroll);
  }
  function startCourseDrag(){
    const drag=courseDrag;if(!drag||!drag.card.isConnected||view!=='classification')return;
    drag.active=true;drag.card.classList.add('general-drag-source');
    const ghost=document.createElement('div');ghost.className='general-drag-ghost';ghost.textContent=drag.card.querySelector('h3').textContent;
    ghost.style.width=Math.min(drag.card.getBoundingClientRect().width,280)+'px';document.body.appendChild(ghost);drag.ghost=ghost;
    document.body.classList.add('general-dragging');moveCourseDrag(drag.x,drag.y);
    $('generalEditMessage').textContent='拖到目标分类栏后松开，再点击“保存修改”。';
    dragFrame=requestAnimationFrame(dragScroll);
  }
  function moveCourseDrag(x,y){
    const drag=courseDrag;if(!drag)return;drag.x=x;drag.y=y;
    if(!drag.active){if(Math.hypot(x-drag.startX,y-drag.startY)>9)finishCourseDrag(false);return;}
    drag.ghost.style.left=Math.max(4,Math.min(innerWidth-drag.ghost.offsetWidth-4,x-30))+'px';
    drag.ghost.style.top=Math.max(4,Math.min(innerHeight-48,y+14))+'px';highlightDrop();
  }
  function finishCourseDrag(commit){
    const drag=courseDrag;if(!drag)return;
    clearTimeout(drag.timer);cancelAnimationFrame(dragFrame);courseDrag=null;
    drag.ghost?.remove();drag.card.classList.remove('general-drag-source');document.body.classList.remove('general-dragging');
    document.querySelectorAll('.general-drop-active').forEach(node=>node.classList.remove('general-drop-active'));
    if(!drag.active)return;
    suppressDragClickUntil=Date.now()+400;
    if(commit&&drag.target?.isConnected){
      const category=drag.target.dataset.categoryDrop;
      if(category!==drag.card.dataset.courseCategory){
        draft[drag.key]=category;renderEditor();
        $('generalEditMessage').textContent='已移至“'+(category||'待分类')+'”，点击“保存修改”保存在本机。';
      }else $('generalEditMessage').textContent='课程分类未改变。';
    }else $('generalEditMessage').textContent='已取消移动，分类未改变。';
  }
  function prepareCourseDrag(target,x,y,kind){
    if(courseDrag||view!=='classification')return;
    const handle=target.closest('.general-drag-handle'),card=target.closest('[data-course-key]');
    if(!card||(!handle&&target.closest('input,select,button,a,label')))return;
    courseDrag={card,key:card.dataset.courseKey,startX:x,startY:y,x,y,kind,active:false,target:null};
    if(handle)startCourseDrag();else courseDrag.timer=setTimeout(startCourseDrag,450);
  }
  $('generalEditRows').addEventListener('pointerdown',event=>{
    if(event.pointerType==='touch'||event.button!==0)return;
    prepareCourseDrag(event.target,event.clientX,event.clientY,'pointer');if(courseDrag?.active)event.preventDefault();
  });
  document.addEventListener('pointermove',event=>{if(courseDrag?.kind==='pointer'){moveCourseDrag(event.clientX,event.clientY);if(courseDrag?.active)event.preventDefault();}},{passive:false});
  document.addEventListener('pointerup',event=>{if(courseDrag?.kind==='pointer'){if(courseDrag.active)highlightDrop();finishCourseDrag(true);}});
  document.addEventListener('pointercancel',()=>{if(courseDrag?.kind==='pointer')finishCourseDrag(false);});
  $('generalEditRows').addEventListener('touchstart',event=>{
    if(event.touches.length!==1){finishCourseDrag(false);return;}
    const touch=event.touches[0];prepareCourseDrag(event.target,touch.clientX,touch.clientY,'touch');
    if(courseDrag?.active&&event.cancelable)event.preventDefault();
  },{passive:false});
  document.addEventListener('touchmove',event=>{
    if(courseDrag?.kind!=='touch')return;
    if(event.touches.length!==1){finishCourseDrag(false);return;}
    if(courseDrag.active&&event.cancelable)event.preventDefault();
    const touch=event.touches[0];moveCourseDrag(touch.clientX,touch.clientY);
  },{passive:false});
  document.addEventListener('touchend',event=>{if(courseDrag?.kind==='touch'){if(courseDrag.active&&event.cancelable)event.preventDefault();finishCourseDrag(true);}},{passive:false});
  document.addEventListener('touchcancel',()=>finishCourseDrag(false));
  $('generalEditRows').addEventListener('contextmenu',event=>{if(event.target.closest('[data-course-key]'))event.preventDefault();});
  $('generalEditRows').addEventListener('click',event=>{if(Date.now()<suppressDragClickUntil){event.preventDefault();event.stopImmediatePropagation();}},true);
  window.addEventListener('blur',()=>finishCourseDrag(false));
  document.addEventListener('visibilitychange',()=>{if(document.hidden)finishCourseDrag(false);});
  document.addEventListener('keydown',event=>{if(event.key==='Escape'&&courseDrag){finishCourseDrag(false);event.stopImmediatePropagation();}},true);
`;
