package io.nutrient.demo.conversion;

import io.nutrient.sdk.Document;
import io.nutrient.sdk.editors.PdfEditor;
import io.nutrient.sdk.enums.ImageExportFormat;
import io.nutrient.sdk.exceptions.NutrientException;
import io.nutrient.sdk.exceptions.UnsupportedFormatException;
import io.nutrient.sdk.settings.DocumentSettings;
import io.nutrient.sdk.validation.PdfValidationResult;
import io.nutrient.sdk.validation.PdfValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** All calls to the Nutrient Java SDK. */
@Service
public class ConversionService {

    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);

    /** One input file saved on disk, with the name the client sent. */
    public record Input(Path path, String name) {}

    /** The outcome of one conversion or one merge. */
    public record Result(String output, int pages, long bytes, long conversionMs,
                         String detectedType, PdfAValidation pdfa) {}

    /** The result of the PDF/A validation of an output file. */
    public record PdfAValidation(String level, boolean valid, long validationMs, int failedChecks) {}

    private final Semaphore slots;

    public ConversionService(@Value("${conversion.concurrency:4}") int concurrency) {
        this.slots = new Semaphore(Math.max(1, concurrency), true);
    }

    /** Converts one file to PDF or PDF/A. */
    public Result convert(Input input, OutputFormat format, Path output) {
        return withSlot(() -> {
            long start = System.nanoTime();
            String type;
            int pages;
            try (Document document = Document.open(input.path().toString())) {
                type = typeName(document);
                export(document, format, output);
                pages = document.getPageCount();
            } catch (Exception e) {
                throw failure(e, input.name());
            }
            long ms = millisSince(start);
            log.info("Converted {} ({}) to {} in {} ms", input.name(), type, format.label(), ms);
            return new Result(output.getFileName().toString(), pages, size(output), ms, type, validate(output, format));
        });
    }

    /** Converts every file and appends it, in order, to a single PDF or PDF/A. */
    public Result merge(List<Input> inputs, OutputFormat format, Path output) {
        return withSlot(() -> {
            long start = System.nanoTime();
            Input first = inputs.getFirst();
            int pages;
            try (Document base = Document.open(first.path().toString())) {
                PdfEditor editor = PdfEditor.edit(base);
                try {
                    for (Input next : inputs.subList(1, inputs.size())) {
                        try (Document document = Document.open(next.path().toString())) {
                            editor.appendDocument(document);
                        } catch (Exception e) {
                            throw failure(e, next.name());
                        }
                    }
                    editor.save();
                } finally {
                    editor.close();
                }
                export(base, format, output);
                pages = base.getPageCount();
            } catch (Exception e) {
                throw failure(e, first.name());
            }
            long ms = millisSince(start);
            log.info("Merged {} files to {} in {} ms", inputs.size(), format.label(), ms);
            return new Result(output.getFileName().toString(), pages, size(output), ms, "Merged", validate(output, format));
        });
    }

    /** Renders the first page of a PDF as a small PNG for the upload page. */
    public void thumbnail(Path pdf, Path png) {
        withSlot(() -> {
            DocumentSettings settings = new DocumentSettings();
            settings.getImageSettings().setExportFormat(ImageExportFormat.Png);
            settings.getImageSettings().setRasterizationDpi(30);
            Path folder = png.resolveSibling(png.getFileName() + ".pages");
            try (Document document = Document.open(pdf.toString(), settings)) {
                Files.createDirectories(folder);
                // The image export writes one file per page: page-1.png, page-2.png, ...
                document.exportAsImage(folder.resolve("page.png").toString());
                try (Stream<Path> files = Files.list(folder)) {
                    Path firstPage = files.filter(p -> p.getFileName().toString().matches("page(-0*1)?\\.png"))
                            .findFirst().orElseThrow(() -> new IOException("No page image was written"));
                    Files.move(firstPage, png);
                }
            } catch (Exception e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "thumbnail_failed", firstLine(e.getMessage()), null);
            } finally {
                JobStore.deleteQuietly(folder);
            }
            return null;
        });
    }

    private PdfAValidation validate(Path pdf, OutputFormat format) {
        if (!format.isPdfA()) {
            return null;
        }
        long start = System.nanoTime();
        try (Document document = Document.open(pdf.toString());
             PdfValidator validator = PdfValidator.set(document)) {
            validator.setConformance(format.validation());
            PdfValidationResult result = validator.validate();
            return new PdfAValidation(format.label(), result.getIsValid(), millisSince(start), failedChecks(result.getReport()));
        } catch (Exception e) {
            log.warn("PDF/A validation of {} failed: {}", pdf.getFileName(), firstLine(e.getMessage()));
            return new PdfAValidation(format.label(), false, millisSince(start), -1);
        }
    }

    /**
     * Writes the document as PDF, or as PDF/A in two steps: a plain PDF first, then that PDF is
     * converted to PDF/A. With SDK 2.1.13 on Linux, a direct PDF/A export of a multi-page Office
     * document turns all text into outlines (no text layer). The two steps keep searchable text on
     * every platform.
     */
    private static void export(Document document, OutputFormat format, Path output) throws Exception {
        if (!format.isPdfA()) {
            document.exportAsPdf(output.toString());
            return;
        }
        Path intermediate = output.resolveSibling("." + output.getFileName() + ".intermediate.pdf");
        try {
            document.exportAsPdf(intermediate.toString());
            try (Document pdf = Document.open(intermediate.toString())) {
                pdf.getSettings().getPdfSettings().setConformance(format.conformance());
                pdf.exportAsPdf(output.toString());
            }
        } finally {
            Files.deleteIfExists(intermediate);
        }
    }

    /**
     * Maps SDK exceptions to clear API errors. The SDK methods do not declare their exceptions,
     * so they are caught as Exception and checked here.
     */
    private static ApiException failure(Exception e, String file) {
        if (e instanceof ApiException api) {
            return api;
        }
        if (e instanceof UnsupportedFormatException) {
            return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_content",
                    "'" + file + "' could not be opened. The content is not a supported document: "
                            + "the file may be empty, damaged or saved with the wrong extension.", file);
        }
        ApiException error = new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "conversion_failed",
                "'" + file + "' could not be converted: " + firstLine(e.getMessage()), file);
        if (e instanceof NutrientException sdkError) {
            error.with("sdkErrorCode", sdkError.getErrorCode());
        }
        return error;
    }

    private static String typeName(Document document) {
        return document.getUnderlyingType().name().replaceFirst("^DocumentType", "");
    }

    /** SDK messages end with a native stack trace; the first line is the useful part. */
    static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.lines().findFirst().orElse(message).replaceFirst("^\\[\\w+]\\s*", "").trim();
    }

    private static int failedChecks(String report) {
        var matcher = java.util.regex.Pattern.compile("FailedChecks Count=\"(\\d+)\"").matcher(report == null ? "" : report);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run();
    }

    /** Limits how many documents the native engine processes at the same time. */
    private <T> T withSlot(Work<T> work) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted", "The service is shutting down.", null);
        }
        try {
            return work.run();
        } finally {
            slots.release();
        }
    }
}
