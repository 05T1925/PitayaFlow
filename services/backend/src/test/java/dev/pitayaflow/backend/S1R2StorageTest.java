package dev.pitayaflow.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Real two-connection MySQL and MinIO barriers. Only synthetic rows with this run's UUID are touched. */
@EnabledIfEnvironmentVariable(named="PF_S1_R2_INTEGRATION", matches="1")
class S1R2StorageTest {
    private static final String SYNTHETIC_SOURCE_ID = "PF-C001-synthetic";
    static JdbcTemplate db;
    static TransactionTemplate tx;
    static ObjectStore store;
    static long owner;
    static final String prefix="s1r2_"+UUID.randomUUID().toString().replace("-","").substring(0,10);
    static final java.util.List<String> mediaIds=new java.util.ArrayList<>();
    static final java.util.List<String> jobIds=new java.util.ArrayList<>();
    static final java.util.List<String> objectKeys=new java.util.ArrayList<>();
    static final java.util.List<String> sourceIds=new java.util.ArrayList<>();
    @BeforeAll static void setup() {
        var source=new DriverManagerDataSource(System.getenv().getOrDefault("PF_DB_URL","jdbc:mysql://127.0.0.1:33761/pitayaflow?useSSL=false&allowPublicKeyRetrieval=true"),"pf_s1",System.getenv("PF_DB_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();
        db=new JdbcTemplate(source); tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        store=new ObjectStore(System.getenv().getOrDefault("PF_MINIO_ENDPOINT","http://127.0.0.1:19001"),System.getenv("PF_MINIO_ACCESS_KEY"),System.getenv("PF_MINIO_SECRET_KEY"),System.getenv().getOrDefault("PF_MINIO_BUCKET","pf-s1-private"));
        db.update("INSERT INTO pf_user(username,password_hash,role) VALUES(?,?,'USER')",prefix,"synthetic-test-only");
        owner=db.queryForObject("SELECT id FROM pf_user WHERE username=?",Long.class,prefix);
    }
    @AfterAll static void cleanup() throws Exception {
        if(db==null) return;
        for(String id:jobIds){db.update("DELETE FROM pf_agent_result WHERE job_id=?",id);db.update("DELETE FROM pf_agent_grant WHERE job_id=?",id);
            db.update("DELETE FROM pf_agent_input WHERE job_id=?",id);db.update("DELETE FROM pf_outbox WHERE job_id=?",id);db.update("DELETE FROM pf_job WHERE id=?",id);}
        for(String id:sourceIds){db.update("DELETE FROM pf_synthetic_revision WHERE source_id=?",id);db.update("DELETE FROM pf_synthetic_source WHERE id=?",id);}
        for(String id:mediaIds) db.update("DELETE FROM pf_media WHERE id=?",id);
        for(String key:objectKeys){store.remove(key);db.update("DELETE FROM pf_object_intent WHERE object_key=?",key);}
        db.update("DELETE FROM pf_user WHERE id=?",owner);
    }
    static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String media() throws Exception {
        String id=UUID.randomUUID().toString(), key="media/"+id;
        mediaIds.add(id);objectKeys.add(key);
        byte[] bytes=new byte[]{1,2,3};Path file=Files.createTempFile("pf-r2-", ".bin");
        try {Files.write(file,bytes);store.put(key,file);} finally {Files.deleteIfExists(file);}
        db.update("INSERT INTO pf_media(id,owner_id,object_key,filename,content_sha256,size_bytes,kind,status) VALUES(?,?,?,?,?,3,'WAV','ACTIVE')",id,owner,key,"synthetic.wav",hash(bytes));
        return id;
    }
    static String job(String media) {
        String id=UUID.randomUUID().toString();jobIds.add(id);
        db.update("INSERT INTO pf_job(id,owner_id,media_id,media_version,operation,idempotency_key,request_digest,status,deadline_at) VALUES(?,?,?,1,'PROBE',?,?,'QUEUED',DATE_ADD(NOW(3),INTERVAL 120 SECOND))",id,owner,media,UUID.randomUUID().toString(),"0".repeat(64));
        return id;
    }
    static MediaService service(ObjectStore objectStore, TransactionTemplate transaction, java.util.function.Function<MediaService.Media,Map<String,Object>> response) throws Exception {
        Probe probe=mock(Probe.class);
        when(probe.inspect(any(),any(Duration.class))).thenReturn(Map.of("kind","WAV","duration_seconds",1.0));
        return new MediaService(db,objectStore,probe,transaction,new ProbeTempFiles(Files.createTempDirectory("pf-r2-upload-").toString()),response);
    }
    static MockMultipartFile uploadFile(){return new MockMultipartFile("file","synthetic.wav","audio/wav",new byte[]{5,6,7});}
    @Test void commitThenResponseFailureKeepsPublishedObject() throws Exception {
        var before=db.queryForList("SELECT id FROM pf_media WHERE owner_id=?",String.class,owner);
        var api=service(store,tx,m->{throw new IllegalStateException("injected response failure");});
        assertThrows(IllegalStateException.class,()->api.upload(owner,uploadFile()));
        var after=db.queryForList("SELECT id,object_key,content_sha256 FROM pf_media WHERE owner_id=? AND status='ACTIVE'",owner);
        var published=after.stream().filter(row->!before.contains(row.get("id"))).toList();
        assertEquals(1,published.size());
        String id=(String)published.getFirst().get("id"), key=(String)published.getFirst().get("object_key");
        mediaIds.add(id);objectKeys.add(key);
        assertEquals(published.getFirst().get("content_sha256"),hash(store.get(key)));
        api.reconcile();
        assertEquals(published.getFirst().get("content_sha256"),hash(store.get(key)));
        api.stopHeartbeats();
    }
    @Test void explicitRollbackLeavesReapIntentAndNoPublishedMedia() throws Exception {
        TransactionTemplate rollback=spy(tx);
        doThrow(new IllegalStateException("injected transaction rollback")).when(rollback).executeWithoutResult(any());
        var api=service(store,rollback,MediaService.Media::publicView);
        int before=db.queryForObject("SELECT COUNT(*) FROM pf_media WHERE owner_id=?",Integer.class,owner);
        assertThrows(IllegalStateException.class,()->api.upload(owner,uploadFile()));
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM pf_media WHERE owner_id=?",Integer.class,owner));
        var keys=db.queryForList("SELECT object_key FROM pf_object_intent WHERE state='REMOVED' ORDER BY created_at DESC LIMIT 1",String.class);
        assertFalse(keys.isEmpty());objectKeys.add(keys.getFirst());
        assertThrows(Exception.class,()->store.get(keys.getFirst()));
        api.stopHeartbeats();
    }
    @Test void unknownCommitResultMustKeepPublishedObject() throws Exception {
        TransactionTemplate uncertain=spy(tx);
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("injected unknown commit acknowledgement");})
                .when(uncertain).executeWithoutResult(any());
        int before=db.queryForObject("SELECT COUNT(*) FROM pf_media WHERE owner_id=?",Integer.class,owner);
        var api=service(store,uncertain,MediaService.Media::publicView);
        assertThrows(IllegalStateException.class,()->api.upload(owner,uploadFile()));
        var rows=db.queryForList("SELECT id,object_key,content_sha256 FROM pf_media WHERE owner_id=? ORDER BY created_at DESC LIMIT 1",owner);
        assertEquals(before+1,db.queryForObject("SELECT COUNT(*) FROM pf_media WHERE owner_id=?",Integer.class,owner));
        var row=rows.getFirst();mediaIds.add((String)row.get("id"));objectKeys.add((String)row.get("object_key"));
        assertEquals(row.get("content_sha256"),hash(store.get((String)row.get("object_key"))));
        api.stopHeartbeats();
    }
    @Test void oldClaimCannotBorrowNewEpochOrPublishAfterLeaseLoss() throws Exception {
        String id=job(media());CountDownLatch claimed=new CountDownLatch(1), release=new CountDownLatch(1);
        JobLease first=new JobLease(db,()->{claimed.countDown();try{assertTrue(release.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}});
        var old=java.util.concurrent.CompletableFuture.supplyAsync(()->first.claim(id));
        assertTrue(claimed.await(5,TimeUnit.SECONDS));
        db.update("UPDATE pf_job SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",id);
        JobLease next=new JobLease(db);var newer=next.claim(id);assertNotNull(newer);
        release.countDown();assertNull(old.get(8,TimeUnit.SECONDS));
        assertEquals(2,newer.epoch());
        assertTrue(next.renew(newer));
        db.update("UPDATE pf_job SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",id);
        assertFalse(next.renew(newer));assertFalse(next.publishProbe(newer,"{}"));next.fail(newer);
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM pf_job WHERE id=?",String.class,id));
        var third=next.claim(id);assertNotNull(third);
        db.update("UPDATE pf_job SET cancel_requested=TRUE WHERE id=?",id);
        assertFalse(next.publishProbe(third,"{}"));assertFalse(next.renew(third));
        String deadlineJob=job(media());var deadlineClaim=next.claim(deadlineJob);assertNotNull(deadlineClaim);
        db.update("UPDATE pf_job SET deadline_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",deadlineJob);
        assertFalse(next.publishProbe(deadlineClaim,"{}"));next.fail(deadlineClaim);
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM pf_job WHERE id=?",String.class,deadlineJob));
        String deletedMedia=media(), deletedJob=job(deletedMedia);var deletedClaim=next.claim(deletedJob);assertNotNull(deletedClaim);
        db.update("UPDATE pf_media SET status='DELETING' WHERE id=?",deletedMedia);
        assertFalse(next.publishProbe(deletedClaim,"{}"));assertFalse(next.renew(deletedClaim));
    }
    @Test void activeUploadAndReaperAreSerializedByIntent() throws Exception {
        var real=store;ObjectStore held=spy(real);
        CountDownLatch putDone=new CountDownLatch(1), release=new CountDownLatch(1);
        doAnswer(inv->{Object result=inv.callRealMethod();putDone.countDown();assertTrue(release.await(8,TimeUnit.SECONDS));return result;}).when(held).put(any(),any());
        var api=service(held,tx,MediaService.Media::publicView);
        var future=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return api.upload(owner,uploadFile());}catch(Exception e){throw new RuntimeException(e);}});
        assertTrue(putDone.await(6,TimeUnit.SECONDS));
        String key=db.queryForObject("SELECT object_key FROM pf_object_intent WHERE claim_token IS NOT NULL AND state='UPLOADING' ORDER BY created_at DESC LIMIT 1",String.class);
        objectKeys.add(key);
        api.reconcile();assertArrayEquals(new byte[]{5,6,7},store.get(key));
        release.countDown();String id=(String)future.get(8,TimeUnit.SECONDS).get("media_id");mediaIds.add(id);
        api.reconcile();assertArrayEquals(new byte[]{5,6,7},store.get(key));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM pf_object_intent WHERE object_key=?",Integer.class,key));
        api.stopHeartbeats();
    }
    @Test void expiredCrashedIntentIsReapedAndRepeatedReapersAreSafe() throws Exception {
        String key="media/"+UUID.randomUUID();objectKeys.add(key);
        Path file=Files.createTempFile("pf-r2-orphan-",".bin");
        try {Files.write(file,new byte[]{9});store.put(key,file);} finally {Files.deleteIfExists(file);}
        db.update("INSERT INTO pf_object_intent(object_key,state,claim_token,lease_until) VALUES(?,'UPLOADING',?,DATE_SUB(NOW(3),INTERVAL 1 SECOND))",key,UUID.randomUUID().toString());
        var api=service(store,tx,MediaService.Media::publicView);
        var one=java.util.concurrent.CompletableFuture.runAsync(api::reconcile);
        var two=java.util.concurrent.CompletableFuture.runAsync(api::reconcile);
        one.get(30,TimeUnit.SECONDS);two.get(30,TimeUnit.SECONDS);
        assertEquals("REMOVED",db.queryForObject("SELECT state FROM pf_object_intent WHERE object_key=?",String.class,key));
        assertThrows(Exception.class,()->store.get(key));api.stopHeartbeats();
    }
    @Test void legacyNullLeaseIsReapedButReferencedObjectIsProtected() throws Exception {
        String orphan="media/"+UUID.randomUUID();objectKeys.add(orphan);
        Path file=Files.createTempFile("pf-legacy-orphan-",".bin");
        try {Files.write(file,new byte[]{8});store.put(orphan,file);} finally {Files.deleteIfExists(file);}
        db.update("INSERT INTO pf_object_intent(object_key,state,claim_token,lease_until) VALUES(?,'UPLOADING',NULL,NULL)",orphan);
        String liveMedia=media();String referenced=db.queryForObject("SELECT object_key FROM pf_media WHERE id=?",String.class,liveMedia);
        db.update("INSERT INTO pf_object_intent(object_key,state,claim_token,lease_until) VALUES(?,'UPLOADING',NULL,NULL)",referenced);
        var api=service(store,tx,MediaService.Media::publicView);
        api.reconcile();
        assertEquals("REMOVED",db.queryForObject("SELECT state FROM pf_object_intent WHERE object_key=?",String.class,orphan));
        assertThrows(Exception.class,()->store.get(orphan));
        assertEquals("UPLOADING",db.queryForObject("SELECT state FROM pf_object_intent WHERE object_key=?",String.class,referenced));
        assertArrayEquals(new byte[]{1,2,3},store.get(referenced));api.stopHeartbeats();
    }
    @Test void v1RowsSurviveRealMigrationAndFollowCurrentReaperRules() throws Exception {
        String schema="pf_legacy_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        String base=System.getenv().getOrDefault("PF_DB_URL","jdbc:mysql://127.0.0.1:33761/pitayaflow?useSSL=false&allowPublicKeyRetrieval=true");
        String rootUrl=base.replaceFirst("/pitayaflow\\?","/?");
        var admin=new JdbcTemplate(new DriverManagerDataSource(rootUrl,"root",System.getenv("PF_DB_ROOT_PASSWORD")));
        String orphan="media/"+UUID.randomUUID(),referenced="media/"+UUID.randomUUID();
        objectKeys.add(orphan);objectKeys.add(referenced);
        admin.execute("CREATE DATABASE "+schema);
        try {
            String scratchUrl=base.replaceFirst("/pitayaflow\\?","/"+schema+"?");
            var datasource=new DriverManagerDataSource(scratchUrl,"root",System.getenv("PF_DB_ROOT_PASSWORD"));
            Flyway.configure().dataSource(datasource).target("1").load().migrate();
            var legacy=new JdbcTemplate(datasource);
            legacy.update("INSERT INTO pf_user(username,password_hash,role) VALUES(?,?,'USER')",schema,"synthetic-test-only");
            long scratchOwner=legacy.queryForObject("SELECT id FROM pf_user WHERE username=?",Long.class,schema);
            legacy.update("INSERT INTO pf_object_intent(object_key) VALUES(?),(?)",orphan,referenced);
            legacy.update("INSERT INTO pf_media(id,owner_id,object_key,filename,content_sha256,size_bytes,kind,status) VALUES(?,?,?,?,?,1,'WAV','ACTIVE')",
                UUID.randomUUID().toString(),scratchOwner,referenced,"legacy.wav","0".repeat(64));
            Path file=Files.createTempFile("pf-v1-migration-",".bin");
            try {Files.write(file,new byte[]{7});store.put(orphan,file);store.put(referenced,file);} finally {Files.deleteIfExists(file);}
            Flyway.configure().dataSource(datasource).load().migrate();
            assertNull(legacy.queryForObject("SELECT lease_until FROM pf_object_intent WHERE object_key=?",java.sql.Timestamp.class,orphan));
            var scratchTx=new TransactionTemplate(new DataSourceTransactionManager(datasource));
            var migrated=new MediaService(legacy,store,mock(Probe.class),scratchTx,
                new ProbeTempFiles(Files.createTempDirectory("pf-v1-probe-").toString()),MediaService.Media::publicView);
            migrated.reconcile();
            assertEquals("REMOVED",legacy.queryForObject("SELECT state FROM pf_object_intent WHERE object_key=?",String.class,orphan));
            assertThrows(Exception.class,()->store.get(orphan));
            assertEquals("UPLOADING",legacy.queryForObject("SELECT state FROM pf_object_intent WHERE object_key=?",String.class,referenced));
            assertArrayEquals(new byte[]{7},store.get(referenced));migrated.stopHeartbeats();
        } finally {admin.execute("DROP DATABASE "+schema);}
    }
    @Test void removedAtDoesNotRenewAndBacklogRotatesPastOneHundred() throws Exception {
        var api=service(store,tx,MediaService.Media::publicView);
        String target="media/"+UUID.randomUUID();objectKeys.add(target);
        db.update("INSERT INTO pf_object_intent(object_key,state,removed_at,updated_at) VALUES(?,'REMOVED',DATE_SUB(NOW(3),INTERVAL 90 SECOND),DATE_SUB(NOW(3),INTERVAL 90 SECOND))",target);
        // A held tombstone is revisited, but its retention clock remains stable.
        api.reconcile();
        long age=db.queryForObject("SELECT TIMESTAMPDIFF(SECOND,removed_at,NOW(3)) FROM pf_object_intent WHERE object_key=?",Long.class,target);
        assertTrue(age>=90);
        db.update("UPDATE pf_object_intent SET removed_at=DATE_SUB(NOW(3),INTERVAL 121 SECOND) WHERE object_key=?",target);
        api.reconcile();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM pf_object_intent WHERE object_key=?",Integer.class,target));
        // More than one scan page must eventually be processed.
        java.util.List<String> backlog=new java.util.ArrayList<>();
        for(int i=0;i<105;i++){String key="media/"+UUID.randomUUID();backlog.add(key);objectKeys.add(key);
            db.update("INSERT INTO pf_object_intent(object_key,state,removed_at,updated_at) VALUES(?,'REMOVED',NOW(3),DATE_SUB(NOW(3),INTERVAL 10 SECOND))",key);}
        java.sql.Timestamp checkpoint=db.queryForObject("SELECT NOW(3)",java.sql.Timestamp.class);
        int visited=0;
        for(int pass=0;pass<10 && visited<105;pass++) {
            api.reconcile();
            visited=db.queryForObject("SELECT COUNT(*) FROM pf_object_intent WHERE object_key IN ("+String.join(",",java.util.Collections.nCopies(backlog.size(),"?"))+") AND updated_at>=?",Integer.class,
                java.util.stream.Stream.concat(backlog.stream(),java.util.stream.Stream.of(checkpoint)).toArray());
        }
        assertEquals(105,visited);
        db.update("DELETE FROM pf_object_intent WHERE object_key IN ("+String.join(",",java.util.Collections.nCopies(backlog.size(),"?"))+")",backlog.toArray());
        api.stopHeartbeats();
    }
    @Test void deletedMediaHidesHistoricalSourceAndResultBody() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();String mediaId=media();
        var sources=new SyntheticSourceService(db,tx,mapper,"../../packages/contracts");
        String sourceId=(String)sources.create(owner,mediaId,"a").get("source_id");sourceIds.add(sourceId);
        sources.revise(owner,sourceId,"b");
        assertNotNull(sources.version(owner,sourceId,1));
        assertThrows(ApiError.class,()->sources.version(owner+1,sourceId,1));
        String id=job(mediaId);db.update("UPDATE pf_job SET status='SUCCEEDED',result_json=? WHERE id=?","{\"synthetic\":true}",id);
        var jobs=new JobService(db,tx,null,null,null,mapper,null,null,null,null,null);
        assertTrue(jobs.view(id,owner).containsKey("result"));
        db.update("UPDATE pf_media SET status='DELETING' WHERE id=?",mediaId);
        assertThrows(ApiError.class,()->sources.version(owner,sourceId,1));
        assertFalse(jobs.view(id,owner).containsKey("result"));
        db.update("UPDATE pf_media SET status='DELETED' WHERE id=?",mediaId);
        assertThrows(ApiError.class,()->sources.version(owner,sourceId,2));
        assertFalse(jobs.view(id,owner).containsKey("result"));
    }
    @Test void uploadLongerThanInitialLeaseRenewsBeforePublishing() throws Exception {
        ObjectStore held=spy(store);CountDownLatch putDone=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(inv->{Object result=inv.callRealMethod();putDone.countDown();assertTrue(release.await(45,TimeUnit.SECONDS));return result;}).when(held).put(any(),any());
        var api=service(held,tx,MediaService.Media::publicView);
        var future=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return api.upload(owner,uploadFile());}catch(Exception e){throw new RuntimeException(e);}});
        assertTrue(putDone.await(6,TimeUnit.SECONDS));
        String key=db.queryForObject("SELECT object_key FROM pf_object_intent WHERE claim_token IS NOT NULL AND state='UPLOADING' ORDER BY created_at DESC LIMIT 1",String.class);
        objectKeys.add(key);
        Thread.sleep(32_000);
        assertEquals(1,db.queryForObject("SELECT lease_until>NOW(3) FROM pf_object_intent WHERE object_key=?",Integer.class,key));
        api.reconcile();release.countDown();
        String id=(String)future.get(8,TimeUnit.SECONDS).get("media_id");mediaIds.add(id);
        assertArrayEquals(new byte[]{5,6,7},store.get(key));api.stopHeartbeats();
    }
    @Test void latePutAfterExpiredReaperCannotPublishAndIsRemoved() throws Exception {
        ObjectStore held=spy(store);CountDownLatch enteredPut=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(inv->{enteredPut.countDown();assertTrue(release.await(30,TimeUnit.SECONDS));return inv.callRealMethod();}).when(held).put(any(),any());
        var api=service(held,tx,MediaService.Media::publicView);
        var future=java.util.concurrent.CompletableFuture.runAsync(()->{try{api.upload(owner,uploadFile());}catch(Exception expected){return;}throw new AssertionError("expired upload published");});
        assertTrue(enteredPut.await(5,TimeUnit.SECONDS));
        String key=db.queryForObject("SELECT object_key FROM pf_object_intent WHERE claim_token IS NOT NULL AND state='UPLOADING' ORDER BY created_at DESC LIMIT 1",String.class);
        objectKeys.add(key);
        db.update("UPDATE pf_object_intent SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE object_key=?",key);
        api.reconcile();
        assertEquals("REMOVED",db.queryForObject("SELECT state FROM pf_object_intent WHERE object_key=?",String.class,key));
        release.countDown();future.get(30,TimeUnit.SECONDS);api.reconcile();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM pf_media WHERE object_key=?",Integer.class,key));
        assertThrows(Exception.class,()->store.get(key));api.stopHeartbeats();
    }
    @Test void agentResultInsertAndTerminalStateAreAtomicAndGrantIsScoped() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();String mediaId=media();
        var sourceService=new SyntheticSourceService(db,tx,mapper,"../../packages/contracts");
        var source=sourceService.create(owner,mediaId,"a");String sourceId=(String)source.get("source_id");sourceIds.add(sourceId);
        assertEquals(source.get("source_sha256"),sourceService.version(owner,sourceId,1).sha256());
        assertThrows(ApiError.class,()->sourceService.version(owner+1,sourceId,1));
        var protocol=new AgentProtocol(mapper,"../../packages/contracts");var lease=new JobLease(db);
        var agent=new AgentService(db,tx,sourceService,lease,mapper,protocol,"unused","node","http://127.0.0.1:18080","../../packages/contracts","success",0);
        var accepted=agent.accept(owner,mediaId,sourceId,UUID.randomUUID().toString());String id=(String)accepted.get("task_id");jobIds.add(id);
        var claim=lease.claim(id);assertNotNull(claim);String attempt=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
        String token=UUID.randomUUID().toString()+UUID.randomUUID().toString();
        db.update("INSERT INTO pf_agent_grant(attempt_id,job_id,epoch,claim_token,token_sha256,run_id,expires_at,state) VALUES(?,?,?,?,?,?,DATE_ADD(NOW(3),INTERVAL 60 SECOND),'READY')",
            attempt,id,claim.epoch(),claim.token(),hash(token.getBytes()),run);
        String readingAttempt=UUID.randomUUID().toString(),readingToken=UUID.randomUUID().toString()+UUID.randomUUID().toString();
        db.update("INSERT INTO pf_agent_grant(attempt_id,job_id,epoch,claim_token,token_sha256,run_id,expires_at,state) VALUES(?,?,?,?,?,?,DATE_ADD(NOW(3),INTERVAL 60 SECOND),'ACTIVE')",
            readingAttempt,id,claim.epoch(),claim.token(),hash(readingToken.getBytes()),UUID.randomUUID().toString());
        assertEquals(SYNTHETIC_SOURCE_ID,sourceService.authorized(readingAttempt,readingToken,SYNTHETIC_SOURCE_ID,String.valueOf(owner)).path("case_id").asText());
        assertThrows(ApiError.class,()->sourceService.authorized(readingAttempt,readingToken,SYNTHETIC_SOURCE_ID,String.valueOf(owner+1)));
        assertThrows(ApiError.class,()->sourceService.authorized(readingAttempt,readingToken,"wrong-case",String.valueOf(owner)));
        assertThrows(ApiError.class,()->sourceService.authorized(readingAttempt,"wrong-token",SYNTHETIC_SOURCE_ID,String.valueOf(owner)));
        db.update("UPDATE pf_agent_input SET source_sha256=? WHERE job_id=?","0".repeat(64),id);
        assertThrows(ApiError.class,()->sourceService.authorized(readingAttempt,readingToken,SYNTHETIC_SOURCE_ID,String.valueOf(owner)));
        db.update("UPDATE pf_agent_input SET source_sha256=? WHERE job_id=?",source.get("source_sha256"),id);
        String originalJson=sourceService.version(owner,sourceId,1).json();
        db.update("UPDATE pf_synthetic_revision SET content_json=? WHERE source_id=? AND revision=1","{\"case_id\":\"tampered\"}",sourceId);
        assertThrows(ApiError.class,()->sourceService.version(owner,sourceId,1));
        assertThrows(ApiError.class,()->sourceService.authorized(readingAttempt,readingToken,SYNTHETIC_SOURCE_ID,String.valueOf(owner)));
        db.update("UPDATE pf_synthetic_revision SET content_json=? WHERE source_id=? AND revision=1",originalJson,sourceId);
        String expired=UUID.randomUUID().toString(), expiredToken=UUID.randomUUID().toString()+UUID.randomUUID().toString();
        db.update("INSERT INTO pf_agent_grant(attempt_id,job_id,epoch,claim_token,token_sha256,run_id,expires_at,state) VALUES(?,?,?,?,?,?,DATE_SUB(NOW(3),INTERVAL 1 SECOND),'ACTIVE')",
            expired,id,claim.epoch(),claim.token(),hash(expiredToken.getBytes()),UUID.randomUUID().toString());
        assertThrows(ApiError.class,()->sourceService.authorized(expired,expiredToken,SYNTHETIC_SOURCE_ID,String.valueOf(owner)));
        var input=new AgentService.Input(sourceId,1,(String)source.get("source_sha256"),1,SYNTHETIC_SOURCE_ID,owner);
        var draft=mapper.readTree("{\"case_id\":\""+SYNTHETIC_SOURCE_ID+"\",\"transcript_revision\":\"rev-1\",\"assessment\":\"Queue was mentioned.\",\"citation\":{\"transcript_revision\":\"rev-1\",\"segment_id\":\"seg-1\",\"start_ms\":1000,\"end_ms\":4200}}");
        db.update("INSERT INTO pf_agent_result(job_id,attempt_id,run_id,epoch,source_id,source_revision,source_sha256,speaker_revision,result_json) VALUES(?,?,?,?,?,1,?,1,'{}')",
            id,UUID.randomUUID().toString(),UUID.randomUUID().toString(),claim.epoch(),sourceId,source.get("source_sha256"));
        assertThrows(Exception.class,()->agent.publish(claim,input,attempt,run,draft));
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM pf_job WHERE id=?",String.class,id));
        db.update("DELETE FROM pf_agent_result WHERE job_id=?",id);
        agent.publish(claim,input,attempt,run,draft);
        assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM pf_job WHERE id=?",String.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM pf_agent_result WHERE job_id=?",Integer.class,id));
        assertThrows(Exception.class,()->agent.publish(claim,input,attempt,run,draft));
        assertThrows(ApiError.class,()->sourceService.authorized(attempt,token,SYNTHETIC_SOURCE_ID,String.valueOf(owner)));
    }
}
