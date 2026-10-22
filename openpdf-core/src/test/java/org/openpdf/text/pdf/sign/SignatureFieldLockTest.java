package org.openpdf.text.pdf.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openpdf.text.Document;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.AcroFields;
import org.openpdf.text.pdf.LockAction;
import org.openpdf.text.pdf.LockPermission;
import org.openpdf.text.pdf.PdfArray;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfFormField;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfNumber;
import org.openpdf.text.pdf.PdfObject;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.PdfSignatureAppearance;
import org.openpdf.text.pdf.PdfStamper;
import org.openpdf.text.pdf.PdfString;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.pdf.SignatureFieldLock;
import org.openpdf.text.pdf.TextField;

class SignatureFieldLockTest {

    @Test
    void everyActionAndPermissionSurvivesRoundTrip() {
        List<SignatureFieldLock> locks = List.of(
                SignatureFieldLock.all(),
                SignatureFieldLock.include("Name", "Address.City"),
                SignatureFieldLock.exclude("Note"),
                SignatureFieldLock.all().withPermission(LockPermission.NO_CHANGES),
                SignatureFieldLock.include("Name").withPermission(LockPermission.FORM_FILLING),
                SignatureFieldLock.exclude("Note").withPermission(LockPermission.FORM_FILLING_AND_ANNOTATIONS));
        for (SignatureFieldLock lock : locks) {
            SignatureFieldLock.ParseResult result = SignatureFieldLock.parse(lock.toPdfDictionary());
            assertThat(result.isWellFormed()).as("%s", lock).isTrue();
            assertThat(result.lock()).isEqualTo(lock);
        }
    }

    @Test
    void lockDictionaryHasTheSpecifiedEntries() {
        PdfDictionary dictionary = SignatureFieldLock.include("Name", "Amount")
                .withPermission(LockPermission.FORM_FILLING).toPdfDictionary();

        assertThat(dictionary.getAsName(PdfName.TYPE)).isEqualTo(PdfName.SIGFIELDLOCK);
        assertThat(dictionary.getAsName(PdfName.ACTION)).isEqualTo(PdfName.INCLUDE);
        PdfArray fields = dictionary.getAsArray(PdfName.FIELDS);
        assertThat(fields.size()).isEqualTo(2);
        assertThat(fields.getAsString(0).toUnicodeString()).isEqualTo("Name");
        assertThat(dictionary.getAsNumber(PdfName.P).intValue()).isEqualTo(2);
        assertThat(SignatureFieldLock.all().toPdfDictionary().get(PdfName.FIELDS)).isNull();
    }

    @Test
    void transformParamsCarryTheTransformVersion() {
        PdfDictionary params = SignatureFieldLock.exclude("Note").toTransformParams();

        assertThat(params.getAsName(PdfName.TYPE)).isEqualTo(PdfName.TRANSFORMPARAMS);
        assertThat(params.getAsName(PdfName.V)).isEqualTo(new PdfName("1.2"));
        assertThat(params.getAsName(PdfName.ACTION)).isEqualTo(PdfName.EXCLUDE);
        assertThat(SignatureFieldLock.parse(params).lock()).isEqualTo(SignatureFieldLock.exclude("Note"));
    }

