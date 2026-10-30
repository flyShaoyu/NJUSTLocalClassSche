import test from 'node:test';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { renderCampusToolsPage } from '../dist/campus-tools-ui.js';
import { renderTimetablePage } from '../dist/timetable-ui.js';

test('校园页面：日程自定义时间、查询与失败恢复、教师入口和收藏', { timeout: 60000 }, async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 393, height: 800 } });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  async function open(tool) {
    await page.goto('about:blank');
    await page.evaluate(tool => {
      window.calls = [];
      window.favoriteBooks = [];
      window.failRooms = false;
      const book = { id:'b1', title:'活着',author:'余华',publisher:'作家出版社',year:'2012',copies:2,available:1,callNumber:'I247' };
      window.CampusBridge = {
        request(id, action, raw) {
          const data = JSON.parse(raw); window.calls.push({action,data});
          let result = {};
          switch(action) {
            case 'bootstrap': result = {page:tool,semester:'2026-2027-1',semesters:['2026-2027-1'],week:9,weekday:3,today:'2026-10-28',week1Monday:'2026-08-31'}; break;
            case 'schedules.list': result = {items:[]}; break;
            case 'schedules.save': result = {item:data}; break;
            case 'rooms.filters': result = {buildings:[{id:'z',label:'实验楼'},{id:'b',label:'四工'},{id:'two',label:'Ⅱ教学楼'},{id:'one',label:'第一教学楼'},{id:'three',label:'三教'}],periods:Array.from({length:13},(_,i)=>({id:String(i+1)}))}; break;
            case 'rooms.query': result = {summary:'第9周 周三 · 1—3节',rooms:[{id:'z',name:'实验楼101',capacity:'80'},{id:'r',name:'Ⅳ-A101',capacity:'60'},{id:'a',name:'Ⅰ-A102',capacity:'30'}]}; break;
            case 'favorites.list': result = {books:window.favoriteBooks}; break;
            case 'favorites.toggle': window.favoriteBooks = [data.book]; result = {books:window.favoriteBooks}; break;
            case 'library.search': result = {total:21,page:data.page,books:[book]}; break;
            case 'library.detail': result = {fields:[{label:'ISBN',value:'978-1'}],holdings:[{location:'图书馆一楼',status:'可借',callNumber:'I247'}]}; break;
            case 'library.status': result = {cards:Array.from({length:4},(_,i)=>({label:'模拟状态'+(i+1),value:String(i)}))}; break;
            case 'courses.search': result = {total:1,courses:[{courseName:'控制工程基础',teacher:'马立丰',classroom:'Ⅳ-B207',weeks:'3,5-15周',weekday:'星期一',periods:'6-7节'}]}; break;
            case 'teachers.search': result = {teachers:[{id:'t',name:'马立丰'}]}; break;
          }
          const packet = action==='rooms.query' && window.failRooms ? {ok:false,error:'网页连接超时',code:'QUERY_FAILED'} : {ok:true,data:result};
          setTimeout(()=>window.CampusCallbacks.receive(id,JSON.stringify(packet)),15);
        },
        showTimetable(){window.shownTimetable=true;},
        openTeacher(id,name,semester){window.teacherOpened={id,name,semester};},
        openUrl(url){window.openedUrl=url;},
        openLogin(){window.libraryLoginOpened=true;}
      };
    }, tool);
    await page.setContent(renderCampusToolsPage());
    await page.evaluate(()=>window.CampusUI.start());
    await page.waitForFunction(() => !document.getElementById('root').textContent.includes('正在准备页面'), null, {timeout:5000}).catch(async e=>{throw new Error(JSON.stringify({errors,body:await page.locator('#root').innerText(),status:await page.locator('#status').innerText()}),{cause:e});});
  }
  try {
    await open('schedule');
    await page.locator('#addSchedule').click();
    await page.locator('[name=title]').fill('午间讨论 <img src=x>');
    await page.locator('[name=startTime]').fill('10:20');
    await page.locator('[name=endTime]').fill('13:10');
    await page.locator('#selectAllWeeks').click();
    assert.equal(await page.locator('#weekPicks .active').count(),20);
    await page.locator('[data-week=\"4\"]').click();
    assert.equal(await page.locator('[name=weeksText]').inputValue(),'1-3,5-20');
    await page.locator('[name=weeksText]').fill('1,3,5-8');
    await page.locator('[name=notes]').fill('带上资料\n课间继续');
    assert.equal(await page.locator('#weekPicks .active').count(),6);
    await page.locator('#saveSchedule').dblclick({force:true});
    await page.waitForFunction(()=>window.shownTimetable);
    const saves = await page.evaluate(()=>window.calls.filter(x=>x.action==='schedules.save'));
    assert.equal(saves.length,1);
    assert.equal(saves[0].data.location,'');
    assert.equal(saves[0].data.startTime,'10:20');
    assert.equal(saves[0].data.endTime,'13:10');
    assert.equal(saves[0].data.notes,'带上资料\n课间继续');
    assert.equal(await page.locator('img').count(),0);

    await open('rooms');
    await page.locator('[data-building]').first().waitFor();
    assert.deepEqual(await page.locator('[data-building]').allTextContents(),['全部','第一教学楼','Ⅱ教学楼','三教','四工','实验楼']);
    await page.locator('[data-building=b]').click();
    await page.locator('[data-start="6"]').click();
    assert.equal(await page.locator('#periodChips .active').count(),2);
    assert.equal(await page.locator('[data-start="14"]').count(),0);
    await page.locator('#queryRooms').click();
    await page.locator('.room-name').first().waitFor();
    assert.deepEqual(await page.locator('.room-name').allTextContents(),['Ⅰ-A102','Ⅳ-A101','实验楼101']);
    assert.equal(await page.locator('.room-name small').count(),0);
    assert.deepEqual(await page.evaluate(()=>window.calls.find(x=>x.action==='rooms.query').data.periodRanges),[{startPeriod:1,endPeriod:3},{startPeriod:6,endPeriod:7}]);
    await page.evaluate(()=>window.failRooms=true);
    await page.locator('#queryRooms').click();
    await page.waitForFunction(()=>document.getElementById('status').textContent.includes('网页连接超时'));
    assert.equal(await page.locator('.room-name').count(),3);
    await page.locator('[data-start="1"]').click();await page.locator('[data-start="6"]').click();
    const before=await page.evaluate(()=>window.calls.filter(x=>x.action==='rooms.query').length);await page.locator('#queryRooms').click();
    assert.equal(await page.evaluate(()=>window.calls.filter(x=>x.action==='rooms.query').length),before);
    assert.match(await page.locator('#status').innerText(),/至少选择一个时段/);
    assert.equal(await page.locator('#queryRooms').isEnabled(),true);

    await open('library');
    assert.equal(await page.locator('#bookSearch').evaluate(el=>el.getBoundingClientRect().top),await page.locator('[aria-label=图书功能]').evaluate(el=>el.getBoundingClientRect().bottom));
    assert.equal(await page.locator('#bookSearch button').evaluate(el=>getComputedStyle(el).backgroundColor),'rgba(0, 0, 0, 0)');
    await page.locator('#bookKeyword').fill('活着');
    await page.locator('#bookSearch button').click();
    await page.locator('[data-star]').waitFor();
    await page.locator('[data-star]').click();
    await page.locator('.star.on').waitFor();
    await page.locator('[data-book]').click();
    await page.getByText('图书馆一楼',{exact:true}).waitFor();
    assert.equal(await page.evaluate(()=>window.CampusUI.back()),true);
    await page.locator('#nextBooks').click();
    await page.waitForFunction(()=>document.querySelector('.summary')?.textContent.includes('第 2 页'));
    await page.locator('[data-library-tab=favorites]').click();
    assert.match(await page.locator('#libraryContent').innerText(),/活着/);
    assert.equal(await page.locator('[data-library-tab=loans]').count(),0);
    assert.equal(await page.locator('.book-card').first().evaluate(el=>el.getBoundingClientRect().height<220),true);
    assert.equal(await page.evaluate(()=>window.calls.some(x=>x.action==='library.loans')),false);
    await page.evaluate(()=>{window.libraryStorageAccesses=0;Object.defineProperty(window,'localStorage',{configurable:true,get(){window.libraryStorageAccesses++;throw new Error('Account state must not use local storage');}});});
    await page.locator('[data-library-tab=account]').click();
    await page.locator('.library-account-card').first().waitFor();
    assert.equal(await page.locator('.library-account-card').count(),4);
    assert.equal(await page.getByText('独立登录',{exact:true}).count(),0);
    assert.equal(await page.locator('#libraryContent h2').count(),0);
    assert.equal(await page.locator('#refreshLibraryStatus svg').count(),1);
    await page.locator('#libraryLogin').click();assert.equal(await page.evaluate(()=>window.libraryLoginOpened),true);
    await page.locator('#openLibraryHome').click();assert.equal(await page.evaluate(()=>window.openedUrl),'http://202.119.83.14:8080/uopac/reader/redr_info.php');
    assert.equal(await page.evaluate(()=>window.libraryStorageAccesses),0);

    await open('courses');
    await page.locator('#courseKeyword').fill('控制工程');
    await page.locator('#courseSearch button').click();
    await page.locator('[data-course-teacher]').click();
    await page.waitForFunction(()=>window.teacherOpened);
    assert.deepEqual(await page.evaluate(()=>window.teacherOpened),{id:'t',name:'马立丰',semester:'2026-2027-1'});
    assert.deepEqual(errors,[]);
  } finally { await browser.close(); }
});

