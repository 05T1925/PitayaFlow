<script setup>
import {ref,watch,onBeforeUnmount,computed} from 'vue';
const props=defineProps({token:{type:String,required:true},mediaId:{type:String,required:true},transcript:{type:Object,default:null}});
const emit=defineEmits(['seek']);
const outline=ref(null),assignments=ref({}),parents=ref({}),reports=ref([]),selectedId=ref(''),selectedReport=ref(null),error=ref(''),notice=ref(''),busy=ref(false);
const materials=ref([]),selection=ref({revision:0,materials:[]}),selectedMaterialIds=ref([]),pasteName=ref(''),pasteText=ref(''),practiceDraft=ref({}),practiceRevision=ref({});
let poll=null;
const current=computed(()=>props.transcript&&props.transcript.revision===props.transcript.current_revision);
const groupIds=computed(()=>[...new Set((props.transcript?.segments||[]).map(segment=>assignments.value[segment.id])
  .filter(item=>item?.kind==='question'&&item.group).map(item=>item.group))]);
async function api(path,options={}){
  const headers=new Headers(options.headers||{});headers.set('Authorization',`Bearer ${props.token}`);
  const response=await fetch(`/api/v1/media/${props.mediaId}/review${path}`,{...options,headers,cache:'no-store'});
  if(!response.ok)throw new Error(`${response.status} ${(await response.text()).slice(0,180)}`);
  return response.json();
}
async function rootApi(path,options={}){const headers=new Headers(options.headers||{});headers.set('Authorization',`Bearer ${props.token}`);
  const response=await fetch(`/api/v1${path}`,{...options,headers,cache:'no-store'});
  if(!response.ok)throw new Error(`${response.status} ${(await response.text()).slice(0,180)}`);
  return response.status===204?null:response.json();}
async function run(action){error.value='';notice.value='';busy.value=true;try{await action();}catch(e){error.value=String(e.message||e);}finally{busy.value=false;}}
function defaultAssignments(){
  const value={};let group=0;
  for(const segment of props.transcript?.segments||[]){
    if(segment.role==='INTERVIEWER'){group++;value[segment.id]={group:`q-${group}`,kind:'question'};}
    else if(segment.role==='ME'&&group)value[segment.id]={group:`q-${group}`,kind:'answer'};
    else value[segment.id]={group:'',kind:'unassigned'};
  }
  return value;
}
function applyOutline(){
  const value=defaultAssignments();
  parents.value={};
  if(outline.value?.revision>0){
    for(const segment of props.transcript?.segments||[])value[segment.id]={group:'',kind:'unassigned'};
    for(const group of outline.value.groups||[]){
      parents.value[group.id]=group.parent_id||'';
      for(const id of group.question_ids)value[id]={group:group.id,kind:'question'};
      for(const id of group.answer_ids)value[id]={group:group.id,kind:'answer'};
    }
  }
  assignments.value=value;
}
async function load(){if(!props.token||!props.mediaId||!props.transcript)return;
  const mediaId=props.mediaId;
  const [nextOutline,nextReports,nextMaterials,nextSelection,nextPractice]=await Promise.all([
    api('/outline'),api(''),rootApi('/materials'),rootApi(`/media/${props.mediaId}/materials`),api('/practice')]);
  if(mediaId!==props.mediaId)return;
  outline.value=nextOutline;reports.value=nextReports;applyOutline();
  materials.value=nextMaterials;selection.value=nextSelection;selectedMaterialIds.value=nextSelection.materials.map(item=>item.material_id);
  practiceDraft.value=Object.fromEntries(nextPractice.map(item=>[item.question_id,item.text]));
  practiceRevision.value=Object.fromEntries(nextPractice.map(item=>[item.question_id,item.revision]));
  if(selectedId.value&&nextReports.some(item=>item.task_id===selectedId.value))await choose(selectedId.value);
}
async function pasteMaterial(){await run(async()=>{const created=await rootApi('/materials/paste',{method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify({name:pasteName.value,text:pasteText.value})});
  pasteText.value='';notice.value=created.status==='ERROR'?`提取失败：${created.error}`:'私有材料已导入；请明确选择本场材料。';await load();});}
