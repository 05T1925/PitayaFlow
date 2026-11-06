package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Task-bound full transcript review. Every published reference is checked against durable transcript rows. */
@Service
final class QuestionReviewService {
    private static final Logger log=LoggerFactory.getLogger(QuestionReviewService.class);
    static final String RULE="question-review-2", MODEL="pf-scripted-s2-2", TOOL="read-review-transcript-2";
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper mapper;
    private final JobLease leases;private final AgentService bridge;private final MaterialService materials;private final PracticeService practice;
    private final String dshSource,bridgeUrl,scenario;private final boolean scriptedEnabled;private final int resultHoldMs;
    QuestionReviewService(JdbcTemplate db,TransactionTemplate tx,ObjectMapper mapper,JobLease leases,AgentService bridge,MaterialService materials,PracticeService practice,
            @Value("${pf.agent.dsh-source:}") String dshSource,
            @Value("${pf.agent.bridge-url:http://127.0.0.1:18080}") String bridgeUrl,
            @Value("${pf.review.scenario:success}") String scenario,
            @Value("${pf.review.scripted-enabled:false}") boolean scriptedEnabled,
            @Value("${pf.review.result-hold-ms:0}") int resultHoldMs) {
        this.db=db;this.tx=tx;this.mapper=mapper;this.leases=leases;this.bridge=bridge;this.materials=materials;this.practice=practice;
        this.dshSource=dshSource;this.bridgeUrl=bridgeUrl;this.scenario=scenario;this.scriptedEnabled=scriptedEnabled;this.resultHoldMs=resultHoldMs;
    }
    record Input(String jobId,String sourceId,long revision,String hash,long speakerRevision,long outlineRevision,String outlineHash,
        String mediaId,long mediaVersion,long owner,MaterialService.Selection selection,List<PracticeService.Answer> answers,
        String ruleVersion,String modelVersion,String toolVersion) {
        Input(String jobId,String sourceId,long revision,String hash,long speakerRevision,long outlineRevision,String outlineHash,
            String mediaId,long mediaVersion,long owner){this(jobId,sourceId,revision,hash,speakerRevision,outlineRevision,outlineHash,mediaId,mediaVersion,owner,
                new MaterialService.Selection(0,List.of()),List.of(),RULE,MODEL,TOOL);}
    }
    private static String sha(String text) {
        try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private static boolean yes(Object value){return Boolean.TRUE.equals(value)||value instanceof Number number&&number.intValue()==1;}
    private Input input(String jobId) {
        var rows=db.queryForList("SELECT i.source_id,i.source_revision,i.source_sha256,i.speaker_revision,i.outline_revision,i.outline_sha256,i.selection_revision,i.materials_json,i.practice_json,i.rule_version,i.model_version,i.tool_version,j.media_id,j.media_version,j.owner_id FROM pf_review_input i JOIN pf_job j ON j.id=i.job_id WHERE i.job_id=?",jobId);
        if(rows.size()!=1)throw ApiError.missing();var row=rows.getFirst();
        try{return new Input(jobId,(String)row.get("source_id"),((Number)row.get("source_revision")).longValue(),(String)row.get("source_sha256"),
                ((Number)row.get("speaker_revision")).longValue(),((Number)row.get("outline_revision")).longValue(),
                (String)row.get("outline_sha256"),(String)row.get("media_id"),((Number)row.get("media_version")).longValue(),
                ((Number)row.get("owner_id")).longValue(),new MaterialService.Selection(((Number)row.get("selection_revision")).longValue(),
                    parseMaterials((String)row.get("materials_json"))),
                    mapper.readValue((String)row.get("practice_json"),new TypeReference<List<PracticeService.Answer>>(){}),
                    (String)row.get("rule_version"),(String)row.get("model_version"),(String)row.get("tool_version"));}
        catch(Exception error){throw new IllegalStateException("stored review snapshot invalid",error);}
    }
    private List<Map<String,Object>> parseMaterials(String json) throws Exception{
        List<Map<String,Object>> result=new ArrayList<>();JsonNode array=mapper.readTree(json);
        if(!array.isArray()||array.size()>5)throw new IllegalStateException("material snapshot invalid");
        for(JsonNode item:array)result.add(Map.of("material_id",item.path("material_id").asText(),
                "revision",item.path("revision").asLong(),"sha256",item.path("sha256").asText()));
        return result;
    }
    private Map<String,Object> current(long owner,String mediaId,boolean lock) {
        var rows=db.queryForList("SELECT m.content_version,m.content_sha256,s.id AS source_id,s.current_revision,r.content_sha256 AS source_sha256,r.speaker_revision,COALESCE(o.current_revision,0) AS outline_revision FROM pf_media m JOIN pf_transcript_source s ON s.media_id=m.id AND s.media_version=m.content_version JOIN pf_transcript_revision r ON r.source_id=s.id AND r.revision=s.current_revision LEFT JOIN pf_review_outline o ON o.source_id=s.id WHERE m.id=? AND m.owner_id=? AND m.status='ACTIVE'"+(lock?" FOR UPDATE":""),mediaId,owner);
        if(rows.size()!=1)throw ApiError.missing();return rows.getFirst();
    }
    private String outlineHash(String source,long revision) {
        if(revision==0)return sha("[]");
        var rows=db.queryForList("SELECT content_sha256,content_json FROM pf_review_outline_revision WHERE source_id=? AND revision=?",source,revision);
        if(rows.size()!=1)throw ApiError.conflict("outline revision unavailable");var row=rows.getFirst();
        if(!sha((String)row.get("content_json")).equals(row.get("content_sha256")))throw ApiError.conflict("outline hash changed");
        return (String)row.get("content_sha256");
    }
    Map<String,Object> accept(long owner,String mediaId,String idem) {
        if(idem==null||!idem.matches("[A-Za-z0-9_-]{8,100}"))throw ApiError.bad("invalid idempotency key");
        if(dshSource.isBlank()||!scriptedEnabled)throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE,"REVIEW_MODEL_UNCONFIGURED");
        try{return tx.execute(state->{
            var row=current(owner,mediaId,true);String source=(String)row.get("source_id");
            long revision=((Number)row.get("current_revision")).longValue(),speaker=((Number)row.get("speaker_revision")).longValue(),
                    outline=((Number)row.get("outline_revision")).longValue(),mediaVersion=((Number)row.get("content_version")).longValue();
            String sourceHash=(String)row.get("source_sha256"),outlineHash=outlineHash(source,outline);
            requireCompatibleOutline(source,outline,revision);
            var selected=materials.selection(owner,mediaId,true);
            if(!materials.matches(owner,mediaId,selected,true))throw ApiError.conflict("MATERIAL_SOURCE_STALE");
            var answers=practice.snapshot(owner,mediaId,revision,outline,true);
            String selectionJson,practiceJson;
            try{selectionJson=mapper.writeValueAsString(selected.materials());practiceJson=mapper.writeValueAsString(answers);}
            catch(Exception error){throw new IllegalStateException(error);}
            if(selectionJson.length()>1000)throw ApiError.bad("MATERIAL_SELECTION_LARGE");
            String digest=sha(row.get("content_sha256")+"|"+mediaVersion+"|"+source+"|"+revision+"|"+sourceHash+"|"+speaker+"|"+outline+"|"+outlineHash+"|"+RULE+"|"+MODEL+"|"+TOOL+"|"+selected.revision()+"|"+selectionJson+"|"+practiceJson);
            var prior=db.queryForList("SELECT id,request_digest FROM pf_job WHERE owner_id=? AND operation='REVIEW' AND media_id=? AND media_version=? AND idempotency_key=?",owner,mediaId,mediaVersion,idem);
            if(!prior.isEmpty()){if(!digest.equals(prior.getFirst().get("request_digest")))throw ApiError.conflict("idempotency key changed input");
                return Map.of("task_id",prior.getFirst().get("id"),"status","existing");}
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO pf_job(id,owner_id,media_id,media_version,operation,idempotency_key,request_digest,status,deadline_at) VALUES(?,?,?,?,'REVIEW',?,?,'QUEUED',DATE_ADD(NOW(3),INTERVAL 120 SECOND))",id,owner,mediaId,mediaVersion,idem,digest);
            db.update("INSERT INTO pf_review_input(job_id,source_id,source_revision,source_sha256,speaker_revision,outline_revision,outline_sha256,rule_version,model_version,tool_version,materials_json,selection_revision,practice_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,source,revision,sourceHash,speaker,outline,outlineHash,RULE,MODEL,TOOL,selectionJson,selected.revision(),practiceJson);
            db.update("INSERT INTO pf_outbox(job_id) VALUES(?)",id);
            return Map.of("task_id",id,"status","QUEUED","source_revision",revision,"outline_revision",outline,"synthetic_model",true);
        });}catch(DuplicateKeyException error){throw ApiError.conflict("concurrent idempotency request");}
    }
    void run(String id) {
        JobLease.Claim claim=leases.claim(id);if(claim==null)return;
        String attempt=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
        try {
            Input input=input(id);requireCurrentProtocol(input);byte[] random=new byte[32];new SecureRandom().nextBytes(random);
            String grant=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            db.update("INSERT INTO pf_agent_grant(attempt_id,job_id,epoch,claim_token,token_sha256,run_id,expires_at) VALUES(?,?,?,?,?,?,LEAST((SELECT deadline_at FROM pf_job WHERE id=?),DATE_ADD(NOW(3),INTERVAL 90 SECOND)))",
                    attempt,id,claim.epoch(),claim.token(),sha(grant),run,id);
            Map<String,Object> spec=new LinkedHashMap<>();spec.put("task_id",id);spec.put("run_id",run);spec.put("attempt_id",attempt);
            spec.put("execution_epoch",claim.epoch());spec.put("claim_token",claim.token());spec.put("owner_id",String.valueOf(input.owner()));
            spec.put("case_id",input.mediaId());spec.put("source_id",input.sourceId());spec.put("source_revision",input.revision());
            spec.put("source_sha256",input.hash());spec.put("speaker_revision",input.speakerRevision());spec.put("rule_version",RULE);spec.put("tool_version",TOOL);
            spec.put("deadline_ms",Math.min(90000,Math.max(1,claim.remaining().toMillis())));spec.put("dsh_source",dshSource);
            spec.put("bridge_url",bridgeUrl);spec.put("grant_token",grant);spec.put("home_path",AgentService.ownedHome(attempt).toString());
            spec.put("mode","review");spec.put("scenario",scenario);
            if(resultHoldMs>0)spec.put("result_hold_ms",resultHoldMs);
            JsonNode outcome=bridge.invoke(mapper.valueToTree(spec),claim);
            if(!id.equals(outcome.path("task_id").asText())||!run.equals(outcome.path("run_id").asText())||
                    !attempt.equals(outcome.path("attempt_id").asText())||outcome.path("execution_epoch").asLong()!=claim.epoch()||
                    !outcome.path("cleanup").path("tree_stop_confirmed").asBoolean()||!outcome.path("cleanup").path("home_removed").asBoolean()||
                    !outcome.path("validation").path("usable").asBoolean()||outcome.path("registered_tools").size()!=1||
                    !"read_review_transcript".equals(outcome.path("registered_tools").path(0).asText()))throw new IllegalStateException("review bridge outcome rejected");
            publish(claim,input,attempt,run,outcome.path("draft"));
        } catch(Exception error) {
            String code=error.getMessage()!=null&&error.getMessage().contains("draft")?"REVIEW_DRAFT_REJECTED":
                    error.getMessage()!=null&&error.getMessage().contains("source")?"REVIEW_SOURCE_STALE":"REVIEW_FAILED";
            log.warn("review_failed task_id={} classification={} code={}",id,error.getClass().getSimpleName(),code);
            leases.fail(claim,code);
        } finally {db.update("UPDATE pf_agent_grant SET state='CLOSED' WHERE attempt_id=? AND state IN ('ACTIVE','READY')",attempt);}
    }
    JsonNode authorized(String attempt,String token,String mediaId,String ownerId) {
        if(token==null||token.length()<40)throw ApiError.unauthorized();
        var rows=db.queryForList("SELECT i.job_id,i.source_id,i.source_revision,i.source_sha256,i.speaker_revision,i.outline_revision,i.outline_sha256,j.owner_id,j.media_id FROM pf_agent_grant g JOIN pf_job j ON j.id=g.job_id JOIN pf_review_input i ON i.job_id=j.id JOIN pf_transcript_source s ON s.id=i.source_id JOIN pf_transcript_revision r ON r.source_id=s.id AND r.revision=i.source_revision JOIN pf_media m ON m.id=j.media_id WHERE g.attempt_id=? AND g.token_sha256=? AND g.state='ACTIVE' AND g.expires_at>NOW(3) AND j.operation='REVIEW' AND j.epoch=g.epoch AND j.claim_token=g.claim_token AND j.status='RUNNING' AND j.lease_until>NOW(3) AND j.deadline_at>NOW(3) AND j.cancel_requested=FALSE AND j.owner_id=s.owner_id AND j.owner_id=m.owner_id AND s.media_id=j.media_id AND s.media_version=j.media_version AND s.current_revision=i.source_revision AND r.content_sha256=i.source_sha256 AND r.speaker_revision=i.speaker_revision AND m.status='ACTIVE' AND m.content_version=j.media_version AND COALESCE((SELECT current_revision FROM pf_review_outline WHERE source_id=s.id),0)=i.outline_revision",attempt,sha(token));
        if(rows.size()!=1)throw ApiError.missing();var row=rows.getFirst();
        if(!ownerId.equals(String.valueOf(row.get("owner_id")))||!mediaId.equals(row.get("media_id")))throw ApiError.missing();
        Input bound=input((String)row.get("job_id"));
        requireCurrentProtocol(bound);
        if(!materials.matches(bound.owner(),bound.mediaId(),bound.selection())||
                !practice.matches(bound.owner(),bound.mediaId(),bound.revision(),bound.outlineRevision(),bound.answers(),false))
            throw ApiError.conflict("REVIEW_INPUT_STALE");
        String source=(String)row.get("source_id");long revision=((Number)row.get("source_revision")).longValue(),outline=((Number)row.get("outline_revision")).longValue();
        requireCompatibleOutline(source,outline,revision);
        if(!outlineHash(source,outline).equals(row.get("outline_sha256")))throw ApiError.conflict("outline changed");
        var segments=segments(source,revision);String hash=TranscriptService.hash(toSegmentRecords(segments));
        if(!hash.equals(row.get("source_sha256")))throw ApiError.conflict("transcript source hash changed");
        Map<String,Object> content=new LinkedHashMap<>();content.put("source_id",source);content.put("source_revision",revision);
        content.put("outline_revision",outline);content.put("segments",segments);
        content.put("materials",materials.authorized(bound.owner(),bound.mediaId(),bound.selection()));
        content.put("practice_answers",bound.answers());
        if(outline>0)content.put("outline",outlineContent(source,outline));
        return mapper.valueToTree(content);
    }
    void ready(String attempt,String token,String mediaId,String ownerId) {
        authorized(attempt,token,mediaId,ownerId);
        if(db.update("UPDATE pf_agent_grant SET state='READY' WHERE attempt_id=? AND token_sha256=? AND state='ACTIVE'",attempt,sha(token))!=1)
            throw ApiError.conflict("attempt already returned");
    }
    private List<Map<String,Object>> segments(String source,long revision) {
        return db.query("SELECT segment_id,start_ms,end_ms,text_content,speaker_id,role_name,track_index,confidence FROM pf_transcript_segment WHERE source_id=? AND revision=? ORDER BY start_ms,segment_id",(rs,n)->{
            Map<String,Object> segment=new LinkedHashMap<>();segment.put("id",rs.getString(1));segment.put("start_ms",rs.getLong(2));
            segment.put("end_ms",rs.getLong(3));segment.put("text",rs.getString(4));segment.put("speaker_id",rs.getString(5));
            segment.put("role",rs.getString(6));segment.put("track",rs.getInt(7));segment.put("confidence",rs.getObject(8,Double.class));return segment;
        },source,revision);
    }
    private String toSegmentRecords(List<Map<String,Object>> segments) {
        var records=segments.stream().map(s->new TranscriptService.Segment((String)s.get("id"),((Number)s.get("start_ms")).longValue(),
                ((Number)s.get("end_ms")).longValue(),(String)s.get("text"),(String)s.get("speaker_id"),(String)s.get("role"),
                ((Number)s.get("track")).intValue(),(Double)s.get("confidence"))).toList();
        try{return mapper.writeValueAsString(records);}catch(Exception error){throw new IllegalStateException(error);}
    }
    private JsonNode outlineContent(String source,long revision) {
        var rows=db.queryForList("SELECT content_json FROM pf_review_outline_revision WHERE source_id=? AND revision=?",source,revision);
        if(rows.size()!=1)throw ApiError.missing();try{return mapper.readTree((String)rows.getFirst().get("content_json"));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private void publish(JobLease.Claim claim,Input input,String attempt,String run,JsonNode draft) {
        requireCurrentProtocol(input);
        Boolean saved=tx.execute(state->{
            // Lock order matches media deletion and transcript revision: media, transcript source, then job.
            var media=db.queryForList("SELECT status,content_version FROM pf_media WHERE id=? AND owner_id=? FOR UPDATE",input.mediaId(),input.owner());
            if(media.size()!=1||!"ACTIVE".equals(media.getFirst().get("status"))||((Number)media.getFirst().get("content_version")).longValue()!=input.mediaVersion())return false;
            var source=db.queryForList("SELECT s.current_revision,r.content_sha256,r.speaker_revision,COALESCE(o.current_revision,0) AS outline_revision FROM pf_transcript_source s JOIN pf_transcript_revision r ON r.source_id=s.id AND r.revision=s.current_revision LEFT JOIN pf_review_outline o ON o.source_id=s.id WHERE s.id=? AND s.media_id=? AND s.owner_id=? FOR UPDATE",input.sourceId(),input.mediaId(),input.owner());
            if(source.size()!=1)return false;var s=source.getFirst();
            if(((Number)s.get("current_revision")).longValue()!=input.revision()||!input.hash().equals(s.get("content_sha256"))||
                    ((Number)s.get("speaker_revision")).longValue()!=input.speakerRevision()||
                    ((Number)s.get("outline_revision")).longValue()!=input.outlineRevision()||!input.outlineHash().equals(outlineHash(input.sourceId(),input.outlineRevision())))return false;
            if(!materials.matches(input.owner(),input.mediaId(),input.selection(),true)||
                    !practice.matches(input.owner(),input.mediaId(),input.revision(),input.outlineRevision(),input.answers(),true))return false;
            var jobs=db.queryForList("SELECT j.status,j.epoch,j.claim_token,j.lease_until>NOW(3) AS live,j.deadline_at>NOW(3) AS deadline_live,j.cancel_requested,g.state,g.expires_at>NOW(3) AS grant_live FROM pf_job j JOIN pf_agent_grant g ON g.job_id=j.id AND g.attempt_id=? WHERE j.id=? FOR UPDATE",attempt,claim.taskId());
            if(jobs.size()!=1)return false;var j=jobs.getFirst();
            if(!"RUNNING".equals(j.get("status"))||((Number)j.get("epoch")).longValue()!=claim.epoch()||!claim.token().equals(j.get("claim_token"))||
                    !yes(j.get("live"))||!yes(j.get("deadline_live"))||yes(j.get("cancel_requested"))||!"READY".equals(j.get("state"))||!yes(j.get("grant_live")))return false;
            JsonNode report=validateDraft(input,draft,segments(input.sourceId(),input.revision()));
            String json;try{json=mapper.writeValueAsString(report);}catch(Exception error){throw new IllegalStateException(error);}
            db.update("INSERT INTO pf_review_result(job_id,attempt_id,run_id,epoch,source_id,source_revision,source_sha256,outline_revision,outline_sha256,report_json) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    claim.taskId(),attempt,run,claim.epoch(),input.sourceId(),input.revision(),input.hash(),input.outlineRevision(),input.outlineHash(),json);
            if(db.update("UPDATE pf_job SET status='SUCCEEDED',result_json=?,claim_token=NULL,lease_until=NULL,updated_at=NOW(3) WHERE id=? AND status='RUNNING' AND claim_token=?",json,claim.taskId(),claim.token())!=1)
                throw new IllegalStateException("review terminal update failed");
            db.update("UPDATE pf_agent_grant SET state='CLOSED' WHERE attempt_id=?",attempt);return true;
        });
        if(!Boolean.TRUE.equals(saved))throw new IllegalStateException("review publication qualification lost");
    }
    private static void requireCurrentProtocol(Input input){
        if(!RULE.equals(input.ruleVersion())||!MODEL.equals(input.modelVersion())||!TOOL.equals(input.toolVersion()))
            throw ApiError.conflict("REVIEW_PROTOCOL_STALE");
    }
    JsonNode validateDraft(Input input,JsonNode draft,List<Map<String,Object>> segments) {
        if(!only(draft,Set.of("source_id","source_revision","outline_revision","questions","unassigned_refs"))||
                !input.sourceId().equals(draft.path("source_id").asText())||!safeInteger(draft.path("source_revision"))||
                draft.path("source_revision").asLong()!=input.revision()||!safeInteger(draft.path("outline_revision"))||
                draft.path("outline_revision").asLong()!=input.outlineRevision()||!draft.path("questions").isArray()||
                !draft.path("unassigned_refs").isArray()||draft.path("questions").size()>segments.size())
            throw new IllegalStateException("review draft source or structure invalid");
        Map<String,Map<String,Object>> original=new HashMap<>();for(var segment:segments)original.put((String)segment.get("id"),segment);
        Map<String,Map<String,Object>> materialById=new HashMap<>();
        if(!input.selection().materials().isEmpty())for(var material:materials.authorized(input.owner(),input.mediaId(),input.selection()))
            materialById.put((String)material.get("material_id"),material);
        Set<String> used=new HashSet<>(),questionIds=new HashSet<>(),priorQuestions=new HashSet<>();boolean unknown=segments.stream().anyMatch(s->"UNKNOWN".equals(s.get("role")));
        var validatedQuestions=mapper.createArrayNode();
        for(JsonNode question:draft.path("questions")) {
            String id=question.path("id").asText();if(!only(question,Set.of("id","parent_id","question_refs","answer_refs","status","covered_points","gaps","advice","reason","material_background","practice_card","revised_answer"))||
                    !id.matches("[A-Za-z0-9_-]{1,64}")||!questionIds.add(id)||
                    !question.path("question_refs").isArray()||question.path("question_refs").isEmpty()||!question.path("answer_refs").isArray()||
                    !shortStrings(question.path("covered_points"))||!shortStrings(question.path("gaps"))||
                    !question.path("advice").isTextual()||question.path("advice").asText().length()>500||
                    !question.path("reason").isTextual()||question.path("reason").asText().length()>500)
                throw new IllegalStateException("review draft question structure invalid");
            String status=question.path("status").asText();if(!List.of("reviewed","await_confirmation","insufficient_evidence").contains(status)||
                    (unknown&&!status.equals("await_confirmation"))||question.path("answer_refs").isEmpty()&&status.equals("reviewed"))
                throw new IllegalStateException("review draft role qualification invalid");
            checkRefs(question.path("question_refs"),original,used,"INTERVIEWER");checkRefs(question.path("answer_refs"),original,used,"ME");
            if(!materialById.isEmpty())for(JsonNode point:question.path("covered_points")){
                String claim=point.asText();boolean grounded=false;
                if(claim.startsWith("原回答提及："))for(JsonNode ref:question.path("answer_refs"))
                    if(ref.path("text").asText().contains(claim.substring("原回答提及：".length())))grounded=true;
                if(!grounded)throw new IllegalStateException("review draft covered point not grounded in spoken answer");
            }
            if(status.equals("await_confirmation")&&!question.path("covered_points").isEmpty())throw new IllegalStateException("review draft uncertain feedback invalid");
            JsonNode parent=question.path("parent_id");
            if(!parent.isMissingNode()&&!parent.isNull()&&(!parent.isTextual()||!priorQuestions.contains(parent.asText())))
                throw new IllegalStateException("review draft follow-up parent invalid");
            JsonNode background=question.path("material_background");
            if(!background.isMissingNode()||!materialById.isEmpty()){
                if(!background.isArray()||background.size()>20)throw new IllegalStateException("review draft material structure invalid");
                for(JsonNode ref:background){
                    var material=materialById.get(ref.path("material_id").asText());
                    if(material==null||!only(ref,Set.of("material_id","revision","sha256","quote"))||
                            !safeInteger(ref.path("revision"))||ref.path("revision").asLong()!=((Number)material.get("revision")).longValue()||
                            !ref.path("sha256").asText().equals(material.get("sha256"))||!ref.path("quote").isTextual()||
                            ref.path("quote").asText().isBlank()||ref.path("quote").asText().length()>300||
                            !((String)material.get("text")).contains(ref.path("quote").asText()))
                        throw new IllegalStateException("review draft material citation invalid");
                }
            }
            JsonNode card=question.path("practice_card"),answer=question.path("revised_answer");
            if(!card.isMissingNode()||!answer.isMissingNode()||!materialById.isEmpty()){
                if(!only(card,Set.of("goal","evidence_gap","next_step","to_verify","follow_up","reference_answer"))||
                        !bounded(card.path("goal"),500)||!bounded(card.path("evidence_gap"),500)||
                        !bounded(card.path("next_step"),500)||!shortStrings(card.path("to_verify"))||
                        !bounded(card.path("follow_up"),500)||!bounded(card.path("reference_answer"),1000)||
                        !answer.isTextual()||answer.asText().length()>2000)
                    throw new IllegalStateException("review draft practice structure invalid");
            }
            ObjectNode safe=question.deepCopy();
            var spoken=mapper.createArrayNode();for(JsonNode ref:question.path("answer_refs")){
                ObjectNode tagged=ref.deepCopy();tagged.put("source_kind","spoken_quote");spoken.add(tagged);
            }
            safe.set("spoken_quote",spoken);safe.put("revised_answer_status","needs_verification");
            if(card.isMissingNode()){
                ObjectNode fallback=mapper.createObjectNode();fallback.put("goal","核对题目和原回答");fallback.put("evidence_gap","未选择背景材料，知识事实待核查");
                fallback.put("next_step","根据有证据的原回答练习表达");fallback.set("to_verify",mapper.createArrayNode());
                fallback.put("follow_up","请核查本题事实依据");fallback.put("reference_answer","参考表达待核查");safe.set("practice_card",fallback);
                safe.put("revised_answer","");safe.set("material_background",mapper.createArrayNode());
            }
            ObjectNode suggestion=mapper.createObjectNode();suggestion.put("source_kind","practice_suggestion");
            suggestion.set("card",safe.path("practice_card"));suggestion.set("revised_answer",safe.path("revised_answer"));
            suggestion.put("verification_status","needs_verification");safe.set("practice_suggestion",suggestion);
            validatedQuestions.add(safe);
            priorQuestions.add(id);
        }
        checkRefs(draft.path("unassigned_refs"),original,used,null);
        if(used.size()!=original.size())throw new IllegalStateException("review draft omitted segments");
        ObjectNode report=mapper.createObjectNode();report.put("source_id",input.sourceId());report.put("source_revision",input.revision());
        report.put("source_sha256",input.hash());report.put("speaker_revision",input.speakerRevision());
        report.put("outline_revision",input.outlineRevision());report.put("outline_sha256",input.outlineHash());
        report.put("rule_version",RULE);report.put("model_version",MODEL);report.put("tool_version",TOOL);
        report.put("synthetic_model",true);report.put("materials_selected",input.selection().materials().size());
        report.put("selection_revision",input.selection().revision());
        var materialSummary=new ArrayList<Map<String,Object>>();for(var item:input.selection().materials()){
            var detail=materialById.get(item.get("material_id"));
            materialSummary.add(Map.of("material_id",item.get("material_id"),"revision",item.get("revision"),"sha256",item.get("sha256"),
                    "name",detail.get("name"),"kind",detail.get("kind")));
        }
        report.set("materials",mapper.valueToTree(materialSummary));
        report.set("practice_input",mapper.valueToTree(input.answers()));report.put("generated_at",Instant.now().toString());
        report.set("questions",validatedQuestions);report.set("unassigned_refs",draft.path("unassigned_refs"));return report;
    }
    private static void checkRefs(JsonNode refs,Map<String,Map<String,Object>> original,Set<String> used,String role) {
        for(JsonNode ref:refs) {
            String id=ref.path("segment_id").asText();var source=original.get(id);
            if(source==null||!used.add(id)||!only(ref,Set.of("segment_id","start_ms","end_ms","text"))||
                    !safeInteger(ref.path("start_ms"))||!safeInteger(ref.path("end_ms"))||
                    ref.path("start_ms").asLong()!=((Number)source.get("start_ms")).longValue()||
                    ref.path("end_ms").asLong()!=((Number)source.get("end_ms")).longValue()||
                    !ref.path("text").isTextual()||!ref.path("text").asText().equals(source.get("text"))||
                    role!=null&&!role.equals(source.get("role")))throw new IllegalStateException("review draft citation invalid");
        }
    }
    private static boolean safeInteger(JsonNode value){return value.isIntegralNumber()&&value.canConvertToLong()&&
            value.asLong()>=0&&value.asLong()<=9_007_199_254_740_991L;}
    private static boolean only(JsonNode value,Set<String> allowed){if(!value.isObject())return false;
        var names=value.fieldNames();while(names.hasNext())if(!allowed.contains(names.next()))return false;return true;}
    private static boolean shortStrings(JsonNode values){if(!values.isArray()||values.size()>100)return false;
        for(JsonNode item:values)if(!item.isTextual()||item.asText().isBlank()||item.asText().length()>300)return false;return true;}
    private static boolean bounded(JsonNode value,int max){return value.isTextual()&&!value.asText().isBlank()&&value.asText().length()<=max;}
    Map<String,Object> outline(long owner,String mediaId) {
        var row=current(owner,mediaId,false);String source=(String)row.get("source_id");
        long revision=((Number)row.get("outline_revision")).longValue();
        return Map.of("source_id",source,"revision",revision,"transcript_revision",((Number)row.get("current_revision")).longValue(),
                "groups",revision==0?mapper.createArrayNode():outlineContent(source,revision).path("groups"));
    }
    Map<String,Object> reviseOutline(long owner,String mediaId,JsonNode body) {
        if(body==null||!body.path("expected_revision").isIntegralNumber()||!body.path("transcript_revision").isIntegralNumber()||
                !body.path("groups").isArray()||body.path("groups").size()>500)throw ApiError.bad("invalid outline revision");
        try{return tx.execute(state->{
            var row=current(owner,mediaId,true);String source=(String)row.get("source_id");
            long current=((Number)row.get("outline_revision")).longValue(),transcript=((Number)row.get("current_revision")).longValue();
            if(current!=body.path("expected_revision").asLong()||transcript!=body.path("transcript_revision").asLong())
                throw ApiError.conflict("OUTLINE_REVISION_CONFLICT");
            var segments=segments(source,transcript);Map<String,String> roles=new HashMap<>();for(var s:segments)roles.put((String)s.get("id"),(String)s.get("role"));
            Set<String> ids=new HashSet<>(),groupIds=new HashSet<>();
            for(JsonNode group:body.path("groups")){
                String id=group.path("id").asText();
                JsonNode parent=group.path("parent_id");
                if(!parent.isMissingNode()&&!parent.isNull()&&(!parent.isTextual()||!groupIds.contains(parent.asText())))
                    throw ApiError.bad("invalid follow-up parent");
                if(!id.matches("[A-Za-z0-9_-]{1,64}")||!groupIds.add(id)||!group.path("question_ids").isArray()||
                        group.path("question_ids").isEmpty()||!group.path("answer_ids").isArray())throw ApiError.bad("invalid outline group");
                for(JsonNode item:group.path("question_ids"))if(!item.isTextual()||!"INTERVIEWER".equals(roles.get(item.asText()))||!ids.add(item.asText()))throw ApiError.bad("invalid question assignment");
                for(JsonNode item:group.path("answer_ids"))if(!item.isTextual()||!"ME".equals(roles.get(item.asText()))||!ids.add(item.asText()))throw ApiError.bad("invalid answer assignment");
            }
            ObjectNode content=mapper.createObjectNode();content.set("groups",body.path("groups"));
            String json;try{json=mapper.writeValueAsString(content);}catch(Exception error){throw new IllegalStateException(error);}
            if(current==0)db.update("INSERT INTO pf_review_outline(source_id,current_revision) VALUES(?,0)",source);
            db.update("INSERT INTO pf_review_outline_revision(source_id,revision,transcript_revision,content_sha256,content_json) VALUES(?,?,?,?,?)",
                    source,current+1,transcript,sha(json),json);
            if(db.update("UPDATE pf_review_outline SET current_revision=? WHERE source_id=? AND current_revision=?",current+1,source,current)!=1)
                throw ApiError.conflict("OUTLINE_REVISION_CONFLICT");
            return Map.of("source_id",source,"revision",current+1,"transcript_revision",transcript,"groups",body.path("groups"));
        });}catch(DuplicateKeyException error){throw ApiError.conflict("OUTLINE_REVISION_CONFLICT");}
    }
    private void requireCompatibleOutline(String source,long outline,long transcript) {
        if(outline==0)return;
        var rows=db.queryForList("SELECT transcript_revision FROM pf_review_outline_revision WHERE source_id=? AND revision=?",source,outline);
        if(rows.size()!=1||((Number)rows.getFirst().get("transcript_revision")).longValue()!=transcript)
            throw ApiError.conflict("OUTLINE_STALE_RESET_REQUIRED");
    }
    List<Map<String,Object>> reports(long owner,String mediaId) {
        var latest=current(owner,mediaId,false);String source=(String)latest.get("source_id");
        long revision=((Number)latest.get("current_revision")).longValue(),outline=((Number)latest.get("outline_revision")).longValue();
        return db.query("SELECT j.id,j.status,j.failure_code,i.source_revision,i.outline_revision,r.created_at FROM pf_job j JOIN pf_review_input i ON i.job_id=j.id LEFT JOIN pf_review_result r ON r.job_id=j.id WHERE j.owner_id=? AND j.media_id=? AND i.source_id=? ORDER BY j.created_at DESC LIMIT 100",(rs,n)->{
            Map<String,Object> result=new LinkedHashMap<>();result.put("task_id",rs.getString(1));result.put("status",rs.getString(2));
            result.put("failure_code",rs.getString(3));result.put("source_revision",rs.getLong(4));result.put("outline_revision",rs.getLong(5));
            result.put("stale",rs.getLong(4)!=revision||rs.getLong(5)!=outline||snapshotStale(input(rs.getString(1))));result.put("created_at",rs.getTimestamp(6));return result;
        },owner,mediaId,source);
    }
    Map<String,Object> report(long owner,String mediaId,String taskId) {
        var latest=current(owner,mediaId,false);var rows=db.queryForList("SELECT j.status,j.failure_code,j.result_json,i.source_revision,i.outline_revision FROM pf_job j JOIN pf_review_input i ON i.job_id=j.id WHERE j.id=? AND j.owner_id=? AND j.media_id=? AND i.source_id=?",taskId,owner,mediaId,latest.get("source_id"));
        if(rows.size()!=1)throw ApiError.missing();var row=rows.getFirst();
        Map<String,Object> result=new LinkedHashMap<>();result.put("task_id",taskId);result.put("status",row.get("status"));
        result.put("stale",((Number)row.get("source_revision")).longValue()!=((Number)latest.get("current_revision")).longValue()||
                ((Number)row.get("outline_revision")).longValue()!=((Number)latest.get("outline_revision")).longValue()||snapshotStale(input(taskId)));
        if(row.get("failure_code")!=null)result.put("failure_code",row.get("failure_code"));
        if(row.get("result_json")!=null)try{result.put("report",mapper.readTree((String)row.get("result_json")));}
        catch(Exception error){throw new IllegalStateException(error);}return result;
    }
    private boolean snapshotStale(Input input){return !RULE.equals(input.ruleVersion())||!MODEL.equals(input.modelVersion())||!TOOL.equals(input.toolVersion())||
            !materials.matches(input.owner(),input.mediaId(),input.selection())||
            !practice.matches(input.owner(),input.mediaId(),input.revision(),input.outlineRevision(),input.answers(),false);}
    String export(long owner,String mediaId,String taskId) {
        var view=report(owner,mediaId,taskId);JsonNode result=(JsonNode)view.get("report");
        if(!"SUCCEEDED".equals(view.get("status"))||result==null)throw ApiError.conflict("report unavailable");
        StringBuilder text=new StringBuilder("PitayaFlow 逐题复盘\n合成脚本模型：是；录音发言证据：持久转写，角色需人工核对\n");
        text.append("媒体：").append(mediaId).append("\n任务：").append(taskId).append("\n来源：")
                .append(result.path("source_id").asText()).append(" 修订 ").append(result.path("source_revision").asLong())
                .append(" 哈希 ").append(result.path("source_sha256").asText()).append("\n题目边界修订：")
                .append(result.path("outline_revision").asLong()).append("\n报告状态：").append(yes(view.get("stale"))?"历史过期":"当前").append("\n材料选择修订：")
                .append(result.path("selection_revision").asLong()).append("\n选中背景材料（仅供评价背景）：\n");
        if(result.path("materials").isEmpty())text.append("未选择\n");
        else for(JsonNode material:result.path("materials"))text.append(clean(material.path("name").asText())).append(" [")
                .append(clean(material.path("material_id").asText())).append("] 修订 ")
                .append(material.path("revision").asLong()).append(" sha256 ").append(clean(material.path("sha256").asText())).append('\n');
        text.append('\n');
        int number=0;for(JsonNode question:result.path("questions")){
            text.append("题 ").append(++number).append(" [").append(clean(question.path("status").asText())).append("]\n问题原文：\n");
            appendRefs(text,question.path("question_refs"));text.append("原回答：\n");appendRefs(text,question.path("answer_refs"));
            text.append("已覆盖：");appendItems(text,question.path("covered_points"));text.append("缺口：");appendItems(text,question.path("gaps"));
            text.append("材料背景（非发言证据）：\n");for(JsonNode ref:question.path("material_background"))text.append(clean(ref.path("material_id").asText()))
                    .append(" 修订 ").append(ref.path("revision").asLong()).append(": ").append(clean(ref.path("quote").asText())).append('\n');
            text.append("待确认／无法评价原因：").append(clean(question.path("reason").asText())).append("\n准备建议（非录音原话）：")
                    .append(clean(question.path("advice").asText())).append("\n练习目标：").append(clean(question.path("practice_card").path("goal").asText()))
                    .append("\n证据缺口：").append(clean(question.path("practice_card").path("evidence_gap").asText()))
                    .append("\n下一步：").append(clean(question.path("practice_card").path("next_step").asText()))
                    .append("\n准备性追问：").append(clean(question.path("practice_card").path("follow_up").asText()))
                    .append("\n参考表达（待核查）：").append(clean(question.path("practice_card").path("reference_answer").asText()))
                    .append("\n待核查：");appendItems(text,question.path("practice_card").path("to_verify"));
            text.append("文字新版回答（练习稿，断言待核查）：").append(clean(question.path("revised_answer").asText())).append("\n\n");
        }
        for(JsonNode answer:result.path("practice_input"))text.append("用户文字练习稿 ").append(clean(answer.path("question_id").asText()))
                .append(" 修订 ").append(answer.path("revision").asLong()).append("（非录音原话）：")
                .append(clean(answer.path("text").asText())).append('\n');
        text.append("未分配／待确认原文：\n");appendRefs(text,result.path("unassigned_refs"));return text.toString();
    }
    private static void appendRefs(StringBuilder text,JsonNode refs){for(JsonNode ref:refs)text.append('[').append(ref.path("start_ms").asLong()).append('-')
            .append(ref.path("end_ms").asLong()).append(" ms; ").append(clean(ref.path("segment_id").asText())).append("] ")
            .append(clean(ref.path("text").asText())).append('\n');}
    private static void appendItems(StringBuilder text,JsonNode items){if(items.isEmpty())text.append("无已确认条目");
        else for(JsonNode item:items)text.append(clean(item.asText())).append("；");text.append('\n');}
    private static String clean(String text){return text.replace('\r',' ').replace('\n',' ').replaceAll("[\\p{Cntrl}&&[^\\t]]"," ");}
}
