const tokens=new Map();
const mediaPath=/^\/api\/v1\/media\/[^/]+\/(play|download)$/;
const ownClient=client=>client?.id&&new URL(client.url).origin===self.location.origin;

self.addEventListener('install',event=>event.waitUntil(self.skipWaiting()));
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
self.addEventListener('message',event=>{
  if(event.data?.type!=='PF_TOKEN'||!ownClient(event.source))return;
  if(typeof event.data.token==='string'&&event.data.token)tokens.set(event.source.id,event.data.token);
  else tokens.delete(event.source.id);
});

async function tokenFor(clientId){
  if(!clientId)return null;
  const client=await self.clients.get(clientId);
  if(!ownClient(client))return null;
  if(tokens.has(clientId))return tokens.get(clientId);
  // A service worker may restart between media range requests. Ask only the
  // browser client that issued this fetch; the reply channel is never shared.
  const channel=new MessageChannel();
  const reply=new Promise(resolve=>{
    const timer=setTimeout(()=>resolve(null),1200);
    channel.port1.onmessage=event=>{clearTimeout(timer);resolve(event.data?.token||null);};
  });
  try{client.postMessage({type:'PF_TOKEN_REQUEST'},[channel.port2]);}
  catch{channel.port1.close();return null;}
  const token=await reply;
  channel.port1.close();
  if(typeof token!=='string'||!token)return null;
  tokens.set(clientId,token);
  return token;
}

self.addEventListener('fetch',event=>{
  const url=new URL(event.request.url);
  if(url.origin!==self.location.origin||!mediaPath.test(url.pathname)||event.request.method!=='GET')return;
  event.respondWith((async()=>{
    const token=await tokenFor(event.clientId);
    if(!token)return new Response('login required',{status:401});
    const headers=new Headers(event.request.headers);
    headers.set('Authorization',`Bearer ${token}`);
    // crossorigin="anonymous" puts native media fetches in CORS mode.
    return fetch(new Request(event.request,{mode:'cors',headers,credentials:'same-origin',cache:'no-store',redirect:'manual'}));
  })());
});