async function uploadMaterial(event){const file=event.target.files?.[0];if(!file)return;await run(async()=>{
  const body=new FormData();body.append('file',file);const created=await rootApi('/materials',{method:'POST',body});event.target.value='';
  notice.value=created.status==='ERROR'?`提取失败：${created.error}`:'私有材料已导入；请明确选择本场材料。';await load();});}
async function deleteMaterial(id){await run(async()=>{await rootApi(`/materials/${id}`,{method:'DELETE'});notice.value='材料已删除；旧报告将标为历史过期。';await load();});}
async function saveSelection(){await run(async()=>{const saved=await rootApi(`/media/${props.mediaId}/materials`,{method:'PUT',
  headers:{'Content-Type':'application/json'},body:JSON.stringify({expected_revision:selection.value.revision,material_ids:selectedMaterialIds.value})});
  selection.value=saved;notice.value='本场材料选择已保存；旧报告需重新生成。';await load();});}
async function savePractice(question){await run(async()=>{const saved=await api(`/${selectedId.value}/practice/${question.id}`,{method:'PUT',
  headers:{'Content-Type':'application/json'},body:JSON.stringify({expected_revision:practiceRevision.value[question.id]||0,text:practiceDraft.value[question.id]})});
  practiceRevision.value[question.id]=saved.revision;selectedReport.value.stale=true;notice.value='文字练习稿已保存为不可变修订；请重新生成依赖报告。';});}
async function saveOutline(){await run(async()=>{
  if(!current.value)throw new Error('先切到最新转写修订');
  const groups=new Map();
  for(const segment of props.transcript.segments){
    const item=assignments.value[segment.id];if(!item||item.kind==='unassigned')continue;
    if(!/^[A-Za-z0-9_-]{1,64}$/.test(item.group))throw new Error('题组 ID 仅允许字母、数字、下划线、连字符');
    if(!groups.has(item.group))groups.set(item.group,{id:item.group,question_ids:[],answer_ids:[]});
    groups.get(item.group)[item.kind==='question'?'question_ids':'answer_ids'].push(segment.id);
  }
  for(const group of groups.values())if(parents.value[group.id])group.parent_id=parents.value[group.id];
  const saved=await api('/outline',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({
    expected_revision:outline.value.revision,transcript_revision:props.transcript.revision,groups:[...groups.values()]})});
  outline.value=saved;notice.value=`题目边界已保存为修订 ${saved.revision}；请重新生成报告。`;await load();
});}
async function generate(){await run(async()=>{
  if(!current.value)throw new Error('历史转写只读；先切到最新修订');
  const task=await api('',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()}});
  selectedId.value=task.task_id;notice.value='逐题复盘已受理；结果使用真实 DSH 与脚本模型，仍需人工核对。';
  await load();beginPoll(task.task_id);
});}
function beginPoll(id){if(poll)clearInterval(poll);poll=setInterval(async()=>{
  try{const item=await api(`/${id}`);if(selectedId.value!==id)return;
    selectedReport.value=item;if(['SUCCEEDED','FAILED','CANCELLED'].includes(item.status)){clearInterval(poll);poll=null;reports.value=await api('');}
  }catch(e){error.value=String(e.message);clearInterval(poll);poll=null;}
},1500);}
async function choose(id){await run(async()=>{selectedId.value=id;selectedReport.value=await api(`/${id}`);
  if(['QUEUED','RUNNING'].includes(selectedReport.value.status))beginPoll(id);
});}
async function exportReport(){await run(async()=>{
  if(!selectedId.value)throw new Error('先选择报告版本');
  const response=await fetch(`/api/v1/media/${props.mediaId}/review/${selectedId.value}/export`,{
    headers:{Authorization:`Bearer ${props.token}`},cache:'no-store'});
  if(!response.ok)throw new Error(`导出失败 ${response.status}`);
  const blob=await response.blob(),url=URL.createObjectURL(blob),link=document.createElement('a');
  link.href=url;link.download=`question-review-${selectedId.value}.txt`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
});}
async function copyReport(){await run(async()=>{if(!selectedId.value)throw new Error('先选择报告版本');
  const response=await fetch(`/api/v1/media/${props.mediaId}/review/${selectedId.value}/export`,
    {headers:{Authorization:`Bearer ${props.token}`},cache:'no-store'});
  if(!response.ok)throw new Error(`复制失败 ${response.status}`);
  if(!navigator.clipboard?.writeText)throw new Error('浏览器不支持剪贴板写入');
  await navigator.clipboard.writeText(await response.text());notice.value='所选版本文字报告已复制。';
});}
function questionStatusLabel(status){return ({reviewed:'待人工核对',await_confirmation:'待确认',insufficient_evidence:'无法评价',cannot_evaluate:'无法评价'})[status]||'待确认';}
function stamp(ms){const sec=Math.floor(ms/1000);return `${String(Math.floor(sec/60)).padStart(2,'0')}:${String(sec%60).padStart(2,'0')}`;}
watch(()=>[props.mediaId,props.transcript?.revision,props.token],()=>{if(poll)clearInterval(poll);poll=null;
  outline.value=null;reports.value=[];selectedReport.value=null;selectedId.value='';assignments.value=defaultAssignments();parents.value={};if(props.transcript)run(load);
},{immediate:true});
onBeforeUnmount(()=>{if(poll)clearInterval(poll);});
</script>

