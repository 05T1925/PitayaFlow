package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Comparator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class AgentService {
    private static final Logger log=LoggerFactory.getLogger(AgentService.class);
    private static final String RULE="synthetic-citation-1", TOOL="read-synthetic-bridge-1";
    private final JdbcTemplate db;private final TransactionTemplate tx;private final SyntheticSourceService sources;
    private final JobLease leases;private final ObjectMapper mapper;private final AgentProtocol protocol;
    private final String dshSource,node,bridgeUrl,scenario;
    private final int resultHoldMs;
    private final java.io.File repositoryRoot;
    AgentService(JdbcTemplate db,TransactionTemplate tx,SyntheticSourceService sources,JobLease leases,ObjectMapper mapper,AgentProtocol protocol,
        @Value("${pf.agent.dsh-source:}") String dshSource,@Value("${pf.agent.node:node}") String node,
        @Value("${pf.agent.bridge-url:http://127.0.0.1:18080}") String bridgeUrl,
        @Value("${pf.agent.contracts-dir:packages/contracts}") String contractsDir,
        @Value("${pf.agent.scenario:success}") String scenario,
        @Value("${pf.agent.result-hold-ms:0}") int resultHoldMs) {
        this.db=db;this.tx=tx;this.sources=sources;this.leases=leases;this.mapper=mapper;this.protocol=protocol;
        this.dshSource=dshSource;this.node=node;this.bridgeUrl=bridgeUrl;this.scenario=scenario;this.resultHoldMs=resultHoldMs;
        this.repositoryRoot=Path.of(contractsDir).toAbsolutePath().getParent().getParent().toFile();
    }
    private static String hash(String text) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    Map<String,Object> accept(long owner,String mediaId,String sourceId,String idem) throws Exception {
        if(idem==null || !idem.matches("[A-Za-z0-9_-]{8,100}")) throw ApiError.bad("invalid idempotency key");
        try {return tx.execute(state->{
            var media=db.queryForList("SELECT id,content_version,content_sha256 FROM pf_media WHERE id=? AND owner_id=? AND status='ACTIVE' FOR UPDATE",mediaId,owner);
            if(media.size()!=1) throw ApiError.missing();
            var source=db.queryForList("SELECT s.current_revision,r.content_sha256,r.speaker_revision FROM pf_synthetic_source s JOIN pf_synthetic_revision r ON r.source_id=s.id AND r.revision=s.current_revision WHERE s.id=? AND s.owner_id=? AND s.media_id=? FOR UPDATE",sourceId,owner,mediaId);
            if(source.size()!=1) throw ApiError.missing();
            var row=source.getFirst();long revision=((Number)row.get("current_revision")).longValue(),speaker=((Number)row.get("speaker_revision")).longValue();
            long version=((Number)media.getFirst().get("content_version")).longValue();String sourceHash=(String)row.get("content_sha256");
            String digest;
            try{digest=hash(media.getFirst().get("content_sha256")+"|"+version+"|"+sourceId+"|"+revision+"|"+sourceHash+"|"+speaker+"|"+RULE+"|"+TOOL);}catch(Exception e){throw new IllegalStateException(e);}
            var existing=db.queryForList("SELECT id,request_digest FROM pf_job WHERE owner_id=? AND operation='AGENT' AND media_id=? AND media_version=? AND idempotency_key=?",owner,mediaId,version,idem);
            if(!existing.isEmpty()) {if(!digest.equals(existing.getFirst().get("request_digest"))) throw ApiError.conflict("idempotency key changed input");
                return Map.of("task_id",existing.getFirst().get("id"),"status","existing");}
            String id=UUID.randomUUID().toString();
            db.update("INSERT INTO pf_job(id,owner_id,media_id,media_version,operation,idempotency_key,request_digest,status,deadline_at) VALUES(?,?,?,?,'AGENT',?,?,'QUEUED',DATE_ADD(NOW(3),INTERVAL 120 SECOND))",id,owner,mediaId,version,idem,digest);
            db.update("INSERT INTO pf_agent_input(job_id,source_id,source_revision,source_sha256,speaker_revision,rule_version,tool_version) VALUES(?,?,?,?,?,?,?)",id,sourceId,revision,sourceHash,speaker,RULE,TOOL);
            db.update("INSERT INTO pf_outbox(job_id) VALUES(?)",id);
            return Map.of("task_id",id,"status","QUEUED","source_id",sourceId,"source_revision",revision,"source_sha256",sourceHash,"speaker_revision",speaker,"synthetic",true);
        });}catch(DuplicateKeyException e){throw ApiError.conflict("concurrent idempotency request");}
    }
    record Input(String sourceId,long revision,String hash,long speakerRevision,String caseId,long owner) {}
    private Input input(JobLease.Claim claim) throws Exception {
        var rows=db.queryForList("SELECT i.source_id,i.source_revision,i.source_sha256,i.speaker_revision,j.owner_id,r.content_json FROM pf_agent_input i JOIN pf_job j ON j.id=i.job_id JOIN pf_synthetic_source s ON s.id=i.source_id JOIN pf_synthetic_revision r ON r.source_id=s.id AND r.revision=i.source_revision WHERE i.job_id=? AND s.current_revision=i.source_revision AND i.source_sha256=r.content_sha256 AND i.speaker_revision=r.speaker_revision AND s.owner_id=j.owner_id AND s.media_id=j.media_id",claim.taskId());
        if(rows.size()!=1) throw ApiError.conflict("source revision changed");var row=rows.getFirst();
        return new Input((String)row.get("source_id"),((Number)row.get("source_revision")).longValue(),(String)row.get("source_sha256"),
            ((Number)row.get("speaker_revision")).longValue(),mapper.readTree((String)row.get("content_json")).path("case_id").asText(),
            ((Number)row.get("owner_id")).longValue());
    }
    void run(String id) {
        JobLease.Claim claim=leases.claim(id);if(claim==null)return;
        String attempt=UUID.randomUUID().toString(),run=UUID.randomUUID().toString(),grant=null;
        try {
            Input input=input(claim);byte[] random=new byte[32];new SecureRandom().nextBytes(random);grant=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            db.update("INSERT INTO pf_agent_grant(attempt_id,job_id,epoch,claim_token,token_sha256,run_id,expires_at) VALUES(?,?,?,?,?,?,LEAST((SELECT deadline_at FROM pf_job WHERE id=?),DATE_ADD(NOW(3),INTERVAL 90 SECOND)))",
                attempt,id,claim.epoch(),claim.token(),hash(grant),run,id);
            Map<String,Object> spec=new LinkedHashMap<>();
            spec.put("task_id",id);spec.put("run_id",run);spec.put("attempt_id",attempt);spec.put("execution_epoch",claim.epoch());
            spec.put("claim_token",claim.token());spec.put("owner_id",String.valueOf(input.owner()));spec.put("case_id",input.caseId());
            spec.put("source_id",input.sourceId());spec.put("source_revision",input.revision());spec.put("source_sha256",input.hash());
            spec.put("speaker_revision",input.speakerRevision());spec.put("rule_version",RULE);spec.put("tool_version",TOOL);
            spec.put("deadline_ms",Math.min(90000,Math.max(1,claim.remaining().toMillis())));spec.put("dsh_source",dshSource);
            spec.put("bridge_url",bridgeUrl);spec.put("grant_token",grant);
            spec.put("home_path",ownedHome(attempt).toString());
            spec.put("scenario",scenario);
            if("1".equals(System.getenv("PF_AGENT_INJECT_CLEANUP_FAILURE")))spec.put("inject_cleanup_failure",true);
            if(resultHoldMs>0) spec.put("result_hold_ms",resultHoldMs);
            JsonNode runSpec=mapper.valueToTree(spec);if(!protocol.valid("run_spec",runSpec)) throw new IllegalStateException("invalid run spec");
            JsonNode bridge=invoke(runSpec,claim);
            log.info("agent_tool_inventory task_id={} tools={}",id,bridge.path("registered_tools"));
            if(!protocol.valid("bridge_result",bridge) || !bridge.path("task_id").asText().equals(id) ||
                !bridge.path("run_id").asText().equals(run) || !bridge.path("attempt_id").asText().equals(attempt) ||
                bridge.path("execution_epoch").asLong()!=claim.epoch() || !bridge.path("cleanup").path("tree_stop_confirmed").asBoolean() ||
                !bridge.path("cleanup").path("home_removed").asBoolean() || bridge.path("registered_tools").size()!=1 ||
                !"read_synthetic_transcript".equals(bridge.path("registered_tools").path(0).asText())) throw new IllegalStateException("invalid bridge outcome");
            publish(claim,input,attempt,run,bridge.path("draft"));
        } catch(Exception error) {
            String code=error.getMessage()!=null && (error.getMessage().contains("draft") || error.getMessage().contains("citation"))?"DRAFT_REJECTED":
                error.getMessage()!=null && error.getMessage().contains("revision")?"SOURCE_STALE":
                error.getMessage()!=null && error.getMessage().contains("stop unconfirmed")?"STOP_UNCONFIRMED":"AGENT_FAILED";
            log.warn("agent_failed task_id={} exception_type={} code={}",id,error.getClass().getSimpleName(),code);
            leases.fail(claim,code);
        } finally {db.update("UPDATE pf_agent_grant SET state='CLOSED' WHERE attempt_id=? AND state IN ('ACTIVE','READY')",attempt);}
    }
    JsonNode invoke(JsonNode spec,JobLease.Claim claim) throws Exception {
        if(dshSource.isBlank()) throw new IllegalStateException("DSH source is not configured");
        Path home=Path.of(spec.path("home_path").asText());
        Process process=new ProcessBuilder(node,"services/agent-runtime/bridge.mjs").directory(repositoryRoot).redirectErrorStream(false).start();
        ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
        int outputLimit="review".equals(spec.path("mode").asText())?2_200_000:65536;
        Thread out=Thread.ofVirtual().start(()->copyBounded(process.getInputStream(),stdout,outputLimit));
        Thread err=Thread.ofVirtual().start(()->copyBounded(process.getErrorStream(),stderr,65536));
        Exception failure=null;JsonNode result=null;boolean forcedConfirmed=false;
        try {
            process.getOutputStream().write((mapper.writeValueAsString(spec)+"\n").getBytes(StandardCharsets.UTF_8));process.getOutputStream().flush();
            long until=System.nanoTime()+Math.min(Duration.ofMillis(spec.path("deadline_ms").asLong()).toNanos(),Duration.ofSeconds(90).toNanos());
            while(process.isAlive()) {
                if(!leases.renew(claim) || System.nanoTime()>=until) throw new IllegalStateException("agent lease or deadline lost");
                process.waitFor(1000,TimeUnit.MILLISECONDS);
            }
            out.join(2000);err.join(2000);
            if(process.exitValue()!=0) throw new IllegalStateException("DSH bridge exited "+process.exitValue()+" diagnostic="+safeDiagnostic(stderr));
            result=mapper.readTree(stdout.toByteArray());
        } catch(Exception error) {failure=error;}
        finally {
            if(process.isAlive()) {
                try {
                    // The Node bridge owns AttemptHost.close; give it time to stop DSH and remove its home.
                    process.getOutputStream().write("{\"type\":\"cancel\"}\n".getBytes(StandardCharsets.UTF_8));
                    process.getOutputStream().flush();
                } catch(Exception signalError) {if(failure!=null)failure.addSuppressed(signalError);else failure=signalError;}
                try {
                    if(!process.waitFor(12,TimeUnit.SECONDS)) {stopTree(process);forcedConfirmed=true;}
                } catch(Exception stopError) {if(failure!=null)failure.addSuppressed(stopError);else failure=stopError;}
            }
            try {process.getOutputStream().close();} catch(Exception ignored) { }
            out.join(2000);err.join(2000);
            if(forcedConfirmed || (!process.isAlive() && safeDiagnostic(stderr).contains("tree_confirmed=true"))) {
                boolean retry=Files.exists(home,LinkOption.NOFOLLOW_LINKS);
                try {removeOwnedHome(home);
                    if(retry)log.info("agent_home_retry task_id={} tree_confirmed=true home_removed=true",claim.taskId());
                } catch(Exception cleanupError) {if(failure!=null)failure.addSuppressed(cleanupError);else failure=cleanupError;
                    log.warn("agent_home_retry task_id={} tree_confirmed=true home_removed=false exception_type={}",claim.taskId(),cleanupError.getClass().getSimpleName());}
            }
            if(Files.exists(home,LinkOption.NOFOLLOW_LINKS))
                log.warn("agent_home_retained task_id={} home={} diagnostic={}",claim.taskId(),home,safeDiagnostic(stderr));
        }
        if(failure!=null)throw failure;
        return result;
    }
    static Path ownedHome(String attempt) {
        return Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("pitayaflow-attempt-"+attempt);
    }
    private static void removeOwnedHome(Path home) throws Exception {
        Path normalized=home.toAbsolutePath().normalize();
        if(!normalized.getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()) ||
            !normalized.getFileName().toString().matches("pitayaflow-attempt-[0-9a-f-]{36}"))
            throw new IllegalStateException("unrecognized agent home");
        if(!Files.exists(normalized,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(normalized))throw new IllegalStateException("agent home is a symlink");
        try(var paths=Files.walk(normalized)) {
            for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
        }
    }
    private static String safeDiagnostic(ByteArrayOutputStream stderr) {
        String text=stderr.toString(StandardCharsets.UTF_8);
        int at=text.lastIndexOf("PF_BRIDGE_FAILURE=");
        if(at<0)return "none";
        return text.substring(at).replaceAll("[^A-Za-z0-9_= .:/\\\\-]","?").substring(0,Math.min(300,text.length()-at));
    }
    private static void copyBounded(java.io.InputStream in,ByteArrayOutputStream output,int limit) {
        try(in){byte[] buf=new byte[1024];int size;while((size=in.read(buf))!=-1) {
            if(output.size()+size>limit) throw new IllegalStateException("bridge output limit");output.write(buf,0,size);
        }}catch(Exception ignored){/* bridge parse or exit detects incomplete output */}
    }
    private static void stopTree(Process process) throws Exception {
        var descendants=process.toHandle().descendants().toList();
        for(var child:descendants)child.destroyForcibly();process.destroyForcibly();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<until && (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)))process.waitFor(50,TimeUnit.MILLISECONDS);
        if(process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))throw new IllegalStateException("agent process tree stop unconfirmed");
    }
    void publish(JobLease.Claim claim,Input input,String attempt,String run,JsonNode draft) throws Exception {
        var snapshot=sources.current(input.owner(),input.sourceId());
        if(snapshot.revision()!=input.revision() || !snapshot.sha256().equals(input.hash()) || snapshot.speakerRevision()!=input.speakerRevision())
            throw new IllegalStateException("source revision changed");
        JsonNode source=snapshot.content();
        if(!protocol.valid("draft",draft)) throw new IllegalStateException("invalid draft structure");
        if(!input.caseId().equals(draft.path("case_id").asText()) || !source.path("transcript_revision").asText().equals(draft.path("transcript_revision").asText()))
            throw new IllegalStateException("draft revision mismatch");
        var citation=draft.path("citation");JsonNode segment=null;
        for(JsonNode item:source.path("segments")) if(item.path("id").asText().equals(citation.path("segment_id").asText())) segment=item;
        if(segment==null || !citation.path("transcript_revision").asText().equals(source.path("transcript_revision").asText()) ||
            citation.path("start_ms").asLong()<segment.path("start_ms").asLong() || citation.path("end_ms").asLong()>segment.path("end_ms").asLong() ||
            citation.path("start_ms").asLong()>=citation.path("end_ms").asLong()) throw new IllegalStateException("invalid citation");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("status",source.path("speaker_status").asText().equals("confirmed")?"validated":"await_confirmation");
        result.put("synthetic",true);result.put("source_id",input.sourceId());result.put("source_revision",input.revision());
        result.put("source_sha256",input.hash());result.put("speaker_revision",input.speakerRevision());result.put("citation",citation);
        if("validated".equals(result.get("status")))result.put("assessment",draft.path("assessment").asText());
        String json=mapper.writeValueAsString(result);
        Boolean saved=tx.execute(state->{
            var rows=db.queryForList("SELECT j.status,j.epoch,j.claim_token,j.lease_until>NOW(3) AS lease_live,j.deadline_at>NOW(3) AS deadline_live,j.cancel_requested,m.status AS media_status,m.content_version,s.current_revision,g.state AS grant_state,g.expires_at>NOW(3) AS grant_live FROM pf_job j JOIN pf_media m ON m.id=j.media_id JOIN pf_agent_input i ON i.job_id=j.id JOIN pf_synthetic_source s ON s.id=i.source_id JOIN pf_agent_grant g ON g.attempt_id=? AND g.job_id=j.id WHERE j.id=? FOR UPDATE",attempt,claim.taskId());
            if(rows.size()!=1)return false;var row=rows.getFirst();
            if(!"RUNNING".equals(row.get("status")) || ((Number)row.get("epoch")).longValue()!=claim.epoch() ||
                !claim.token().equals(row.get("claim_token")) || ((Number)row.get("lease_live")).intValue()!=1 ||
                ((Number)row.get("deadline_live")).intValue()!=1 || (row.get("cancel_requested") instanceof Boolean b ? b : ((Number)row.get("cancel_requested")).intValue()!=0) ||
                !"ACTIVE".equals(row.get("media_status")) || ((Number)row.get("content_version")).longValue()!=claim.mediaVersion() ||
                ((Number)row.get("current_revision")).longValue()!=input.revision() || !"READY".equals(row.get("grant_state")) ||
                ((Number)row.get("grant_live")).intValue()!=1) return false;
            db.update("INSERT INTO pf_agent_result(job_id,attempt_id,run_id,epoch,source_id,source_revision,source_sha256,speaker_revision,result_json) VALUES(?,?,?,?,?,?,?,?,?)",
                claim.taskId(),attempt,run,claim.epoch(),input.sourceId(),input.revision(),input.hash(),input.speakerRevision(),json);
            int changed=db.update("UPDATE pf_job SET status='SUCCEEDED',result_json=?,claim_token=NULL,lease_until=NULL,updated_at=NOW(3) WHERE id=? AND claim_token=? AND status='RUNNING'",json,claim.taskId(),claim.token());
            if(changed!=1)throw new IllegalStateException("agent result terminal update failed");
            db.update("UPDATE pf_agent_grant SET state='CLOSED' WHERE attempt_id=?",attempt);
            return true;
        });
        if(!Boolean.TRUE.equals(saved)) throw new IllegalStateException("agent publication qualification lost");
    }
}
