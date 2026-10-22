package org.openpdf.text.pdf.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.AcroFields;
import org.openpdf.text.pdf.LockPermission;
import org.openpdf.text.pdf.PdfAnnotation;
import org.openpdf.text.pdf.PdfArray;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfNumber;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfSignatureAppearance;
import org.openpdf.text.pdf.PdfStamper;
import org.openpdf.text.pdf.PdfString;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.pdf.SignatureFieldLock;
import org.openpdf.text.pdf.SignatureType;

class FieldMdpWriterTest {

    private static final SignatureFieldLock LOCK_NAME = SignatureFieldLock.include(FormFixture.NAME);

    @Test
    void signingLockedVisibleFieldAddsFieldMdp() throws Exception {
        byte[] pdf = FormFixture.builder().lock(FormFixture.SIGNATURE_A, LOCK_NAME).build();
        pdf = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A"));
        DocumentEditor.save("fieldmdp-visible.pdf", pdf);

        try (PdfReader reader = new PdfReader(pdf)) {
            AcroFields fields = reader.getAcroFields();
            PdfArray references = fields.getSignatureDictionary(FormFixture.SIGNATURE_A).getAsArray(PdfName.REFERENCE);
            assertThat(references.size()).isEqualTo(1);
            PdfDictionary reference = references.getAsDict(0);
            assertThat(reference.getAsName(PdfName.TRANSFORMMETHOD)).isEqualTo(PdfName.FIELDMDP);
            assertThat(reference.getAsName(PdfName.TYPE)).isEqualTo(PdfName.SIGREF);
            assertThat(reference.getAsIndirectObject(PdfName.DATA)).isNotNull();
            assertThat(fields.getSignatureFieldMdp(FormFixture.SIGNATURE_A)).isEqualTo(LOCK_NAME);
            assertThat(fields.getSignatureTypes()).containsEntry(FormFixture.SIGNATURE_A, SignatureType.APPROVAL);
            assertThat(fields.verifySignature(FormFixture.SIGNATURE_A).verify()).isTrue();
        }
    }

