import http from 'node:http';
const port=Number(process.env.PF_ASR_FIXTURE_PORT);
const key=process.env.PF_ASR_API_KEY;
if(!Number.isInteger(port)||port<1024||port>65535||!key)throw new Error('fixture configuration missing');
const response=JSON.stringify({results:{utterances:[
  {start:0.15,end:0.9,transcript:'合成问题：Explain the queue.',speaker:0,channel:0,confidence:0.9},
  {start:0.85,end:1.6,transcript:'合成回答：I used a queue.',speaker:1,channel:1,confidence:0.8}
]}});
http.createServer((request,res)=>{
  if(request.method==='GET'&&request.url==='/health'){res.writeHead(200,{'content-type':'text/plain'});res.end('fixture');return;}
  if(request.method!=='POST'||!request.url?.startsWith('/v1/listen?')||request.headers.authorization!==`Token ${key}`){res.writeHead(404);res.end();return;}
  const query=new URL(request.url,'http://127.0.0.1').searchParams;
  if(query.get('model')!=='nova-3'||query.get('language')!=='multi'||query.get('multichannel')!=='true'||
      !query.getAll('language_hint').includes('zh')||!query.getAll('language_hint').includes('en')){
    res.writeHead(400,{'content-type':'text/plain'});res.end('fixture request strategy mismatch');return;
  }
  let size=0;request.on('data',chunk=>{size+=chunk.length;if(size>33*1024*1024)request.destroy();});
  request.on('end',()=>{if(size<44){res.writeHead(400);res.end();return;}
    res.writeHead(200,{'content-type':'application/json','cache-control':'no-store'});res.end(response);});
}).listen(port,'127.0.0.1');
