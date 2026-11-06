package dev.pitayaflow.backend;

// Adapted from LinkVideo f5451c4, MediaIngestService/MediaService/MediaFile/MinioUtils (MIT, Copyright 2026 Majst):
// keeps server-side ownership and private object identity, without YtDlp, public URL or video-only naming.
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.function.Function;

@Service
final class MediaService {
    private static final Logger log=LoggerFactory.getLogger(MediaService.class);
    record Media(String id, long ownerId, String objectKey, String filename, String sha256, long size, long version, String kind, String status, Long durationMs) {
        Map<String,Object> publicView() { var view=new java.util.LinkedHashMap<String,Object>();view.put("media_id",id);view.put("filename",filename);
            view.put("size_bytes",size);view.put("kind",kind);view.put("content_version",version);view.put("duration_ms",durationMs);return view; }
    }
    private final JdbcTemplate db;
    private final ObjectStore objects;
    private final Probe probe;
    private final TransactionTemplate transaction;
    private final ProbeTempFiles tempFiles;
    private final Function<Media,Map<String,Object>> responseView;
    private final ScheduledExecutorService heartbeats=Executors.newScheduledThreadPool(2, task -> {
        Thread thread=new Thread(task,"pf-upload-lease"); thread.setDaemon(true); return thread;
    });
    @Autowired MediaService(JdbcTemplate db, ObjectStore objects, Probe probe, TransactionTemplate transaction, ProbeTempFiles tempFiles) {
        this(db,objects,probe,transaction,tempFiles,Media::publicView);
    }
    MediaService(JdbcTemplate db,ObjectStore objects,Probe probe,TransactionTemplate transaction,ProbeTempFiles tempFiles,
                 Function<Media,Map<String,Object>> responseView) {
        this.db=db; this.objects=objects; this.probe=probe; this.transaction=transaction;
        this.tempFiles=tempFiles; this.responseView=responseView;
    }
    Media owned(String id, long owner) {
        var rows = db.query("SELECT * FROM pf_media WHERE id=? AND owner_id=? AND status='ACTIVE'", (rs,n) -> new Media(
                rs.getString("id"), rs.getLong("owner_id"), rs.getString("object_key"), rs.getString("filename"),
                rs.getString("content_sha256"), rs.getLong("size_bytes"), rs.getLong("content_version"), rs.getString("kind"), rs.getString("status"),
                rs.getObject("duration_ms",Long.class)), id, owner);
        if (rows.size() != 1) throw ApiError.missing(); return rows.getFirst();
    }
    List<Map<String,Object>> list(long owner) {
        return db.query("SELECT * FROM pf_media WHERE owner_id=? AND status='ACTIVE' ORDER BY created_at DESC", (rs,n) ->
                new Media(rs.getString("id"), rs.getLong("owner_id"), rs.getString("object_key"), rs.getString("filename"),
                        rs.getString("content_sha256"), rs.getLong("size_bytes"), rs.getLong("content_version"), rs.getString("kind"), rs.getString("status"),
                        rs.getObject("duration_ms",Long.class)).publicView(), owner);
    }
    Map<String,Object> upload(long owner, MultipartFile file) throws Exception {
        if (file.isEmpty() || file.getSize() <= 0 || file.getSize() > 32 * 1024 * 1024) throw ApiError.bad("invalid file size");
        Path temp = tempFiles.create();
        String key = null;
        String token = null;
        Exception primary = null;
        java.util.concurrent.ScheduledFuture<?> heartbeat = null;
        try {
            try (InputStream in = file.getInputStream()) { Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            long actual = Files.size(temp);
            if (actual == 0 || actual > 32 * 1024 * 1024) throw ApiError.bad("invalid file size");
            Map<String,Object> details = probe.inspect(temp, Duration.ofSeconds(12));
            String id = UUID.randomUUID().toString(); key = "media/" + id; token=UUID.randomUUID().toString();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(temp)));
            db.update("INSERT INTO pf_object_intent(object_key,state,claim_token,lease_until) VALUES(?,'UPLOADING',?,DATE_ADD(NOW(3),INTERVAL 30 SECOND))",key,token);
            String uploadKey=key, uploadToken=token;
            AtomicBoolean leaseLost=new AtomicBoolean();
            heartbeat=heartbeats.scheduleWithFixedDelay(() -> {
                try {
                    if (db.update("UPDATE pf_object_intent SET lease_until=DATE_ADD(NOW(3),INTERVAL 30 SECOND),updated_at=NOW(3) WHERE object_key=? AND claim_token=? AND state='UPLOADING' AND lease_until>NOW(3)",uploadKey,uploadToken)==0)
                        leaseLost.set(true);
                } catch (Exception e) { leaseLost.set(true); }
            },5,5,TimeUnit.SECONDS);
            objects.put(key, temp);
            String name = normalizeFilename(file.getOriginalFilename());
            String finalKey=key;
            String finalToken=token;
            transaction.executeWithoutResult(tx -> {
                var intent=db.queryForList("SELECT state,claim_token,lease_until>NOW(3) AS live FROM pf_object_intent WHERE object_key=? FOR UPDATE",finalKey);
                if (leaseLost.get() || intent.size()!=1 || !"UPLOADING".equals(intent.getFirst().get("state")) ||
                        !finalToken.equals(intent.getFirst().get("claim_token")) || ((Number)intent.getFirst().get("live")).intValue()!=1)
                    throw ApiError.conflict("upload qualification expired");
                db.update("INSERT INTO pf_media(id,owner_id,object_key,filename,content_sha256,size_bytes,kind,status,duration_ms) VALUES(?,?,?,?,?,?,?,'ACTIVE',?)",
                        id, owner, finalKey, name, hash, actual, details.get("kind"),Math.round(((Number)details.get("duration_seconds")).doubleValue()*1000));
                if (db.update("DELETE FROM pf_object_intent WHERE object_key=? AND claim_token=? AND state='UPLOADING'",finalKey,finalToken)!=1)
                    throw ApiError.conflict("upload qualification changed");
            });
            return responseView.apply(new Media(id,owner,key,name,hash,actual,1,(String)details.get("kind"),"ACTIVE",
                    Math.round(((Number)details.get("duration_seconds")).doubleValue()*1000)));
        } catch (Exception e) {
            primary=e;
            if (key != null && token != null) try { if (claimReap(key,token,false)) reap(key); }
            catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw e;
        } finally {
            if (heartbeat!=null) heartbeat.cancel(true);
            tempFiles.cleanup(temp,primary);
        }
    }
    byte[] download(String id, long owner) throws Exception { return objects.get(owned(id, owner).objectKey()); }
    InputStream openRange(String key,long offset,long length) throws java.io.IOException {
        try{return objects.openRange(key,offset,length);}catch(Exception error){throw new java.io.IOException("private media read failed",error);}
    }
    void delete(String id, long owner) {
        String key=transaction.execute(tx -> {
            var rows=db.queryForList("SELECT object_key FROM pf_media WHERE id=? AND owner_id=? AND status='ACTIVE' FOR UPDATE",id,owner);
            if (rows.size()!=1) throw ApiError.missing();
            db.update("UPDATE pf_media SET status='DELETING' WHERE id=?",id);
            db.update("UPDATE pf_job SET cancel_requested=TRUE,status=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE status END WHERE media_id=? AND status IN ('QUEUED','RUNNING')",id);
            return (String)rows.getFirst().get("object_key");
        });
        cleanup(key,id);
    }
    private void cleanup(String key, String id) {
        try { objects.remove(key); db.update("UPDATE pf_media SET status='DELETED' WHERE id=? AND status='DELETING'", id); }
        catch (Exception ignored) { /* tombstone remains for retry */ }
    }
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void reconcile() {
        // Object calls have a 30 s cap; retained tombstones cover late puts before eventual pruning.
        db.update("DELETE FROM pf_object_intent WHERE state='REMOVED' AND removed_at<DATE_SUB(NOW(3),INTERVAL 2 MINUTE)");
        db.query("SELECT id,object_key FROM pf_media WHERE status='DELETING'", (org.springframework.jdbc.core.RowCallbackHandler)rs -> cleanup(rs.getString(2), rs.getString(1)));
        // Rotate each queue independently: a large tombstone backlog must not starve legacy uploads.
        for (String key:db.queryForList("SELECT object_key FROM pf_object_intent WHERE state='REAPING' OR (state='UPLOADING' AND (lease_until IS NULL OR lease_until<NOW(3))) ORDER BY updated_at,object_key LIMIT 100",String.class)) {
            try { if (claimReap(key,null,true)) reap(key); }
            catch (Exception error) { log.warn("object_reap_retry exception_type={}",error.getClass().getSimpleName()); }
        }
        for (String key:db.queryForList("SELECT object_key FROM pf_object_intent WHERE state='REMOVED' ORDER BY updated_at,object_key LIMIT 100",String.class)) {
            try { if (claimReap(key,null,true)) reap(key); }
            catch (Exception error) { log.warn("object_reap_retry exception_type={}",error.getClass().getSimpleName()); }
        }
    }
    private boolean claimReap(String key,String token,boolean expiredOnly) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            var rows=db.queryForList("SELECT state,claim_token,lease_until>NOW(3) AS live FROM pf_object_intent WHERE object_key=? FOR UPDATE",key);
            if (rows.size()!=1) return false; // committed media has removed the intent in the same transaction
            var row=rows.getFirst();
            if (token!=null && !token.equals(row.get("claim_token"))) return false;
            if ("UPLOADING".equals(row.get("state"))) {
                if (expiredOnly && row.get("live") instanceof Number live && live.intValue()==1) return false;
                Integer refs=db.queryForObject("SELECT COUNT(*) FROM pf_media WHERE object_key=?",Integer.class,key);
                if (refs!=null && refs>0) return false;
                db.update("UPDATE pf_object_intent SET state='REAPING',updated_at=NOW(3) WHERE object_key=? AND state='UPLOADING'",key);
                return true;
            }
            return "REAPING".equals(row.get("state")) || "REMOVED".equals(row.get("state"));
        }));
    }
    private void reap(String key) throws Exception {
        objects.remove(key);
        db.update("UPDATE pf_object_intent SET state='REMOVED',removed_at=COALESCE(removed_at,NOW(3)),updated_at=NOW(3) WHERE object_key=? AND state IN ('REAPING','REMOVED')",key);
        // Keep the tombstone so a slow put that returns after this remove is removed on a later pass.
    }
    @PreDestroy void stopHeartbeats() { heartbeats.shutdownNow(); }
    private static String normalizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "media";
        String name = raw.replace('\\','/'); name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "_").trim();
        return name.isBlank() ? "media" : name.substring(0, Math.min(255, name.length()));
    }
}