test('个人课表显示真实日程时间与备注，文本安全，教师课表不出现日程编辑', { timeout: 30000 }, async () => {
  const browser = await chromium.launch({headless:true});
  try {
    const page = await browser.newPage({viewport:{width:393,height:800}});
    page.on('pageerror',e=>console.error('Timetable script:',e.message));
    page.setDefaultTimeout(5000);
    const course = {courseName:'讨论 <img src=x onerror=alert(1)>',weekday:'星期三',periods:'3-5节',weeks:'1-20周',classroom:'',teacher:'',courseCode:'local:abc',startTime:'10:20',endTime:'13:10',notes:'资料\n<备注>',courseType:'本地日程'};
    await page.setContent(renderTimetablePage([course]));
    await page.locator('.course-card').first().click();
    assert.equal(await page.locator('#sheetPeriodLine').innerText(),'10:20-13:10');
    assert.equal(await page.locator('#sheetScheduleNotes').innerText(),'资料\n<备注>');
    assert.equal(await page.locator('#local-schedule-edit').getAttribute('href'),'classsche://schedule/edit?id=abc');
    assert.ok((await page.locator('#local-schedule-edit').boundingBox()).height<=32);
    assert.equal(await page.locator('#local-schedule-edit').evaluate(el=>getComputedStyle(el).backgroundColor),'rgba(0, 0, 0, 0)');
    assert.equal(await page.locator('#local-schedule-edit svg').count(),1);
    assert.equal(await page.locator('img').count(),0);
    assert.equal(await page.locator('#teacherRow').isVisible(),false);
    assert.equal(await page.locator('#sheetWeeksLabel').innerText(),'（周数）');
    await page.goto('about:blank');
    await page.setContent(renderTimetablePage([{...course,courseCode:'teacher',startTime:undefined,endTime:undefined,courseType:'教师课表'}]));
    await page.locator('.course-card').first().click();
    assert.equal(await page.locator('#local-schedule-edit').isVisible(),false);
    assert.equal(await page.locator('#scheduleNotesRow').isVisible(),false);
  } finally {await browser.close();}
});


