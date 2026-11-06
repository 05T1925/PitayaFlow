<script setup>
import {ref,computed,onMounted,onBeforeUnmount,nextTick} from 'vue';
import ReviewPanel from './ReviewPanel.vue';
const token=ref(sessionStorage.getItem('pf_token')||'');
const username=ref(''),password=ref(''),media=ref([]),selected=ref(null),task=ref(null),transcript=ref(null),draft=ref([]);
const versionChoice=ref(''),partialSegments=ref([]),failedIntervals=ref([]);
const error=ref(''),notice=ref(''),busy=ref(false),player=ref(null),playerReady=ref(false),deleteConfirmationOpen=ref(false);
let poll=null,streamAbort=null,streamEpoch=0;
const playback=computed(()=>selected.value&&playerReady.value?`/api/v1/media/${selected.value.media_id}/play`:null);
function tellWorker(){navigator.serviceWorker?.controller?.postMessage({type:'PF_TOKEN',token:token.value});}
function answerWorker(event){if(event.data?.type==='PF_TOKEN_REQUEST')event.ports?.[0]?.postMessage({token:token.value});}
async function setupPlayer(){
  if(!('serviceWorker'in navigator)){error.value='浏览器不支持受控回听';return;}
  navigator.serviceWorker.addEventListener('message',answerWorker);
  await navigator.serviceWorker.register('/media-auth-sw.js',{scope:'/'});
  await navigator.serviceWorker.ready;
  if(!navigator.serviceWorker.controller){await new Promise(resolve=>{navigator.serviceWorker.addEventListener('controllerchange',resolve,{once:true});setTimeout(resolve,3000);});}
  tellWorker();playerReady.value=!!navigator.serviceWorker.controller;
}
async function api(path,options={}){
  const headers=new Headers(options.headers||{});if(token.value)headers.set('Authorization',`Bearer ${token.value}`);
  const response=await fetch(`/api/v1${path}`,{...options,headers,cache:'no-store'});
  if(!response.ok){let body='';try{body=await response.text();}catch{}throw new Error(`${response.status} ${body.slice(0,240)}`);}
  if(response.status===204)return null;return response.json();
}
async function run(work){error.value='';notice.value='';busy.value=true;try{await work();}catch(e){error.value=String(e.message||e);}finally{busy.value=false;}}
async function login(){await run(async()=>{
  const response=await api('/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:username.value,password:password.value})});
  token.value=response.token;sessionStorage.setItem('pf_token',token.value);password.value='';tellWorker();await loadMedia();
});}
function stopStream(){streamEpoch++;streamAbort?.abort();streamAbort=null;}
function logout(){clearInterval(poll);poll=null;stopStream();token.value='';sessionStorage.removeItem('pf_token');sessionStorage.removeItem('pf_media');sessionStorage.removeItem('pf_task');tellWorker();selected.value=null;task.value=null;transcript.value=null;media.value=[];partialSegments.value=[];failedIntervals.value=[];deleteConfirmationOpen.value=false;}
async function loadMedia(restore=true){media.value=await api('/media');if(restore&&!selected.value){const saved=media.value.find(item=>item.media_id===sessionStorage.getItem('pf_media'));if(saved)await selectMedia(saved);}}
async function upload(event){const file=event.target.files?.[0];if(!file)return;await run(async()=>{
  const body=new FormData();body.append('file',file);
  const created=await api('/media',{method:'POST',body});await loadMedia(false);await selectMedia(created);
  notice.value='私有媒体已上传；转写需由实例管理员配置 ASR。';event.target.value='';
});}
async function selectMedia(item){const previous=sessionStorage.getItem('pf_media');if(previous!==item.media_id)sessionStorage.removeItem('pf_task');
  if(poll){clearInterval(poll);poll=null;}stopStream();partialSegments.value=[];failedIntervals.value=[];
  selected.value=item;deleteConfirmationOpen.value=false;sessionStorage.setItem('pf_media',item.media_id);task.value=null;transcript.value=null;draft.value=[];
  await nextTick();
  if(player.value){player.value.pause();player.value.load();}
  try{await loadTranscript();}catch(e){if(!String(e.message).startsWith('404'))error.value=String(e.message);}
  const taskId=sessionStorage.getItem('pf_task');if(taskId){try{const restored=await api(`/jobs/${taskId}`);if(selected.value?.media_id!==item.media_id)return;
    task.value=restored;if(['QUEUED','RUNNING'].includes(restored.status)){beginPoll();beginStream(restored.task_id,item.media_id);}}catch{sessionStorage.removeItem('pf_task');}}
}
async function loadTranscript(version){if(!selected.value)return;
  const mediaId=selected.value.media_id;
  const suffix=typeof version==='number'?`?revision=${version}`:'';
  const loaded=await api(`/media/${mediaId}/transcript${suffix}`);if(selected.value?.media_id!==mediaId)return;
  transcript.value=loaded;
  versionChoice.value=String(transcript.value.revision);
  draft.value=transcript.value.segments.map(segment=>({...segment}));}
async function chooseVersion(){const version=Number(versionChoice.value);if(!Number.isSafeInteger(version)||version<1){error.value='修订号必须是正整数';return;}await run(()=>loadTranscript(version));}
async function startTranscript(){if(!selected.value)return;await run(async()=>{
  const mediaId=selected.value.media_id;
  const accepted=await api(`/media/${mediaId}/transcript`,{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()}});
  if(selected.value?.media_id!==mediaId)return;
  task.value=accepted;
  sessionStorage.setItem('pf_task',task.value.task_id);
  partialSegments.value=[];failedIntervals.value=[];beginPoll();beginStream(task.value.task_id,mediaId);notice.value='转写任务已受理；句段事件可断线续读。';
});}
function beginStream(id,mediaId){stopStream();const epoch=streamEpoch;let cursor=0;
  const follow=async()=>{while(epoch===streamEpoch&&token.value&&selected.value?.media_id===mediaId){
    const controller=new AbortController();streamAbort=controller;
    try{const response=await fetch(`/api/v1/media/${mediaId}/transcript/jobs/${id}/events?after=${cursor}`,
      {headers:{Authorization:`Bearer ${token.value}`,Accept:'text/event-stream'},signal:controller.signal,cache:'no-store'});
      if(!response.ok||!response.body)throw new Error(`增量事件 ${response.status}`);
      const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='';
      while(epoch===streamEpoch){const {value,done}=await reader.read();if(done)break;buffer+=decoder.decode(value,{stream:true});
        const frames=buffer.split(/\r?\n\r?\n/);buffer=frames.pop();
        for(const frame of frames){const line=frame.split(/\r?\n/).find(item=>item.startsWith('data:'));
          if(!line)continue;let event;try{event=JSON.parse(line.slice(5).trim());}catch{continue;}
          if(event.seq){if(event.seq<=cursor)continue;cursor=event.seq;
            if(event.kind==='partial_segment'&&event.payload&&!partialSegments.value.some(item=>item.id===event.payload.id))partialSegments.value.push(event.payload);
            if(event.kind==='failed_interval')failedIntervals.value.push(event.payload?.code||'FAILED');
          }else if(event.status){return;}
        }
      }
    }catch(failure){if(controller.signal.aborted||epoch!==streamEpoch)return;error.value=String(failure.message||failure);}
    if(epoch!==streamEpoch)return;await new Promise(resolve=>setTimeout(resolve,750));
  }};void follow();}
