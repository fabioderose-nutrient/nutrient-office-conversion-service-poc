package io.nutrient.demo.conversion;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error returned to the client as JSON: {@code { "error", "message", "file", ... }}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String file;
    private final Map<String, Object> details = new LinkedHashMap<>();

    public ApiException(HttpStatus status, String code, String message, String file) {
        super(message);
        this.status = status;
        this.code = code;
        this.file = file;
    }

    public ApiException with(String key, Object value) {
        details.put(key, value);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String file() {
        return file;
    }

    public Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", getMessage());
        if (file != null) {
            body.put("file", file);
        }
        body.putAll(details);
        return body;
    }

    public static ApiException unsupportedExtension(String file) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_format",
                "'" + file + "' is not a supported file type. Supported: " + String.join(", ", InputFormat.extensions()) + ".",
                file).with("supported", InputFormat.extensions());
    }
}
