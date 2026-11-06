package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Durable cursor events. Segments are provisional until the transcript job publishes. */
@Service
final class TranscriptEvents {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper mapper;
    TranscriptEvents(JdbcTemplate db,TransactionTemplate tx,ObjectMapper mapper){this.db=db;this.tx=tx;this.mapper=mapper;}
    void partial(JobLease.Claim claim,List<TranscriptService.Segment> segments){
        tx.executeWithoutResult(state->{
            var media=db.queryForList("SELECT status FROM pf_media WHERE object_key=? FOR UPDATE",claim.objectKey());
            var rows=db.queryForList("SELECT status,epoch,claim_token,cancel_requested FROM pf_job WHERE id=? FOR UPDATE",claim.taskId());
            if(rows.size()!=1||media.size()!=1)throw new IllegalStateException("transcript event task absent");var row=rows.getFirst();
            if(!"RUNNING".equals(row.get("status"))||!"ACTIVE".equals(media.getFirst().get("status"))||
                    ((Number)row.get("epoch")).longValue()!=claim.epoch()||!claim.token().equals(row.get("claim_token"))||yes(row.get("cancel_requested")))
                throw new IllegalStateException("transcript event claim stale");
            long seq=db.queryForObject("SELECT COALESCE(MAX(seq),0) FROM pf_transcript_event WHERE job_id=?",Long.class,claim.taskId());
            for(var segment:segments){
                String json;try{json=mapper.writeValueAsString(segment);}catch(Exception error){throw new IllegalStateException(error);}
                db.update("INSERT INTO pf_transcript_event(job_id,seq,kind,start_ms,end_ms,payload_json) VALUES(?,?,'partial_segment',?,?,?)",
                        claim.taskId(),++seq,segment.startMs(),segment.endMs(),json);
            }
        });
    }
    void failure(JobLease.Claim claim,String code){
        try{long seq=db.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM pf_transcript_event WHERE job_id=?",Long.class,claim.taskId());
            Long duration=db.queryForObject("SELECT m.duration_ms FROM pf_job j JOIN pf_media m ON m.id=j.media_id WHERE j.id=?",Long.class,claim.taskId());
            db.update("INSERT INTO pf_transcript_event(job_id,seq,kind,start_ms,end_ms,payload_json) VALUES(?,?,'failed_interval',0,?,?)",
                claim.taskId(),seq,duration==null?0:duration,"{\"code\":\""+code.replaceAll("[^A-Z0-9_]","")+"\"}");}
        catch(Exception ignored){/* terminal job failure remains authoritative */}
    }
    Map<String,Object> state(long owner,String mediaId,String taskId){
        var rows=db.queryForList("SELECT j.status,j.cancel_requested,m.status AS media_status FROM pf_job j JOIN pf_media m ON m.id=j.media_id WHERE j.id=? AND j.owner_id=? AND j.media_id=? AND j.operation='TRANSCRIBE'",taskId,owner,mediaId);
        if(rows.size()!=1||!"ACTIVE".equals(rows.getFirst().get("media_status")))throw ApiError.missing();return rows.getFirst();
    }
    List<Map<String,Object>> after(long owner,String mediaId,String taskId,long after){
        Map<String,Object> state=state(owner,mediaId,taskId);
        if(yes(state.get("cancel_requested"))||"CANCELLED".equals(state.get("status")))return List.of();
        return db.query("SELECT seq,kind,start_ms,end_ms,payload_json FROM pf_transcript_event WHERE job_id=? AND seq>? ORDER BY seq LIMIT 100",
                (rs,n)->{Map<String,Object> event=new LinkedHashMap<>();event.put("seq",rs.getLong(1));event.put("kind",rs.getString(2));
                    event.put("start_ms",rs.getObject(3));event.put("end_ms",rs.getObject(4));
                    try{event.put("payload",mapper.readTree(rs.getString(5)));}catch(Exception error){throw new IllegalStateException(error);}
                    return event;},taskId,after);
    }
    SseEmitter stream(long owner,String mediaId,String taskId,long after){
        state(owner,mediaId,taskId);SseEmitter emitter=new SseEmitter(40_000L);
        Thread.startVirtualThread(()->{
            long cursor=after;long until=System.nanoTime()+35_000_000_000L;
            try{while(System.nanoTime()<until){
                var state=state(owner,mediaId,taskId);
                if(yes(state.get("cancel_requested"))||"CANCELLED".equals(state.get("status")))break;
                var events=after(owner,mediaId,taskId,cursor);
                for(var event:events){
                    // Recheck immediately before emitting private body after cancel/deletion.
                    if(!sendIfLive(owner,mediaId,taskId,event,emitter))break;
                    cursor=((Number)event.get("seq")).longValue();
                }
                if(List.of("SUCCEEDED","FAILED","CANCELLED").contains(state.get("status"))){
                    emitter.send(SseEmitter.event().name("terminal").data(Map.of("status",state.get("status"),"after",cursor)));break;
                }
                Thread.sleep(400);
            }emitter.complete();}
            catch(Exception error){emitter.completeWithError(error);}
        });return emitter;
    }
    private boolean sendIfLive(long owner,String mediaId,String taskId,Map<String,Object> event,SseEmitter emitter){
        return Boolean.TRUE.equals(tx.execute(status->{
            var media=db.queryForList("SELECT status FROM pf_media WHERE id=? AND owner_id=? FOR UPDATE",mediaId,owner);
            if(media.size()!=1||!"ACTIVE".equals(media.getFirst().get("status")))return false;
            var jobs=db.queryForList("SELECT status,cancel_requested FROM pf_job WHERE id=? AND owner_id=? AND media_id=? AND operation='TRANSCRIBE' FOR UPDATE",taskId,owner,mediaId);
            if(jobs.size()!=1||yes(jobs.getFirst().get("cancel_requested"))||"CANCELLED".equals(jobs.getFirst().get("status")))return false;
            try{long seq=((Number)event.get("seq")).longValue();
                emitter.send(SseEmitter.event().id(Long.toString(seq)).name((String)event.get("kind")).data(event));return true;}
            catch(Exception error){throw new IllegalStateException(error);}
        }));
    }
    private static boolean yes(Object value){return Boolean.TRUE.equals(value)||value instanceof Number number&&number.intValue()==1;}
}