function beginPoll(){if(poll)clearInterval(poll);if(!task.value)return;const id=task.value.task_id,mediaId=selected.value?.media_id;
  const check=async()=>{try{const latest=await api(`/jobs/${id}`);if(selected.value?.media_id!==mediaId)return;task.value=latest;if(['SUCCEEDED','FAILED','CANCELLED'].includes(task.value.status)){
    clearInterval(poll);poll=null;if(task.value.status==='SUCCEEDED')await loadTranscript();
  }}catch(e){error.value=String(e.message);}};
  poll=setInterval(check,1500);void check();
}
async function cancel(){if(!task.value)return;await run(async()=>{task.value=await api(`/jobs/${task.value.task_id}/cancel`,{method:'POST'});stopStream();partialSegments.value=[];});}
async function deleteSelectedMedia(){if(!selected.value)return;
  const mediaId=selected.value.media_id;
  await run(async()=>{
    await api(`/media/${mediaId}`,{method:'DELETE'});
    if(selected.value?.media_id!==mediaId)return;
    if(poll){clearInterval(poll);poll=null;}stopStream();
    if(player.value){player.value.pause();player.value.removeAttribute('src');player.value.load();}
    sessionStorage.removeItem('pf_media');sessionStorage.removeItem('pf_task');
    selected.value=null;task.value=null;transcript.value=null;draft.value=[];versionChoice.value='';deleteConfirmationOpen.value=false;
    partialSegments.value=[];failedIntervals.value=[];
    await loadMedia(false);notice.value='私有媒体及关联历史正文已删除。';
  });
}
async function save(){if(!transcript.value||!selected.value)return;await run(async()=>{
  if(transcript.value.revision!==transcript.value.current_revision)throw new Error('历史修订只读；先查看最新版本。');
  const mediaId=selected.value.media_id;
  const saved=await api(`/media/${mediaId}/transcript`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({expectedRevision:transcript.value.revision,segments:draft.value})});
  if(selected.value?.media_id!==mediaId)return;
  transcript.value=saved;
  versionChoice.value=String(transcript.value.revision);
  draft.value=transcript.value.segments.map(segment=>({...segment}));notice.value=`修订 ${transcript.value.revision} 已保存。`;
});}
function seek(item){if(!player.value)return;player.value.currentTime=item.startMs/1000;player.value.play().catch(()=>{});}
async function exportText(){if(!selected.value)return;await run(async()=>{
  const response=await fetch(`/api/v1/media/${selected.value.media_id}/transcript/export?revision=${transcript.value.revision}`,{headers:{Authorization:`Bearer ${token.value}`},cache:'no-store'});
  if(!response.ok)throw new Error(`导出失败 ${response.status}`);
  const blob=await response.blob(),url=URL.createObjectURL(blob),link=document.createElement('a');
  link.href=url;link.download=`transcript-r${transcript.value.revision}.txt`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
});}
function stamp(ms){const sec=Math.floor(ms/1000);return `${String(Math.floor(sec/60)).padStart(2,'0')}:${String(sec%60).padStart(2,'0')}.${String(ms%1000).padStart(3,'0')}`;}
onMounted(async()=>{await setupPlayer();if(token.value)await run(loadMedia);});
onBeforeUnmount(()=>{if(poll)clearInterval(poll);stopStream();navigator.serviceWorker?.removeEventListener('message',answerWorker);});
</script>