    @Test
    void signingLockedInvisibleFieldReusesTheField() throws Exception {
        SignatureFieldLock lock = SignatureFieldLock.all();
        byte[] pdf = FormFixture.builder().lock(FormFixture.INVISIBLE_SIGNATURE, lock).build();
        int fieldCount = topLevelFieldCount(pdf);
        pdf = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.INVISIBLE_SIGNATURE).signer("A"));
        DocumentEditor.save("fieldmdp-invisible.pdf", pdf);

        try (PdfReader reader = new PdfReader(pdf)) {
            AcroFields fields = reader.getAcroFields();
            assertThat(fields.getFieldItem(FormFixture.INVISIBLE_SIGNATURE).size()).isEqualTo(1);
            assertThat(topLevelFieldCount(pdf)).isEqualTo(fieldCount);
            assertThat(fields.getSignedFieldNames()).containsExactly(FormFixture.INVISIBLE_SIGNATURE);
            assertThat(fields.getSignatureFieldMdp(FormFixture.INVISIBLE_SIGNATURE)).isEqualTo(lock);
            assertThat(fields.verifySignature(FormFixture.INVISIBLE_SIGNATURE).verify()).isTrue();
        }
    }

    @Test
    void newFieldWithLockGetsLockAndFieldMdp() throws Exception {
        SignatureFieldLock lock = SignatureFieldLock.exclude(FormFixture.NOTE);
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner
                .newVisibleField("NewSignature", new Rectangle(300, 350, 500, 420), 1).signer("A").lock(lock));

        try (PdfReader reader = new PdfReader(pdf)) {
            AcroFields fields = reader.getAcroFields();
            assertThat(fields.getSignatureFieldLock("NewSignature")).isEqualTo(lock);
            assertThat(fields.getSignatureFieldMdp("NewSignature")).isEqualTo(lock);
            assertThat(fields.verifySignature("NewSignature").verify()).isTrue();
        }
    }

    @Test
    void certificationAndLockShareTheReferenceArray() throws Exception {
        byte[] pdf = FormFixture.builder().lock(FormFixture.SIGNATURE_A, LOCK_NAME).build();
        pdf = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A")
                .certify(PdfSignatureAppearance.CERTIFIED_FORM_FILLING));
        DocumentEditor.save("fieldmdp-certified.pdf", pdf);

        try (PdfReader reader = new PdfReader(pdf)) {
            AcroFields fields = reader.getAcroFields();
            PdfArray references = fields.getSignatureDictionary(FormFixture.SIGNATURE_A).getAsArray(PdfName.REFERENCE);
            assertThat(references.size()).isEqualTo(2);
            assertThat(references.getAsDict(0).getAsName(PdfName.TRANSFORMMETHOD)).isEqualTo(PdfName.DOCMDP);
            assertThat(references.getAsDict(1).getAsName(PdfName.TRANSFORMMETHOD)).isEqualTo(PdfName.FIELDMDP);
            assertThat(fields.getSignatureTypes())
                    .containsEntry(FormFixture.SIGNATURE_A, SignatureType.CERTIFICATION_FILLINGFORMS_SIGNING);
            assertThat(reader.getCertificationLevel()).isEqualTo(PdfSignatureAppearance.CERTIFIED_FORM_FILLING);
            assertThat(fields.verifySignature(FormFixture.SIGNATURE_A).verify()).isTrue();
        }
    }

    @Test
    void certificationKeepsUsageRights() throws Exception {
        byte[] pdf = withUsageRights(FormFixture.create());
        pdf = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A")
                .certify(PdfSignatureAppearance.CERTIFIED_FORM_FILLING));

        try (PdfReader reader = new PdfReader(pdf)) {
            PdfDictionary perms = reader.getCatalog().getAsDict(PdfName.PERMS);
            assertThat(perms.get(PdfName.UR3)).isNotNull();
            assertThat(perms.get(PdfName.DOCMDP)).isNotNull();
            assertThat(reader.getCertificationLevel()).isEqualTo(PdfSignatureAppearance.CERTIFIED_FORM_FILLING);
        }
    }

    @Test
    void certificationMustBeTheFirstSignature() throws Exception {
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner.existingField(FormFixture.SIGNATURE_A));

        TestSigner.Options certification = TestSigner.existingField(FormFixture.SIGNATURE_B)
                .certify(PdfSignatureAppearance.CERTIFIED_FORM_FILLING);
        assertThatThrownBy(() -> TestSigner.sign(pdf, certification))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("first signature");
    }

    @Test
    void noChangesCertificationForbidsLaterSignatures() throws Exception {
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner.existingField(FormFixture.SIGNATURE_A)
                .signer("A").certify(PdfSignatureAppearance.CERTIFIED_NO_CHANGES_ALLOWED));

        TestSigner.Options existingField = TestSigner.existingField(FormFixture.SIGNATURE_B).signer("B");
        assertThatThrownBy(() -> TestSigner.sign(pdf, existingField))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("does not allow any changes");
        TestSigner.Options newField = TestSigner.newInvisibleField("NewSignature").signer("B");
        assertThatThrownBy(() -> TestSigner.sign(pdf, newField))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("does not allow any changes");
    }

    @Test
    void aNoChangesLockForbidsLaterSignatures() throws Exception {
        SignatureFieldLock lock = SignatureFieldLock.include(FormFixture.NAME)
                .withPermission(LockPermission.NO_CHANGES);
        byte[] form = FormFixture.builder().pdfVersion(PdfWriter.VERSION_2_0).lock(FormFixture.SIGNATURE_A, lock)
                .build();
        byte[] pdf = TestSigner.sign(form, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A"));

        TestSigner.Options later = TestSigner.existingField(FormFixture.SIGNATURE_B).signer("B");
        assertThatThrownBy(() -> TestSigner.sign(pdf, later))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("does not allow any changes");
    }

    @Test
    void lockPermissionOnlyOnTheLockIsEnforced() throws Exception {
        SignatureFieldLock lock = SignatureFieldLock.include(FormFixture.NAME)
                .withPermission(LockPermission.NO_CHANGES);
        byte[] form = FormFixture.builder().pdfVersion(PdfWriter.VERSION_2_0).lock(FormFixture.SIGNATURE_A, lock)
                .build();
        byte[] pdf = TestSigner.sign(form, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A")
                .signatureEvent(TestSigner.removeFieldMdpPermission()));
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getAcroFields().getSignatureFieldMdp(FormFixture.SIGNATURE_A).getPermission()).isNull();
        }

        TestSigner.Options later = TestSigner.existingField(FormFixture.SIGNATURE_B).signer("B");
        assertThatThrownBy(() -> TestSigner.sign(pdf, later))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("does not allow any changes");
    }

    @Test
    void documentTimestampIsAllowedAfterNoChanges() throws Exception {
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner.existingField(FormFixture.SIGNATURE_A)
                .signer("A").certify(PdfSignatureAppearance.CERTIFIED_NO_CHANGES_ALLOWED));

        pdf = TestSigner.sign(pdf, TestSigner.newInvisibleField("Timestamp").signer("TSA").rfc3161Timestamp());
        try (PdfReader reader = new PdfReader(pdf)) {
            PdfDictionary timestamp = reader.getAcroFields().getSignatureDictionary("Timestamp");
            assertThat(timestamp.getAsName(PdfName.TYPE)).isEqualTo(PdfName.DOCTIMESTAMP);
        }
    }

    @Test
    void documentTimestampInExistingInvisibleFieldIsAllowedAfterNoChanges() throws Exception {
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner.existingField(FormFixture.SIGNATURE_A)
                .signer("A").certify(PdfSignatureAppearance.CERTIFIED_NO_CHANGES_ALLOWED));

        pdf = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.INVISIBLE_SIGNATURE).signer("TSA")
                .rfc3161Timestamp());
        try (PdfReader reader = new PdfReader(pdf)) {
            PdfDictionary timestamp = reader.getAcroFields().getSignatureDictionary(FormFixture.INVISIBLE_SIGNATURE);
            assertThat(timestamp.getAsName(PdfName.TYPE)).isEqualTo(PdfName.DOCTIMESTAMP);
        }
    }

    @Test
    void onlyInvisibleRfc3161TimestampIsAllowedAfterNoChanges() throws Exception {
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner.existingField(FormFixture.SIGNATURE_A)
                .signer("A").certify(PdfSignatureAppearance.CERTIFIED_NO_CHANGES_ALLOWED));

        TestSigner.Options notRfc3161 = TestSigner.newInvisibleField("Timestamp").signer("TSA").documentTimestamp();
        TestSigner.Options visible = TestSigner.newVisibleField("Timestamp", new Rectangle(50, 50, 250, 120), 1)
                .signer("TSA").rfc3161Timestamp();
        TestSigner.Options visibleField = TestSigner.existingField(FormFixture.SIGNATURE_B).signer("TSA")
                .rfc3161Timestamp();
        for (TestSigner.Options options : List.of(notRfc3161, visible, visibleField)) {
            assertThatThrownBy(() -> TestSigner.sign(pdf, options))
                    .isInstanceOf(DocumentException.class)
                    .hasMessageContaining("does not allow any changes");
        }
    }

    @Test
    void onlyWidgetsThatShowNothingAreInvisible() {
        float[] area = {50, 50, 250, 120};
        float[] noArea = {50, 50, 50, 120};

        assertThat(PdfSignatureAppearance.isInvisibleWidget(widget(PdfAnnotation.FLAGS_HIDDEN, area))).isTrue();
        assertThat(PdfSignatureAppearance.isInvisibleWidget(widget(PdfAnnotation.FLAGS_NOVIEW, area))).isTrue();
        assertThat(PdfSignatureAppearance.isInvisibleWidget(widget(PdfAnnotation.FLAGS_PRINT, noArea))).isTrue();
        assertThat(PdfSignatureAppearance.isInvisibleWidget(widget(PdfAnnotation.FLAGS_PRINT, area))).isFalse();
        assertThat(PdfSignatureAppearance.isInvisibleWidget(
                widget(PdfAnnotation.FLAGS_NOVIEW | PdfAnnotation.FLAGS_PRINT, area))).isFalse();
        assertThat(PdfSignatureAppearance.isInvisibleWidget(
                widget(PdfAnnotation.FLAGS_NOVIEW | PdfAnnotation.FLAGS_TOGGLENOVIEW, area))).isFalse();
        assertThat(PdfSignatureAppearance.isInvisibleWidget(widget(0))).isFalse();
    }

    @Test
    void existingFieldWithWiderLockCanBeSigned() throws Exception {
        SignatureFieldLock wider = SignatureFieldLock.include(FormFixture.NAME)
                .withPermission(LockPermission.FORM_FILLING_AND_ANNOTATIONS);
        byte[] form = FormFixture.builder().pdfVersion(PdfWriter.VERSION_2_0).lock(FormFixture.SIGNATURE_B, wider)
                .build();
        byte[] pdf = TestSigner.sign(form, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A")
                .certify(PdfSignatureAppearance.CERTIFIED_FORM_FILLING));

        pdf = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.SIGNATURE_B).signer("B"));
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getAcroFields().getSignatureFieldMdp(FormFixture.SIGNATURE_B)).isEqualTo(wider);
        }
    }

    @Test
    void existingFieldWithMissingLockNameCanBeSigned() throws Exception {
        SignatureFieldLock stale = SignatureFieldLock.include("Missing");
        byte[] form = FormFixture.builder().lock(FormFixture.SIGNATURE_A, stale).build();

        byte[] pdf = TestSigner.sign(form, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A"));
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getAcroFields().getSignatureFieldMdp(FormFixture.SIGNATURE_A)).isEqualTo(stale);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void invalidCertificationLevelReadsAsFormFilling() throws Exception {
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner.existingField(FormFixture.SIGNATURE_A)
                .signer("A").certify(PdfSignatureAppearance.CERTIFIED_ALL_CHANGES_ALLOWED));

        try (PdfReader reader = new PdfReader(pdf)) {
            PdfDictionary docMdp = reader.getAcroFields().getSignatureDictionary(FormFixture.SIGNATURE_A)
                    .getAsArray(PdfName.REFERENCE).getAsDict(0);
            assertThat(docMdp.getAsName(PdfName.TRANSFORMMETHOD)).isEqualTo(PdfName.DOCMDP);
            assertThat(docMdp.getAsDict(PdfName.TRANSFORMPARAMS).getAsNumber(PdfName.P).intValue()).isZero();
            assertThat(reader.getCertificationLevel()).isEqualTo(PdfSignatureAppearance.CERTIFIED_FORM_FILLING);
            assertThat(reader.getAcroFields().getSignatureTypes())
                    .containsEntry(FormFixture.SIGNATURE_A, SignatureType.CERTIFICATION_FILLINGFORMS_SIGNING);
        }
    }

    @Test
    void aFieldLockedByAnEarlierSignatureCannotBeSigned() throws Exception {
        byte[] original = FormFixture.builder()
                .lock(FormFixture.SIGNATURE_A, SignatureFieldLock.include(FormFixture.SIGNATURE_B)).build();
        byte[] pdf = TestSigner.sign(original, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A"));

        TestSigner.Options lockedField = TestSigner.existingField(FormFixture.SIGNATURE_B).signer("B");
        assertThatThrownBy(() -> TestSigner.sign(pdf, lockedField))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("locked by the signature " + FormFixture.SIGNATURE_A);
        byte[] signedTwice = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.SIGNATURE_C).signer("C"));
        try (PdfReader reader = new PdfReader(signedTwice)) {
            assertThat(reader.getAcroFields().getSignedFieldNames()).hasSize(2);
        }
    }

    @Test
    void lockNamesMustExistWhenSigning() throws Exception {
        byte[] form = FormFixture.create();
        TestSigner.Options missingName = TestSigner.newInvisibleField("NewSignature")
                .lock(SignatureFieldLock.include("Missing"));
        assertThatThrownBy(() -> TestSigner.sign(form, missingName))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("Missing");
    }

    @Test
    void lockPermissionCannotWidenThePermissionInEffect() throws Exception {
        byte[] pdf = TestSigner.sign(FormFixture.builder().pdfVersion(PdfWriter.VERSION_2_0).build(),
                TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A")
                        .certify(PdfSignatureAppearance.CERTIFIED_FORM_FILLING));

        TestSigner.Options widening = TestSigner.newInvisibleField("NewSignature").signer("B")
                .lock(SignatureFieldLock.all().withPermission(LockPermission.FORM_FILLING_AND_ANNOTATIONS));
        assertThatThrownBy(() -> TestSigner.sign(pdf, widening))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("wider");
        byte[] restricted = TestSigner.sign(pdf, TestSigner.newInvisibleField("NewSignature").signer("B")
                .lock(SignatureFieldLock.include(FormFixture.NAME).withPermission(LockPermission.NO_CHANGES)));
        try (PdfReader reader = new PdfReader(restricted)) {
            assertThat(reader.getAcroFields().getSignatureFieldMdp("NewSignature").getPermission())
                    .isEqualTo(LockPermission.NO_CHANGES);
        }
    }

    @Test
    void lockPermissionOnNewFieldRequiresPdf20() throws Exception {
        SignatureFieldLock lock = SignatureFieldLock.include(FormFixture.NAME)
                .withPermission(LockPermission.FORM_FILLING);

        byte[] form = FormFixture.create();
        TestSigner.Options before20 = TestSigner.newInvisibleField("NewSignature").lock(lock);
        assertThatThrownBy(() -> TestSigner.sign(form, before20))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("PDF 2.0");
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner.newInvisibleField("NewSignature").lock(lock)
                .pdf20());
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getAcroFields().getSignatureFieldMdp("NewSignature")).isEqualTo(lock);
        }
    }

    @Test
    void theLockOfAnExistingFieldCannotBeReplaced() throws Exception {
        byte[] form = FormFixture.builder().lock(FormFixture.SIGNATURE_A, LOCK_NAME).build();
        TestSigner.Options replacement = TestSigner.existingField(FormFixture.SIGNATURE_A)
                .lock(SignatureFieldLock.include(FormFixture.NOTE));
        assertThatThrownBy(() -> TestSigner.sign(form, replacement))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("cannot be replaced");
    }

    @Test
    void aLockCanOnlyBeSetOnANewField() throws Exception {
        byte[] form = FormFixture.create();
        TestSigner.Options existingField = TestSigner.existingField(FormFixture.SIGNATURE_A).lock(LOCK_NAME);
        assertThatThrownBy(() -> TestSigner.sign(form, existingField))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("new signature field");
    }

    @Test
    void existingAllLockWithFieldsCanBeSigned() throws Exception {
        byte[] pdf = DocumentEditor.putLock(FormFixture.create(), FormFixture.SIGNATURE_A,
                lockDictionary(PdfName.ALL, FormFixture.NAME));

        pdf = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A"));
        try (PdfReader reader = new PdfReader(pdf)) {
            PdfDictionary params = fieldMdpParams(reader, FormFixture.SIGNATURE_A);
            assertThat(params.getAsName(PdfName.ACTION)).isEqualTo(PdfName.ALL);
            assertThat(params.getAsArray(PdfName.FIELDS).size()).isEqualTo(1);
            assertThat(reader.getAcroFields().getSignatureFieldMdp(FormFixture.SIGNATURE_A))
                    .isEqualTo(SignatureFieldLock.all());
        }
    }

    @Test
    void existingAllLockWithMalformedFieldsCanBeSigned() throws Exception {
        PdfDictionary lock = new PdfDictionary(PdfName.SIGFIELDLOCK);
        lock.put(PdfName.ACTION, PdfName.ALL);
        lock.put(PdfName.FIELDS, PdfName.FIELDS);
        byte[] pdf = DocumentEditor.putLock(FormFixture.create(), FormFixture.SIGNATURE_A, lock);

        pdf = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A"));
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getAcroFields().getSignatureFieldMdp(FormFixture.SIGNATURE_A))
                    .isEqualTo(SignatureFieldLock.all());
        }
    }

    @Test
    void fieldLockedOnlyByTheLockOfAnEarlierSignatureCannotBeSigned() throws Exception {
        byte[] form = FormFixture.builder()
                .lock(FormFixture.SIGNATURE_A, SignatureFieldLock.include(FormFixture.SIGNATURE_B)).build();
        // a producer that writes a FieldMDP narrower than the /Lock of the field
        byte[] pdf = TestSigner.sign(form, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A")
                .signatureEvent(signature -> {
                    PdfArray fields = new PdfArray();
                    fields.add(new PdfString(FormFixture.NAME));
                    signature.getAsArray(PdfName.REFERENCE).getAsDict(0).getAsDict(PdfName.TRANSFORMPARAMS)
                            .put(PdfName.FIELDS, fields);
                }));
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getAcroFields().getSignatureFieldMdp(FormFixture.SIGNATURE_A)).isEqualTo(LOCK_NAME);
        }

        TestSigner.Options lockedField = TestSigner.existingField(FormFixture.SIGNATURE_B).signer("B");
        assertThatThrownBy(() -> TestSigner.sign(pdf, lockedField))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("locked by the signature " + FormFixture.SIGNATURE_A);
    }

    @Test
    void documentTimestampCannotSignALockedField() throws Exception {
        byte[] form = FormFixture.builder().lock(FormFixture.INVISIBLE_SIGNATURE, LOCK_NAME).build();

        TestSigner.Options timestamp = TestSigner.existingField(FormFixture.INVISIBLE_SIGNATURE).signer("TSA")
                .rfc3161Timestamp();
        assertThatThrownBy(() -> TestSigner.sign(form, timestamp))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining(FormFixture.INVISIBLE_SIGNATURE)
                .hasMessageContaining("has a lock");
    }

    @Test
    void existingLockIsCopiedAsWritten() throws Exception {
        byte[] pdf = DocumentEditor.putLock(FormFixture.create(), FormFixture.SIGNATURE_A,
                lockDictionary(PdfName.INCLUDE, FormFixture.NAME, FormFixture.NAME));

        pdf = TestSigner.sign(pdf, TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A"));
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(fieldMdpParams(reader, FormFixture.SIGNATURE_A).getAsArray(PdfName.FIELDS).size())
                    .isEqualTo(2);
            assertThat(reader.getAcroFields().getSignatureFieldMdp(FormFixture.SIGNATURE_A)).isEqualTo(LOCK_NAME);
        }
    }

    @Test
    void documentTimestampCannotCertifyOrLock() throws Exception {
        byte[] form = FormFixture.create();

        TestSigner.Options certifying = TestSigner.newInvisibleField("Timestamp").signer("TSA").documentTimestamp()
                .certify(PdfSignatureAppearance.CERTIFIED_FORM_FILLING);
        assertThatThrownBy(() -> TestSigner.sign(form, certifying))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("document timestamp");
        TestSigner.Options locking = TestSigner.newInvisibleField("Timestamp").signer("TSA").documentTimestamp()
                .lock(LOCK_NAME);
        assertThatThrownBy(() -> TestSigner.sign(form, locking))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("document timestamp");
    }

    @Test
    void invalidCertificationLevelIsRejected() {
        TestSigner.Options invalid = TestSigner.existingField(FormFixture.SIGNATURE_A).certify(7);
        assertThatThrownBy(() -> TestSigner.sign(FormFixture.create(), invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7");
    }

    @Test
    @SuppressWarnings("deprecation")
    void levelZeroCountsAsFormFillingForLocks() throws Exception {
        byte[] form = FormFixture.create();
        TestSigner.Options levelZero = TestSigner.newInvisibleField("NewSignature").signer("A")
                .certify(PdfSignatureAppearance.CERTIFIED_ALL_CHANGES_ALLOWED)
                .lock(SignatureFieldLock.include(FormFixture.NAME)
                        .withPermission(LockPermission.FORM_FILLING_AND_ANNOTATIONS))
                .pdf20();
        assertThatThrownBy(() -> TestSigner.sign(form, levelZero))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("wider");
    }

    @Test
    void docMdpWithoutPermissionReadsAsFormFilling() throws Exception {
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner.existingField(FormFixture.SIGNATURE_A)
                .signer("A").certify(PdfSignatureAppearance.CERTIFIED_NO_CHANGES_ALLOWED)
                .signatureEvent(signature -> signature.getAsArray(PdfName.REFERENCE).getAsDict(0)
                        .getAsDict(PdfName.TRANSFORMPARAMS).remove(PdfName.P)));

        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getCertificationLevel()).isEqualTo(PdfSignatureAppearance.CERTIFIED_FORM_FILLING);
            assertThat(reader.getAcroFields().getSignatureTypes())
                    .containsEntry(FormFixture.SIGNATURE_A, SignatureType.CERTIFICATION_FILLINGFORMS_SIGNING);
        }
    }

    @Test
    void aNoChangesDocMdpWithoutPermsForbidsLaterSignatures() throws Exception {
        // the DocMDP transform of the first signature applies even when the catalog /Perms does not point to it
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner.existingField(FormFixture.SIGNATURE_A)
                .signer("A").signatureEvent(signature -> addReference(signature, PdfName.DOCMDP,
                        docMdpParams(PdfSignatureAppearance.CERTIFIED_NO_CHANGES_ALLOWED))));
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getCertificationLevel()).isEqualTo(PdfSignatureAppearance.NOT_CERTIFIED);
        }

        TestSigner.Options later = TestSigner.existingField(FormFixture.SIGNATURE_B).signer("B");
        assertThatThrownBy(() -> TestSigner.sign(pdf, later))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("does not allow any changes");
    }

    @Test
    void docMdpTakesPrecedenceOverUsageRights() throws Exception {
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner.existingField(FormFixture.SIGNATURE_A)
                .signer("A").signatureEvent(signature -> {
                    addReference(signature, PdfName.UR3, new PdfDictionary(PdfName.TRANSFORMPARAMS));
                    addReference(signature, PdfName.DOCMDP,
                            docMdpParams(PdfSignatureAppearance.CERTIFIED_NO_CHANGES_ALLOWED));
                }));

        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getAcroFields().getSignatureTypes())
                    .containsEntry(FormFixture.SIGNATURE_A, SignatureType.CERTIFICATION_NO_CHANGES);
        }
    }

    /**
     * Appends a signature reference to the {@code /Reference} array of a signature dictionary, creating it if needed.
     */
    private static void addReference(PdfDictionary signature, PdfName method, PdfDictionary params) {
        PdfDictionary reference = new PdfDictionary(PdfName.SIGREF);
        reference.put(PdfName.TRANSFORMMETHOD, method);
        reference.put(PdfName.TRANSFORMPARAMS, params);
        PdfArray references = signature.getAsArray(PdfName.REFERENCE);
        if (references == null) {
            references = new PdfArray();
            signature.put(PdfName.REFERENCE, references);
        }
        references.add(reference);
    }

    private static PdfDictionary docMdpParams(int level) {
        PdfDictionary params = new PdfDictionary(PdfName.TRANSFORMPARAMS);
        params.put(PdfName.P, new PdfNumber(level));
        params.put(PdfName.V, new PdfName("1.2"));
        return params;
    }

    private static PdfDictionary widget(int flags, float... rectangle) {
        PdfDictionary widget = new PdfDictionary(PdfName.ANNOT);
        widget.put(PdfName.SUBTYPE, PdfName.WIDGET);
        widget.put(PdfName.F, new PdfNumber(flags));
        if (rectangle.length > 0) {
            widget.put(PdfName.RECT, new PdfArray(rectangle));
        }
        return widget;
    }

    private static PdfDictionary lockDictionary(PdfName action, String... names) {
        PdfDictionary lock = new PdfDictionary(PdfName.SIGFIELDLOCK);
        lock.put(PdfName.ACTION, action);
        PdfArray fields = new PdfArray();
        for (String name : names) {
            fields.add(new PdfString(name));
        }
        lock.put(PdfName.FIELDS, fields);
        return lock;
    }

    private static PdfDictionary fieldMdpParams(PdfReader reader, String signatureName) {
        PdfArray references = reader.getAcroFields().getSignatureDictionary(signatureName)
                .getAsArray(PdfName.REFERENCE);
        for (int k = 0; k < references.size(); ++k) {
            PdfDictionary reference = references.getAsDict(k);
            if (PdfName.FIELDMDP.equals(reference.getAsName(PdfName.TRANSFORMMETHOD))) {
                return reference.getAsDict(PdfName.TRANSFORMPARAMS);
            }
        }
        throw new AssertionError("No FieldMDP transform in " + signatureName);
    }

    @Test
    void aSignedFieldCannotBeSignedAgain() throws Exception {
        byte[] pdf = TestSigner.sign(FormFixture.create(), TestSigner.existingField(FormFixture.SIGNATURE_A));

        TestSigner.Options signedField = TestSigner.existingField(FormFixture.SIGNATURE_A);
        assertThatThrownBy(() -> TestSigner.sign(pdf, signedField))
                .isInstanceOf(DocumentException.class)
                .hasMessageContaining("already signed");
    }

    private static int topLevelFieldCount(byte[] pdf) throws Exception {
        try (PdfReader reader = new PdfReader(pdf)) {
            PdfDictionary acroForm = reader.getCatalog().getAsDict(PdfName.ACROFORM);
            return acroForm.getAsArray(PdfName.FIELDS).size();
        }
    }

    private static byte[] withUsageRights(byte[] pdf) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfReader reader = new PdfReader(pdf)) {
            PdfStamper stamper = new PdfStamper(reader, out, null, true);
            PdfDictionary perms = new PdfDictionary();
            perms.put(PdfName.UR3, new PdfDictionary(PdfName.SIG));
            PdfDictionary catalog = reader.getCatalog();
            catalog.put(PdfName.PERMS, perms);
            stamper.markUsed(catalog);
            stamper.close();
        }
        return out.toByteArray();
    }
}
