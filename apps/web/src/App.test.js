import {beforeEach,afterEach,expect,test,vi} from 'vitest';
import {createApp,nextTick} from 'vue';
import App from './App.vue';

const media={media_id:'11111111-1111-4111-8111-111111111111',filename:'fixture.wav',kind:'WAV',size_bytes:32044,duration_ms:2000};
let inventory,ready,revision,transcript,app,keepJobRunning;
function response(body,status=200){return {ok:status>=200&&status<300,status,json:async()=>structuredClone(body),text:async()=>JSON.stringify(body)};}
async function settle(){for(let i=0;i<12;i++){await Promise.resolve();await nextTick();}await new Promise(resolve=>setTimeout(resolve,0));}
async function waitFor(selector){for(let i=0;i<50;i++){await settle();if(document.querySelector(selector))return;}throw new Error(`missing ${selector}: ${document.body.textContent.slice(0,700)}`);}
function mountApp(){document.body.innerHTML='<div id="test-app"></div>';app=createApp(App);app.mount('#test-app');}
function button(text){const found=[...document.querySelectorAll('button')].find(item=>item.textContent.trim()===text);if(!found)throw new Error(`button missing: ${text}`);return found;}
async function change(element,value){element.value=value;element.dispatchEvent(new Event('input',{bubbles:true}));element.dispatchEvent(new Event('change',{bubbles:true}));await settle();}
beforeEach(()=>{
  sessionStorage.clear();inventory=[];ready=false;revision=1;keepJobRunning=false;
  transcript={source_id:'source-fixture',media_id:media.media_id,revision:1,current_revision:1,source_sha256:'a'.repeat(64),
    speaker_revision:0,origin:'MACHINE',provider:'deepgram-fixture',model:'nova-3',segments:[
      {id:'seg-00001',startMs:150,endMs:900,text:'Synthetic fixture text',speakerId:'speaker-0',role:'UNKNOWN',track:0,confidence:null}
    ]};
  Object.defineProperty(navigator,'serviceWorker',{configurable:true,value:{controller:{postMessage:vi.fn()},register:vi.fn(async()=>({})),ready:Promise.resolve({}),addEventListener:vi.fn(),removeEventListener:vi.fn()}});
  vi.stubGlobal('fetch',vi.fn(async(url,options={})=>{
    const path=String(url),method=options.method||'GET';
    if(path.endsWith('/login'))return response({token:'synthetic-test-session',user_id:1,role:'USER'});
    if(path.endsWith('/media')&&method==='GET')return response(inventory);
    if(path.endsWith('/media')&&method==='POST'){inventory=[media];return response(media,201);}
    if(path.endsWith(`/media/${media.media_id}`)&&method==='DELETE'){inventory=[];ready=false;return response(null,204);}
    if(path.endsWith('/transcript')&&method==='POST'){ready=true;return response({task_id:'job-fixture',status:'QUEUED'},202);}
    if(path.includes('/transcript/jobs/job-fixture/events?after=')){
      const payload={seq:1,kind:'partial_segment',payload:{id:'seg-00001',startMs:150,endMs:900,text:'Synthetic fixture text'}};
      const frames=`id:1\nevent:partial_segment\ndata:${JSON.stringify(payload)}\n\nid:1\nevent:partial_segment\ndata:${JSON.stringify(payload)}\n\nevent:terminal\ndata:{"status":"SUCCEEDED"}\n\n`;
      return {ok:true,status:200,body:new ReadableStream({start(controller){controller.enqueue(new TextEncoder().encode(frames));controller.close();}})};
    }
    if(path.endsWith('/jobs/job-fixture'))return response({task_id:'job-fixture',status:keepJobRunning?'RUNNING':'SUCCEEDED'});
    if(path.endsWith('/transcript')&&method==='PUT'){
      const payload=JSON.parse(options.body);if(payload.expectedRevision!==revision)return response({error:'conflict'},409);
      revision++;transcript={...transcript,revision,current_revision:revision,speaker_revision:1,origin:'HUMAN',segments:payload.segments};return response(transcript);
    }
    if(path.endsWith('/transcript'))return ready?response(transcript):response({error:'not found'},404);
    throw new Error(`unexpected request ${method} ${path}`);
  }));
  vi.spyOn(HTMLMediaElement.prototype,'play').mockResolvedValue();
  vi.spyOn(HTMLMediaElement.prototype,'pause').mockImplementation(()=>{});
  vi.spyOn(HTMLMediaElement.prototype,'load').mockImplementation(()=>{});
});
afterEach(()=>{if(app){app.unmount();app=null;}vi.restoreAllMocks();vi.unstubAllGlobals();});
test('login, upload, fixture transcript, seek, correct and refresh',async()=>{
  mountApp();await settle();
  await change(document.querySelector('input[autocomplete="username"]'),'synthetic_user');
  await change(document.querySelector('input[autocomplete="current-password"]'),'test-only-password');
  button('进入工作区').click();await settle();
  const file=document.querySelector('input[type="file"]');Object.defineProperty(file,'files',{configurable:true,value:[new File(['RIFF'],'fixture.wav',{type:'audio/wav'})]});
  file.dispatchEvent(new Event('change',{bubbles:true}));await settle();expect(document.body.textContent).toContain('fixture.wav');
  button('开始转写').click();await settle();
  await waitFor('.jump');
  expect(document.querySelectorAll('.segments p')).toHaveLength(1);
  expect(document.body.textContent).toContain('deepgram-fixture');expect(document.body.textContent).toContain('待确认');
  document.querySelector('.jump').click();expect(document.querySelector('audio').currentTime).toBeCloseTo(0.15);
  await change(document.querySelector('select'),'ME');await change(document.querySelector('textarea'),'Corrected synthetic text');
  button('保存为新修订').click();await settle();
  expect(document.body.textContent).toContain('版本 2');expect(transcript.segments[0].text).toBe('Corrected synthetic text');
  app.unmount();app=null;mountApp();await settle();
  document.querySelector('.media-list button').click();await settle();
  expect(document.body.textContent).toContain('版本 2');expect(document.querySelector('textarea').value).toBe('Corrected synthetic text');
});
test('stale edit shows conflict, reloads current revision and escapes text',async()=>{
  sessionStorage.setItem('pf_token','synthetic-test-session');inventory=[media];ready=true;
  transcript.segments[0].text='<img src=x onerror=alert(1)>';
  mountApp();await settle();document.querySelector('.media-list button').click();await waitFor('.jump');
  expect(document.querySelector('img')).toBeNull();
  revision=2;transcript={...transcript,revision:2,current_revision:2,segments:[{...transcript.segments[0],text:'Newer server text'}]};
  await change(document.querySelector('textarea'),'stale local edit');button('保存为新修订').click();await settle();
  expect(document.querySelector('[role="alert"]').textContent).toContain('409');
  button('重新载入当前版本').click();await settle();
  expect(document.querySelector('textarea').value).toBe('Newer server text');
});
test('delete selected media confirms scope and clears the current browser session',async()=>{
  sessionStorage.setItem('pf_token','synthetic-test-session');inventory=[media];ready=true;
  mountApp();await settle();document.querySelector('.media-list button').click();await settle();await waitFor('audio');
  button('删除当前媒体').click();await settle();
  expect(document.querySelector('[role="alertdialog"]').textContent).toContain('未完成任务将取消');
  button('确认删除').click();await settle();
  expect(fetch).toHaveBeenCalledWith(`/api/v1/media/${media.media_id}`,expect.objectContaining({method:'DELETE'}));
  expect(document.querySelector('.media-list').textContent).not.toContain(media.filename);
  expect(document.querySelector('audio')).toBeNull();
  expect(sessionStorage.getItem('pf_media')).toBeNull();expect(sessionStorage.getItem('pf_task')).toBeNull();
  expect(document.body.textContent).toContain('私有媒体及关联历史正文已删除');
});
test('keep media closes the confirmation without deleting or losing the selection',async()=>{
  sessionStorage.setItem('pf_token','synthetic-test-session');inventory=[media];ready=true;
  mountApp();await settle();document.querySelector('.media-list button').click();await settle();await waitFor('audio');
  button('删除当前媒体').click();await settle();button('保留媒体').click();await settle();
  expect(document.querySelector('[role="alertdialog"]')).toBeNull();
  expect(document.querySelector('.media-list').textContent).toContain(media.filename);
  expect(document.querySelector('audio')).not.toBeNull();
  expect(sessionStorage.getItem('pf_media')).toBe(media.media_id);
  expect(fetch).not.toHaveBeenCalledWith(`/api/v1/media/${media.media_id}`,expect.objectContaining({method:'DELETE'}));
});
test('deleting while a task is active aborts the stream and clears polling',async()=>{
  sessionStorage.setItem('pf_token','synthetic-test-session');inventory=[media];keepJobRunning=true;
  const abort=vi.spyOn(AbortController.prototype,'abort');
  const clear=vi.spyOn(globalThis,'clearInterval');
  mountApp();await settle();document.querySelector('.media-list button').click();await settle();
  button('开始转写').click();await settle();
  expect(document.body.textContent).toContain('RUNNING');
  const abortBefore=abort.mock.calls.length,clearBefore=clear.mock.calls.length;
  button('删除当前媒体').click();await settle();button('确认删除').click();await settle();
  expect(abort.mock.calls.length).toBeGreaterThan(abortBefore);
  expect(clear.mock.calls.length).toBeGreaterThan(clearBefore);
  expect(document.querySelector('audio')).toBeNull();
  expect(sessionStorage.getItem('pf_task')).toBeNull();
});