    @Test
    void invalidLocksAreRejected() {
        assertThatThrownBy(SignatureFieldLock::include).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignatureFieldLock.exclude(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignatureFieldLock.include("a..b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignatureFieldLock.include("a.")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignatureFieldLock.include("Name", "Name"))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> names = List.of("Name");
        assertThatThrownBy(() -> SignatureFieldLock.of(LockAction.ALL, names, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void locksMatchFieldsAndTheirDescendants() {
        SignatureFieldLock include = SignatureFieldLock.include("Address");
        assertThat(include.locks("Address")).isTrue();
        assertThat(include.locks("Address.City")).isTrue();
        assertThat(include.locks("AddressLine")).isFalse();

        SignatureFieldLock exclude = SignatureFieldLock.exclude("Note");
        assertThat(exclude.locks("Note")).isFalse();
        assertThat(exclude.locks("Name")).isTrue();

        assertThat(SignatureFieldLock.all().locks("anything")).isTrue();
    }

    @Test
    void parsingReportsProblemsInsteadOfThrowing() {
        PdfDictionary noAction = new PdfDictionary();
        assertThat(SignatureFieldLock.parse(noAction).lock()).isNull();
        assertThat(SignatureFieldLock.parse(null).problems()).isNotEmpty();

        PdfDictionary badEntries = new PdfDictionary();
        badEntries.put(PdfName.ACTION, PdfName.INCLUDE);
        PdfArray fields = new PdfArray();
        fields.add(new PdfString("Name"));
        fields.add(new PdfNumber(3));
        badEntries.put(PdfName.FIELDS, fields);
        badEntries.put(PdfName.P, new PdfNumber(7));
        SignatureFieldLock.ParseResult result = SignatureFieldLock.parse(badEntries);

        assertThat(result.lock()).isEqualTo(SignatureFieldLock.include("Name"));
        assertThat(result.problems()).hasSize(2);
        assertThat(result.isWellFormed()).isFalse();
    }

    @Test
    void lockOfNewDocumentFieldIsIndirectAndSurvivesReopening() throws Exception {
        SignatureFieldLock lock = SignatureFieldLock.include(FormFixture.NAME, FormFixture.AMOUNT);
        byte[] pdf = FormFixture.builder().lock(FormFixture.SIGNATURE_A, lock).build();

        try (PdfReader reader = new PdfReader(pdf)) {
            AcroFields fields = reader.getAcroFields();
            assertThat(fields.getSignatureFieldLock(FormFixture.SIGNATURE_A)).isEqualTo(lock);
            assertThat(fields.getSignatureFieldLock(FormFixture.SIGNATURE_B)).isNull();
            assertThat(fields.getFieldItem(FormFixture.SIGNATURE_A).getMerged(0).get(PdfName.LOCK).isIndirect())
                    .isTrue();
        }
    }

    @Test
    void lockPermissionRequiresPdf20Output() throws Exception {
        SignatureFieldLock lock = SignatureFieldLock.include(FormFixture.NAME)
                .withPermission(LockPermission.FORM_FILLING);

        FormFixture before20 = FormFixture.builder().pdfVersion(PdfWriter.VERSION_1_7)
                .lock(FormFixture.SIGNATURE_A, lock);
        assertThatThrownBy(before20::build)
                .isInstanceOf(IllegalStateException.class);

        byte[] pdf = FormFixture.builder().pdfVersion(PdfWriter.VERSION_2_0)
                .lock(FormFixture.SIGNATURE_A, lock).build();
        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getAcroFields().getSignatureFieldLock(FormFixture.SIGNATURE_A)).isEqualTo(lock);
        }
    }

    @Test
    void stamperAddsLockedSignatureFieldsInAppendMode() throws Exception {
        byte[] original = FormFixture.create();
        SignatureFieldLock lock = SignatureFieldLock.exclude(FormFixture.NOTE)
                .withPermission(LockPermission.FORM_FILLING);

        try (PdfReader reader = new PdfReader(original)) {
            PdfStamper stamper = new PdfStamper(reader, new ByteArrayOutputStream(), null, true);
            assertThatThrownBy(() -> stamper.addSignature("SignatureD", 1, 300, 350, 500, 420, lock))
                    .isInstanceOf(IllegalStateException.class);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfReader reader = new PdfReader(original)) {
            PdfStamper stamper = new PdfStamper(reader, out, null, true);
            stamper.getWriter().setPdfVersion(PdfWriter.PDF_VERSION_2_0);
            stamper.addSignature("SignatureD", 1, 300, 350, 500, 420, lock);
            stamper.close();
        }
        try (PdfReader reader = new PdfReader(out.toByteArray())) {
            assertThat(reader.getAcroFields().getSignatureFieldLock("SignatureD")).isEqualTo(lock);
            assertThat(reader.getCatalog().getAsName(PdfName.VERSION)).isEqualTo(PdfWriter.PDF_VERSION_2_0);
        }
    }

    @Test
    void allWithFieldsIsWellFormed() {
        PdfDictionary dictionary = new PdfDictionary(PdfName.SIGFIELDLOCK);
        dictionary.put(PdfName.ACTION, PdfName.ALL);
        PdfArray fields = new PdfArray();
        fields.add(new PdfString(FormFixture.NAME));
        dictionary.put(PdfName.FIELDS, fields);

        SignatureFieldLock.ParseResult result = SignatureFieldLock.parse(dictionary);

        assertThat(result.isWellFormed()).as("%s", result.problems()).isTrue();
        assertThat(result.lock()).isEqualTo(SignatureFieldLock.all());
    }

    @Test
    void allWithMalformedFieldsIsWellFormed() {
        PdfArray badEntry = new PdfArray();
        badEntry.add(new PdfNumber(3));
        for (PdfObject fields : List.of(PdfName.FIELDS, badEntry)) {
            PdfDictionary dictionary = new PdfDictionary(PdfName.SIGFIELDLOCK);
            dictionary.put(PdfName.ACTION, PdfName.ALL);
            dictionary.put(PdfName.FIELDS, fields);

            SignatureFieldLock.ParseResult result = SignatureFieldLock.parse(dictionary);

            assertThat(result.isWellFormed()).as("%s", result.problems()).isTrue();
            assertThat(result.lock()).isEqualTo(SignatureFieldLock.all());
        }
    }

    @Test
    void stamperRefusesALockOnAMissingField() throws Exception {
        SignatureFieldLock missing = SignatureFieldLock.include("Nmae");
        try (PdfReader reader = new PdfReader(FormFixture.create())) {
            PdfStamper stamper = new PdfStamper(reader, new ByteArrayOutputStream(), null, true);
            assertThatThrownBy(() -> stamper.addSignature("SignatureD", 1, 300, 350, 500, 420, missing))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Nmae");
            assertThat(stamper.addSignature("SignatureE", 1, 300, 250, 500, 320,
                    SignatureFieldLock.include(FormFixture.NAME))).isNotNull();
        }
    }

    @Test
    void newDocumentAcceptsALockOnAFieldAddedLater() throws Exception {
        SignatureFieldLock later = SignatureFieldLock.include("Later");
        byte[] pdf = FormFixture.builder().lock(FormFixture.SIGNATURE_A, later).build();

        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getAcroFields().getSignatureFieldLock(FormFixture.SIGNATURE_A)).isEqualTo(later);
        }
    }

    @Test
    void stamperRefusesAWiderLockPermission() throws Exception {
        byte[] certified = TestSigner.sign(FormFixture.builder().pdfVersion(PdfWriter.VERSION_2_0).build(),
                TestSigner.existingField(FormFixture.SIGNATURE_A).signer("A")
                        .certify(PdfSignatureAppearance.CERTIFIED_FORM_FILLING));
        SignatureFieldLock wider = SignatureFieldLock.include(FormFixture.NAME)
                .withPermission(LockPermission.FORM_FILLING_AND_ANNOTATIONS);
        SignatureFieldLock same = SignatureFieldLock.include(FormFixture.NAME)
                .withPermission(LockPermission.FORM_FILLING);

        try (PdfReader reader = new PdfReader(certified)) {
            PdfStamper stamper = new PdfStamper(reader, new ByteArrayOutputStream(), null, true);
            assertThatThrownBy(() -> stamper.addSignature("SignatureD", 1, 300, 350, 500, 420, wider))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wider");
            assertThat(stamper.addSignature("SignatureE", 1, 300, 250, 500, 320, same)).isNotNull();
        }
    }

    @Test
    void setSignatureLockRejectsNonSignatureFields() throws Exception {
        Document document = new Document();
        PdfWriter writer = PdfWriter.getInstance(document, new ByteArrayOutputStream());
        document.open();
        document.add(new Paragraph("Form"));
        PdfFormField text = new TextField(writer, new Rectangle(100, 100, 300, 124), "Text").getTextField();

        assertThatThrownBy(() -> writer.getAcroForm().setSignatureLock(text, SignatureFieldLock.all()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Text");
        document.close();
    }
}