test('日程连续周数合并，考试座位单列且无场次备注，课程周数标签保持', {timeout:30000},async()=>{
 const browser=await chromium.launch({headless:true});try{
  const page=await browser.newPage({viewport:{width:393,height:800}});
  const base={weekday:'星期五',periods:'3-5节',classroom:'模拟地点',weeks:'1,2,3,9,10,11,12周',rawText:''};
  await page.setContent(renderTimetablePage([{...base,courseName:'模拟日程',courseCode:'local:range',courseType:'本地日程',notes:''}]));
  await page.locator('.course-card').first().click();
  assert.equal(await page.locator('#sheetWeeks').innerText(),'1-3,9-12（周）');
  assert.equal(await page.locator('#sheetWeeksLabel').innerText(),'（周数）');
  assert.equal(await page.locator('#scheduleNotesRow').isVisible(),false);
  await page.goto('about:blank');await page.setContent(renderTimetablePage([{...base,courseName:'模拟考试',courseCode:'exam:test',courseType:'考试',seatNumber:'18',startTime:'10:20',endTime:'12:00'}]));
  await page.locator('.course-card').first().click();
  assert.equal(await page.locator('#sheetSeatNumber').innerText(),'18');
  assert.equal(await page.locator('#seatNumberRow').isVisible(),true);
  assert.equal(await page.locator('#seatNumberRow').evaluate(el=>!!el.previousElementSibling.querySelector('#sheetRoom')),true);
  assert.equal(await page.locator('#seatNumberRow .sheet-note').innerText(),'（座位号）');
  assert.equal(await page.locator('#sheetWeeksLabel').innerText(),'（周数）');
  assert.equal(await page.locator('#scheduleNotesRow').isVisible(),false);
  await page.goto('about:blank');await page.setContent(renderTimetablePage([{...base,courseName:'课程',courseCode:'official'}]));await page.locator('.course-card').first().click();
  assert.equal(await page.locator('#sheetWeeksLabel').innerText(),'（课程周数）');assert.equal(await page.locator('#seatNumberRow').isVisible(),false);
 }finally{await browser.close();}
});


