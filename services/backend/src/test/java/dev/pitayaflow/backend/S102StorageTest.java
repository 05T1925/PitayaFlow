package dev.pitayaflow.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL/MinIO, local HTTP ASR fixture, synthetic WAV; no supplier audio call. */
@EnabledIfEnvironmentVariable(named="PF_S1_R2_INTEGRATION",matches="1")
class S102StorageTest {
    static JdbcTemplate db;static TransactionTemplate tx;static ObjectStore store;static MediaService media;static TranscriptService transcripts;
    static HttpServer fixture;static long owner,other;static String prefix="s102_"+UUID.randomUUID().toString().substring(0,8);
    static final java.util.List<String> mediaIds=new java.util.ArrayList<>();
    static final java.util.List<String> materialIds=new java.util.ArrayList<>();
    static final AtomicBoolean slow=new AtomicBoolean();static volatile CountDownLatch entered,release;
    static final String RESULT="""
        {"results":{"utterances":[{"start":0.1,"end":0.5,"transcript":"synthetic hello","speaker":0,"channel":0},
          {"start":0.4,"end":0.8,"transcript":"synthetic overlap","speaker":1,"channel":1}]}}
        """;
    @BeforeAll static void setup() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("PF_DB_URL"),"pf_s1",System.getenv("PF_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).load().migrate();db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        store=new ObjectStore(System.getenv("PF_MINIO_ENDPOINT"),System.getenv("PF_MINIO_ACCESS_KEY"),System.getenv("PF_MINIO_SECRET_KEY"),System.getenv("PF_MINIO_BUCKET"));
        for(String name:List.of(prefix+"a",prefix+"b"))db.update("INSERT INTO pf_user(username,password_hash,role) VALUES(?,?,'USER')",name,"synthetic-test-only");
        owner=db.queryForObject("SELECT id FROM pf_user WHERE username=?",Long.class,prefix+"a");
        other=db.queryForObject("SELECT id FROM pf_user WHERE username=?",Long.class,prefix+"b");
        fixture=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        fixture.createContext("/v1/listen",exchange->{
            exchange.getRequestBody().readAllBytes();
            if(slow.get()){entered.countDown();try{release.await(8,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
            byte[] body=RESULT.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try{exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}}catch(Exception ignored){exchange.close();}
        });fixture.start();
        var mapper=new ObjectMapper();var probe=new Probe(mapper,System.getenv().getOrDefault("PF_FFPROBE","ffprobe"),12);
        var temps=new ProbeTempFiles(Files.createTempDirectory("pf-s102-owned-").toString());
        media=new MediaService(db,store,probe,tx,temps);
        var adapter=new DeepgramAsr(mapper,"http://127.0.0.1:"+fixture.getAddress().getPort()+"/v1/listen","fixture-only","nova-3",true);
        transcripts=new TranscriptService(db,tx,media,store,probe,temps,new JobLease(db),adapter,mapper,new TranscriptEvents(db,tx,mapper),"ffmpeg");
    }
    @AfterAll static void cleanup() throws Exception {
        if(fixture!=null)fixture.stop(0);if(media!=null)media.stopHeartbeats();if(db==null)return;
        for(String id:mediaIds){
            for(String source:db.queryForList("SELECT id FROM pf_transcript_source WHERE media_id=?",String.class,id)){
                db.update("DELETE FROM pf_review_outline_revision WHERE source_id=?",source);db.update("DELETE FROM pf_review_outline WHERE source_id=?",source);
                db.update("DELETE FROM pf_review_result WHERE source_id=?",source);
                db.update("DELETE FROM pf_review_input WHERE source_id=?",source);
                db.update("DELETE FROM pf_transcript_segment WHERE source_id=?",source);db.update("DELETE FROM pf_transcript_revision WHERE source_id=?",source);
                db.update("DELETE FROM pf_transcript_machine WHERE source_id=?",source);db.update("DELETE FROM pf_transcript_source WHERE id=?",source);
            }
            for(String job:db.queryForList("SELECT id FROM pf_job WHERE media_id=?",String.class,id)){
                db.update("DELETE FROM pf_transcript_event WHERE job_id=?",job);
                db.update("DELETE FROM pf_agent_grant WHERE job_id=?",job);
                db.update("DELETE FROM pf_review_input WHERE job_id=?",job);db.update("DELETE FROM pf_transcribe_input WHERE job_id=?",job);
                db.update("DELETE FROM pf_outbox WHERE job_id=?",job);db.update("DELETE FROM pf_job WHERE id=?",job);
            }
            db.update("DELETE FROM pf_practice_answer_revision WHERE media_id=?",id);db.update("DELETE FROM pf_practice_answer WHERE media_id=?",id);
            db.update("DELETE FROM pf_review_selection WHERE media_id=?",id);
            String key=db.queryForObject("SELECT object_key FROM pf_media WHERE id=?",String.class,id);
            db.update("DELETE FROM pf_media WHERE id=?",id);store.remove(key);
        }
        for(String id:materialIds){db.update("DELETE FROM pf_material_revision WHERE material_id=?",id);db.update("DELETE FROM pf_material WHERE id=?",id);}
        db.update("DELETE FROM pf_user WHERE id IN (?,?)",owner,other);
    }
    static byte[] wav(){
        byte[] data=new byte[32044];var b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(data.length-8).put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1)
         .putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16)
         .put(new byte[]{'d','a','t','a'}).putInt(32000);
        return data;
    }
    static String upload() throws Exception {
        var view=media.upload(owner,new MockMultipartFile("file","synthetic.wav","audio/wav",wav()));
        String id=(String)view.get("media_id");mediaIds.add(id);return id;
    }
    @Test void immutableRevisionOwnerAndDuplicateTask() throws Exception {
        String id=upload();String key="synthetic_"+UUID.randomUUID();
        var task=transcripts.accept(owner,id,key);assertEquals(task.get("task_id"),transcripts.accept(owner,id,key).get("task_id"));
        assertEquals(404,assertThrows(ApiError.class,()->transcripts.accept(other,id,"other_"+UUID.randomUUID())).getStatusCode().value());
        transcripts.run((String)task.get("task_id"));
        var first=transcripts.get(owner,id,null);assertEquals(1L,((Number)first.get("revision")).longValue());
        assertEquals(404,assertThrows(ApiError.class,()->transcripts.get(other,id,null)).getStatusCode().value());
        assertEquals(404,assertThrows(ApiError.class,()->media.owned(id,other)).getStatusCode().value());
        @SuppressWarnings("unchecked") List<TranscriptService.Segment> segments=(List<TranscriptService.Segment>)first.get("segments");
        assertEquals(2,segments.size());assertEquals("UNKNOWN",segments.getFirst().role());assertTrue(segments.get(1).startMs()<segments.getFirst().endMs());
        var changed=List.of(new TranscriptService.Segment(segments.getFirst().id(),100,500,"human correction",segments.getFirst().speakerId(),"ME",0,null),segments.get(1));
        var second=transcripts.revise(owner,id,new TranscriptService.Revision(1,changed));
        assertEquals(2L,((Number)second.get("revision")).longValue());assertEquals(1L,((Number)second.get("speaker_revision")).longValue());
        assertEquals("synthetic hello",((List<TranscriptService.Segment>)transcripts.get(owner,id,1L).get("segments")).getFirst().text());
        assertEquals(409,assertThrows(ApiError.class,()->transcripts.revise(owner,id,new TranscriptService.Revision(1,changed))).getStatusCode().value());
        var invalid=List.of(new TranscriptService.Segment(segments.getFirst().id(),700,100,"bad time","speaker-0","ME",0,Double.NaN),segments.get(1));
        assertEquals(400,assertThrows(ApiError.class,()->transcripts.revise(owner,id,new TranscriptService.Revision(2,invalid))).getStatusCode().value());
        var restarted=new TranscriptService(db,tx,media,store,new Probe(new ObjectMapper(),"ffprobe",12),
                new ProbeTempFiles(Files.createTempDirectory("pf-s102-restart-").toString()),new JobLease(db),
                new DeepgramAsr(new ObjectMapper(),"http://127.0.0.1:"+fixture.getAddress().getPort()+"/v1/listen","fixture-only","nova-3",true),new ObjectMapper(),new TranscriptEvents(db,tx,new ObjectMapper()),"ffmpeg");
        assertEquals(2L,((Number)restarted.get(owner,id,null).get("revision")).longValue());
    }
    @Test void deletionDuringHttpPreventsLatePublication() throws Exception {
        String id=upload();var task=transcripts.accept(owner,id,"late_"+UUID.randomUUID());
        entered=new CountDownLatch(1);release=new CountDownLatch(1);slow.set(true);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var work=pool.submit(()->transcripts.run((String)task.get("task_id")));
            assertTrue(entered.await(8,TimeUnit.SECONDS));media.delete(id,owner);release.countDown();work.get(12,TimeUnit.SECONDS);
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM pf_transcript_source WHERE media_id=?",Integer.class,id));
            assertEquals(404,assertThrows(ApiError.class,()->transcripts.get(owner,id,null)).getStatusCode().value());
            assertEquals(404,assertThrows(ApiError.class,()->media.owned(id,owner)).getStatusCode().value());
        } finally {release.countDown();slow.set(false);pool.shutdownNow();}
    }
    @Test void simultaneousHumanEditsYieldOneImmutableRevisionAndOneConflict() throws Exception {
        String id=upload();var task=transcripts.accept(owner,id,"parallel_"+UUID.randomUUID());
        transcripts.run((String)task.get("task_id"));
        @SuppressWarnings("unchecked") List<TranscriptService.Segment> first=(List<TranscriptService.Segment>)transcripts.get(owner,id,1L).get("segments");
        var changed=List.of(new TranscriptService.Segment(first.getFirst().id(),first.getFirst().startMs(),first.getFirst().endMs(),
                "simultaneous human correction",first.getFirst().speakerId(),"ME",0,null),first.get(1));
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var edits=java.util.stream.IntStream.range(0,2).mapToObj(n->pool.submit(()->{
                ready.countDown();start.await();
                try{transcripts.revise(owner,id,new TranscriptService.Revision(1,changed));return 200;}
                catch(ApiError conflict){return conflict.getStatusCode().value();}
            })).toList();
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            var statuses=List.of(edits.get(0).get(10,TimeUnit.SECONDS),edits.get(1).get(10,TimeUnit.SECONDS));
            assertTrue(statuses.contains(200));assertTrue(statuses.contains(409));
            assertEquals(2L,((Number)transcripts.get(owner,id,null).get("revision")).longValue());
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM pf_transcript_revision WHERE source_id=?",Integer.class,
                    transcripts.get(owner,id,null).get("source_id")));
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void expiredWorkerClaimIsRotatedBeforePublication() throws Exception {
        String id=upload();var task=transcripts.accept(owner,id,"reclaim_"+UUID.randomUUID());String job=(String)task.get("task_id");
        var leases=new JobLease(db);var old=leases.claim(job);assertNotNull(old);
        db.update("UPDATE pf_job SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",job);
        var newer=leases.claim(job);assertNotNull(newer);assertTrue(newer.epoch()>old.epoch());assertNotEquals(old.token(),newer.token());
        assertFalse(leases.renew(old));leases.fail(old,"STALE");
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM pf_job WHERE id=?",String.class,job));
        db.update("UPDATE pf_job SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",job);
        transcripts.run(job);
        assertEquals(1L,((Number)transcripts.get(owner,id,null).get("revision")).longValue());
        assertFalse(leases.publishProbe(newer,"{}"));
    }
    @Test void concurrentWorkerClaimsYieldOneLiveOwner() throws Exception {
        String id=upload();var task=transcripts.accept(owner,id,"two_workers_"+UUID.randomUUID());String job=(String)task.get("task_id");
        var workerA=new JobLease(db);var workerB=new JobLease(db);
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{ready.countDown();start.await();return workerA.claim(job);});
            var second=pool.submit(()->{ready.countDown();start.await();return workerB.claim(job);});
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            var claimA=first.get(10,TimeUnit.SECONDS);var claimB=second.get(10,TimeUnit.SECONDS);
            assertNotEquals(claimA==null,claimB==null,"exactly one worker must own the live lease");
            var winner=claimA==null?claimB:claimA;
            assertNotNull(winner);assertEquals(job,winner.taskId());
            var row=db.queryForMap("SELECT status,epoch,claim_token FROM pf_job WHERE id=?",job);
            assertEquals("RUNNING",row.get("status"));assertEquals(winner.epoch(),((Number)row.get("epoch")).longValue());
            assertEquals(winner.token(),row.get("claim_token"));
            workerA.fail(winner,"SYNTHETIC_TEST_COMPLETE");
            assertEquals("FAILED",db.queryForObject("SELECT status FROM pf_job WHERE id=?",String.class,job));
        } finally {start.countDown();pool.shutdownNow();}
    }
    @Test void multipleAudioTracksAreRejectedInsteadOfSilentlyTruncated() throws Exception {
        var file=Files.createTempFile("pf-s102-multitrack-",".mp4");
        try {
            var process=new ProcessBuilder("ffmpeg","-v","error","-f","lavfi","-i","sine=frequency=440:duration=2",
                    "-f","lavfi","-i","sine=frequency=660:duration=2","-map","0:a","-map","1:a","-c:a","aac","-y",file.toString())
                    .redirectErrorStream(true).start();assertTrue(process.waitFor(12,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
            var view=media.upload(owner,new MockMultipartFile("file","synthetic-multi.mp4","video/mp4",Files.readAllBytes(file)));
            String id=(String)view.get("media_id");mediaIds.add(id);
            var task=transcripts.accept(owner,id,"multi_"+UUID.randomUUID());transcripts.run((String)task.get("task_id"));
            assertEquals("ASR_MULTI_TRACK_UNSUPPORTED",db.queryForObject("SELECT failure_code FROM pf_job WHERE id=?",String.class,task.get("task_id")));
            var failureEvents=new TranscriptEvents(db,tx,new ObjectMapper()).after(owner,id,(String)task.get("task_id"),0);
            assertEquals(1,failureEvents.size());assertEquals("failed_interval",failureEvents.getFirst().get("kind"));
            assertEquals("ASR_MULTI_TRACK_UNSUPPORTED",((com.fasterxml.jackson.databind.JsonNode)failureEvents.getFirst().get("payload")).path("code").asText());
        } finally {Files.deleteIfExists(file);}
    }
    @Test void reviewBindsPersistentTranscriptAndRejectsCrossOwnerOrStaleOutline() throws Exception {
        String id=upload();var transcriptJob=transcripts.accept(owner,id,"review_source_"+UUID.randomUUID());
        transcripts.run((String)transcriptJob.get("task_id"));
        @SuppressWarnings("unchecked") List<TranscriptService.Segment> original=(List<TranscriptService.Segment>)transcripts.get(owner,id,null).get("segments");
        var confirmed=List.of(new TranscriptService.Segment(original.get(0).id(),original.get(0).startMs(),original.get(0).endMs(),
                    original.get(0).text(),original.get(0).speakerId(),"INTERVIEWER",original.get(0).track(),original.get(0).confidence()),
                new TranscriptService.Segment(original.get(1).id(),original.get(1).startMs(),original.get(1).endMs(),
                    original.get(1).text(),original.get(1).speakerId(),"ME",original.get(1).track(),original.get(1).confidence()));
        transcripts.revise(owner,id,new TranscriptService.Revision(1,confirmed));
        var mapper=new ObjectMapper();var materials=new MaterialService(db,tx,mapper);var reviews=new QuestionReviewService(db,tx,mapper,new JobLease(db),null,materials,new PracticeService(db,tx,mapper,materials),"fixed-dsh",
                "http://127.0.0.1:18080","success",true,0);
        String idem="review_"+UUID.randomUUID();var task=reviews.accept(owner,id,idem);
        assertEquals(task.get("task_id"),reviews.accept(owner,id,idem).get("task_id"));
        assertEquals(404,assertThrows(ApiError.class,()->reviews.accept(other,id,"review_"+UUID.randomUUID())).getStatusCode().value());
        assertEquals(404,assertThrows(ApiError.class,()->reviews.reports(other,id)).getStatusCode().value());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM pf_review_input WHERE job_id=? AND source_revision=2 AND materials_json='[]'",Integer.class,task.get("task_id")));
        var lease=new JobLease(db);var claim=lease.claim((String)task.get("task_id"));assertNotNull(claim);
        String grant=UUID.randomUUID().toString()+UUID.randomUUID();String attempt=UUID.randomUUID().toString();
        db.update("INSERT INTO pf_agent_grant(attempt_id,job_id,epoch,claim_token,token_sha256,run_id,expires_at) VALUES(?,?,?,?,?,?,DATE_ADD(NOW(3),INTERVAL 60 SECOND))",
                attempt,task.get("task_id"),claim.epoch(),claim.token(),TranscriptService.hash(grant),UUID.randomUUID().toString());
        assertEquals(2,reviews.authorized(attempt,grant,id,String.valueOf(owner)).path("segments").size());
        assertEquals(404,assertThrows(ApiError.class,()->reviews.authorized(attempt,grant,id,String.valueOf(other))).getStatusCode().value());
        var outline=mapper.readTree("{\"expected_revision\":0,\"transcript_revision\":2,\"groups\":[{\"id\":\"q-1\",\"question_ids\":[\""+
                confirmed.get(0).id()+"\"],\"answer_ids\":[\""+confirmed.get(1).id()+"\"]}]}");
        var saved=reviews.reviseOutline(owner,id,outline);assertEquals(1L,((Number)saved.get("revision")).longValue());
        assertEquals(404,assertThrows(ApiError.class,()->reviews.authorized(attempt,grant,id,String.valueOf(owner))).getStatusCode().value());
        assertEquals(409,assertThrows(ApiError.class,()->reviews.reviseOutline(owner,id,outline)).getStatusCode().value());
        assertEquals(409,assertThrows(ApiError.class,()->reviews.accept(owner,id,idem)).getStatusCode().value());
        var newer=reviews.accept(owner,id,"review_new_"+UUID.randomUUID());
        assertEquals(1,db.queryForObject("SELECT outline_revision FROM pf_review_input WHERE job_id=?",Integer.class,newer.get("task_id")));
        var firstClaim=lease.claim((String)newer.get("task_id"));assertNotNull(firstClaim);
        db.update("UPDATE pf_job SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",newer.get("task_id"));
        var secondClaim=lease.claim((String)newer.get("task_id"));assertNotNull(secondClaim);
        assertTrue(secondClaim.epoch()>firstClaim.epoch());assertFalse(lease.renew(firstClaim));
        lease.fail(firstClaim,"STALE_REVIEW");
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM pf_job WHERE id=?",String.class,newer.get("task_id")));
        var inputMethod=QuestionReviewService.class.getDeclaredMethod("input",String.class);inputMethod.setAccessible(true);
        var reviewInput=(QuestionReviewService.Input)inputMethod.invoke(reviews,(String)newer.get("task_id"));
        var publish=QuestionReviewService.class.getDeclaredMethod("publish",JobLease.Claim.class,QuestionReviewService.Input.class,
                String.class,String.class,com.fasterxml.jackson.databind.JsonNode.class);publish.setAccessible(true);
        String oldAttempt=UUID.randomUUID().toString();
        db.update("INSERT INTO pf_agent_grant(attempt_id,job_id,epoch,claim_token,token_sha256,run_id,expires_at,state) VALUES(?,?,?,?,?,?,DATE_ADD(NOW(3),INTERVAL 60 SECOND),'READY')",
                oldAttempt,newer.get("task_id"),firstClaim.epoch(),firstClaim.token(),TranscriptService.hash("old"),UUID.randomUUID().toString());
        var rejected=assertThrows(java.lang.reflect.InvocationTargetException.class,()->publish.invoke(reviews,firstClaim,reviewInput,
                oldAttempt,UUID.randomUUID().toString(),mapper.createObjectNode()));
        assertInstanceOf(IllegalStateException.class,rejected.getCause());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM pf_review_result WHERE job_id=?",Integer.class,newer.get("task_id")));
        String activeAttempt=UUID.randomUUID().toString();
        db.update("INSERT INTO pf_agent_grant(attempt_id,job_id,epoch,claim_token,token_sha256,run_id,expires_at,state) VALUES(?,?,?,?,?,?,DATE_ADD(NOW(3),INTERVAL 60 SECOND),'READY')",
                activeAttempt,newer.get("task_id"),secondClaim.epoch(),secondClaim.token(),TranscriptService.hash("current"),UUID.randomUUID().toString());
        var questionRef=Map.of("segment_id",confirmed.get(0).id(),"start_ms",confirmed.get(0).startMs(),"end_ms",confirmed.get(0).endMs(),"text",confirmed.get(0).text());
        var answerRef=Map.of("segment_id",confirmed.get(1).id(),"start_ms",confirmed.get(1).startMs(),"end_ms",confirmed.get(1).endMs(),"text",confirmed.get(1).text());
        var valid=mapper.valueToTree(Map.of("source_id",reviewInput.sourceId(),"source_revision",reviewInput.revision(),
                "outline_revision",reviewInput.outlineRevision(),"questions",List.of(Map.of("id","q-1","question_refs",List.of(questionRef),
                "answer_refs",List.of(answerRef),"status","reviewed","covered_points",List.of("Recorded answer"),"gaps",List.of(),
                "advice","Check evidence.","reason","Knowledge not checked")),"unassigned_refs",List.of()));
        publish.invoke(reviews,secondClaim,reviewInput,activeAttempt,UUID.randomUUID().toString(),valid);
        assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM pf_job WHERE id=?",String.class,newer.get("task_id")));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM pf_review_result WHERE job_id=?",Integer.class,newer.get("task_id")));
        var practice=new PracticeService(db,tx,mapper,materials);
        var answer=practice.save(owner,id,(String)newer.get("task_id"),"q-1",0,"合成文字练习稿；技术断言待核查");
        assertEquals(1L,((Number)answer.get("revision")).longValue());
        assertTrue((Boolean)reviews.report(owner,id,(String)newer.get("task_id")).get("stale"));
        assertEquals(409,assertThrows(ApiError.class,()->practice.save(owner,id,(String)newer.get("task_id"),"q-1",0,"并发旧稿")).getStatusCode().value());
        var withPractice=reviews.accept(owner,id,"practice_"+UUID.randomUUID());
        assertTrue(db.queryForObject("SELECT practice_json FROM pf_review_input WHERE job_id=?",String.class,withPractice.get("task_id")).contains("合成文字练习稿"));
        var unrelated=mapper.readTree("{\"expected_revision\":1,\"transcript_revision\":2,\"groups\":[{\"id\":\"q-1\",\"question_ids\":[\"missing\"],\"answer_ids\":[]}]}");
        assertEquals(400,assertThrows(ApiError.class,()->reviews.reviseOutline(owner,id,unrelated)).getStatusCode().value());
    }
    @Test void materialSelectionVersionsAndTranscriptCursorAreDurableAndPrivate() throws Exception {
        String mediaId=upload();var task=transcripts.accept(owner,mediaId,"events_"+UUID.randomUUID());transcripts.run((String)task.get("task_id"));
        var mapper=new ObjectMapper();var events=new TranscriptEvents(db,tx,mapper);
        var first=events.after(owner,mediaId,(String)task.get("task_id"),0);
        assertEquals(2,first.size());assertEquals("partial_segment",first.getFirst().get("kind"));
        assertEquals(1,events.after(owner,mediaId,(String)task.get("task_id"),((Number)first.getFirst().get("seq")).longValue()).size());
        assertEquals(404,assertThrows(ApiError.class,()->events.after(other,mediaId,(String)task.get("task_id"),0)).getStatusCode().value());
        db.update("UPDATE pf_job SET cancel_requested=1 WHERE id=?",task.get("task_id"));
        assertTrue(events.after(owner,mediaId,(String)task.get("task_id"),0).isEmpty());
        db.update("UPDATE pf_job SET cancel_requested=0 WHERE id=?",task.get("task_id"));
        var materials=new MaterialService(db,tx,mapper);var saved=materials.ingest(owner,"JD","PASTE","需要 Java 并发经验；这是背景，不是录音原话".getBytes(java.nio.charset.StandardCharsets.UTF_8),"paste");
        String materialId=(String)saved.get("material_id");materialIds.add(materialId);
        assertTrue(materials.list(other).isEmpty());
        assertEquals(404,assertThrows(ApiError.class,()->materials.select(other,mediaId,0,List.of(materialId))).getStatusCode().value());
        var selection=materials.select(owner,mediaId,0,List.of(materialId));assertEquals(1L,((Number)selection.get("revision")).longValue());
        assertEquals(409,assertThrows(ApiError.class,()->materials.select(owner,mediaId,0,List.of())).getStatusCode().value());
        var source=transcripts.get(owner,mediaId,null);
        @SuppressWarnings("unchecked") var pieces=(List<TranscriptService.Segment>)source.get("segments");
        var confirmed=List.of(new TranscriptService.Segment(pieces.get(0).id(),pieces.get(0).startMs(),pieces.get(0).endMs(),pieces.get(0).text(),pieces.get(0).speakerId(),"INTERVIEWER",pieces.get(0).track(),pieces.get(0).confidence()),
                new TranscriptService.Segment(pieces.get(1).id(),pieces.get(1).startMs(),pieces.get(1).endMs(),pieces.get(1).text(),pieces.get(1).speakerId(),"ME",pieces.get(1).track(),pieces.get(1).confidence()));
        transcripts.revise(owner,mediaId,new TranscriptService.Revision(1,confirmed));
        var reviews=new QuestionReviewService(db,tx,mapper,new JobLease(db),null,materials,new PracticeService(db,tx,mapper,materials),"fixed-dsh","http://127.0.0.1:18080","success",true,0);
        var review=reviews.accept(owner,mediaId,"material_"+UUID.randomUUID());
        var lease=new JobLease(db);var claim=lease.claim((String)review.get("task_id"));assertNotNull(claim);
        String grant=UUID.randomUUID().toString()+UUID.randomUUID(),attempt=UUID.randomUUID().toString();
        db.update("INSERT INTO pf_agent_grant(attempt_id,job_id,epoch,claim_token,token_sha256,run_id,expires_at) VALUES(?,?,?,?,?,?,DATE_ADD(NOW(3),INTERVAL 60 SECOND))",
                attempt,review.get("task_id"),claim.epoch(),claim.token(),TranscriptService.hash(grant),UUID.randomUUID().toString());
        assertEquals(1,reviews.authorized(attempt,grant,mediaId,String.valueOf(owner)).path("materials").size());
        assertEquals(404,assertThrows(ApiError.class,()->reviews.authorized(attempt,grant,mediaId,String.valueOf(other))).getStatusCode().value());
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var firstEdit=pool.submit(()->{gate.await();return materials.revise(owner,materialId,1,"新版材料 A".getBytes(java.nio.charset.StandardCharsets.UTF_8),"paste");});
            var secondEdit=pool.submit(()->{gate.await();return materials.revise(owner,materialId,1,"新版材料 B".getBytes(java.nio.charset.StandardCharsets.UTF_8),"paste");});
            gate.countDown();int success=0,conflict=0;
            for(var result:List.of(firstEdit,secondEdit))try{result.get(10,TimeUnit.SECONDS);success++;}
                catch(java.util.concurrent.ExecutionException error){if(error.getCause() instanceof ApiError api&&api.getStatusCode().value()==409)conflict++;else throw error;}
            assertEquals(1,success);assertEquals(1,conflict);
        }finally{pool.shutdownNow();}
        assertEquals(409,assertThrows(ApiError.class,()->reviews.authorized(attempt,grant,mediaId,String.valueOf(owner))).getStatusCode().value());
        assertEquals(409,assertThrows(ApiError.class,()->reviews.accept(owner,mediaId,"fresh_"+UUID.randomUUID())).getStatusCode().value());
        materials.select(owner,mediaId,1,List.of(materialId));
        var next=reviews.accept(owner,mediaId,"fresh_"+UUID.randomUUID());assertNotNull(next.get("task_id"));
        var lateClaim=lease.claim((String)next.get("task_id"));assertNotNull(lateClaim);
        String lateAttempt=UUID.randomUUID().toString();
        db.update("INSERT INTO pf_agent_grant(attempt_id,job_id,epoch,claim_token,token_sha256,run_id,expires_at,state) VALUES(?,?,?,?,?,?,DATE_ADD(NOW(3),INTERVAL 60 SECOND),'READY')",
                lateAttempt,next.get("task_id"),lateClaim.epoch(),lateClaim.token(),TranscriptService.hash("late"),UUID.randomUUID().toString());
        var inputMethod=QuestionReviewService.class.getDeclaredMethod("input",String.class);inputMethod.setAccessible(true);
        var bound=(QuestionReviewService.Input)inputMethod.invoke(reviews,(String)next.get("task_id"));
        materials.delete(owner,materialId);
        var publish=QuestionReviewService.class.getDeclaredMethod("publish",JobLease.Claim.class,QuestionReviewService.Input.class,
                String.class,String.class,com.fasterxml.jackson.databind.JsonNode.class);publish.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->publish.invoke(reviews,lateClaim,bound,lateAttempt,UUID.randomUUID().toString(),mapper.createObjectNode()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM pf_review_result WHERE job_id=?",Integer.class,next.get("task_id")));
        assertEquals(409,assertThrows(ApiError.class,()->reviews.accept(owner,mediaId,"deleted_"+UUID.randomUUID())).getStatusCode().value());
        assertTrue((Boolean)reviews.report(owner,mediaId,(String)review.get("task_id")).get("stale"));
        media.delete(mediaId,owner);
        assertEquals(404,assertThrows(ApiError.class,()->events.after(owner,mediaId,(String)task.get("task_id"),0)).getStatusCode().value());
    }
}
