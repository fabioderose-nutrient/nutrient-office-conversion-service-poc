package io.nutrient.demo.conversion;

import io.nutrient.sdk.License;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Registers the Nutrient license once, when the service starts.
 *
 * <p>An empty key starts the SDK in evaluation mode: every output page carries a watermark.
 */
@Component
public class NutrientSdk {

    private static final Logger log = LoggerFactory.getLogger(NutrientSdk.class);

    private final String version;
    private final boolean licensed;

    public NutrientSdk(@Value("${nutrient.license-key:}") String licenseKey,
                       @Value("${nutrient.sdk-version}") String version) {
        this.version = version;
        String key = licenseKey == null ? "" : licenseKey.trim();
        boolean registered = false;
        try {
            License.registerKey(key);
            registered = !key.isEmpty();
        } catch (RuntimeException e) {
            log.warn("The license key was rejected, running in evaluation mode: {}", e.getMessage());
            License.registerKey("");
        }
        this.licensed = registered;
        log.info("Nutrient Java SDK {} ready, {} mode, process locale {}",
                version, licensed ? "licensed" : "evaluation", processLocale());
    }

    public String version() {
        return version;
    }

    public String licenseMode() {
        return licensed ? "licensed" : "evaluation";
    }

    /** The native engine formats spreadsheet numbers with the locale of the process (LC_ALL, LANG). */
    public static String processLocale() {
        for (String name : new String[] {"LC_ALL", "LC_NUMERIC", "LANG"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "not set (system default, Java sees " + Locale.getDefault().toLanguageTag() + ")";
    }
}