test('图书搜索保留最新请求，串行避开原生操作冲突，旧结果和旧错误不覆盖新搜索',{timeout:30000},async()=>{
 const browser=await chromium.launch({headless:true});try{
  const page=await browser.newPage({viewport:{width:393,height:800}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.evaluate(()=>{window.queries=[];window.respond=(index,fail=false)=>{const q=window.queries[index];window.CampusCallbacks.receive(q.id,fail?{ok:false,error:'旧查询失败'}:{ok:true,data:{total:1,page:q.page,books:[{id:q.keyword,title:q.keyword,author:'模拟作者',copies:1,available:1}]}});};window.CampusBridge={request(id,action,raw){const data=JSON.parse(raw);if(action==='library.search'){window.queries.push({id,...data});return;}setTimeout(()=>window.CampusCallbacks.receive(id,{ok:true,data:action==='bootstrap'?{page:'library'}:{books:[]}}),0);}};});
  await page.setContent(renderCampusToolsPage());await page.evaluate(()=>window.CampusUI.start());await page.locator('#bookKeyword').waitFor();
  const search=async keyword=>{await page.locator('#bookKeyword').fill(keyword);await page.locator('#bookSearch').evaluate(el=>el.requestSubmit());};
  await search('旧书');await search('中间书');await search('新书');
  assert.equal(await page.evaluate(()=>window.queries.length),1);
  await page.evaluate(()=>window.respond(0));await page.waitForFunction(()=>window.queries.length===2);
  assert.equal(await page.evaluate(()=>window.queries[1].keyword),'新书');assert.doesNotMatch(await page.locator('#bookResults').innerText(),/旧书/);
  await page.evaluate(()=>window.respond(1));await page.locator('.book-card h3').waitFor();assert.equal(await page.locator('.book-card h3').innerText(),'新书');
  await search('重复书');await search('重复书');assert.equal(await page.evaluate(()=>window.queries.length),3);await page.evaluate(()=>window.respond(2));
  await page.waitForFunction(()=>document.querySelector('.book-card h3')?.textContent==='重复书');assert.equal(await page.evaluate(()=>window.queries.length),3);
  await search('会失败的旧请求');await search('恢复书');await page.evaluate(()=>window.respond(3,true));await page.waitForFunction(()=>window.queries.length===5);await page.evaluate(()=>window.respond(4));
  await page.waitForFunction(()=>document.querySelector('.book-card h3')?.textContent==='恢复书');assert.equal(await page.locator('#status').innerText(),'');assert.equal(await page.locator('#bookSearch button').innerText(),'搜索');
  // Latest failure keeps visible results, permits manual retry, and does not become an empty success.
  await search('失败书');await page.evaluate(()=>window.respond(5,true));await page.waitForFunction(()=>document.getElementById('status').textContent.includes('旧查询失败'));
  assert.equal(await page.locator('.book-card h3').innerText(),'恢复书');await search('失败书');await page.evaluate(()=>window.respond(6));await page.waitForFunction(()=>document.querySelector('.book-card h3')?.textContent==='失败书');
  assert.deepEqual(errors,[]);
 }finally{await browser.close();}
});


test('小屏重叠日程：编辑无背景小按钮，详情可滚动且底部切换圆点可见可点击',{timeout:30000},async()=>{
 const browser=await chromium.launch({headless:true});try{
  const page=await browser.newPage({viewport:{width:393,height:640}});page.setDefaultTimeout(6000);
  const base={weekday:'星期一',periods:'1-3节',weeks:'1-20周',courseType:'本地日程',startTime:'08:00',endTime:'10:00',classroom:'模拟地点',notes:'模拟说明'.repeat(100)};
  await page.setContent(renderTimetablePage([{...base,courseName:'日程甲',courseCode:'local:a'},{...base,courseName:'日程乙',courseCode:'local:b'}]));
  await page.locator('.course-card').last().click();await page.waitForTimeout(300);
  assert.equal(await page.locator('#sheetDots .sheet-dot').count(),2);
  const activeBefore=await page.locator('#sheetDots .sheet-dot.active').getAttribute('data-dot-index');
  await page.locator('.sheet-body').hover();await page.mouse.wheel(0,150);await page.waitForFunction(()=>document.querySelector('.sheet-body').scrollTop>0);
  assert.equal(await page.locator('#sheetDots .sheet-dot.active').getAttribute('data-dot-index'),activeBefore,'垂直滚动备注不切换日程');
  await page.locator('#local-schedule-edit').scrollIntoViewIfNeeded();
  const edit=await page.locator('#local-schedule-edit').boundingBox(),dots=await page.locator('#sheetDots').boundingBox();
  assert.ok(edit.height<=32);assert.ok(edit.y+edit.height<=dots.y+1,'编辑按钮不侵占圆点区');assert.ok(dots.y+dots.height<=640,'圆点不被详情底部裁掉');
  const target=await page.locator('#sheetDots .sheet-dot').nth(1).getAttribute('data-dot-index');
  await page.locator('#sheetDots .sheet-dot').nth(1).click();assert.equal(await page.locator('#sheetDots .sheet-dot.active').getAttribute('data-dot-index'),target);
 }finally{await browser.close();}
});
