package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable audio source and immutable human revisions. Raw provider result is retained separately. */
@Service
final class TranscriptService {
    private static final Logger log=LoggerFactory.getLogger(TranscriptService.class);
    record Segment(String id,long startMs,long endMs,String text,String speakerId,String role,int track,Double confidence) {}
    record Revision(long expectedRevision,List<Segment> segments) {}
    private final JdbcTemplate db;private final TransactionTemplate tx;private final MediaService media;
    private final ObjectStore objects;private final Probe probe;private final ProbeTempFiles temps;
    private final JobLease leases;private final DeepgramAsr asr;private final ObjectMapper mapper;private final TranscriptEvents events;private final String ffmpeg;
    private final Semaphore slots=new Semaphore(2);
    TranscriptService(JdbcTemplate db,TransactionTemplate tx,MediaService media,ObjectStore objects,Probe probe,
            ProbeTempFiles temps,JobLease leases,DeepgramAsr asr,ObjectMapper mapper,TranscriptEvents events,
            @Value("${pf.asr.ffmpeg:ffmpeg}") String ffmpeg) {
        this.db=db;this.tx=tx;this.media=media;this.objects=objects;this.probe=probe;this.temps=temps;
        this.leases=leases;this.asr=asr;this.mapper=mapper;this.events=events;this.ffmpeg=ffmpeg;
    }
    Map<String,Object> accept(long owner,String mediaId,String idem) {
        if(idem==null || !idem.matches("[A-Za-z0-9_-]{8,100}"))throw ApiError.bad("invalid idempotency key");
        if(!asr.configured())throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE,"ASR_UNCONFIGURED");
        try{asr.validateConfig();}catch(DeepgramAsr.Failure failure){throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE,failure.code);}
        try{return tx.execute(status->{
            var rows=db.queryForList("SELECT content_version,content_sha256,duration_ms FROM pf_media WHERE id=? AND owner_id=? AND status='ACTIVE' FOR UPDATE",mediaId,owner);
            if(rows.size()!=1)throw ApiError.missing();var row=rows.getFirst();
            long version=((Number)row.get("content_version")).longValue();
            if(row.get("duration_ms") instanceof Number duration && duration.longValue()>900_000)throw ApiError.bad("ASR_DURATION_LIMIT");
            String config=asr.configVersion();String digest=hash((String)row.get("content_sha256")+"|"+version+"|"+config);
            var prior=db.queryForList("SELECT id,request_digest FROM pf_job WHERE owner_id=? AND operation='TRANSCRIBE' AND media_id=? AND media_version=? AND idempotency_key=?",owner,mediaId,version,idem);
            if(!prior.isEmpty()){
                if(!digest.equals(prior.getFirst().get("request_digest")))throw ApiError.conflict("idempotency key changed input");
                return Map.of("task_id",prior.getFirst().get("id"),"status","existing");
            }
            if(db.queryForObject("SELECT COUNT(*) FROM pf_transcript_source WHERE media_id=? AND media_version=?",Integer.class,mediaId,version)>0)
                throw ApiError.conflict("media already transcribed");
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO pf_job(id,owner_id,media_id,media_version,operation,idempotency_key,request_digest,status,deadline_at) VALUES(?,?,?,?,'TRANSCRIBE',?,?,'QUEUED',DATE_ADD(NOW(3),INTERVAL 300 SECOND))",id,owner,mediaId,version,idem,digest);
            db.update("INSERT INTO pf_transcribe_input(job_id,config_version) VALUES(?,?)",id,config);
            db.update("INSERT INTO pf_outbox(job_id) VALUES(?)",id);
            return Map.of("task_id",id,"status","QUEUED","media_version",version);
        });}catch(DuplicateKeyException error){throw ApiError.conflict("concurrent idempotency request");}
    }
    void run(String id) {
        if(!slots.tryAcquire())return;
        JobLease.Claim claim=null;Path source=null,wav=null;
        try {
            claim=leases.claim(id);if(claim==null)return;
            JobLease.Claim activeClaim=claim;
            var inputs=db.queryForList("SELECT config_version FROM pf_transcribe_input WHERE job_id=?",id);
            if(inputs.size()!=1 || !asr.configVersion().equals(inputs.getFirst().get("config_version")))throw new DeepgramAsr.Failure("ASR_CONFIG_CHANGED");
            source=temps.create();
            objects.copyTo(claim.objectKey(),source,33L*1024*1024);
            if(!hash(source).equals(claim.contentHash()))throw new DeepgramAsr.Failure("MEDIA_HASH_CHANGED");
            var details=probe.inspect(source,Duration.ofSeconds(12),()->!leases.renew(activeClaim));
            long duration=Math.round(((Number)details.get("duration_seconds")).doubleValue()*1000);
            if(duration<=0 || duration>900_000)throw new DeepgramAsr.Failure("ASR_DURATION_LIMIT");
            int trackCount=((Number)details.get("audio_stream_count")).intValue();
            if(trackCount!=1)throw new DeepgramAsr.Failure("ASR_MULTI_TRACK_UNSUPPORTED");
            long audioOffset=((Number)details.get("audio_start_ms")).longValue();
            if(audioOffset<0 || audioOffset>=duration)throw new DeepgramAsr.Failure("ASR_TIMELINE_INVALID");
            wav=source;
            if("MP4".equals(details.get("kind"))){wav=temps.create();extract(source,wav,claim);}
            Duration budget=claim.remaining().minusSeconds(20);
            if(budget.isNegative()||budget.isZero())throw new DeepgramAsr.Failure("ASR_TIMEOUT");
            var initial=asr.transcribe(wav,budget,()->!leases.renew(activeClaim),duration-audioOffset);
            String parameters=mapper.writeValueAsString(Map.of("request",mapper.readTree(initial.parameters()),"audio_start_ms",audioOffset,
                    "audio_stream_index",details.get("audio_stream_index"),"audio_stream_count",trackCount,"channels",details.get("channels")));
            var result=new DeepgramAsr.Result(initial.segments(),initial.raw(),initial.provider(),initial.model(),parameters);
            List<Segment> segments=result.segments().stream().map(s->new Segment(s.id(),s.startMs()+audioOffset,s.endMs()+audioOffset,s.text(),s.speakerId(),"UNKNOWN",s.track(),s.confidence())).toList();
            validate(segments,duration);
            events.partial(claim,segments);
            if(!publish(claim,result,segments,duration))leases.fail(claim,"TRANSCRIPT_NOT_PUBLISHED");
        } catch(Exception error) {
            log.warn("transcribe_failed task_id={} classification={}",id,error instanceof DeepgramAsr.Failure failure?failure.code:error.getClass().getSimpleName());
            if(claim!=null){String code=error instanceof DeepgramAsr.Failure failure?failure.code:"TRANSCRIBE_FAILED";
                events.failure(claim,code);leases.fail(claim,code);}
        } finally {
            if(wav!=null && !wav.equals(source))temps.cleanup(wav,null);
            temps.cleanup(source,null);slots.release();
        }
    }
    private void extract(Path source,Path wav,JobLease.Claim claim) throws Exception {
        Process process=new ProcessBuilder(ffmpeg,"-nostdin","-loglevel","error","-y","-i",source.toString(),
                "-map","0:a:0","-vn","-c:a","pcm_s16le","-ar","16000","-f","wav",wav.toString())
                .redirectErrorStream(true).start();
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(process.isAlive()){
                if(!leases.renew(claim)){process.destroyForcibly();throw new DeepgramAsr.Failure("ASR_CANCELLED");}
                if(System.nanoTime()>until){process.destroyForcibly();throw new DeepgramAsr.Failure("ASR_EXTRACT_TIMEOUT");}
                if(Files.size(wav)>128L*1024*1024){process.destroyForcibly();throw new DeepgramAsr.Failure("ASR_EXTRACT_SIZE");}
                process.waitFor(250,TimeUnit.MILLISECONDS);
            }
            if(process.exitValue()!=0 || Files.size(wav)==0 || Files.size(wav)>128L*1024*1024)throw new DeepgramAsr.Failure("ASR_EXTRACT_FAILED");
        } finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);}}
    }
    private boolean publish(JobLease.Claim claim,DeepgramAsr.Result result,List<Segment> segments,long duration) {
        var published=new java.util.concurrent.atomic.AtomicBoolean();
        tx.executeWithoutResult(status->{
            // Same lock order as deletion: media, then task. A late result cannot resurrect deleted media.
            var mediaRows=db.queryForList("SELECT status,content_version FROM pf_media WHERE object_key=? FOR UPDATE",claim.objectKey());
            if(mediaRows.size()!=1 || !"ACTIVE".equals(mediaRows.getFirst().get("status")) ||
                    ((Number)mediaRows.getFirst().get("content_version")).longValue()!=claim.mediaVersion())return;
            var rows=db.queryForList("SELECT owner_id,media_id,status,epoch,claim_token,cancel_requested,lease_until>NOW(3) AS live,deadline_at>NOW(3) AS deadline_live FROM pf_job WHERE id=? FOR UPDATE",claim.taskId());
            if(rows.size()!=1)return;var row=rows.getFirst();
            if(!"RUNNING".equals(row.get("status")) || ((Number)row.get("epoch")).longValue()!=claim.epoch() ||
                    !claim.token().equals(row.get("claim_token")) || truthy(row.get("cancel_requested")) ||
                    !truthy(row.get("live")) || !truthy(row.get("deadline_live")))return;
            String mediaId=(String)row.get("media_id");
            if(db.queryForObject("SELECT COUNT(*) FROM pf_transcript_source WHERE media_id=? AND media_version=?",Integer.class,mediaId,claim.mediaVersion())>0)return;
            String sourceId=UUID.randomUUID().toString();long owner=((Number)row.get("owner_id")).longValue();
            db.update("INSERT INTO pf_transcript_source(id,owner_id,media_id,media_version,current_revision) VALUES(?,?,?,?,1)",sourceId,owner,mediaId,claim.mediaVersion());
            save(sourceId,1,"MACHINE",0,result.provider(),result.model(),result.parameters(),segments);
            db.update("INSERT INTO pf_transcript_machine(source_id,provider_response) VALUES(?,?)",sourceId,result.raw());
            db.update("UPDATE pf_media SET duration_ms=? WHERE id=? AND status='ACTIVE'",duration,mediaId);
            String json;
            try{json=mapper.writeValueAsString(Map.of("source_id",sourceId,"source_revision",1,"provider",result.provider(),"model",result.model()));}
            catch(Exception error){throw new IllegalStateException(error);}
            if(db.update("UPDATE pf_job SET status='SUCCEEDED',result_json=?,lease_until=NULL,claim_token=NULL,updated_at=NOW(3) WHERE id=? AND status='RUNNING' AND epoch=? AND claim_token=?",json,claim.taskId(),claim.epoch(),claim.token())!=1)
                throw new IllegalStateException("claim changed during transcript publication");
            published.set(true);
        });
        return published.get();
    }
    Map<String,Object> get(long owner,String mediaId,Long requestedRevision) {
        media.owned(mediaId,owner);
        var sources=db.queryForList("SELECT s.id,s.current_revision,m.duration_ms FROM pf_transcript_source s JOIN pf_media m ON m.id=s.media_id WHERE s.owner_id=? AND s.media_id=? AND s.media_version=m.content_version AND m.status='ACTIVE'",owner,mediaId);
        if(sources.size()!=1)throw ApiError.missing();var source=sources.getFirst();String id=(String)source.get("id");
        long revision=requestedRevision==null?((Number)source.get("current_revision")).longValue():requestedRevision;
        var details=db.queryForList("SELECT origin,content_sha256,speaker_revision,provider,model,parameters_json FROM pf_transcript_revision WHERE source_id=? AND revision=?",id,revision);
        if(details.size()!=1)throw ApiError.missing();var d=details.getFirst();
        List<Segment> segments=db.query("SELECT segment_id,start_ms,end_ms,text_content,speaker_id,role_name,track_index,confidence FROM pf_transcript_segment WHERE source_id=? AND revision=? ORDER BY start_ms,segment_id",(rs,n)->new Segment(
                rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getObject(8,Double.class)),id,revision);
        Map<String,Object> result=new LinkedHashMap<>();result.put("source_id",id);result.put("media_id",mediaId);result.put("revision",revision);
        result.put("current_revision",source.get("current_revision"));result.put("source_sha256",d.get("content_sha256"));
        result.put("speaker_revision",d.get("speaker_revision"));result.put("origin",d.get("origin"));result.put("provider",d.get("provider"));
        result.put("model",d.get("model"));result.put("parameters",d.get("parameters_json"));result.put("duration_ms",source.get("duration_ms"));
        result.put("segments",segments);return result;
    }
    Map<String,Object> revise(long owner,String mediaId,Revision input) {
        if(input==null||input.expectedRevision()<1||input.segments()==null)throw ApiError.bad("invalid revision");
        return tx.execute(status->{
            var rows=db.queryForList("SELECT s.id,s.current_revision,m.duration_ms FROM pf_transcript_source s JOIN pf_media m ON m.id=s.media_id WHERE s.media_id=? AND s.owner_id=? AND s.media_version=m.content_version AND m.status='ACTIVE' FOR UPDATE",mediaId,owner);
            if(rows.size()!=1)throw ApiError.missing();var row=rows.getFirst();String id=(String)row.get("id");
            long current=((Number)row.get("current_revision")).longValue();
            if(current!=input.expectedRevision())throw ApiError.conflict("TRANSCRIPT_REVISION_CONFLICT");
            if(!(row.get("duration_ms") instanceof Number duration))throw ApiError.conflict("media duration unavailable");
            validate(input.segments(),duration.longValue());
            var prior=db.queryForList("SELECT provider,model,parameters_json,speaker_revision FROM pf_transcript_revision WHERE source_id=? AND revision=?",id,current).getFirst();
            List<Segment> old=db.query("SELECT segment_id,start_ms,end_ms,text_content,speaker_id,role_name,track_index,confidence FROM pf_transcript_segment WHERE source_id=? AND revision=? ORDER BY segment_id",(rs,n)->new Segment(
                    rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getObject(8,Double.class)),id,current);
            Map<String,String> roles=new java.util.HashMap<>();for(Segment s:old)roles.put(s.id(),s.speakerId()+"|"+s.role());
            boolean speakerChanged=input.segments().size()!=old.size();
            for(Segment s:input.segments())if(!java.util.Objects.equals(roles.get(s.id()),s.speakerId()+"|"+s.role()))speakerChanged=true;
            long speakerRevision=((Number)prior.get("speaker_revision")).longValue()+(speakerChanged?1:0);
            save(id,current+1,"HUMAN",speakerRevision,(String)prior.get("provider"),(String)prior.get("model"),
                    (String)prior.get("parameters_json"),input.segments());
            db.update("UPDATE pf_transcript_source SET current_revision=? WHERE id=? AND current_revision=?",current+1,id,current);
            return get(owner,mediaId,current+1);
        });
    }
    private void save(String source,long revision,String origin,long speakerRevision,String provider,String model,String params,List<Segment> segments) {
        String digest;
        try{digest=hash(mapper.writeValueAsString(segments));}catch(Exception error){throw new IllegalStateException(error);}
        db.update("INSERT INTO pf_transcript_revision(source_id,revision,origin,content_sha256,speaker_revision,provider,model,parameters_json) VALUES(?,?,?,?,?,?,?,?)",
                source,revision,origin,digest,speakerRevision,provider,model,params);
        for(Segment s:segments)db.update("INSERT INTO pf_transcript_segment(source_id,revision,segment_id,start_ms,end_ms,text_content,speaker_id,role_name,track_index,confidence) VALUES(?,?,?,?,?,?,?,?,?,?)",
                source,revision,s.id(),s.startMs(),s.endMs(),s.text(),s.speakerId(),s.role(),s.track(),s.confidence());
    }
    private static void validate(List<Segment> segments,long duration) {
        if(segments.isEmpty()||segments.size()>20_000)throw ApiError.bad("invalid segment count");
        var ids=new HashSet<String>();
        for(Segment s:segments){
            if(s==null||s.id()==null||!s.id().matches("[A-Za-z0-9_-]{1,64}")||!ids.add(s.id())||s.startMs()<0||s.endMs()<=s.startMs()||s.endMs()>duration+250||
                    s.text()==null||s.text().isBlank()||s.text().length()>10_000||s.speakerId()==null||!s.speakerId().matches("[A-Za-z0-9_-]{1,64}")||
                    s.role()==null||!List.of("ME","INTERVIEWER","UNKNOWN").contains(s.role())||s.track()<0||s.track()>31||
                    (s.confidence()!=null&&(!Double.isFinite(s.confidence())||s.confidence()<0||s.confidence()>1)))throw ApiError.bad("invalid segment");
        }
    }
    static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private static boolean truthy(Object value){return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue()==1;}
    static String hash(Path file) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];
        try(InputStream in=Files.newInputStream(file)){for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