<template>
  <main class="shell">
    <header><div><small>PRIVATE REVIEW / S2-02</small><h1>转写、回听与逐题复盘</h1><p>原录音是发言来源。机器说话人和未确认角色保留待确认。</p></div><button v-if="token" @click="logout">退出</button></header>
    <p v-if="error" class="message bad" role="alert">{{error}} <button v-if="transcript" @click="loadTranscript">重新载入当前版本</button></p>
    <p v-if="notice" class="message">{{notice}}</p>
    <section v-if="!token" class="panel login"><h2>登录</h2><label>用户名<input v-model="username" autocomplete="username"></label><label>密码<input v-model="password" type="password" autocomplete="current-password" @keyup.enter="login"></label><button :disabled="busy" @click="login">进入工作区</button></section>
    <template v-else>
      <section class="panel"><div class="row"><h2>私有媒体</h2><label class="upload">上传 WAV / MP4<input type="file" accept="audio/wav,video/mp4,.wav,.mp4" @change="upload"></label><button @click="run(loadMedia)">刷新列表</button></div>
        <div class="media-list"><button v-for="item in media" :key="item.media_id" :class="{active:selected?.media_id===item.media_id}" @click="selectMedia(item)">{{item.filename}} <small>{{item.kind}} · {{Math.round(item.size_bytes/1024)}} KB</small></button></div>
      </section>
      <section v-if="selected" class="panel"><div class="row"><h2>{{selected.filename}}</h2><span>{{selected.kind}} · {{stamp(selected.duration_ms||0)}}</span><button v-if="!deleteConfirmationOpen" :disabled="busy" @click="deleteConfirmationOpen=true">删除当前媒体</button></div>
        <div v-if="deleteConfirmationOpen" class="message bad" role="alertdialog" aria-label="确认删除媒体"><p>删除“{{selected.filename}}”？未完成任务将取消，媒体、转写及关联复盘正文会变得不可访问。</p><button :disabled="busy" @click="deleteSelectedMedia">确认删除</button><button :disabled="busy" @click="deleteConfirmationOpen=false">保留媒体</button></div>
        <div v-if="playerReady" class="player"><video v-if="selected.kind==='MP4'" ref="player" controls preload="metadata" crossorigin="anonymous" :key="selected.media_id" :src="playback"></video><audio v-else ref="player" controls preload="metadata" crossorigin="anonymous" :key="selected.media_id" :src="playback"></audio></div>
        <p v-else>正在准备鉴权播放器…</p><div class="row"><button :disabled="busy||task?.status==='RUNNING'||task?.status==='QUEUED'" @click="startTranscript">{{task?.status==='FAILED'?'重试转写':'开始转写'}}</button><button v-if="task&&['QUEUED','RUNNING'].includes(task.status)" @click="cancel">取消任务</button><button @click="run(loadTranscript)">查询已保存转写</button></div>
        <p v-if="task" class="task">任务 {{task.task_id}} · {{task.status}} <strong v-if="task.failure_code">{{task.failure_code}}</strong></p>
        <div v-if="partialSegments.length" class="segments"><h3>逐步到达的机器句段（发布前为暂存，待人工核对）</h3>
          <p v-for="part in partialSegments" :key="part.id">{{stamp(part.startMs)}}–{{stamp(part.endMs)}} {{part.text}}</p></div>
        <p v-if="failedIntervals.length" class="message bad">失败区间／原因：{{failedIntervals.join('；')}}；可重新提交转写。</p>
        <p class="hint">无 ASR 配置会明确失败；本地协议测试产物须标注为 fixture。视频画面不作为发言证据。</p>
      </section>
      <section v-if="transcript" class="panel"><div class="row"><div><h2>句段修订</h2><p>版本 {{transcript.revision}} / 最新 {{transcript.current_revision}} · {{transcript.origin}} · {{transcript.provider}} / {{transcript.model}}</p></div><div class="row"><label>版本<input v-model="versionChoice" type="number" min="1" class="version"></label><button @click="chooseVersion">查看版本</button><button @click="run(()=>loadTranscript())">查看最新</button><button @click="exportText">导出逐字稿</button></div></div>
        <p class="hint">来源哈希 {{transcript.source_sha256}} · 角色修订 {{transcript.speaker_revision}}。未知角色需人工确认；保存冲突时重新载入。</p>
        <div class="segments"><article v-for="part in draft" :key="part.id" class="segment"><button class="jump" @click="seek(part)">▶ {{stamp(part.startMs)}}–{{stamp(part.endMs)}}</button><label>说话人<input v-model="part.speakerId" aria-label="说话人"></label><label>角色<select v-model="part.role" aria-label="角色"><option value="UNKNOWN">待确认</option><option value="ME">我</option><option value="INTERVIEWER">面试官</option></select></label><label>开始 ms<input v-model.number="part.startMs" type="number" min="0"></label><label>结束 ms<input v-model.number="part.endMs" type="number" min="0"></label><label class="text">转写文本<textarea v-model="part.text" rows="2"></textarea></label></article></div>
        <button :disabled="busy||transcript.revision!==transcript.current_revision" @click="save">保存为新修订</button>
      </section>
      <ReviewPanel v-if="transcript&&selected" :key="selected.media_id" :token="token" :media-id="selected.media_id" :transcript="transcript" @seek="seek" />
    </template>
  </main>
</template>
