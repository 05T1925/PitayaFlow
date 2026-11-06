package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
final class JobService {
    private static final Logger log=LoggerFactory.getLogger(JobService.class);
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final MediaService media;
    private final ObjectStore objects;
    private final Probe probe;
    private final ProbeTempFiles tempFiles;
    private final JobLease leases;
    private final AgentService agents;
    private final TranscriptService transcripts;
    private final QuestionReviewService reviews;
    private final ObjectMapper mapper;
    JobService(JdbcTemplate db, TransactionTemplate transaction, MediaService media, ObjectStore objects, Probe probe, ObjectMapper mapper, ProbeTempFiles tempFiles, JobLease leases, AgentService agents, TranscriptService transcripts, QuestionReviewService reviews) {
        this.db=db; this.transaction=transaction; this.media=media; this.objects=objects; this.probe=probe; this.mapper=mapper; this.tempFiles=tempFiles; this.leases=leases; this.agents=agents;this.transcripts=transcripts;this.reviews=reviews;
    }
    Map<String,Object> accept(long owner, String mediaId, String idem) {
        if (idem == null || !idem.matches("[A-Za-z0-9_-]{8,100}")) throw ApiError.bad("invalid idempotency key");
        try {
            return transaction.execute(status -> {
                // Lock the media row so deletion cannot cross task admission.
                var rows = db.queryForList("SELECT id,content_version,content_sha256 FROM pf_media WHERE id=? AND owner_id=? AND status='ACTIVE' FOR UPDATE", mediaId, owner);
                if (rows.isEmpty()) throw ApiError.missing();
                long version = ((Number)rows.getFirst().get("content_version")).longValue();
                String digest = (String)rows.getFirst().get("content_sha256");
                var existing = db.queryForList("SELECT id,request_digest FROM pf_job WHERE owner_id=? AND operation='PROBE' AND media_id=? AND media_version=? AND idempotency_key=?", owner,mediaId,version,idem);
                if (!existing.isEmpty()) {
                    if (!digest.equals(existing.getFirst().get("request_digest"))) throw ApiError.conflict("idempotency key changed input");
                    return view((String)existing.getFirst().get("id"),owner);
                }
                String id = UUID.randomUUID().toString();
                db.update("INSERT INTO pf_job(id,owner_id,media_id,media_version,operation,idempotency_key,request_digest,status,deadline_at) VALUES(?,?,?,?,'PROBE',?,?,'QUEUED',DATE_ADD(NOW(3),INTERVAL 90 SECOND))",
                        id,owner,mediaId,version,idem,digest);
                db.update("INSERT INTO pf_outbox(job_id) VALUES(?)",id);
                return view(id,owner);
            });
        } catch (DuplicateKeyException e) { throw ApiError.conflict("concurrent idempotency request"); }
    }
    Map<String,Object> view(String id,long owner) {
        var rows = db.queryForList("SELECT j.id,j.media_id,j.media_version,j.status,j.cancel_requested,j.epoch,j.result_json,j.failure_code,m.status AS media_status FROM pf_job j JOIN pf_media m ON m.id=j.media_id WHERE j.id=? AND j.owner_id=?",id,owner);
        if (rows.size()!=1) throw ApiError.missing();
        var row=rows.getFirst();
        Map<String,Object> out=new java.util.LinkedHashMap<>();
        out.put("task_id",row.get("id")); out.put("media_id",row.get("media_id")); out.put("media_version",row.get("media_version"));
        out.put("status",row.get("status")); out.put("cancel_requested",row.get("cancel_requested")); out.put("epoch",row.get("epoch"));
        if ("ACTIVE".equals(row.get("media_status")) && row.get("result_json")!=null) try { out.put("result",mapper.readTree((String)row.get("result_json"))); } catch (Exception e) { throw new IllegalStateException(e); }
        if (row.get("failure_code")!=null) out.put("failure_code",row.get("failure_code"));
        return out;
    }
    void cancel(String id,long owner) {
        if (db.update("UPDATE pf_job SET cancel_requested=TRUE,status=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE status END,updated_at=NOW(3) WHERE id=? AND owner_id=? AND status IN ('QUEUED','RUNNING')",id,owner)==0) {
            view(id,owner); // distinguish absent from already terminal
        }
    }
    List<String> recoverable() { return db.queryForList("SELECT id FROM pf_job WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<NOW(3)) ORDER BY created_at LIMIT 20",String.class); }
    void expireTerminated() {
        db.update("UPDATE pf_job SET status='CANCELLED',lease_until=NULL,updated_at=NOW(3) WHERE status='RUNNING' AND cancel_requested=TRUE AND lease_until<NOW(3)");
        db.update("UPDATE pf_job SET status='FAILED',failure_code='DEADLINE',lease_until=NULL,updated_at=NOW(3) WHERE status IN ('QUEUED','RUNNING') AND deadline_at<NOW(3)");
    }
    void run(String id) {
        var operations=db.queryForList("SELECT operation FROM pf_job WHERE id=?",String.class,id);
        if(operations.size()!=1) return;
        if("AGENT".equals(operations.getFirst())) { agents.run(id); return; }
        if("TRANSCRIBE".equals(operations.getFirst())) { transcripts.run(id); return; }
        if("REVIEW".equals(operations.getFirst())) { reviews.run(id); return; }
        if(!"PROBE".equals(operations.getFirst())) return;
        JobLease.Claim claim=leases.claim(id);
        if (claim==null) return;
        Path temp=null;
        try {
            long started=System.nanoTime();
            temp=tempFiles.create();
            byte[] bytes=objects.get(claim.objectKey());
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals(claim.contentHash())) throw new IllegalStateException("media hash mismatch");
            Files.write(temp,bytes);
            Duration remaining=claim.remaining().minusNanos(System.nanoTime()-started);
            Map<String,Object> result=probe.inspect(temp,remaining,()->!leases.renew(claim));
            String json=mapper.writeValueAsString(Map.of("media_version",claim.mediaVersion(),"content_sha256",hash,"probe",result));
            leases.publishProbe(claim,json);
        } catch (Exception e) {
            log.warn("probe_failed task_id={} exception_type={} classification={}",id,e.getClass().getSimpleName(),
                    e instanceof ApiError ? ((ApiError)e).getReason() : "internal");
            leases.fail(claim);
        } finally { tempFiles.cleanup(temp,null); }
    }
}
