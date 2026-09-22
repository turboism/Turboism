package dev.turboism.update;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Bounded transport seam used by the runtime checker and deterministic fixture tests. */
public interface UpdateTransport {
    CompletionStage<Response> fetch(Optional<String> etag);

    record Response(int statusCode, byte[] body, Optional<String> etag) {
        public Response {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("statusCode must be an HTTP status");
            }
            body = Objects.requireNonNull(body, "body").clone();
            etag = Objects.requireNonNull(etag, "etag");
            if (etag.isPresent()) {
                final String value = etag.orElseThrow();
                if (value.isBlank() || value.length() > 512
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("etag is invalid");
                }
            }
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        /** Returns whether this response represents a validated HTTP 304 result. */
        public boolean notModified() {
            return statusCode == 304;
        }

        @Override
        public String toString() {
            return "Response[statusCode=" + statusCode + ", bodyBytes=" + body.length
                + ", etag=" + etag.map(ignored -> "present").orElse("empty") + "]";
        }
    }
}
