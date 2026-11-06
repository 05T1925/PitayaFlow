package dev.pitayaflow.backend;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DeepgramAsrTest {
    static final String RESPONSE="""
        {"results":{"utterances":[
          {"start":0.2,"end":1.5,"transcript":"hello","speaker":0,"channel":0,"confidence":0.9},
          {"start":1.0,"end":2.0,"transcript":"overlap","speaker":1,"channel":1},
          {"start":2.1,"end":2.6,"transcript":"unknown"}
        ]}}
        """;
    DeepgramAsr adapter(){return new DeepgramAsr(new ObjectMapper(),"https://api.deepgram.com/v1/listen","test","nova-3",false);}
    @Test void timedOverlappingAndUnknownSpeakerRemainExplicit() throws Exception {
        var result=adapter().parse(RESPONSE,3000);
        assertEquals(3,result.segments().size());assertEquals(200,result.segments().getFirst().startMs());
        assertEquals("speaker-1",result.segments().get(1).speakerId());assertEquals(1,result.segments().get(1).track());
        assertNull(result.segments().get(1).confidence());assertEquals("unknown",result.segments().get(2).speakerId());
        assertEquals("ASR_SPEAKER_INVALID",assertThrows(DeepgramAsr.Failure.class,()->adapter().parse(RESPONSE.replace("\"speaker\":1","\"speaker\":4294967297"),3000)).code);
        assertEquals("ASR_CHANNEL_INVALID",assertThrows(DeepgramAsr.Failure.class,()->adapter().parse(RESPONSE.replace("\"channel\":1","\"channel\":18446744073709551616"),3000)).code);
    }
    @Test void malformedAndUntimedCannotMasqueradeAsTranscript() {
        var missing=new DeepgramAsr(new ObjectMapper(),"","","nova-3",false);
        assertFalse(missing.configured());
        assertEquals("ASR_UNCONFIGURED",assertThrows(DeepgramAsr.Failure.class,()->missing.transcribe(
                java.nio.file.Path.of("unused"),Duration.ofSeconds(1),()->false,3000)).code);
        var unsafe=new DeepgramAsr(new ObjectMapper(),"http://example.com/v1/listen","test","nova-3",false);
        assertEquals("ASR_CONFIG_INVALID",assertThrows(DeepgramAsr.Failure.class,()->unsafe.transcribe(
                java.nio.file.Path.of("unused"),Duration.ofSeconds(1),()->false,3000)).code);
        assertEquals("ASR_MALFORMED",assertThrows(DeepgramAsr.Failure.class,()->adapter().parse("{",3000)).code);
        assertEquals("ASR_UNTIMED",assertThrows(DeepgramAsr.Failure.class,()->adapter().parse("{\"results\":{\"channels\":[{\"alternatives\":[{\"transcript\":\"text only\"}]}]}}",3000)).code);
        assertEquals("ASR_MALFORMED",assertThrows(DeepgramAsr.Failure.class,()->adapter().parse("{\"results\":{\"utterances\":[{\"end\":1,\"transcript\":\"x\"}]}}",3000)).code);
        assertEquals("ASR_TIME_INVALID",assertThrows(DeepgramAsr.Failure.class,()->adapter().parse(RESPONSE,1000)).code);
    }
    @Test void httpSerializationAndRetryClasses() throws Exception {
        var file=Files.createTempFile("pf-asr-protocol-",".wav");Files.write(file,new byte[]{1,2,3});
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/v1/listen",exchange->{
            int call=calls.incrementAndGet();
            assertEquals("Token fixture-key",exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("audio/wav",exchange.getRequestHeaders().getFirst("Content-Type"));
            assertTrue(exchange.getRequestURI().getQuery().contains("utterances=true"));
            assertTrue(exchange.getRequestURI().getQuery().contains("language=multi"));
            assertTrue(exchange.getRequestURI().getQuery().contains("multichannel=true"));
            assertTrue(exchange.getRequestURI().getQuery().contains("language_hint=zh&language_hint=en"));
            assertArrayEquals(new byte[]{1,2,3},exchange.getRequestBody().readAllBytes());
            int status=call==1?429:call==2?503:200;byte[] body=(status==200?RESPONSE:"{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,body.length);try(var output=exchange.getResponseBody()){output.write(body);}
        });server.start();
        try {
            var asr=new DeepgramAsr(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort()+"/v1/listen","fixture-key","nova-3",true);
            assertEquals(3,asr.transcribe(file,Duration.ofSeconds(8),()->false,3000).segments().size());assertEquals(3,calls.get());
            assertTrue(asr.parse(RESPONSE,3000).parameters().contains("\"multichannel\":true"));
            assertNotEquals(asr.configVersion(),new DeepgramAsr(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort()+"/v1/listen","fixture-key","nova-3",true,"zh","",false).configVersion());
        } finally {server.stop(0);Files.deleteIfExists(file);}
    }
    @Test void unsupportedModelAndLanguageStrategyFailBeforeExternalCall() {
        var unsupported=new DeepgramAsr(new ObjectMapper(),"https://api.deepgram.com/v1/listen","test","nova-2",false,"multi","zh,en",true);
        assertEquals("ASR_MODEL_UNSUPPORTED",assertThrows(DeepgramAsr.Failure.class,unsupported::validateConfig).code);
        var badHints=new DeepgramAsr(new ObjectMapper(),"https://api.deepgram.com/v1/listen","test","nova-3",false,"zh","en",true);
        assertEquals("ASR_HINTS_REQUIRE_MULTI",assertThrows(DeepgramAsr.Failure.class,badHints::validateConfig).code);
    }
    @Test void fourHundredIsNotRetriedAndCancellationIsObserved() throws Exception {
        var file=Files.createTempFile("pf-asr-protocol-",".wav");Files.write(file,new byte[]{1});
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/v1/listen",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(400,-1);exchange.close();});server.start();
        try {
            var asr=new DeepgramAsr(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort()+"/v1/listen","fixture-key","nova-3",true);
            assertEquals("ASR_REJECTED_400",assertThrows(DeepgramAsr.Failure.class,()->asr.transcribe(file,Duration.ofSeconds(3),()->false,3000)).code);
            assertEquals(1,calls.get());
            assertEquals("ASR_CANCELLED",assertThrows(DeepgramAsr.Failure.class,()->asr.transcribe(file,Duration.ofSeconds(3),()->true,3000)).code);
            assertEquals(1,calls.get());
        } finally {server.stop(0);Files.deleteIfExists(file);}
    }
    @Test void inFlightDeadlineCancelsRequest() throws Exception {
        var file=Files.createTempFile("pf-asr-timeout-",".wav");Files.write(file,new byte[]{1,2,3});
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/listen",exchange->{exchange.getRequestBody().readAllBytes();entered.countDown();
            try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}
            try{exchange.sendResponseHeaders(200,-1);}catch(Exception ignored){}exchange.close();});server.start();
        try {
            var asr=new DeepgramAsr(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort()+"/v1/listen","fixture-key","nova-3",true);
            assertEquals("ASR_TIMEOUT",assertThrows(DeepgramAsr.Failure.class,()->asr.transcribe(file,Duration.ofMillis(400),()->false,3000)).code);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
        } finally {release.countDown();server.stop(0);Files.deleteIfExists(file);}
    }
}
