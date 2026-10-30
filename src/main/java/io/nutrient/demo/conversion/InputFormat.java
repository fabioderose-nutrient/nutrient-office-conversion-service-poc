package io.nutrient.demo.conversion;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * File types the service accepts. Each one was converted to PDF and PDF/A-2b while building this demo.
 *
 * <p>The SDK opens more types (DOC, XLS, PPT, ODT, EML, MSG, DWG, DXF). Add them here to accept them.
 */
public enum InputFormat {
    DOCX("docx", "Word document"),
    RTF("rtf", "Rich Text Format"),
    TXT("txt", "Plain text"),
    MD("md", "Markdown"),
    XLSX("xlsx", "Excel workbook"),
    PPTX("pptx", "PowerPoint presentation"),
    PNG("png", "Image"),
    JPG("jpg", "Image"),
    JPEG("jpeg", "Image"),
    TIF("tif", "Image"),
    TIFF("tiff", "Image"),
    BMP("bmp", "Image"),
    GIF("gif", "Image"),
    HTML("html", "Web page"),
    HTM("htm", "Web page"),
    PDF("pdf", "PDF");

    private final String extension;
    private final String family;

    InputFormat(String extension, String family) {
        this.extension = extension;
        this.family = family;
    }

    public String extension() {
        return extension;
    }

    public String family() {
        return family;
    }

    public static Optional<InputFormat> fromFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(f -> f.extension.equals(ext)).findFirst();
    }

    public static List<String> extensions() {
        return Arrays.stream(values()).map(InputFormat::extension).toList();
    }
}