<template>
  <section class="panel review"><div class="row"><div><h2>逐题复盘</h2><p>全份转写 · 脚本模型合成草稿 · 材料仅作评价背景</p></div><button :disabled="busy||!current" @click="generate">生成新报告</button><button @click="run(load)">刷新</button></div>
    <p v-if="error" class="message bad" role="alert">{{error}}</p><p v-if="notice" class="message">{{notice}}</p>
    <details><summary>本场私有背景材料（默认不选择）</summary>
      <p>材料不会成为“实际说了什么”的证据。选择、修订或删除后旧报告会过期。</p>
      <label>材料名称<input v-model="pasteName" aria-label="材料名称"></label>
      <label>粘贴文字<textarea v-model="pasteText" rows="3" aria-label="粘贴材料文字"></textarea></label>
      <button :disabled="busy||!pasteName.trim()||!pasteText.trim()" @click="pasteMaterial">导入粘贴材料</button>
      <label class="upload">导入 TXT / MD / PDF / DOCX<input type="file" accept=".txt,.md,.pdf,.docx" @change="uploadMaterial"></label>
      <div v-for="item in materials" :key="item.material_id" class="review-edit">
        <label><input type="checkbox" :value="item.material_id" v-model="selectedMaterialIds" :disabled="item.status!=='ACTIVE'">{{item.name}} · {{item.kind}} · 修订 {{item.revision}} · {{item.status}}</label>
        <span v-if="item.extraction_error">{{item.extraction_error}}</span><button @click="deleteMaterial(item.material_id)">删除</button>
      </div><button :disabled="busy" @click="saveSelection">保存本场选择</button><small>选择修订 {{selection.revision}}</small>
    </details>
    <p v-if="outline">题目边界修订 {{outline.revision}} · 转写修订 {{transcript?.revision}} / {{transcript?.current_revision}}</p>
    <details><summary>调整题目边界（同一题组 ID 可合并；另起 ID 可拆分）</summary>
      <p>角色请先在句段修订中确认。未分配句段保留在报告末尾。保存边界后需重新生成。</p>
      <div v-for="segment in transcript?.segments||[]" :key="segment.id" class="review-edit">
        <button @click="emit('seek',segment)">▶ {{stamp(segment.startMs)}} {{segment.text}}</button>
        <span>{{segment.role}}</span><select v-model="assignments[segment.id].kind" :aria-label="`${segment.id} 归属`">
          <option value="question">问题</option><option value="answer">回答</option><option value="unassigned">未分配</option>
        </select><input v-model="assignments[segment.id].group" :aria-label="`${segment.id} 题组`" placeholder="q-1">
      </div><button :disabled="busy||!current" @click="saveOutline">保存边界修订</button>
      <div v-for="group in groupIds" :key="`parent-${group}`" class="review-parent"><span>{{group}} 的追问父题（可留空）</span><input v-model="parents[group]" :aria-label="`${group} 追问父题`" placeholder="如 q-1"></div>
    </details>
    <div class="review-history"><h3>报告版本</h3><button v-for="item in reports" :key="item.task_id" @click="choose(item.task_id)">
      {{item.task_id.slice(0,8)}} · {{item.status}} · 转写 {{item.source_revision}} / 边界 {{item.outline_revision}} <strong v-if="item.stale">历史过期</strong>
    </button></div>
    <div v-if="selectedReport" class="review-result"><div class="row"><h3>所选版本 {{selectedId.slice(0,8)}} · {{selectedReport.status}}</h3><button v-if="selectedReport.report" @click="copyReport">复制文字报告</button><button v-if="selectedReport.report" @click="exportReport">导出所选版本文字报告</button></div>
      <p v-if="selectedReport.stale">历史报告已过期；请以当前修订重新生成。</p><p v-if="selectedReport.failure_code">失败：{{selectedReport.failure_code}}，可重新生成。</p>
      <template v-if="selectedReport.report"><p>来源 {{selectedReport.report.source_id}} · 转写 {{selectedReport.report.source_revision}} · 角色 {{selectedReport.report.speaker_revision}} · 边界 {{selectedReport.report.outline_revision}}</p>
        <p>录音转写是发言来源；背景材料仅供评价。分析模型为脚本合成；练习稿不是录音原话。</p>
        <p>所选材料版本：<span v-for="item in selectedReport.report.materials||[]" :key="item.material_id">{{item.name||item.material_id.slice(0,8)}} r{{item.revision}}；</span><span v-if="!selectedReport.report.materials?.length">无</span></p>
        <article v-for="(question,index) in selectedReport.report.questions" :key="question.id" class="review-question">
          <h4>题 {{index+1}} · {{questionStatusLabel(question.status)}} <small v-if="question.parent_id">追问 {{question.parent_id}}</small></h4>
          <h5>原问题</h5><button v-for="ref in question.question_refs" :key="ref.segment_id" @click="emit('seek',{startMs:ref.start_ms})">▶ {{stamp(ref.start_ms)}} {{ref.text}}</button>
          <h5>原回答</h5><button v-for="ref in question.answer_refs" :key="ref.segment_id" @click="emit('seek',{startMs:ref.start_ms})">▶ {{stamp(ref.start_ms)}} {{ref.text}}</button>
          <p>已覆盖：{{question.covered_points.join('；')||'暂无可确认条目'}}</p><p>缺口：{{question.gaps.join('；')||'暂无可确认缺口'}}</p>
          <p>原因：{{question.reason}}</p><p>准备建议（非原话）：{{question.advice}}</p>
          <h5>材料背景（非发言证据）</h5><p v-for="source in question.material_background||[]" :key="`${source.material_id}-${source.revision}`">{{source.material_id.slice(0,8)}} r{{source.revision}}：{{source.quote}}</p>
          <h5>练习卡</h5><p>目标：{{question.practice_card?.goal||'历史版本无练习卡'}}</p><p>证据缺口：{{question.practice_card?.evidence_gap||'待核查'}}</p>
          <p>下一步：{{question.practice_card?.next_step||'待核查'}}</p><p>准备性追问：{{question.practice_card?.follow_up||'待核查'}}</p>
          <p>参考表达（待核查）：{{question.practice_card?.reference_answer||'待核查'}}</p><p>待核查：{{question.practice_card?.to_verify?.join('；')||'无'}}</p>
          <h5>文字新版回答（练习稿，断言待核查）</h5><p>{{question.revised_answer}}</p>
          <label>编辑我的文字练习稿<textarea v-model="practiceDraft[question.id]" rows="3" :aria-label="`${question.id} 文字练习稿`" :placeholder="question.revised_answer"></textarea></label>
          <button :disabled="busy||selectedReport.stale||!practiceDraft[question.id]?.trim()" @click="savePractice(question)">保存练习稿修订 {{practiceRevision[question.id]||0}}</button>
        </article><h4>未分配／待确认</h4><button v-for="ref in selectedReport.report.unassigned_refs" :key="ref.segment_id" @click="emit('seek',{startMs:ref.start_ms})">▶ {{stamp(ref.start_ms)}} {{ref.text}}</button>
      </template>
    </div>
  </section>
</template>
