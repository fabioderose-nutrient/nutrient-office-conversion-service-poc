package io.nutrient.demo.conversion;

import io.nutrient.demo.conversion.ConversionService.Input;
import io.nutrient.demo.conversion.ConversionService.Result;
import io.nutrient.demo.conversion.JobStore.Job;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ConversionController {

    private final ConversionService conversions;
    private final JobStore jobs;
    private final NutrientSdk sdk;
    private final int maxFiles;

    public ConversionController(ConversionService conversions, JobStore jobs, NutrientSdk sdk,
                                @Value("${conversion.max-files:20}") int maxFiles) {
        this.conversions = conversions;
        this.jobs = jobs;
        this.sdk = sdk;
        this.maxFiles = maxFiles;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("sdk", "Nutrient Java SDK");
        body.put("sdkVersion", sdk.version());
        body.put("licenseMode", sdk.licenseMode());
        body.put("locale", NutrientSdk.processLocale());
        return body;
    }

    @GetMapping("/formats")
    public Map<String, Object> formats() {
        List<Map<String, String>> inputs = Arrays.stream(InputFormat.values())
                .map(f -> Map.of("extension", f.extension(), "family", f.family())).toList();
        List<Map<String, String>> outputs = Arrays.stream(OutputFormat.values())
                .map(f -> Map.of("id", f.id(), "label", f.label())).toList();
        return Map.of("inputs", inputs, "outputs", outputs, "maxFiles", maxFiles);
    }

    /**
     * Converts the files and returns the result directly: one PDF, or a ZIP with one PDF per input
     * when several files are sent without {@code merge=true}. Any failure rejects the request.
     */
    @PostMapping(value = "/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> convert(@RequestParam(value = "files", required = false) List<MultipartFile> files,
                                          @RequestParam(defaultValue = "pdf") String output,
                                          @RequestParam(defaultValue = "false") boolean merge) {
        OutputFormat format = outputFormat(output);
        List<MultipartFile> uploads = checkUploads(files);
        uploads.forEach(ConversionController::checkFile);
        Job job = jobs.create();
        try {
            List<Input> inputs = save(job, uploads);
            List<Result> results = new ArrayList<>();
            if (merge || inputs.size() == 1) {
                String name = inputs.size() == 1 ? stem(uploads.getFirst().getOriginalFilename()) + ".pdf" : "merged.pdf";
                results.add(merge && inputs.size() > 1
                        ? conversions.merge(inputs, format, job.outputs().resolve(name))
                        : conversions.convert(inputs.getFirst(), format, job.outputs().resolve(name)));
            } else {
                Set<String> used = new HashSet<>();
                for (int i = 0; i < inputs.size(); i++) {
                    String name = uniqueName(stem(uploads.get(i).getOriginalFilename()), used);
                    results.add(conversions.convert(inputs.get(i), format, job.outputs().resolve(name)));
                }
            }
            long totalMs = results.stream().mapToLong(Result::conversionMs).sum();
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Conversion-Time-Ms", Long.toString(totalMs));
            headers.add("X-Output-Format", format.label());
            if (results.size() == 1) {
                Result result = results.getFirst();
                headers.setContentType(MediaType.APPLICATION_PDF);
                headers.setContentDisposition(ContentDisposition.attachment().filename(result.output()).build());
                headers.add("X-Page-Count", Integer.toString(result.pages()));
                if (result.pdfa() != null) {
                    headers.add("X-PdfA-Valid", Boolean.toString(result.pdfa().valid()));
                }
                return new ResponseEntity<>(read(job.outputs().resolve(result.output())), headers, HttpStatus.OK);
            }
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            headers.setContentDisposition(ContentDisposition.attachment().filename("converted.zip").build());
            return new ResponseEntity<>(zip(job.outputs(), results), headers, HttpStatus.OK);
        } finally {
            jobs.delete(job);
        }
    }

    /**
     * Converts the files and keeps the outputs for download. Used by the upload page.
     * Without merge, each file gets its own result or error, so one bad file does not stop the others.
     */
    @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> createJob(@RequestParam(value = "files", required = false) List<MultipartFile> files,
                                         @RequestParam(defaultValue = "pdf") String output,
                                         @RequestParam(defaultValue = "false") boolean merge) {
        OutputFormat format = outputFormat(output);
        List<MultipartFile> uploads = checkUploads(files);
        Job job = jobs.create();
        long start = System.nanoTime();
        List<Map<String, Object>> items = new ArrayList<>();
        if (merge && uploads.size() > 1) {
            uploads.forEach(ConversionController::checkFile);
            Result result = conversions.merge(save(job, uploads), format, job.outputs().resolve("merged.pdf"));
            Map<String, Object> item = item(String.join(" + ", uploads.stream().map(MultipartFile::getOriginalFilename).toList()),
                    uploads.stream().mapToLong(MultipartFile::getSize).sum());
            items.add(withResult(item, job, result));
        } else {
            Set<String> used = new HashSet<>();
            for (int i = 0; i < uploads.size(); i++) {
                MultipartFile upload = uploads.get(i);
                Map<String, Object> item = item(upload.getOriginalFilename(), upload.getSize());
                try {
                    checkFile(upload);
                    Input input = save(job, upload, i);
                    String name = uniqueName(stem(upload.getOriginalFilename()), used);
                    withResult(item, job, conversions.convert(input, format, job.outputs().resolve(name)));
                } catch (ApiException e) {
                    item.put("status", "failed");
                    item.put("error", e.body());
                }
                items.add(item);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", job.id());
        body.put("output", format.label());
        body.put("merge", merge && uploads.size() > 1);
        body.put("totalMs", (System.nanoTime() - start) / 1_000_000);
        body.put("results", items);
        return body;
    }

    @GetMapping("/jobs/{id}/files/{name}")
    public ResponseEntity<Resource> download(@PathVariable String id, @PathVariable String name,
                                             @RequestParam(defaultValue = "false") boolean inline) {
        Path file = jobs.output(id, name).orElseThrow(() -> notFound(name));
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(name).build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(file));
    }

    @GetMapping("/jobs/{id}/files/{name}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable String id, @PathVariable String name) {
        Path pdf = jobs.output(id, name).orElseThrow(() -> notFound(name));
        Path png = jobs.thumbnailFor(pdf);
        synchronized (this) {
            if (!Files.exists(png)) {
                conversions.thumbnail(pdf, png);
            }
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(new FileSystemResource(png));
    }

    // ------------------------------------------------------------ helpers

    private static Map<String, Object> item(String input, long bytes) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("input", input);
        item.put("inputBytes", bytes);
        return item;
    }

    private static Map<String, Object> withResult(Map<String, Object> item, Job job, Result result) {
        String base = "/api/jobs/" + job.id() + "/files/" + result.output();
        item.put("status", "converted");
        item.put("detectedType", result.detectedType());
        item.put("output", result.output());
        item.put("pages", result.pages());
        item.put("outputBytes", result.bytes());
        item.put("conversionMs", result.conversionMs());
        if (result.pdfa() != null) {
            item.put("pdfa", result.pdfa());
        }
        item.put("download", base);
        item.put("view", base + "?inline=true");
        item.put("thumbnail", base + "/thumbnail");
        return item;
    }

    private static OutputFormat outputFormat(String output) {
        return OutputFormat.fromId(output).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "invalid_output",
                "Unknown output '" + output + "'. Use one of: " + String.join(", ", OutputFormat.ids()) + ".", null)
                .with("supported", OutputFormat.ids()));
    }

    private List<MultipartFile> checkUploads(List<MultipartFile> files) {
        List<MultipartFile> uploads = files == null ? List.of()
                : files.stream().filter(f -> f.getOriginalFilename() != null && !f.getOriginalFilename().isBlank()).toList();
        if (uploads.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "no_files", "Send at least one file in the 'files' form field.", null);
        }
        if (uploads.size() > maxFiles) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "too_many_files",
                    "Send at most " + maxFiles + " files per request.", null).with("maxFiles", maxFiles);
        }
        return uploads;
    }

    private static void checkFile(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (InputFormat.fromFileName(name).isEmpty()) {
            throw ApiException.unsupportedExtension(name);
        }
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_file", "'" + name + "' is empty.", name);
        }
    }

    private static List<Input> save(Job job, List<MultipartFile> uploads) {
        List<Input> inputs = new ArrayList<>();
        for (int i = 0; i < uploads.size(); i++) {
            inputs.add(save(job, uploads.get(i), i));
        }
        return inputs;
    }

    private static Input save(Job job, MultipartFile upload, int index) {
        Path target = job.inputs().resolve(JobStore.safeName(upload.getOriginalFilename(), index));
        try {
            upload.transferTo(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Input(target, upload.getOriginalFilename());
    }

    /** The file name without folder and extension, made safe for a download name. */
    private static String stem(String fileName) {
        String safe = JobStore.safeName(fileName, 0).substring(3);
        int dot = safe.lastIndexOf('.');
        return dot > 0 ? safe.substring(0, dot) : safe;
    }

    private static String uniqueName(String stem, Set<String> used) {
        String name = stem + ".pdf";
        for (int n = 2; !used.add(name); n++) {
            name = stem + "-" + n + ".pdf";
        }
        return name;
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] zip(Path folder, List<Result> results) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Result result : results) {
                zip.putNextEntry(new ZipEntry(result.output()));
                Files.copy(folder.resolve(result.output()), zip);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static ApiException notFound(String name) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found",
                "'" + name + "' does not exist or has expired.", name);
    }
}
