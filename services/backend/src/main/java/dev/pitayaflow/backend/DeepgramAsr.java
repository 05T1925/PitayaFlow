package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Administrator configured prerecorded Deepgram v1 adapter; no user supplied URL or paid default. */
@Component
final class DeepgramAsr {
    record Segment(String id,long startMs,long endMs,String text,String speakerId,int track,Double confidence) {}
    record Result(List<Segment> segments,String raw,String provider,String model,String parameters) {}
    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code=code; }
    }
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String endpoint,key,model,language,hints;
    private final boolean localFixture,multichannel;
    @Autowired DeepgramAsr(ObjectMapper mapper,@Value("${pf.asr.endpoint:}") String endpoint,
            @Value("${pf.asr.api-key:}") String key,@Value("${pf.asr.model:nova-3}") String model,
            @Value("${pf.asr.allow-local-fixture:false}") boolean localFixture,
            @Value("${pf.asr.language:multi}") String language,
            @Value("${pf.asr.language-hints:zh,en}") String hints,
            @Value("${pf.asr.multichannel:true}") boolean multichannel) {
        this.mapper=mapper;this.endpoint=endpoint;this.key=key;this.model=model;this.localFixture=localFixture;
        this.language=language;this.hints=hints;this.multichannel=multichannel;
        this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    DeepgramAsr(ObjectMapper mapper,String endpoint,String key,String model,boolean localFixture){
        this(mapper,endpoint,key,model,localFixture,"multi","zh,en",true);
    }
    boolean configured() { return !endpoint.isBlank() && !key.isBlank() && !model.isBlank(); }
    String configVersion() {
        try {
            byte[] value=MessageDigest.getInstance("SHA-256").digest((endpoint+"|"+model+"|"+key+"|"+language+"|"+hints+"|"+multichannel+"|utterances,diarize_model=latest,punctuate|v2").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        } catch(Exception error) { throw new IllegalStateException(error); }
    }
    private URI uri() throws Failure {
        if(!configured()) throw new Failure("ASR_UNCONFIGURED");
        URI base;
        try {base=URI.create(endpoint);}catch(Exception error){throw new Failure("ASR_CONFIG_INVALID");}
        boolean loopback="127.0.0.1".equals(base.getHost()) || "localhost".equals(base.getHost());
        if(!"https".equals(base.getScheme()) && !(localFixture && loopback && "http".equals(base.getScheme())))
            throw new Failure("ASR_CONFIG_INVALID");
        if(base.getUserInfo()!=null || base.getFragment()!=null || base.getQuery()!=null || !"/v1/listen".equals(base.getPath()))
            throw new Failure("ASR_CONFIG_INVALID");
        var supported=List.of("zh","zh-CN","zh-Hans","zh-HK","zh-TW","zh-Hant","en","en-US","en-GB");
        if(!"nova-3".equals(model)&&!"nova-3-general".equals(model))throw new Failure("ASR_MODEL_UNSUPPORTED");
        if(!"multi".equals(language)&&!supported.contains(language))throw new Failure("ASR_LANGUAGE_UNSUPPORTED");
        StringBuilder query=new StringBuilder(endpoint).append("?model=").append(URLEncoder.encode(model,StandardCharsets.UTF_8))
            .append("&language=").append(URLEncoder.encode(language,StandardCharsets.UTF_8))
            .append("&multichannel=").append(multichannel).append("&utterances=true&diarize_model=latest&punctuate=true");
        if(!hints.isBlank()){
            if(!"multi".equals(language))throw new Failure("ASR_HINTS_REQUIRE_MULTI");
            for(String hint:hints.split(",",-1)){
                if(!supported.contains(hint))throw new Failure("ASR_LANGUAGE_HINT_INVALID");
                query.append("&language_hint=").append(URLEncoder.encode(hint,StandardCharsets.UTF_8));
            }
        }
        return URI.create(query.toString());
    }
    void validateConfig() throws Failure {uri();}
    Result transcribe(Path wav,Duration budget,BooleanSupplier cancelled,long mediaDurationMs) throws Exception {
        URI url=uri();
        if(mediaDurationMs<=0 || mediaDurationMs>900_000) throw new Failure("ASR_DURATION_LIMIT");
        long until=System.nanoTime()+budget.toNanos();
        for(int attempt=0;attempt<3;attempt++) {
            if(cancelled.getAsBoolean()) throw new Failure("ASR_CANCELLED");
            long left=until-System.nanoTime();if(left<=0) throw new Failure("ASR_TIMEOUT");
            HttpRequest request=HttpRequest.newBuilder(url).timeout(Duration.ofNanos(left))
                .header("Authorization","Token "+key).header("Content-Type","audio/wav")
                .POST(HttpRequest.BodyPublishers.ofFile(wav)).build();
            var future=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response;
            try {
                while(true) {
                    if(cancelled.getAsBoolean()) {future.cancel(true);throw new Failure("ASR_CANCELLED");}
                    left=until-System.nanoTime();if(left<=0){future.cancel(true);throw new Failure("ASR_TIMEOUT");}
                    try {response=future.get(Math.min(250,Math.max(1,TimeUnit.NANOSECONDS.toMillis(left))),TimeUnit.MILLISECONDS);break;}
                    catch(TimeoutException wait){/* Poll the durable claim while HTTP is in flight. */}
                }
            } catch(Exception error) {future.cancel(true);if(error instanceof Failure failure)throw failure;
                if(error instanceof java.util.concurrent.ExecutionException execution && execution.getCause() instanceof java.net.http.HttpTimeoutException)
                    throw new Failure("ASR_TIMEOUT");
                throw new Failure("ASR_NETWORK");}
            int status=response.statusCode();
            if(status==429 || status>=500) {
                response.body().close();
                if(attempt==2)throw new Failure("ASR_RETRY_EXHAUSTED");
                for(int i=0;i<5*(attempt+1);i++) {if(cancelled.getAsBoolean())throw new Failure("ASR_CANCELLED");Thread.sleep(100);}
                continue;
            }
            if(status>=400){response.body().close();throw new Failure("ASR_REJECTED_"+status);}
            if(status!=200){response.body().close();throw new Failure("ASR_HTTP_"+status);}
            return parse(readBounded(response.body(),until,cancelled),mediaDurationMs);
        }
        throw new Failure("ASR_RETRY_EXHAUSTED");
    }
    private static String readBounded(InputStream input,long until,BooleanSupplier cancelled) throws Exception {
        var reader=Executors.newVirtualThreadPerTaskExecutor();
        var work=reader.submit(()->readLimited(input,until,cancelled));
        try{
            while(true){
                if(cancelled.getAsBoolean())throw new Failure("ASR_CANCELLED");
                long left=until-System.nanoTime();if(left<=0)throw new Failure("ASR_TIMEOUT");
                try{return work.get(Math.min(250,Math.max(1,TimeUnit.NANOSECONDS.toMillis(left))),TimeUnit.MILLISECONDS);}
                catch(TimeoutException waiting){/* Poll the claim and deadline during response streaming. */}
                catch(ExecutionException error){if(error.getCause() instanceof Exception cause)throw cause;throw new Failure("ASR_NETWORK");}
            }
        }finally{try{input.close();}catch(Exception ignored){}work.cancel(true);reader.shutdownNow();}
    }
    private static String readLimited(InputStream input,long until,BooleanSupplier cancelled) throws Exception {
        try(input;var output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int total=0;
            for(int count;(count=input.read(buffer))!=-1;){
                if(cancelled.getAsBoolean())throw new Failure("ASR_CANCELLED");
                if(System.nanoTime()>until)throw new Failure("ASR_TIMEOUT");
                total+=count;if(total>8_000_000)throw new Failure("ASR_RESPONSE_SIZE");
                output.write(buffer,0,count);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
    Result parse(String raw,long mediaDurationMs) throws Failure {
        if(raw==null || raw.length()>8_000_000)throw new Failure("ASR_RESPONSE_SIZE");
        JsonNode root;
        try{root=mapper.readTree(raw);}catch(Exception error){throw new Failure("ASR_MALFORMED");}
        JsonNode utterances=root.path("results").path("utterances");
        if(!utterances.isArray() || utterances.isEmpty())throw new Failure("ASR_UNTIMED");
        if(utterances.size()>20_000)throw new Failure("ASR_RESPONSE_SIZE");
        List<Segment> segments=new ArrayList<>();int index=0;
        for(JsonNode item:utterances) {
            JsonNode start=item.path("start"),end=item.path("end"),text=item.path("transcript");
            if(!start.isNumber() || !end.isNumber() || !text.isTextual())throw new Failure("ASR_MALFORMED");
            double a=start.doubleValue(),b=end.doubleValue();
            if(!Double.isFinite(a)||!Double.isFinite(b)||a<0||b<=a||b*1000>mediaDurationMs+250)throw new Failure("ASR_TIME_INVALID");
            String value=text.textValue();if(value.isBlank()||value.length()>10_000)throw new Failure("ASR_TEXT_INVALID");
            JsonNode speaker=item.path("speaker");
            String speakerId="unknown";
            if(!speaker.isMissingNode()&&!speaker.isNull()){
                if(!speaker.isIntegralNumber()||!speaker.canConvertToLong()||speaker.longValue()<0||speaker.longValue()>=1000)throw new Failure("ASR_SPEAKER_INVALID");
                speakerId="speaker-"+speaker.longValue();
            }
            JsonNode channel=item.path("channel");int track=0;
            if(!channel.isMissingNode()&&!channel.isNull()){
                if(!channel.isIntegralNumber()||!channel.canConvertToLong()||channel.longValue()<0||channel.longValue()>=32)throw new Failure("ASR_CHANNEL_INVALID");
                track=(int)channel.longValue();
            }
            JsonNode confidence=item.path("confidence");Double score=confidence.isNumber() && Double.isFinite(confidence.doubleValue()) && confidence.doubleValue()>=0 && confidence.doubleValue()<=1 ? confidence.doubleValue() : null;
            segments.add(new Segment("seg-"+String.format("%05d",++index),Math.round(a*1000),Math.round(b*1000),value,speakerId,track,score));
        }
        String provider=localFixture && (endpoint.startsWith("http://127.0.0.1:") || endpoint.startsWith("http://localhost:")) ? "deepgram-fixture" : "deepgram";
        String parameters;
        try{parameters=mapper.writeValueAsString(java.util.Map.of("utterances",true,"diarize_model","latest","punctuate",true,
                "language",language,"language_hints",hints,"multichannel",multichannel,"track","first-audio"));}
        catch(Exception error){throw new Failure("ASR_CONFIG_INVALID");}
        return new Result(List.copyOf(segments),raw,provider,model,parameters);
    }
}
