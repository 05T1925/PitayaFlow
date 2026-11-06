import {afterEach,expect,test,vi} from 'vitest';
import {createApp,nextTick} from 'vue';
import ReviewPanel from './ReviewPanel.vue';

let app;
async function settle(){for(let i=0;i<20;i++){await Promise.resolve();await nextTick();}}
afterEach(()=>{app?.unmount();app=null;vi.unstubAllGlobals();});
test('selected report shows all questions, stale label and safe original text with seek',async()=>{
  const mediaId='11111111-1111-4111-8111-111111111111',taskId='22222222-2222-4222-8222-222222222222';
  const original='<img src=x onerror=alert(1)>';
  const transcript={revision:2,current_revision:2,segments:[
    {id:'q1',startMs:100,endMs:200,text:'Question 1',role:'INTERVIEWER'},
    {id:'a1',startMs:210,endMs:400,text:original,role:'ME'},
    {id:'q2',startMs:500,endMs:600,text:'Question 2',role:'INTERVIEWER'},
    {id:'a2',startMs:610,endMs:900,text:'Answer 2',role:'ME'}]};
  const reference=(segment)=>({segment_id:segment.id,start_ms:segment.startMs,end_ms:segment.endMs,text:segment.text});
  const report={source_id:'source',source_revision:1,speaker_revision:1,outline_revision:0,synthetic_model:true,
    materials:[{material_id:'33333333-3333-4333-8333-333333333333',revision:1,name:'合成 JD'}],
    questions:[{id:'q-1',question_refs:[reference(transcript.segments[0])],answer_refs:[reference(transcript.segments[1])],
      status:'insufficient_evidence',covered_points:['原回答已记录'],gaps:['缺少量化结果'],reason:'证据不足，无法评价',advice:'核对原话',
      material_background:[{material_id:'33333333-3333-4333-8333-333333333333',revision:1,quote:'合成背景引用'}],
      practice_card:{goal:'清楚表达',evidence_gap:'待核查',next_step:'练习',follow_up:'如何验证结果？',reference_answer:'请补充可核实证据',to_verify:[]},revised_answer:'文字练习'},
      {id:'q-2',question_refs:[reference(transcript.segments[2])],answer_refs:[reference(transcript.segments[3])],
      status:'await_confirmation',covered_points:[],gaps:[],reason:'说话人角色待确认',advice:'核对原话'}],unassigned_refs:[]};
  vi.stubGlobal('fetch',vi.fn(async url=>{
    const path=String(url),body=path.endsWith('/outline')?{revision:0,groups:[]}:
      path.endsWith('/materials')&&path.includes(`/media/${mediaId}/`)?{revision:0,materials:[]}:
      path.endsWith('/materials')||path.endsWith('/practice')?[]:
      path.endsWith(`/${taskId}`)?{task_id:taskId,status:'SUCCEEDED',stale:true,report}:
      [{task_id:taskId,status:'SUCCEEDED',source_revision:1,outline_revision:0,stale:true}];
    return {ok:true,json:async()=>body};
  }));
  const seek=vi.fn();document.body.innerHTML='<div id="review-test"></div>';
  app=createApp(ReviewPanel,{token:'fixture-token',mediaId,transcript,onSeek:seek});app.mount('#review-test');await settle();
  document.querySelector('.review-history button').click();await settle();
  expect(document.querySelectorAll('.review-question')).toHaveLength(2);
  expect(document.body.textContent).toContain('历史报告已过期');
  expect(document.body.textContent).toContain('无法评价');
  expect(document.body.textContent).toContain('待确认');
  expect(document.body.textContent).toContain('缺少量化结果');
  expect(document.body.textContent).toContain('如何验证结果？');
  expect(document.body.textContent).toContain('请补充可核实证据');
  expect(document.body.textContent).toContain('合成背景引用');
  expect(document.body.textContent).toContain(original);expect(document.querySelector('img')).toBeNull();
  document.querySelector('.review-question button').click();expect(seek).toHaveBeenCalledWith({startMs:100});
});
test('material selection and practice text are explicit versioned writes',async()=>{
  const mediaId='11111111-1111-4111-8111-111111111111',materialId='33333333-3333-4333-8333-333333333333',taskId='22222222-2222-4222-8222-222222222222';
  const writes=[],exportedText='Synthetic report export';let selection={revision:0,materials:[]};
  const clipboardWrite=vi.fn();vi.stubGlobal('navigator',{clipboard:{writeText:clipboardWrite}});
  const report={materials:[],questions:[{id:'q-1',question_refs:[{segment_id:'q1',start_ms:0,end_ms:100,text:'Question'}],
    answer_refs:[{segment_id:'a1',start_ms:100,end_ms:200,text:'Answer'}],covered_points:[],gaps:[],status:'reviewed',reason:'check',advice:'practice',
    material_background:[],practice_card:{goal:'表达',evidence_gap:'未核查',next_step:'练习',to_verify:['事实']},revised_answer:'建议文字'}],unassigned_refs:[]};
  vi.stubGlobal('fetch',vi.fn(async (url,options={})=>{
    const path=String(url);if(path.endsWith('/export'))return {ok:true,status:200,text:async()=>exportedText};
    if(options.method==='PUT'){writes.push({path,body:JSON.parse(options.body)});
      if(path.endsWith('/materials'))selection={revision:1,materials:[{material_id:materialId,revision:1,sha256:'hash'}]};
      return {ok:true,status:200,json:async()=>path.endsWith('/materials')?selection:{question_id:'q-1',revision:1,text:'我的新稿'}};}
    const body=path.endsWith('/outline')?{revision:0,groups:[]}:
      path.endsWith(`/media/${mediaId}/materials`)?selection:
      path.endsWith('/materials')?[{material_id:materialId,name:'合成 JD',kind:'PASTE',revision:1,status:'ACTIVE'}]:
      path.endsWith('/practice')?[]:
      path.endsWith(`/${taskId}`)?{task_id:taskId,status:'SUCCEEDED',stale:false,report}:
      [{task_id:taskId,status:'SUCCEEDED',source_revision:1,outline_revision:0,stale:false}];
    return {ok:true,status:200,json:async()=>body};
  }));
  document.body.innerHTML='<div id="review-test"></div>';
  app=createApp(ReviewPanel,{token:'fixture-token',mediaId,transcript:{revision:1,current_revision:1,segments:[
    {id:'q1',startMs:0,endMs:100,text:'Question',role:'INTERVIEWER'},
    {id:'a1',startMs:100,endMs:200,text:'Answer',role:'ME'}]}});app.mount('#review-test');await settle();
  document.querySelector('details').open=true;await settle();
  const check=document.querySelector('input[type=checkbox]');expect(check).not.toBeNull();check.click();await settle();
  [...document.querySelectorAll('button')].find(item=>item.textContent.includes('保存本场选择')).click();await settle();
  expect(writes[0].body).toEqual({expected_revision:0,material_ids:[materialId]});
  document.querySelector('.review-history button').click();await settle();
  const field=document.querySelector('textarea[aria-label="q-1 文字练习稿"]');field.value='我的新稿';field.dispatchEvent(new Event('input',{bubbles:true}));await settle();
  [...document.querySelectorAll('button')].find(item=>item.textContent.includes('保存练习稿修订')).click();await settle();
  expect(writes[1].body).toEqual({expected_revision:0,text:'我的新稿'});
  expect(document.body.textContent).toContain('历史报告已过期');
  [...document.querySelectorAll('button')].find(item=>item.textContent.includes('复制文字报告')).click();await settle();
  expect(clipboardWrite).toHaveBeenCalledWith(exportedText);
  expect(fetch).toHaveBeenCalledWith(`/api/v1/media/${mediaId}/review/${taskId}/export`,
    expect.objectContaining({headers:{Authorization:'Bearer fixture-token'},cache:'no-store'}));
});
