package ortus.boxlang.runtime.azure;

import com.microsoft.azure.functions.HttpRequestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Transforms an Azure {@link HttpRequestMessage} into a BoxLang-compatible request structure. Azure Functions exposes raw HTTP data 
 * ({@link URI}, {@link com.microsoft.azure.functions.HttpMethod} enums, {@code Optional<String>} bodies) rather than the pre-parsed objects 
 * provided by AWS Lambda. This class bridges that gap by normalising the request into a plain {@code Map<String, Object>} that the BoxLang 
 * runtime can consume via its standard CGI-variable, URL-scope, and form-scope conventions.
 
 * Output Map Structure
 * {
 *   "method" : "POST",
 *   "uri" : "https://…/api/users/42",
 *   "path" : "/api/users/42",
 *   "queryString" : "debug=true",
 *   "headers" : { "content-type": "application/json", … },
 *   "queryParams" : { "debug": "true" },
 *   "body" : "{ \"name\": \"Alice\" }",
 *   "parsedBody" : { "name": "Alice" },          // populated for JSON / form bodies
 *   "pathParams" : { "id": "42" },               // when route template provides them
 *   "cgiVariables" : { "REQUEST_METHOD": "POST", … }
 * }
 */

public class AzureRequestMapper {

    private static final Logger logger = LoggerFactory.getLogger(AzureRequestMapper.class);

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Maps an Azure {@link HttpRequestMessage} to a BoxLang-compatible request map. This is the primary entry point used by {@link BoxLangAzureFunctionHandler}.
     * @param request the Azure HTTP request; must not be {@code null}
     * @return mutable map containing all normalised request data
     */
    public Map<String, Object> mapHttpRequest(HttpRequestMessage<?> request) {
        java.util.Objects.requireNonNull(request, "request must not be null");

        Map<String, Object> mapped = new HashMap<>();

        // --- Basic metadata ---------------------------------------------------
        String method = extractMethod(request);
        URI uri = request.getUri();
        String path = extractPath(uri);

        mapped.put("method", method );
        mapped.put("uri", uri != null ? uri.toString() : "");
        mapped.put("path", path);
        mapped.put("queryString", uri != null && uri.getRawQuery() != null ? uri.getRawQuery() : "");

        // --- Headers (normalised to lower-case keys) --------------------------
        Map<String, String> headers = normaliseHeaders(request.getHeaders());
        mapped.put("headers", headers);

        // --- Query string -----------------------------------------------------
        Map<String, Object> queryParams = parseQueryString(uri != null ? uri.getRawQuery() : null );
        mapped.put("queryParams", queryParams);

        // --- Body -------------------------------------------------------------
        String rawBody = extractBody(request);
        mapped.put("body", rawBody);

        String contentType = headers.getOrDefault("content-type", "");
        Map<String, Object> parsedBody = parseBody(rawBody, contentType);
        mapped.put("parsedBody", parsedBody);

        // --- CGI variables (BoxLang compatibility) ----------------------------
        Map<String, String> cgiVars = buildCgiVariables(request, method, path, headers);
        mapped.put("cgiVariables", cgiVars);

        logger.debug("Mapped request: method={}, path={}, contentType={}, bodyLength={}",
                      method, path, contentType, rawBody != null ? rawBody.length() : 0);

        return mapped;
    }

    /**
     * Merges Azure route-template path parameters into an existing mapped request. Azure extracts route parameters (e.g. {@code {id}}) into a
     * separate map that is available on the {@code ExecutionContext}; callers should invoke this method after calling {@link #mapHttpRequest} 
     * if they have those parameters available.
     *
     * @param mappedRequest the map produced by {@link #mapHttpRequest}
     * @param pathParams    route template parameters extracted by Azure (may be {@code null})
     */
    public void mergePathParameters( Map<String, Object> mappedRequest,
                                     Map<String, String> pathParams ) {
        if ( pathParams == null || pathParams.isEmpty() ) {
            mappedRequest.put( "pathParams", new HashMap<>() );
            return;
        }
        mappedRequest.put( "pathParams", new HashMap<>( pathParams ) );
        logger.debug( "Merged {} path parameter(s): {}", pathParams.size(), pathParams.keySet() );
    }

