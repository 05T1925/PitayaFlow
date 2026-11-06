package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class SyntheticSourceService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Path contracts;
    SyntheticSourceService(JdbcTemplate db,TransactionTemplate tx,ObjectMapper mapper,
            @Value("${pf.agent.contracts-dir:packages/contracts}") String contracts) {
        this.db=db;this.tx=tx;this.mapper=mapper;this.contracts=Path.of(contracts);
    }
    record Snapshot(String id,long revision,String sha256,long speakerRevision,String json,JsonNode content) {}
    private String fixture(String fixtureId) throws Exception {
        String name=switch(fixtureId) {case "a" -> "synthetic-case.json";case "b" -> "synthetic-case-b.json";
            case "unknown" -> "synthetic-case.json";default -> throw ApiError.bad("unknown synthetic fixture");};
        JsonNode root=mapper.readTree(Files.readString(contracts.resolve(name),StandardCharsets.UTF_8));
        if(fixtureId.equals("unknown")) ((com.fasterxml.jackson.databind.node.ObjectNode)root).put("speaker_status","unknown");
        return mapper.writeValueAsString(root);
    }
    private static String sha(String text) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    Map<String,Object> create(long owner,String mediaId,String fixtureId) throws Exception {
        String json=fixture(fixtureId),hash=sha(json),id=UUID.randomUUID().toString();
        return tx.execute(state->{
            var media=db.queryForList("SELECT id FROM pf_media WHERE id=? AND owner_id=? AND status='ACTIVE' FOR UPDATE",mediaId,owner);
            if(media.size()!=1) throw ApiError.missing();
            db.update("INSERT INTO pf_synthetic_source(id,owner_id,media_id) VALUES(?,?,?)",id,owner,mediaId);
            db.update("INSERT INTO pf_synthetic_revision(source_id,revision,content_sha256,content_json,speaker_revision) VALUES(?,1,?,?,1)",id,hash,json);
            return Map.of("source_id",id,"revision",1,"source_sha256",hash,"speaker_revision",1,"synthetic",true);
        });
    }
    Map<String,Object> revise(long owner,String id,String fixtureId) throws Exception {
        String json=fixture(fixtureId),hash=sha(json);
        return tx.execute(state->{
            var rows=db.queryForList("SELECT s.current_revision FROM pf_synthetic_source s JOIN pf_media m ON m.id=s.media_id WHERE s.id=? AND s.owner_id=? AND m.status='ACTIVE' FOR UPDATE",id,owner);
            if(rows.size()!=1) throw ApiError.missing();
            long next=((Number)rows.getFirst().get("current_revision")).longValue()+1;
            db.update("INSERT INTO pf_synthetic_revision(source_id,revision,content_sha256,content_json,speaker_revision) VALUES(?,?,?,?,?)",id,next,hash,json,next);
            db.update("UPDATE pf_synthetic_source SET current_revision=? WHERE id=?",next,id);
            return Map.of("source_id",id,"revision",next,"source_sha256",hash,"speaker_revision",next,"synthetic",true);
        });
    }
    Snapshot current(long owner,String id) {
        var rows=db.queryForList("SELECT r.revision,r.content_sha256,r.content_json,r.speaker_revision FROM pf_synthetic_source s JOIN pf_synthetic_revision r ON r.source_id=s.id AND r.revision=s.current_revision JOIN pf_media m ON m.id=s.media_id WHERE s.id=? AND s.owner_id=? AND m.status='ACTIVE'",id,owner);
        if(rows.size()!=1) throw ApiError.missing();
        return snapshot(id,rows.getFirst());
    }
    Snapshot version(long owner,String id,long revision) {
        var rows=db.queryForList("SELECT r.revision,r.content_sha256,r.content_json,r.speaker_revision FROM pf_synthetic_source s JOIN pf_synthetic_revision r ON r.source_id=s.id JOIN pf_media m ON m.id=s.media_id WHERE s.id=? AND s.owner_id=? AND r.revision=? AND m.status='ACTIVE'",id,owner,revision);
        if(rows.size()!=1) throw ApiError.missing();
        return snapshot(id,rows.getFirst());
    }
    private Snapshot snapshot(String id,Map<String,Object> row) {
        try {
            String json=(String)row.get("content_json"),digest=(String)row.get("content_sha256");
            if(!sha(json).equals(digest)) throw ApiError.conflict("synthetic source content hash mismatch");
            return new Snapshot(id,((Number)row.get("revision")).longValue(),digest,
                ((Number)row.get("speaker_revision")).longValue(),json,mapper.readTree(json));
        }catch(ApiError e){throw e;}catch(Exception e){throw new IllegalStateException(e);}
    }
    JsonNode authorized(String attempt,String token,String caseId,String ownerId) {
        if(token==null || token.length()<40) throw ApiError.unauthorized();
        String digest;
        try{digest=sha(token);}catch(Exception e){throw new IllegalStateException(e);}
        var rows=db.queryForList("SELECT r.content_json,r.content_sha256,s.owner_id FROM pf_agent_grant g JOIN pf_job j ON j.id=g.job_id JOIN pf_agent_input i ON i.job_id=j.id JOIN pf_synthetic_source s ON s.id=i.source_id JOIN pf_synthetic_revision r ON r.source_id=s.id AND r.revision=i.source_revision JOIN pf_media m ON m.id=j.media_id WHERE g.attempt_id=? AND g.token_sha256=? AND g.state='ACTIVE' AND g.expires_at>NOW(3) AND j.epoch=g.epoch AND j.claim_token=g.claim_token AND j.status='RUNNING' AND j.lease_until>NOW(3) AND j.deadline_at>NOW(3) AND j.cancel_requested=FALSE AND j.owner_id=s.owner_id AND j.owner_id=m.owner_id AND s.media_id=j.media_id AND s.current_revision=i.source_revision AND i.source_sha256=r.content_sha256 AND i.speaker_revision=r.speaker_revision AND m.status='ACTIVE' AND m.content_version=j.media_version",attempt,digest);
        if(rows.size()!=1 || !ownerId.equals(String.valueOf(rows.getFirst().get("owner_id")))) throw ApiError.missing();
        try {String json=(String)rows.getFirst().get("content_json");
            if(!sha(json).equals(rows.getFirst().get("content_sha256"))) throw ApiError.conflict("synthetic source content hash mismatch");
            JsonNode content=mapper.readTree(json);
            if(!caseId.equals(content.path("case_id").asText())) throw ApiError.missing();return content;
        }catch(ApiError e){throw e;}catch(Exception e){throw new IllegalStateException(e);}
    }
    void ready(String attempt,String token,String caseId,String ownerId) {
        authorized(attempt,token,caseId,ownerId);
        try {if(db.update("UPDATE pf_agent_grant SET state='READY' WHERE attempt_id=? AND token_sha256=? AND state='ACTIVE'",attempt,sha(token))!=1)
            throw ApiError.conflict("attempt already returned");}
        catch(ApiError e){throw e;}catch(Exception e){throw new IllegalStateException(e);}
    }
}
