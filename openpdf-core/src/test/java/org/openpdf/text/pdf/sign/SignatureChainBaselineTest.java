package org.openpdf.text.pdf.sign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.AcroFields;
import org.openpdf.text.pdf.PdfPKCS7;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfSignatureAppearance;

/**
 * Baseline of a serial signature chain: A certifies with {@code /P 2}, a field is filled in append mode and B signs.
 */
class SignatureChainBaselineTest {

    @Test
    void certifiedChainWithFormFillingKeepsEverySignatureIntact() throws Exception {
        byte[] pdf = FormFixture.create();
        pdf = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A")
                .certify(PdfSignatureAppearance.CERTIFIED_FORM_FILLING));
        pdf = DocumentEditor.fillField(pdf, FormFixture.NOTE, "Filled after the certification");
        pdf = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.SIGNATURE_B).signer("B"));
        DocumentEditor.save("baseline-chain.pdf", pdf);

        try (PdfReader reader = new PdfReader(pdf)) {
            AcroFields fields = reader.getAcroFields();
            assertThat(fields.getSignedFieldNamesInRevisionOrder())
                    .containsExactly(FormFixture.SIGNATURE_A, FormFixture.SIGNATURE_B);
            assertThat(fields.getTotalRevisions()).isEqualTo(2);
            assertThat(fields.getRevision(FormFixture.SIGNATURE_A)).isEqualTo(1);
            assertThat(fields.getRevision(FormFixture.SIGNATURE_B)).isEqualTo(2);
            assertThat(fields.signatureCoversWholeDocument(FormFixture.SIGNATURE_A)).isFalse();
            assertThat(fields.signatureCoversWholeDocument(FormFixture.SIGNATURE_B)).isTrue();
            long[] rangeA = fields.getSignatureByteRange(FormFixture.SIGNATURE_A);
            long[] rangeB = fields.getSignatureByteRange(FormFixture.SIGNATURE_B);
            assertThat(rangeA).hasSize(4).startsWith(0L);
            assertThat(rangeA[2] + rangeA[3]).isLessThan(pdf.length);
            assertThat(rangeB).hasSize(4).startsWith(0L);
            assertThat(rangeB[2] + rangeB[3]).isEqualTo(pdf.length);
            assertThat(fields.getSignatureByteRange(FormFixture.SIGNATURE_C)).isEmpty();
            assertThat(reader.getCertificationLevel()).isEqualTo(PdfSignatureAppearance.CERTIFIED_FORM_FILLING);
            assertThat(fields.getField(FormFixture.NOTE)).isEqualTo("Filled after the certification");
            for (String name : List.of(FormFixture.SIGNATURE_A, FormFixture.SIGNATURE_B)) {
                PdfPKCS7 pkcs7 = fields.verifySignature(name);
                assertThat(pkcs7.verify()).as("signature %s", name).isTrue();
            }
        }
    }
}