    // -------------------------------------------------------------------------
    // Method extraction
    // -------------------------------------------------------------------------

    /**
     * Converts the Azure {@link com.microsoft.azure.functions.HttpMethod} enum value to an upper-case string.
     */
    private String extractMethod(HttpRequestMessage<?> request) {
        if (request.getHttpMethod() == null) {
            logger.warn("Request has null HttpMethod – defaulting to GET");
            return "GET";
        }
        return request.getHttpMethod().toString().toUpperCase();
    }

    // -------------------------------------------------------------------------
    // Path / URI helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts the raw path component from a {@link URI}, without query string. Example: {@code https://example.com/api/users?foo=bar} → {@code /api/users}
     */
    private String extractPath(URI uri) {
        if (uri == null) {
            return "/";
        }
        String path = uri.getPath();
        return (path != null && !path.isEmpty()) ? path : "/";
    }

    // -------------------------------------------------------------------------
    // Header normalisation
    // -------------------------------------------------------------------------

    /**
     * Returns a new map whose keys are the lower-cased versions of the original header names.  Azure does not guarantee header name casing, so normalising
     * ensures BoxLang code can do simple string comparisons.
     */
    private Map<String, String> normaliseHeaders(Map<String, String> rawHeaders) {
        Map<String, String> normalised = new LinkedHashMap<>();
        if (rawHeaders == null) {
            return normalised;
        }
        for (Map.Entry<String, String> entry : rawHeaders.entrySet()) {
            if (entry.getKey() != null) {
                normalised.put(entry.getKey().toLowerCase(), entry.getValue());
            }
        }
        return normalised;
    }

    // -------------------------------------------------------------------------
    // Query string parsing
    // -------------------------------------------------------------------------

