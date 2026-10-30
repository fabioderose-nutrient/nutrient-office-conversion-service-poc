package io.nutrient.demo.conversion;

import io.nutrient.sdk.enums.PdfConformance;
import io.nutrient.sdk.enums.PdfValidationConformance;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Output formats: a plain PDF or one of the PDF/A archival levels. */
public enum OutputFormat {
    PDF("pdf", "PDF", null, null),
    PDF_A_1B("pdfa-1b", "PDF/A-1b", PdfConformance.PDF_A_1b, PdfValidationConformance.PdfA1b),
    PDF_A_2B("pdfa-2b", "PDF/A-2b", PdfConformance.PDF_A_2b, PdfValidationConformance.PdfA2b),
    PDF_A_2U("pdfa-2u", "PDF/A-2u", PdfConformance.PDF_A_2u, PdfValidationConformance.PdfA2u),
    PDF_A_3B("pdfa-3b", "PDF/A-3b", PdfConformance.PDF_A_3b, PdfValidationConformance.PdfA3b);

    private final String id;
    private final String label;
    private final PdfConformance conformance;
    private final PdfValidationConformance validation;

    OutputFormat(String id, String label, PdfConformance conformance, PdfValidationConformance validation) {
        this.id = id;
        this.label = label;
        this.conformance = conformance;
        this.validation = validation;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** The conformance to set on the document before export, or null for a plain PDF. */
    public PdfConformance conformance() {
        return conformance;
    }

    /** The level to validate the output against, or null for a plain PDF. */
    public PdfValidationConformance validation() {
        return validation;
    }

    public boolean isPdfA() {
        return conformance != null;
    }

    public static Optional<OutputFormat> fromId(String id) {
        String wanted = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(f -> f.id.equals(wanted)).findFirst();
    }

    public static List<String> ids() {
        return Arrays.stream(values()).map(OutputFormat::id).toList();
    }
}
