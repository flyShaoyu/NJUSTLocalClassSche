import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {parseRoomAvailability,fetchEmptyRooms,fetchRoomFilters} from '../dist/empty-room.js';
import {createLocalCourse,readLocalCourses,saveLocalCourses,mergeLocalCourses} from '../dist/local-courses.js';
import {parseBookSearchHtml,parseLibraryHoldings,parseCurrentLoans,fetchCurrentLoans,searchLibraryBooks,LibraryLoginRequiredError,LIBRARY_BASE} from '../dist/library-data.js';

const roomHead='<tr><th>星期</th><th colspan=2>星期一</th><th>星期二</th></tr><tr><td></td><td tdvalue=010203>第一大节</td><td tdvalue=0405>第二大节</td><td tdvalue=010203>第一大节</td></tr>';
test('room columns retain day spans, exact periods, capacity and unknown states',()=>{
 const html=`<table id=dataList>${roomHead}<tr jsbh=r1><td><input name=jsids value=r1>101(60/30)</td><td></td><td>◆</td><td>新标记</td></tr></table>`;
 const [room]=parseRoomAvailability(html);
 assert.equal(room.name,'101');assert.equal(room.capacity,60);assert.equal(room.examCapacity,30);
 assert.deepEqual(room.slots.map(s=>[s.weekday,s.periods,s.state]),[['星期一',[1,2,3],'free'],['星期一',[4,5],'occupied'],['星期二',[1,2,3],'unknown']]);
 assert.equal(parseRoomAvailability(html.replace('<td></td><td>◆','<td style="background:red"></td><td>◆'))[0].slots[0].state,'unknown');
});
test('room parser rejects wrong tables, missing encodings and truncated rows, but accepts explicit empty results',()=>{
 assert.throws(()=>parseRoomAvailability('<table id=dataList><tr><th>成绩</th></tr></table>'),/解析错误/);
 assert.throws(()=>parseRoomAvailability(`<table id=dataList>${roomHead.replace('tdvalue=0405','')}<tr jsbh=r1><td>101</td></tr></table>`),/节次编码/);
 assert.throws(()=>parseRoomAvailability(`<table id=dataList>${roomHead}<tr jsbh=r1><td>101</td></tr></table>`),/列数/);
 assert.deepEqual(parseRoomAvailability(`<table id=dataList>${roomHead}<tr><td colspan=4>暂无数据</td></tr></table>`),[]);
 assert.deepEqual(parseRoomAvailability(`<table id=dataList>${roomHead}<tr><td colspan=4>符号说明： Ｌ 临时调课; 空闲; ◆ 正常上课;</td></tr></table>`),[]);
 assert.throws(()=>parseRoomAvailability(`<table id=dataList>${roomHead}<tr><td>服务器处理异常</td></tr></table>`),/无法识别/);
});
test('invalid room query fails before any HTTP request',async()=>{
 await assert.rejects(fetchEmptyRooms({}, {semester:'2026-2027-1',campus:'01',week:10,weekday:3,startPeriod:4,endPeriod:2}),/参数错误/);
});
test('room building lookup uses the school classroom endpoint when the original endpoint rejects access',async()=>{
 const calls=[];
 const context={request:{get:async url=>response(url,200,'<form action="/njlgdx/kbxx/jsjy_query2"><select name=xnxqh><option value=2026-2027-1>2026-2027-1</option></select><select name=xqbh><option value=01>孝陵卫</option></select></form>'),post:async url=>{calls.push(url);return response(url,200,url.includes('jsjy_processAjax')?'<body>提示：非法访问！</body>':'[{"dm":"building1","dmmc":"第一教学楼"}]');}}};
 const filters=await fetchRoomFilters(context,'01');
 assert.equal(filters.buildings[0].value,'building1');assert.match(filters.buildingSource,/getJxlByAjax$/);assert.match(filters.warnings[0],/非法访问/);
 assert.equal(calls.length,2);
});
const input={semester:'2026-2027-1',courseName:' 测试课程 ',weekday:2,startPeriod:1,endPeriod:3,weeks:[3,1,3],classroom:'101'};
test('manual courses persist edits, merge only the chosen semester and survive an online refresh',async()=>{
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'classsche-courses-'));const file=path.join(dir,'courses.json');
 try{
  assert.deepEqual(await readLocalCourses(file),[]);
  const course=createLocalCourse(input,'local1');assert.deepEqual(course.weeks,[1,3]);
  await saveLocalCourses(file,[course]);
  const edited=createLocalCourse({...course,classroom:'202'},course.id);await saveLocalCourses(file,[edited]);
  const restored=await readLocalCourses(file);const online=[{courseName:'教务课程',weekday:'星期一',periods:'1-2节',weeks:'1周',classroom:'303',rawText:''}];
  assert.equal(mergeLocalCourses(online,restored,'2026-2027-1')[1].classroom,'202');
  assert.equal(mergeLocalCourses(online,restored,'2026-2027-2').length,1);
  assert.equal(mergeLocalCourses([],await readLocalCourses(file),'2026-2027-1').length,1);
  assert.equal(online.length,1);
  await saveLocalCourses(file,[]);assert.deepEqual(await readLocalCourses(file),[]);
 }finally{await fs.rm(file,{force:true});await fs.rmdir(dir);}
});
test('manual course validation and corrupt storage never become silent empty data',async()=>{
 for(const patch of [{courseName:''},{weekday:8},{startPeriod:4,endPeriod:2},{weeks:[]},{weeks:[0]}])assert.throws(()=>createLocalCourse({...input,...patch}),/参数错误/);
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'classsche-courses-'));const file=path.join(dir,'courses.json');
 try{await fs.writeFile(file,'{invalid');await assert.rejects(readLocalCourses(file),/解析错误/);assert.equal(await fs.readFile(file,'utf8'),'{invalid');}
 finally{await fs.rm(file,{force:true});await fs.rmdir(dir);}
});
const books='<body>检索到 1 条结果<ol id=search_book_list><li><h3><span>中文图书</span><a href="item.php?marc_no=book1">1.测试图书</a> TP/1</h3><p><span>馆藏复本：2<br>可借复本：0</span>测试作者</p></li></ol></body>';
test('book search exposes stable IDs, availability and detail links; malformed nonempty pages fail',()=>{
 const result=parseBookSearchHtml(books);assert.equal(result.total,1);assert.equal(result.books[0].title,'测试图书');assert.equal(result.books[0].availableCopies,0);assert.equal(result.books[0].callNumber,'TP/1');
 assert.equal(new URL(result.books[0].detailUrl).searchParams.get('marc_no'),'book1');
 assert.throws(()=>parseBookSearchHtml(books.replace('item.php?marc_no=book1','https://evil.example/item.php?marc_no=book1')),/解析错误/);
 assert.throws(()=>parseBookSearchHtml('<body>检索到 1 条结果</body>'),/列表缺失/);
 assert.throws(()=>parseBookSearchHtml('<body>服务器维护</body>'),/解析错误/);
 assert.deepEqual(parseBookSearchHtml('<body>纸本图书检索结果[0] 本馆没有您检索的图书 本馆没有您要检索的图书</body>'),{total:0,books:[]});
});
test('holdings retain shelf status rather than treating aggregate available count as a guarantee',()=>{
 const html='<table id=item><tr><td>索书号</td><td>条码号</td><td>年卷期</td><td>校区—馆藏地</td><td>书刊状态</td><td>还书位置</td></tr><tr><td>TP/1</td><td>b1</td><td></td><td>江阴校区</td><td>新书：正在上架</td><td>405</td></tr></table>';
 assert.equal(parseLibraryHoldings(html)[0].status,'新书：正在上架');
 assert.throws(()=>parseLibraryHoldings('<html>登录</html>'),/馆藏解析错误/);
});
const reader='<a href="../reader/logout.php">注销</a><h3>当前借阅</h3>';
test('authenticated explicit empty loans are valid even with a hidden renewal CAPTCHA template',()=>{
 assert.deepEqual(parseCurrentLoans(reader+'您的该项记录为空！<div hidden><input name=captcha><img src=captcha.php></div>'),[]);
 assert.throws(()=>parseCurrentLoans('<input type=password>当前借阅'),LibraryLoginRequiredError);
 assert.throws(()=>parseCurrentLoans(reader+'服务器异常'),/借阅信息解析错误/);
});
test('loans map headers and due dates without invoking renewal links',()=>{
 const html=reader+'<table><tr><td>条码号</td><td>题名/责任者</td><td>借阅日期</td><td>应还日期</td><td>馆藏地</td><td>操作</td></tr><tr><td>b1</td><td>测试书</td><td>2026-10-01</td><td>2026-11-01</td><td>南京</td><td><a href=renew.php>续借</a></td></tr></table>';
 assert.deepEqual(parseCurrentLoans(html),[{barcode:'b1',title:'测试书',borrowedAt:'2026-10-01',dueAt:'2026-11-01',location:'南京'}]);
});
function response(url,status,html='',location){return {ok:()=>status>=200&&status<300,status:()=>status,url:()=>url,headers:()=>location?{location}:{},body:async()=>Buffer.from(html)};}
test('library login reuses existing IDS and library sessions with GETs and no password POST',async()=>{
 const urls=[];let authenticated=false;
 const context={request:{get:async(url)=>{urls.push(url);const u=new URL(url);
  if(u.pathname.endsWith('/redr_info.php'))return response(url,200,authenticated?reader:'<input type=password>');
  if(u.pathname.endsWith('/login.php')&&!u.search)return response(url,302,'','http://ids.njust.edu.cn/authserver/login?service='+encodeURIComponent(LIBRARY_BASE+'reader/login.php'));
  if(u.hostname==='ids.njust.edu.cn')return response(url,302,'',LIBRARY_BASE+'reader/login.php?ticket=private-ticket');
  if(u.pathname.endsWith('/login.php')){authenticated=true;return response(url,302,'',LIBRARY_BASE+'reader/redr_info.php');}
  if(u.pathname.endsWith('/book_lst.php'))return response(url,200,reader+'您的该项记录为空！');
  throw new Error('unexpected request');
 }}};
 assert.deepEqual(await fetchCurrentLoans(context),[]);
 const first=urls.length;assert.deepEqual(await fetchCurrentLoans(context),[]);
 assert.deepEqual(urls.slice(first),[LIBRARY_BASE+'reader/redr_info.php',LIBRARY_BASE+'reader/book_lst.php']);
 assert.equal(urls.filter(u=>new URL(u).hostname==='ids.njust.edu.cn').length,1);
});
test('expired IDS, foreign redirects and transport errors fail without password fallback or ticket disclosure',async()=>{
 await assert.rejects(fetchCurrentLoans({request:{get:async url=>response(url,200,'<input type=password>')}}),LibraryLoginRequiredError);
 await assert.rejects(fetchCurrentLoans({request:{get:async url=>response(url,302,'','https://evil.example/login?ticket=private-ticket')}}),/跳转错误/);
 const context={request:{get:async url=>{if(url.includes('redr_info'))return response(url,200,'<input type=password>');if(url.includes('login.php?ticket'))throw new Error('timeout http://202.119.83.14:8080/uopac/reader/login.php?ticket=private-ticket');return response(url,302,'',LIBRARY_BASE+'reader/login.php?ticket=private-ticket');}}};
 await assert.rejects(fetchCurrentLoans(context),e=>/超时/.test(e.message)&&!e.message.includes('private-ticket'));
});


test('OPAC新版按表单切换q/t参数并携带当前CSRF，分页不回退旧字段',async()=>{
 const urls=[];const context={request:{get:async url=>{urls.push(url);return response(url,200,url.includes('search.php')?'<form action="openlink.php" method="get"><input name="q"><input type="hidden" name="csrf_token" value="mock-token"></form>':'本馆没有您检索的图书');}}};
 assert.deepEqual(await searchLibraryBooks(context,'活着',2),{total:0,books:[]});
 const params=new URL(urls[1]).searchParams;assert.equal(params.get('q'),'活着');assert.equal(params.get('t'),'title');assert.equal(params.get('page'),'2');assert.equal(params.get('csrf_token'),'mock-token');assert.equal(params.has('strText'),false);
});
