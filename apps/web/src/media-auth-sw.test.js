import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {runInNewContext} from 'node:vm';
import {expect,test} from 'vitest';

const script=readFileSync(resolve(process.cwd(),'apps/web/public/media-auth-sw.js'),'utf8');
const origin='http://127.0.0.1:19090';
const clients=new Map([
  ['tab-a',{id:'tab-a',url:`${origin}/`,postMessage:(_message,ports)=>ports[0].postMessage({token:''})}],
  ['tab-b',{id:'tab-b',url:`${origin}/`,postMessage:(_message,ports)=>ports[0].postMessage({token:''})}]
]);
class TestChannel {
  constructor(){this.port1={onmessage:null,close(){}};this.port2={postMessage:data=>queueMicrotask(()=>this.port1.onmessage?.({data}))};}
}
function worker(){
  const handlers={};const requests=[];
  const self={location:{origin},clients:{get:async id=>clients.get(id)},addEventListener:(name,handler)=>{handlers[name]=handler},skipWaiting:async()=>{}};
  self.clients.claim=async()=>{};
  runInNewContext(script,{self,URL,Map,Headers,Request,Response,MessageChannel:TestChannel,setTimeout,clearTimeout,fetch:async request=>{
    requests.push(request);
    const owner=request.headers.get('Authorization')?.slice(7);
    const media=new URL(request.url).pathname.includes('/media/a/')?'owner-a':'owner-b';
    return new Response('',{status:owner===media?206:404});
  }});
  const message=(id,token)=>handlers.message({source:clients.get(id),data:{type:'PF_TOKEN',token}});
  const play=async(id,resource='a')=>{
    let reply;handlers.fetch({clientId:id,request:new Request(`${origin}/api/v1/media/${resource}/play`,{headers:{Range:'bytes=100-199'},mode:'cors'}),respondWith:value=>{reply=value}});
    return reply;
  };
  return {message,play,requests};
}
test('media auth stays with the issuing tab and survives worker restart by client handshake',async()=>{
  const first=worker();first.message('tab-a','owner-a');first.message('tab-b','owner-b');
  expect((await first.play('tab-a','a')).status).toBe(206);
  expect((await first.play('tab-a','b')).status).toBe(404);
  expect((await first.play('tab-b','b')).status).toBe(206);
  first.message('tab-b','');
  expect((await first.play('tab-a','a')).status).toBe(206);
  expect((await first.play('tab-b','b')).status).toBe(401);
  expect(first.requests[0].headers.get('Range')).toBe('bytes=100-199');
  expect(first.requests[0].mode).toBe('cors');
  expect(first.requests[0].redirect).toBe('manual');
  const restarted=worker();
  clients.get('tab-a').postMessage=(message,ports)=>{expect(message.type).toBe('PF_TOKEN_REQUEST');ports[0].postMessage({token:'owner-a'});};
  clients.get('tab-b').postMessage=(_message,ports)=>ports[0].postMessage({token:''});
  expect((await restarted.play('tab-a','a')).status).toBe(206);
  expect((await restarted.play('tab-b','b')).status).toBe(401);
  expect((await restarted.play('unknown','a')).status).toBe(401);
  expect((await restarted.play('', 'a')).status).toBe(401);
});
