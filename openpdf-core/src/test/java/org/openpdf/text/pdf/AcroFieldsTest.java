package org.openpdf.text.pdf;

import static java.time.Duration.ofMillis;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import org.openpdf.text.Document;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.Security;
import java.util.List;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AcroFieldsTest {

    /**
     * This test fails, because signatureCoversWholeDocument does only check the last signed block.
     *
     * @throws Exception a ClassCastException
     */
    @Test
    public void testGetSignatures() throws Exception {
        // for algorithm SHA256 (without dash)
        Security.addProvider(new BouncyCastleProvider());
        InputStream moddedFile = AcroFieldsTest.class.getResourceAsStream("/siwa.pdf");
        PdfReader reader = new PdfReader(moddedFile);
        Document document = new Document(PageSize.A4);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = PdfWriter.getInstance(document, out);

        AcroFields fields = new AcroFields(reader, writer);
        List<String> names = fields.getSignedFieldNames();
        Assertions.assertEquals(1, names.size());

        for (String signName : names) {
            Assertions.assertFalse(fields.signatureCoversWholeDocument(signName));
            // TODO need other PR to fix java.lang.ClassCastException:
            // org.bouncycastle.asn1.BERTaggedObject cannot be cast to
            // org.bouncycastle.asn1.DERTaggedObject
            // PdfPKCS7 pdfPkcs7 = fields.verifySignature(signName, "BC");
            // Assertions.assertTrue(pdfPkcs7.verify());
        }

    }

    @Test
    public void infiniteLoopTest() throws Exception {
        try (InputStream is = AcroFieldsTest.class.getResourceAsStream("/pades_infinite_loop.pdf");
                PdfReader reader = new PdfReader(is)) {
            assertTimeoutPreemptively(ofMillis(500), () -> {
                reader.getAcroFields();
            });
        }

    }

    @Test
    public void parentCycleDoesNotHang() throws Exception {
        try (PdfReader reader = new PdfReader(childWithParents(true, false))) {
            assertTimeoutPreemptively(ofMillis(2000), () -> {
                reader.getAcroFields();
            });
        }
    }

    @Test
    public void removeFieldStopsAtParentCycle() throws Exception {
        assertFieldRemoved(childWithParents(true, true));
    }

    @Test
    public void removeFieldStopsAtParentWithoutKids() throws Exception {
        assertFieldRemoved(childWithParents(false, false));
    }

    /**
     * A text field "Child" whose /Parent is "First", whose /Parent is "Second".
     *
     * @param cycle whether "Second" has "First" as its /Parent
     * @param kids  whether the parents list their child in /Kids
     */
    private static byte[] childWithParents(boolean cycle, boolean kids) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        document.open();
        document.add(new Paragraph("Parent chain"));
        PdfIndirectReference first = writer.getPdfIndirectReference();
        PdfIndirectReference second = writer.getPdfIndirectReference();
        PdfFormField field = new TextField(writer, new Rectangle(100, 700, 300, 724), "Child").getTextField();
        field.put(PdfName.PARENT, first);
        writer.addAnnotation(field);
        PdfDictionary firstParent = new PdfDictionary();
        firstParent.put(PdfName.T, new PdfString("First"));
        firstParent.put(PdfName.PARENT, second);
        PdfDictionary secondParent = new PdfDictionary();
        secondParent.put(PdfName.T, new PdfString("Second"));
        if (cycle) {
            secondParent.put(PdfName.PARENT, first);
        }
        if (kids) {
            firstParent.put(PdfName.KIDS, new PdfArray(field.getIndirectReference()));
            secondParent.put(PdfName.KIDS, new PdfArray(first));
        }
        writer.addToBody(firstParent, first);
        writer.addToBody(secondParent, second);
        document.close();
        return out.toByteArray();
    }

    private static void assertFieldRemoved(byte[] pdf) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfReader reader = new PdfReader(pdf)) {
            PdfStamper stamper = new PdfStamper(reader, out);
            AcroFields fields = stamper.getAcroFields();
            String name = fields.getAllFields().keySet().iterator().next();
            assertTimeoutPreemptively(ofMillis(2000), () -> {
                Assertions.assertTrue(fields.removeField(name));
            });
            stamper.close();
        }
        try (PdfReader reader = new PdfReader(out.toByteArray())) {
            Assertions.assertTrue(reader.getAcroFields().getAllFields().isEmpty());
        }
    }

    @Test
    public void malformedByteRangeDoesNotBreakTheSignatureList() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        document.open();
        document.add(new Paragraph("Malformed /ByteRange"));
        PdfFormField signature = writer.getAcroForm().addSignature("Signature", 100, 600, 300, 660);
        PdfDictionary value = new PdfDictionary(PdfName.SIG);
        value.put(PdfName.CONTENTS, new PdfString(new byte[]{0}).setHexWriting(true));
        PdfArray range = new PdfArray();
        range.add(new PdfNumber(0));
        range.add(new PdfString("not a number"));
        range.add(new PdfNumber(1));
        range.add(new PdfNumber(1));
        value.put(PdfName.BYTERANGE, range);
        signature.put(PdfName.V, writer.addToBody(value).getIndirectReference());
        document.close();

        try (PdfReader reader = new PdfReader(out.toByteArray())) {
            List<String> names = reader.getAcroFields().getSignedFieldNames();
            Assertions.assertEquals(List.of("Signature"), names);
        }
    }

}
