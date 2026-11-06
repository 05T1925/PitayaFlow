package dev.pitayaflow.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class MaterialParserTest {
    @Test void pdfTextAndImageOnlyOrActiveContent() throws Exception {
        byte[] text;
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){
            var page=new PDPage();pdf.addPage(page);
            try(var stream=new PDPageContentStream(pdf,page)){
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                stream.newLineAtOffset(40,700);stream.showText("Synthetic background only");stream.endText();
            }
            pdf.save(out);text=out.toByteArray();
        }
        assertTrue(MaterialService.extract("PDF",text).contains("Synthetic background only"));
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()){
            pdf.addPage(new PDPage());pdf.save(out);
            assertThrows(ApiError.class,()->MaterialService.extract("PDF",out.toByteArray()));
        }
        assertThrows(ApiError.class,()->MaterialService.extract("PDF","%PDF-1.7 /JavaScript".getBytes(StandardCharsets.US_ASCII)));
    }
    @Test void docxTextRejectsMacroExternalLinksAndExpansion() throws Exception {
        byte[] safe=docx("<w:document xmlns:w='urn:w'><w:body><w:p><w:r><w:t>Private role background</w:t></w:r></w:p></w:body></w:document>",false,false);
        assertEquals("Private role background",MaterialService.extract("DOCX",safe));
        assertThrows(ApiError.class,()->MaterialService.extract("DOCX",docx("<w:document/>",true,false)));
        assertThrows(ApiError.class,()->MaterialService.extract("DOCX",docx("<w:document/>",false,true)));
        assertThrows(ApiError.class,()->MaterialService.extract("DOCX",new byte[]{1,2,3}));
    }
    private static byte[] docx(String body,boolean external,boolean macro) throws Exception {
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){
            zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write(body.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            if(external){zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
                zip.write("<Relationships><Relationship TargetMode='External' Target='https://example.invalid'/></Relationships>".getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
            if(macro){zip.putNextEntry(new ZipEntry("word/vbaProject.bin"));zip.write(new byte[]{1});zip.closeEntry();}
        }return bytes.toByteArray();
    }
}
