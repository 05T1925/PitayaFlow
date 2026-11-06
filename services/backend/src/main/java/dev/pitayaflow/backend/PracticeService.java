package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class PracticeService {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper mapper;private final MaterialService materials;
    PracticeService(JdbcTemplate db,TransactionTemplate tx,ObjectMapper mapper,MaterialService materials){this.db=db;this.tx=tx;this.mapper=mapper;this.materials=materials;}
    record Answer(String question_id,long revision,String sha256,String text) {}
    List<Answer> snapshot(long owner,String mediaId,long sourceRevision,long outlineRevision){return snapshot(owner,mediaId,sourceRevision,outlineRevision,false);}
    List<Answer> snapshot(long owner,String mediaId,long sourceRevision,long outlineRevision,boolean lock){
        return db.query("SELECT p.question_id,p.current_revision,r.text_sha256,r.text_content FROM pf_practice_answer p JOIN pf_practice_answer_revision r ON r.media_id=p.media_id AND r.question_id=p.question_id AND r.revision=p.current_revision WHERE p.media_id=? AND p.owner_id=? AND p.source_revision=? AND p.outline_revision=? ORDER BY p.question_id"+(lock?" FOR UPDATE":""),
                (rs,n)->new Answer(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4)),mediaId,owner,sourceRevision,outlineRevision);
    }
    boolean matches(long owner,String mediaId,long sourceRevision,long outlineRevision,List<Answer> expected,boolean lock){
        return snapshot(owner,mediaId,sourceRevision,outlineRevision,lock).equals(expected);
    }
    List<Answer> list(long owner,String mediaId){
        var rows=db.queryForList("SELECT s.current_revision,COALESCE(o.current_revision,0) AS outline_revision FROM pf_media m JOIN pf_transcript_source s ON s.media_id=m.id AND s.media_version=m.content_version LEFT JOIN pf_review_outline o ON o.source_id=s.id WHERE m.id=? AND m.owner_id=? AND m.status='ACTIVE'",mediaId,owner);
        if(rows.size()!=1)throw ApiError.missing();
        return snapshot(owner,mediaId,((Number)rows.getFirst().get("current_revision")).longValue(),
                ((Number)rows.getFirst().get("outline_revision")).longValue());
    }
    Map<String,Object> save(long owner,String mediaId,String taskId,String questionId,long expected,String text){
        if(questionId==null||!questionId.matches("[A-Za-z0-9_-]{1,64}")||text==null||text.isBlank()||text.length()>10_000)
            throw ApiError.bad("PRACTICE_INPUT_INVALID");
        return tx.execute(state->{
            var media=db.queryForList("SELECT id FROM pf_media WHERE id=? AND owner_id=? AND status='ACTIVE' FOR UPDATE",mediaId,owner);
            if(media.size()!=1)throw ApiError.missing();
            var source=db.queryForList("SELECT s.current_revision,COALESCE(o.current_revision,0) AS outline_revision FROM pf_transcript_source s LEFT JOIN pf_review_outline o ON o.source_id=s.id WHERE s.media_id=? AND s.owner_id=?",mediaId,owner);
            if(source.size()!=1)throw ApiError.missing();
            long sr=((Number)source.getFirst().get("current_revision")).longValue(),or=((Number)source.getFirst().get("outline_revision")).longValue();
            var report=db.queryForList("SELECT j.result_json,i.source_revision,i.outline_revision,i.selection_revision,i.materials_json FROM pf_job j JOIN pf_review_input i ON i.job_id=j.id WHERE j.id=? AND j.media_id=? AND j.owner_id=? AND j.status='SUCCEEDED'",taskId,mediaId,owner);
            if(report.size()!=1||((Number)report.getFirst().get("source_revision")).longValue()!=sr||((Number)report.getFirst().get("outline_revision")).longValue()!=or)
                throw ApiError.conflict("PRACTICE_SOURCE_STALE");
            try{
                var selected=new java.util.ArrayList<Map<String,Object>>();
                for(JsonNode item:mapper.readTree((String)report.getFirst().get("materials_json")))
                    selected.add(Map.of("material_id",item.path("material_id").asText(),"revision",item.path("revision").asLong(),"sha256",item.path("sha256").asText()));
                if(!materials.matches(owner,mediaId,new MaterialService.Selection(((Number)report.getFirst().get("selection_revision")).longValue(),selected),true))
                    throw ApiError.conflict("PRACTICE_SOURCE_STALE");
                boolean found=false;for(JsonNode q:mapper.readTree((String)report.getFirst().get("result_json")).path("questions"))
                    if(questionId.equals(q.path("id").asText()))found=true;
                if(!found)throw ApiError.bad("PRACTICE_QUESTION_UNKNOWN");
            }catch(ApiError e){throw e;}catch(Exception e){throw new IllegalStateException(e);}
            var old=db.queryForList("SELECT current_revision,source_revision,outline_revision FROM pf_practice_answer WHERE media_id=? AND question_id=? AND owner_id=? FOR UPDATE",mediaId,questionId,owner);
            long revision=old.isEmpty()?0:((Number)old.getFirst().get("current_revision")).longValue();
            long visible=old.isEmpty()||((Number)old.getFirst().get("source_revision")).longValue()!=sr||
                    ((Number)old.getFirst().get("outline_revision")).longValue()!=or?0:revision;
            if(visible!=expected)throw ApiError.conflict("PRACTICE_REVISION_CONFLICT");
            if(old.isEmpty())db.update("INSERT INTO pf_practice_answer(media_id,question_id,owner_id,current_revision,source_revision,outline_revision) VALUES(?,?,?,1,?,?)",mediaId,questionId,owner,sr,or);
            else db.update("UPDATE pf_practice_answer SET current_revision=?,source_revision=?,outline_revision=? WHERE media_id=? AND question_id=? AND owner_id=?",revision+1,sr,or,mediaId,questionId,owner);
            db.update("INSERT INTO pf_practice_answer_revision(media_id,question_id,revision,text_sha256,text_content) VALUES(?,?,?,?,?)",
                    mediaId,questionId,revision+1,MaterialService.sha(text),text);
            return Map.of("question_id",questionId,"revision",revision+1,"text",text,"label","practice_suggestion");
        });
    }
}
