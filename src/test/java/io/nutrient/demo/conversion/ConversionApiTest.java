package io.nutrient.demo.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ConversionApiTest {

    private static final Path SAMPLES = Path.of("samples");

    @Autowired
    private MockMvc mvc;

    @Test
    void healthReportsTheSdk() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.sdk").value("Nutrient Java SDK"));
    }

    @Test
    void convertsWordToValidPdfA() throws Exception {
        byte[] pdf = mvc.perform(multipart("/api/convert")
                        .file(sample("quarterly-facilities-report.docx"))
                        .param("output", "pdfa-2b"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-PdfA-Valid", "true"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void mergesSeveralFormatsIntoOnePdf() throws Exception {
        mvc.perform(multipart("/api/convert")
                        .file(sample("maintenance-budget.xlsx"))
                        .file(sample("project-kickoff.pptx"))
                        .file(sample("floor-plan.png"))
                        .param("merge", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page-Count", "9"));
    }

    @Test
    void rejectsUnsupportedExtension() throws Exception {
        mvc.perform(multipart("/api/convert")
                        .file(new MockMultipartFile("files", "archive.zip", "application/zip", new byte[] {1, 2, 3})))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_format"))
                .andExpect(jsonPath("$.file").value("archive.zip"));
    }

    @Test
    void rejectsDamagedContent() throws Exception {
        mvc.perform(multipart("/api/convert")
                        .file(new MockMultipartFile("files", "renamed.docx", "application/octet-stream",
                                "this is not a Word document".getBytes(StandardCharsets.US_ASCII))))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_content"));
    }

    @Test
    void keepsTheExtensionInSafeNames() {
        assertThat(JobStore.safeName("../../etc/report v2.docx", 0)).isEqualTo("01-report v2.docx");
        assertThat(JobStore.safeName("C:\\Users\\someone\\plan.png", 4)).isEqualTo("05-plan.png");
        assertThat(JobStore.safeName(".hidden", 0)).isEqualTo("01-file.hidden");
    }

    private static MockMultipartFile sample(String name) throws Exception {
        return new MockMultipartFile("files", name, "application/octet-stream", Files.readAllBytes(SAMPLES.resolve(name)));
    }
}
