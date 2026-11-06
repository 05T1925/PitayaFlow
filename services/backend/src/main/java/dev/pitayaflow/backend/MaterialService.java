package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Private material bytes stay in MySQL; extracted text is never an authority for spoken claims. */
@Service
final class MaterialService {
    private static final int MAX_BYTES=2*1024*1024, MAX_TEXT=200_000;
    private static final java.util.concurrent.Semaphore PARSE_SLOTS=new java.util.concurrent.Semaphore(2);
    private final JdbcTemplate db; private final TransactionTemplate tx; private final ObjectMapper mapper;
    MaterialService(JdbcTemplate db,TransactionTemplate tx,ObjectMapper mapper){this.db=db;this.tx=tx;this.mapper=mapper;}
    record Selection(long revision,List<Map<String,Object>> materials) {}
    static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException(e);}}
    static String sha(String text){return sha(text.getBytes(StandardCharsets.UTF_8));}
    Map<String,Object> ingest(long owner,String name,String kind,byte[] bytes,String source){
        if(name==null||name.isBlank()||name.length()>180||!List.of("PASTE","TXT","MD","PDF","DOCX").contains(kind)||
                bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw ApiError.bad("MATERIAL_INPUT_INVALID");
        String extracted=null,error=null;
        try{extracted=extract(kind,bytes);if(extracted.isBlank()||extracted.length()>MAX_TEXT)throw new IllegalArgumentException("MATERIAL_EMPTY_OR_LARGE");}
        catch(Exception failure){error=failure instanceof IllegalArgumentException?failure.getMessage():"MATERIAL_PARSE_FAILED";}
        String id=UUID.randomUUID().toString(),text=extracted,failure=error;
        tx.executeWithoutResult(state->{
            db.update("INSERT INTO pf_material(id,owner_id,name,kind,status,current_revision) VALUES(?,?,?,?,?,1)",id,owner,name,kind,failure==null?"ACTIVE":"ERROR");
            db.update("INSERT INTO pf_material_revision(material_id,revision,source_name,original_sha256,extracted_sha256,original_bytes,extracted_text,extraction_error) VALUES(?,1,?,?,?,?,?,?)",
                    id,source,sha(bytes),text==null?sha(""):sha(text),failure==null?bytes:null,text,failure);
        });
        return Map.of("material_id",id,"revision",1,"status",error==null?"ACTIVE":"ERROR","error",error==null?"":error);
    }
    Map<String,Object> revise(long owner,String id,long expected,byte[] bytes,String source){
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw ApiError.bad("MATERIAL_INPUT_INVALID");
        return tx.execute(state->{
            var rows=db.queryForList("SELECT kind,current_revision FROM pf_material WHERE id=? AND owner_id=? AND status='ACTIVE' FOR UPDATE",id,owner);
            if(rows.size()!=1)throw ApiError.missing();long old=((Number)rows.getFirst().get("current_revision")).longValue();
            if(old!=expected)throw ApiError.conflict("MATERIAL_REVISION_CONFLICT");
            String text=extract((String)rows.getFirst().get("kind"),bytes);
            if(text.isBlank()||text.length()>MAX_TEXT)throw ApiError.bad("MATERIAL_EMPTY_OR_LARGE");
            db.update("INSERT INTO pf_material_revision(material_id,revision,source_name,original_sha256,extracted_sha256,original_bytes,extracted_text) VALUES(?,?,?,?,?,?,?)",
                    id,old+1,source,sha(bytes),sha(text),bytes,text);
            db.update("UPDATE pf_material SET current_revision=? WHERE id=?",old+1,id);
            return Map.of("material_id",id,"revision",old+1,"status","ACTIVE");
        });
    }
    List<Map<String,Object>> list(long owner){return db.queryForList("SELECT m.id AS material_id,m.name,m.kind,m.status,m.current_revision AS revision,r.extracted_sha256,r.extraction_error FROM pf_material m JOIN pf_material_revision r ON r.material_id=m.id AND r.revision=m.current_revision WHERE m.owner_id=? AND m.status<>'DELETED' ORDER BY m.created_at DESC",owner);}
    void delete(long owner,String id){tx.executeWithoutResult(state->{
        if(db.update("UPDATE pf_material SET status='DELETED' WHERE id=? AND owner_id=? AND status<>'DELETED'",id,owner)!=1)throw ApiError.missing();
        db.update("UPDATE pf_material_revision SET original_bytes=NULL,extracted_text=NULL WHERE material_id=?",id);
    });}
    Selection selection(long owner,String mediaId,boolean lock){
        if(db.queryForObject("SELECT COUNT(*) FROM pf_media WHERE id=? AND owner_id=? AND status='ACTIVE'",Integer.class,mediaId,owner)!=1)
            throw ApiError.missing();
        String sql="SELECT revision,materials_json FROM pf_review_selection WHERE media_id=? AND owner_id=?"+(lock?" FOR UPDATE":"");
        var rows=db.queryForList(sql,mediaId,owner);if(rows.isEmpty())return new Selection(0,List.of());
        try {List<Map<String,Object>> items=new ArrayList<>();for(JsonNode item:mapper.readTree((String)rows.getFirst().get("materials_json")))
            items.add(Map.of("material_id",item.path("material_id").asText(),"revision",item.path("revision").asLong(),"sha256",item.path("sha256").asText()));
            return new Selection(((Number)rows.getFirst().get("revision")).longValue(),items);
        }catch(Exception e){throw new IllegalStateException("stored material selection invalid",e);}
    }
    Map<String,Object> select(long owner,String mediaId,long expected,List<String> ids){
        if(ids==null||ids.size()>5||new HashSet<>(ids).size()!=ids.size())throw ApiError.bad("MATERIAL_SELECTION_INVALID");
        return tx.execute(state->{
            var media=db.queryForList("SELECT id FROM pf_media WHERE id=? AND owner_id=? AND status='ACTIVE' FOR UPDATE",mediaId,owner);
            if(media.size()!=1)throw ApiError.missing();Selection prior=selection(owner,mediaId,true);
            if(prior.revision()!=expected)throw ApiError.conflict("MATERIAL_SELECTION_CONFLICT");
            List<Map<String,Object>> selected=new ArrayList<>();
            for(String id:ids){
                var rows=db.queryForList("SELECT m.current_revision,r.extracted_sha256 FROM pf_material m JOIN pf_material_revision r ON r.material_id=m.id AND r.revision=m.current_revision WHERE m.id=? AND m.owner_id=? AND m.status='ACTIVE' FOR UPDATE",id,owner);
                if(rows.size()!=1)throw ApiError.missing();
                selected.add(Map.of("material_id",id,"revision",rows.getFirst().get("current_revision"),"sha256",rows.getFirst().get("extracted_sha256")));
            }
            String json;try{json=mapper.writeValueAsString(selected);}catch(Exception e){throw new IllegalStateException(e);}
            if(prior.revision()==0)db.update("INSERT INTO pf_review_selection(media_id,owner_id,revision,materials_json) VALUES(?,?,1,?)",mediaId,owner,json);
            else db.update("UPDATE pf_review_selection SET revision=?,materials_json=? WHERE media_id=? AND owner_id=?",expected+1,json,mediaId,owner);
            return Map.of("revision",expected+1,"materials",selected);
        });
    }
    boolean matches(long owner,String mediaId,Selection snapshot){return matches(owner,mediaId,snapshot,false);}
    boolean matches(long owner,String mediaId,Selection snapshot,boolean lock){
        Selection current=selection(owner,mediaId,lock);
        if(current.revision()!=snapshot.revision()||!current.materials().equals(snapshot.materials()))return false;
        for(var item:snapshot.materials()){
            var rows=db.queryForList("SELECT r.extracted_sha256 FROM pf_material m JOIN pf_material_revision r ON r.material_id=m.id AND r.revision=m.current_revision WHERE m.id=? AND m.owner_id=? AND m.status='ACTIVE' AND m.current_revision=?"+(lock?" FOR UPDATE":""),
                    item.get("material_id"),owner,item.get("revision"));
            if(rows.size()!=1||!item.get("sha256").equals(rows.getFirst().get("extracted_sha256")))return false;
        }
        return true;
    }
    List<Map<String,Object>> authorized(long owner,String mediaId,Selection snapshot){
        if(!matches(owner,mediaId,snapshot))throw ApiError.conflict("MATERIAL_SOURCE_STALE");
        List<Map<String,Object>> result=new ArrayList<>();
        for(var item:snapshot.materials()){
            var rows=db.queryForList("SELECT m.name,m.kind,r.extracted_text FROM pf_material m JOIN pf_material_revision r ON r.material_id=m.id AND r.revision=? WHERE m.id=? AND m.owner_id=? AND m.status='ACTIVE'",
                    item.get("revision"),item.get("material_id"),owner);
            if(rows.size()!=1||rows.getFirst().get("extracted_text")==null)throw ApiError.conflict("MATERIAL_SOURCE_STALE");
            result.add(Map.of("material_id",item.get("material_id"),"revision",item.get("revision"),"sha256",item.get("sha256"),
                    "name",rows.getFirst().get("name"),"kind",rows.getFirst().get("kind"),"text",rows.getFirst().get("extracted_text")));
        }
        return result;
    }
    static String extract(String kind,byte[] bytes){
        if(List.of("PASTE","TXT","MD").contains(kind))return plain(bytes);
        if(!PARSE_SLOTS.tryAcquire())throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE,"MATERIAL_PARSE_BUSY");
        try{
            var executor=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"pf-material-parse");t.setDaemon(true);return t;});
            try{return executor.submit(()->{try{return kind.equals("PDF")?pdf(bytes):docx(bytes);}
                finally{PARSE_SLOTS.release();}}).get(8,TimeUnit.SECONDS);}
            finally{executor.shutdownNow();}
        }catch(Exception e){throw ApiError.bad("MATERIAL_PARSE_OR_TIMEOUT");}
    }
    private static String plain(byte[] bytes){
        try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString().trim();
            if(text.chars().anyMatch(ch->ch==0||(ch<32&&ch!=9&&ch!=10&&ch!=13)))throw ApiError.bad("MATERIAL_TEXT_CONTROL");
            return text;}
        catch(CharacterCodingException e){throw ApiError.bad("MATERIAL_UTF8_INVALID");}
    }
    private static String pdf(byte[] bytes) throws Exception {
        String raw=new String(bytes,StandardCharsets.ISO_8859_1);
        if(!raw.startsWith("%PDF-")||raw.matches("(?s).*(/URI|/JavaScript|/Launch|/EmbeddedFiles|/OpenAction|/AA|/Filespec|/XFA).*"))
            throw new IllegalArgumentException("MATERIAL_PDF_ACTIVE_CONTENT");
        try(var doc=Loader.loadPDF(bytes)){
            if(doc.isEncrypted()||doc.getNumberOfPages()<1||doc.getNumberOfPages()>20||doc.getDocumentCatalog().getOpenAction()!=null)
                throw new IllegalArgumentException("MATERIAL_PDF_UNSUPPORTED");
            Set<COSBase> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for(var key:doc.getDocument().getXrefTable().keySet())
                if(unsafePdf(doc.getDocument().getObjectFromPool(key),seen,0))
                throw new IllegalArgumentException("MATERIAL_PDF_ACTIVE_CONTENT");
            String text=new PDFTextStripper().getText(doc).trim();
            if(text.isBlank())throw new IllegalArgumentException("MATERIAL_PDF_IMAGE_ONLY");return text;
        }
    }
    private static boolean unsafePdf(COSBase node,Set<COSBase> seen,int depth){
        if(node==null)return false;if(depth>40||seen.size()>20_000)return true;
        if(!seen.add(node))return false;
        if(node instanceof COSObject object)return unsafePdf(object.getObject(),seen,depth+1);
        if(node instanceof COSArray array){for(COSBase item:array)if(unsafePdf(item,seen,depth+1))return true;}
        if(node instanceof COSDictionary dict){for(var key:dict.keySet()){
            if(Set.of("URI","JavaScript","JS","Launch","EmbeddedFiles","OpenAction","AA","Filespec","XFA").contains(key.getName()))return true;
            if(unsafePdf(dict.getDictionaryObject(key),seen,depth+1))return true;
        }}
        return false;
    }
    private static String docx(byte[] bytes) throws Exception {
        byte[] document=null;int count=0;long total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){
            if(++count>100||entry.getName().contains("..")||entry.getName().startsWith("/")||entry.getName().contains("\\"))
                throw new IllegalArgumentException("MATERIAL_DOCX_STRUCTURE");
            String name=entry.getName().toLowerCase(java.util.Locale.ROOT);
            if(name.contains("vba")||name.contains("embedding")||name.contains("activex")||name.contains("oleobject"))
                throw new IllegalArgumentException("MATERIAL_DOCX_ACTIVE_CONTENT");
            byte[] part=zip.readNBytes(MAX_BYTES+1);total+=part.length;
            if(part.length>MAX_BYTES||total>4L*MAX_BYTES)throw new IllegalArgumentException("MATERIAL_DOCX_EXPANSION");
            if(name.endsWith(".rels")&&new String(part,StandardCharsets.UTF_8).matches("(?is).*targetmode\\s*=\\s*['\"]external['\"].*"))
                throw new IllegalArgumentException("MATERIAL_DOCX_EXTERNAL_LINK");
            if(name.equals("word/document.xml"))document=part;
        }}
        if(document==null)throw new IllegalArgumentException("MATERIAL_DOCX_STRUCTURE");
        XMLInputFactory factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
        var reader=factory.createXMLStreamReader(new ByteArrayInputStream(document));StringBuilder text=new StringBuilder();boolean inText=false;
        try{while(reader.hasNext()){
            int event=reader.next();if(event==XMLStreamConstants.START_ELEMENT&&reader.getLocalName().equals("t"))inText=true;
            else if(event==XMLStreamConstants.END_ELEMENT){if(reader.getLocalName().equals("t"))inText=false;
                if(reader.getLocalName().equals("p"))text.append('\n');}
            else if(event==XMLStreamConstants.CHARACTERS&&inText){text.append(reader.getText());if(text.length()>MAX_TEXT)throw new IllegalArgumentException("MATERIAL_TEXT_LARGE");}
        }}finally{reader.close();}return text.toString().trim();
    }
}
