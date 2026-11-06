package dev.pitayaflow.backend;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** A claim token is bound in the same UPDATE that increments epoch; no later worker can lend its epoch. */
@Component
final class JobLease {
    record Claim(String taskId,long epoch,String token,String objectKey,String contentHash,long mediaVersion,Duration remaining) {}
    private final JdbcTemplate db;
    private final Runnable afterClaim;
    @Autowired JobLease(JdbcTemplate db) { this(db,()->{}); }
    JobLease(JdbcTemplate db,Runnable afterClaim) { this.db=db; this.afterClaim=afterClaim; }
    Claim claim(String id) {
        String token=UUID.randomUUID().toString();
        int changed=db.update("UPDATE pf_job j JOIN pf_media m ON m.id=j.media_id SET j.status='RUNNING',j.epoch=j.epoch+1,j.claim_token=?,j.lease_until=DATE_ADD(NOW(3),INTERVAL 30 SECOND),j.updated_at=NOW(3) WHERE j.id=? AND j.cancel_requested=FALSE AND j.deadline_at>NOW(3) AND (j.status='QUEUED' OR (j.status='RUNNING' AND j.lease_until<NOW(3))) AND m.status='ACTIVE' AND m.content_version=j.media_version",token,id);
        if (changed!=1) return null;
        afterClaim.run();
        // A later claim can rotate the token before this read. In that case this caller has lost ownership.
        var rows=db.queryForList("SELECT j.epoch,TIMESTAMPDIFF(MICROSECOND,NOW(3),j.deadline_at) AS remaining_micros,j.media_version,m.object_key,m.content_sha256 FROM pf_job j JOIN pf_media m ON m.id=j.media_id WHERE j.id=? AND j.claim_token=? AND j.status='RUNNING' AND j.lease_until>NOW(3) AND j.deadline_at>NOW(3) AND j.cancel_requested=FALSE AND m.status='ACTIVE' AND m.content_version=j.media_version",id,token);
        if (rows.size()!=1) return null;
        var row=rows.getFirst();
        return new Claim(id,((Number)row.get("epoch")).longValue(),token,(String)row.get("object_key"),
                (String)row.get("content_sha256"),((Number)row.get("media_version")).longValue(),
                Duration.ofNanos(((Number)row.get("remaining_micros")).longValue()*1000L));
    }
    boolean renew(Claim claim) {
        return db.update("UPDATE pf_job j JOIN pf_media m ON m.id=j.media_id SET j.lease_until=DATE_ADD(NOW(3),INTERVAL 30 SECOND) WHERE j.id=? AND j.epoch=? AND j.claim_token=? AND j.status='RUNNING' AND j.lease_until>NOW(3) AND j.deadline_at>NOW(3) AND j.cancel_requested=FALSE AND m.status='ACTIVE' AND m.content_version=j.media_version",
                claim.taskId(),claim.epoch(),claim.token())==1;
    }
    boolean publishProbe(Claim claim,String json) {
        return db.update("UPDATE pf_job j JOIN pf_media m ON m.id=j.media_id SET j.status='SUCCEEDED',j.result_json=?,j.lease_until=NULL,j.claim_token=NULL,j.updated_at=NOW(3) WHERE j.id=? AND j.epoch=? AND j.claim_token=? AND j.status='RUNNING' AND j.lease_until>NOW(3) AND j.deadline_at>NOW(3) AND j.cancel_requested=FALSE AND m.status='ACTIVE' AND m.content_version=j.media_version",
                json,claim.taskId(),claim.epoch(),claim.token())==1;
    }
    void fail(Claim claim) { fail(claim,"PROBE_FAILED"); }
    void fail(Claim claim,String code) {
        int cancelled=db.update("UPDATE pf_job j JOIN pf_media m ON m.id=j.media_id SET j.status='CANCELLED',j.failure_code='CANCELLED',j.lease_until=NULL,j.claim_token=NULL,j.updated_at=NOW(3) WHERE j.id=? AND j.epoch=? AND j.claim_token=? AND j.status='RUNNING' AND j.lease_until>NOW(3) AND j.deadline_at>NOW(3) AND (j.cancel_requested=TRUE OR m.status<>'ACTIVE' OR m.content_version<>j.media_version)",
                claim.taskId(),claim.epoch(),claim.token());
        if (cancelled==0) db.update("UPDATE pf_job j JOIN pf_media m ON m.id=j.media_id SET j.status='FAILED',j.failure_code=?,j.lease_until=NULL,j.claim_token=NULL,j.updated_at=NOW(3) WHERE j.id=? AND j.epoch=? AND j.claim_token=? AND j.status='RUNNING' AND j.lease_until>NOW(3) AND j.deadline_at>NOW(3) AND j.cancel_requested=FALSE AND m.status='ACTIVE' AND m.content_version=j.media_version",
                code,claim.taskId(),claim.epoch(),claim.token());
    }
}