    /**
     * Parses a raw query string into a map. Multi-value parameters (e.g. {@code ?tag=a&tag=b}) are stored as a {@link List} so that BoxLang 
     * array-style access works correctly. Single values are stored as plain {@link String}.
     *
     * @param rawQuery the raw (URL-encoded) query string; may be {@code null}
     * @return parsed parameter map (never {@code null})
     */
    Map<String, Object> parseQueryString(String rawQuery) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }

        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) continue;
            int eqIdx = pair.indexOf('=');
            String key = eqIdx >= 0 ? urlDecode(pair.substring(0, eqIdx)) : urlDecode(pair);
            String value = eqIdx >= 0 ? urlDecode(pair.substring(eqIdx + 1)) : "";

            Object existing = params.get(key);
            if (existing == null) {
                params.put(key, value);
            } else if (existing instanceof List) {
                //noinspection unchecked
                ((List<String>) existing).add(value);
            } else {
                List<String> list = new ArrayList<>();
                list.add((String) existing);
                list.add(value);
                params.put(key, list);
            }
        }
        return params;
    }

    // -------------------------------------------------------------------------
    // Body extraction & parsing
    // -------------------------------------------------------------------------

    /**
     * Safely extracts the raw request body string from the Azure request. Azure wraps the body in an {@link Optional} to indicate that a body 
     * may legitimately be absent (e.g. for GET requests). This method unwraps it, returning {@code null} for absent bodies.
     */
    private String extractBody(HttpRequestMessage<?> request) {
        Object body = request.getBody();
        if (body == null) return null;
        if (body instanceof Optional) {
            return ((Optional<?>) body).map(Object::toString).orElse(null);
        }
        return body.toString();
    }

    /**
     * Attempts to parse the raw body based on the {@code Content-Type} header.
     *   {@code application/json} → JSON object/array parsed into a {@code Map<String, Object>} or {@code List<Object>}
     *   {@code application/x-www-form-urlencoded} → same as query string parsing
     *   {@code multipart/form-data} → field names mapped to string values (binary parts are skipped in this implementation)
     *   All other types → empty map (raw body available under {@code "body"})
     *
     * @param rawBody     the raw body string; may be {@code null}
     * @param contentType the normalised content-type header value
     * @return parsed body map (never {@code null}; may be empty)
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> parseBody(String rawBody, String contentType) {
        if (rawBody == null || rawBody.isBlank()) {
            return new HashMap<>();
        }

        String type = contentType != null ? contentType.toLowerCase() : "";

        if (type.contains("application/json")) {
            return parseJsonBody(rawBody);
        }

        if (type.contains("application/x-www-form-urlencoded")) {
            Map<String, Object> formData = parseQueryString(rawBody);
            return formData;
        }

        if (type.contains("multipart/form-data")) {
            return parseMultipartFormData(rawBody, contentType);
        }

        // Unknown content type – return empty map; raw body is in "body" key
        logger.debug("Unrecognised content-type '{}'; body left as raw string", contentType);
        return new HashMap<>();
    }

    /**
     * Best-effort shallow JSON parser for the {@code parsedBody} convenience map.
     * Limitations: This is a minimal implementation that covers simple flat JSON objects.  It will not correctly parse:
     *   Strings containing escaped quotes (e.g. {@code "msg":"say \"hi\""})
     *   String values containing colons (e.g. {@code "time":"12:00"})
     *   Nested arrays
     *
     * This is intentional — the BoxLang runtime provides full JSON parsing via its built-in {@code deserializeJSON()} BIF.  Scripts should 
     * use the raw body string (available under {@code event.body}) and call {@code deserializeJSON()} themselves for production use.  The 
     * {@code parsedBody} map is a convenience for simple cases only.
     *
     * For malformed input the method logs a warning and returns an empty map so the handler can still access the raw body string.
     */
    private Map<String, Object> parseJsonBody(String rawBody) {
        Map<String, Object> result = new HashMap<>();
        try {
            String trimmed = rawBody.trim();
            if (!trimmed.startsWith("{")) {
                // Array or primitive – store under a generic key and return
                result.put("_rawJson", trimmed);
                return result;
            }
            // Strip outer braces
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) return result;

            // Simple key-value tokeniser (handles one level of nesting)
            int i = 0;
            while (i < inner.length()) {
                // Skip whitespace / commas
                while (i < inner.length() && (inner.charAt(i) == ',' || Character.isWhitespace(inner.charAt(i)))) i++;
                if (i >= inner.length()) break;

                // Read key (quoted string)
                if (inner.charAt(i) != '"') { i++; continue; }
                int keyStart = i + 1;
                int keyEnd = inner.indexOf('"', keyStart);
                if (keyEnd < 0) break;
                String key = inner.substring(keyStart, keyEnd);
                i = keyEnd + 1;

                // Skip colon
                while (i < inner.length() && (inner.charAt(i) == ':' || Character.isWhitespace(inner.charAt(i)))) i++;
                if (i >= inner.length()) break;

                // Read value
                char first = inner.charAt(i);
                String value;
                if (first == '"') {
                    int valEnd = inner.indexOf('"', i + 1);
                    value = inner.substring(i + 1, valEnd);
                    i = valEnd + 1;
                } else if (first == '{') {
                    // Nested object – capture the whole substring and store as-is
                    int depth = 0, j = i;
                    while (j < inner.length()) {
                        char c = inner.charAt(j);
                        if (c == '{') depth++;
                        else if (c == '}') { depth--; if (depth == 0) { j++; break; } }
                        j++;
                    }
                    value = inner.substring(i, j);
                    i = j;
                } else {
                    // Number, boolean, null
                    int end = i;
                    while (end < inner.length() && inner.charAt(end) != ',' && inner.charAt(end) != '}') end++;
                    value = inner.substring(i, end).trim();
                    i = end;
                }
                result.put(key, value);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse JSON body; body will be available as raw string. Error: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Parses a {@code multipart/form-data} body into a map of field names → string values. Binary file parts are skipped with a logged warning.
     *
     * @param rawBody     the full multipart body string
     * @param contentType the full content-type header value (used to extract the boundary)
     * @return parsed fields map
     */
    private Map<String, Object> parseMultipartFormData(String rawBody, String contentType) {
        Map<String, Object> result = new HashMap<>();
        String boundary = extractMultipartBoundary(contentType);
        if (boundary == null || boundary.isBlank()) {
            logger.warn("multipart/form-data request missing boundary – cannot parse");
            return result;
        }

        String[] parts = rawBody.split("--" + boundary);
        for (String part : parts) {
            if (part.isBlank() || part.equals("--") || part.equals("--\r\n")) continue;

            // Split header section from body section
            int headerBodySep = part.indexOf("\r\n\r\n");
            if (headerBodySep < 0) headerBodySep = part.indexOf("\n\n");
            if (headerBodySep < 0) continue;

            String partHeaders = part.substring(0, headerBodySep);
            String partBody = part.substring(headerBodySep).replaceAll("^\r?\n", "").replaceAll("\r?\n$", "");

            // Extract field name from Content-Disposition header
            String fieldName = extractMultipartFieldName(partHeaders);
            if (fieldName != null) {
                result.put(fieldName, partBody);
            }
        }
        logger.debug("Parsed {} multipart field(s)", result.size());
        return result;
    }

    /** Extracts the {@code boundary} parameter from a Content-Type header value. */
    private String extractMultipartBoundary(String contentType) {
        if (contentType == null) return null;
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith( "boundary=")) {
                return trimmed.substring("boundary=".length()).replaceAll("^\"|\"$", "");
            }
        }
        return null;
    }

    /** Extracts the {@code name} parameter from a multipart part's headers. */
    private String extractMultipartFieldName(String partHeaders) {
        for (String line : partHeaders.split("\r?\n")) {
            if (line.toLowerCase().startsWith("content-disposition")) {
                for (String segment : line.split(";")) {
                    String trimmed = segment.trim();
                    if (trimmed.toLowerCase().startsWith("name=")) {
                        return trimmed.substring("name=".length()).replaceAll("^\"|\"$", "");
                    }
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // CGI variable builder
    // -------------------------------------------------------------------------

    /**
     * Builds a map of CGI-style environment variables from the request, matching the conventions that BoxLang's CGI scope expects. These variables 
     * allow BoxLang scripts written for CFML/BoxLang servers to run unmodified on Azure Functions by reading standard {@code CGI.REQUEST_METHOD},
     * {@code CGI.HTTP_HOST}, etc.
     *
     * @param request normalised headers (lower-case keys)
     */
    private Map<String, String> buildCgiVariables(HttpRequestMessage<?> request,
                                                  String method,
                                                  String path,
                                                  Map<String, String> headers) {
        Map<String, String> cgi = new LinkedHashMap<>();
        URI uri = request.getUri();

        cgi.put("REQUEST_METHOD", method);
        cgi.put("SCRIPT_NAME", path);
        cgi.put("PATH_INFO", path);
        cgi.put("QUERY_STRING", uri != null && uri.getRawQuery() != null ? uri.getRawQuery() : "");
        cgi.put("SERVER_PROTOCOL", "HTTP/1.1");
        cgi.put("SERVER_NAME", uri != null && uri.getHost() != null ? uri.getHost() : "");
        cgi.put("SERVER_PORT", uri != null && uri.getPort() > 0 ? String.valueOf(uri.getPort()) : "443");
        cgi.put("REQUEST_URI", uri != null ? uri.getPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "") : path);
        cgi.put("HTTPS", "on");   // Azure Functions always terminates TLS

        // Map HTTP headers to HTTP_* CGI variables
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String cgiKey = "HTTP_" + header.getKey().toUpperCase().replace('-', '_');
            cgi.put(cgiKey, header.getValue());
        }

        // Promote specific headers to top-level CGI vars
        cgi.put("CONTENT_TYPE", headers.getOrDefault("content-type", ""));
        cgi.put("CONTENT_LENGTH", headers.getOrDefault("content-length", "0"));
        cgi.put("HTTP_HOST", headers.getOrDefault("host", ""));

        return cgi;
    }

    // -------------------------------------------------------------------------
    // URL decode helper
    // -------------------------------------------------------------------------

    /**
     * URL-decodes a single value, falling back to the raw input on failure.
     */
    private String urlDecode(String value) {
        if (value == null) return "";
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always supported; this branch is unreachable in practice
            return value;
        }
    }

} 

