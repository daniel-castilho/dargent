package io.dargent.api.error;

import java.util.Map;

/** A request failed validation; carries the field→message map for the 400 problem+json body. */
public class RequestValidationException extends RuntimeException {

    private final Map<String, String> fields;

    public RequestValidationException(Map<String, String> fields) {
        super("Validation failed");
        this.fields = fields;
    }

    public Map<String, String> fields() {
        return fields;
    }
}
