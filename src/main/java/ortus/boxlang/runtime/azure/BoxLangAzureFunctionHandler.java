package ortus.boxlang.runtime.azure;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import ortus.boxlang.runtime.azure.BoxLangFunctionExecutor.BoxLangExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Main entry point for Azure Functions invocations of BoxLang scripts.
 *
 * This class is the Java function that the Azure Functions host calls for every HTTP request.  It orchestrates the full request pipeline:
 *   Map the Azure {@link HttpRequestMessage} to a BoxLang-compatible request map via {@link AzureRequestMapper}.
 *   Build a BoxLang context map from the Azure {@link ExecutionContext} via {@link AzureContextAdapter}.
 *   Resolve the target BoxLang script path from the request URI.
 *   Execute the script via {@link BoxLangFunctionExecutor}.
 *   Build and return an {@link HttpResponseMessage} from the script result.
 *
 * Error Mapping
 *   {@link IllegalArgumentException} - 400 Bad Request
 *   {@link FileNotFoundException} - 404 Not Found
 *   {@link BoxLangExecutionException} - 500 Internal Server Error
 *   Any other {@link Exception} - 500 Internal Server Error
 *
 * All error responses include the Azure invocation ID so that operators can correlate client-visible errors with Application Insights traces.
 */
public class BoxLangAzureFunctionHandler {

    private static final Logger logger = LoggerFactory.getLogger(BoxLangAzureFunctionHandler.class);

    /** Content-type returned for JSON responses and error bodies. */
    private static final String CONTENT_TYPE_JSON = "application/json";

    /** BoxLang result-map key that the executor writes the HTTP status code into. */
    private static final String RESULT_STATUS_KEY = "statusCode";

    /** BoxLang result-map key containing the response body string. */
    private static final String RESULT_BODY_KEY = "body";

    /** BoxLang result-map key containing a headers sub-map. */
    private static final String RESULT_HEADERS_KEY = "headers";

    // -------------------------------------------------------------------------
    // Collaborators (final for testability via constructor injection)
    // -------------------------------------------------------------------------

    private final BoxLangFunctionExecutor executor;
    private final AzureRequestMapper requestMapper;
    private final AzureContextAdapter contextAdapter;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Default constructor – used by the Azure Functions host via reflection. Wires up collaborators from their singletons / default instances.
     */
    public BoxLangAzureFunctionHandler() {
        this(
            BoxLangFunctionExecutor.getInstance(),
            new AzureRequestMapper(),
            new AzureContextAdapter()
        );
    }

    /**
     * Dependency-injection constructor – used by unit tests to supply mocks.
     *
     * @param executor       script executor
     * @param requestMapper  HTTP request mapper
     * @param contextAdapter Azure context adapter
     */
    public BoxLangAzureFunctionHandler(BoxLangFunctionExecutor executor,
                                       AzureRequestMapper requestMapper,
                                       AzureContextAdapter contextAdapter ) {
        this.executor = executor;
        this.requestMapper = requestMapper;
        this.contextAdapter = contextAdapter;
    }

    // -------------------------------------------------------------------------
    // Azure Function entry point
    // -------------------------------------------------------------------------

    /**
     * Handles every inbound HTTP request directed at this function app. The wildcard route {@code {*path}} ensures that all sub-paths under {@code /api/} 
     * are captured here and forwarded to the matching BoxLang script. The script file name is derived from the last path segment so that a request to 
     * {@code /api/hello} executes {@code <scriptRoot>/hello.bx}.
     *
     * @param request the Azure HTTP request (body wrapped in {@link Optional})
     * @param context the Azure invocation context (logging, metadata, tracing)
     * @return the HTTP response produced by the BoxLang script, or an error response
     */
    @FunctionName("BoxLangFunction")
    public HttpResponseMessage run(
            @HttpTrigger(
                name = "req",
                methods = { HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                            HttpMethod.DELETE, HttpMethod.PATCH, HttpMethod.OPTIONS },
                authLevel = AuthorizationLevel.ANONYMOUS,
                route = "{*path}"
            )
            HttpRequestMessage<Optional<String>> request,
            final ExecutionContext context
    ) {
        String invocationId = safeInvocationId(context);
        logger.info("[{}] {} {}",
                invocationId,
                request.getHttpMethod(),
                request.getUri());

        try {
            // 1. Map Azure HTTP request → BoxLang request map
            Map<String, Object> requestData = requestMapper.mapHttpRequest(request);

            // 2. Build BoxLang context map from Azure ExecutionContext
            Map<String, Object> contextData = contextAdapter.createBoxLangContext(context);

            // 3. Resolve target script path from the URI
            String scriptPath = resolveScriptPath(request);
            logger.debug("[{}] Resolved script path: {}", invocationId, scriptPath);

            // 4. Execute the BoxLang script
            Map<String, Object> result = executor.executeScript(scriptPath, requestData, contextData);

            // 5. Build and return the HTTP response
            return buildSuccessResponse(request, result, invocationId);

        } catch (IllegalArgumentException e) {
            logger.warn("[{}] Bad request: {}", invocationId, e.getMessage());
            return buildErrorResponse(request, HttpStatus.BAD_REQUEST,
                                      "Bad Request", e.getMessage(), invocationId);

        } catch (FileNotFoundException e) {
            logger.warn("[{}] Script not found: {}", invocationId, e.getMessage());
            return buildErrorResponse(request, HttpStatus.NOT_FOUND,
                                      "Not Found", "The requested resource was not found.", invocationId);

        } catch (BoxLangExecutionException e) {
            logger.error("[{}] BoxLang runtime error", invocationId, e);
            return buildErrorResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                                      "Internal Server Error", "An error occurred executing the function.", invocationId);

        } catch (Exception e) {
            logger.error("[{}] Unexpected error", invocationId, e);
            return buildErrorResponse(request, HttpStatus.INTERNAL_SERVER_ERROR,
                                      "Internal Server Error", "An unexpected error occurred.", invocationId);
        }
    }

    // -------------------------------------------------------------------------
    // Script path resolution
    // -------------------------------------------------------------------------

    /**
     * Extracts the relative BoxLang script path segment from the HTTP request URI. Returns a path segment that the {@link BoxLangFunctionExecutor} will
     * resolve against the configured script root.  
     * Rules:
     *   The {@code /api/} route prefix injected by host.json is stripped.
     *   Trailing slashes are removed.
     *   A bare {@code /} maps to {@code index}.
     *   A {@code .bx} extension is appended unless a file extension is already present.
     *
     * Example: {@code GET /api/users/profile?id=1} → {@code users/profile.bx}
     *
     * @param request the Azure HTTP request
     * @return relative script path segment (no leading slash, no script root)
     * @throws IllegalArgumentException if the URI is missing or the path is empty
     */
    public String resolveScriptPath(HttpRequestMessage<?> request) {
        if (request.getUri() == null) {
            throw new IllegalArgumentException("Request URI is null");
        }

        String rawPath = request.getUri().getPath();
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Request URI path is empty");
        }

        // Strip the /api/ route prefix added by host.json
        String path = rawPath.replaceFirst("^/api", "");

        // Remove leading and trailing slashes so the executor can prepend the root cleanly
        path = path.replaceAll("^/+|/+$", "");

        // Default to index script for empty / root path
        if (path.isBlank()) {
            path = "index";
        }

        // Append .bx extension unless a recognised extension is already present
        if (!path.contains(".")) {
            path = path + ".bx";
        }

        return path;
    }

    // -------------------------------------------------------------------------
    // Response builders
    // -------------------------------------------------------------------------

    /**
     * Constructs a successful {@link HttpResponseMessage} from the BoxLang script result map.
     * The script may control the response by returning a map with any combination of the following keys:
     *   @code statusCode} (int or String) – HTTP status; defaults to 200
     *   {@code body} (String) – response body; defaults to empty JSON object
     *   {@code headers} (Map) – additional response headers
     *
     * If the body is not already a string it is converted via {@code toString()}.  The {@code Content-Type} header defaults to {@code application/json} \
     * unless the script sets a different value in its headers map.
     */
    private HttpResponseMessage buildSuccessResponse(HttpRequestMessage<?> request,
                                                     Map<String, Object> result,
                                                     String invocationId) {
        int statusCode = extractStatusCode(result);
        String body = extractBody(result);
        String contentType = extractContentType(result);

        HttpResponseMessage.Builder builder = request
                .createResponseBuilder(HttpStatus.valueOf(statusCode))
                .header("Content-Type", contentType)
                .header("X-Invocation-Id", invocationId)
                .body(body);

        // Apply any additional headers provided by the script
        Map<String, String> extraHeaders = extractHeaders(result);
        for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
            builder.header(h.getKey(), h.getValue());
        }

        return builder.build();
    }

    /**
     * Constructs an error {@link HttpResponseMessage} with a JSON error body containing the error category and the Azure invocation ID.
     *
     * @param request      original request (needed to call {@code createResponseBuilder})
     * @param status       HTTP status code for this error
     * @param error        short error category (e.g. "Not Found")
     * @param message      human-readable detail message
     * @param invocationId Azure invocation ID for correlation
     */
    private HttpResponseMessage buildErrorResponse(HttpRequestMessage<?> request,
                                                   HttpStatus status,
                                                   String error,
                                                   String message,
                                                   String invocationId) {
        String body = String.format(
                "{\"error\":\"%s\",\"message\":\"%s\",\"invocationId\":\"%s\"}",
                escapeJson(error),
                escapeJson(message),
                escapeJson(invocationId)
        );

        return request
                .createResponseBuilder(status)
                .header("Content-Type", CONTENT_TYPE_JSON)
                .header("X-Invocation-Id", invocationId)
                .body(body)
                .build();
    }

    // -------------------------------------------------------------------------
    // Result-map extraction helpers
    // -------------------------------------------------------------------------

    /** Extracts the HTTP status code from the script result map; defaults to 200. */
    private int extractStatusCode(Map<String, Object> result) {
        Object raw = result.get(RESULT_STATUS_KEY);
        if (raw == null) return 200;
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            logger.warn("Invalid statusCode '{}' in script result; defaulting to 200", raw);
            return 200;
        }
    }

    /** Extracts the response body string from the script result map; defaults to {@code {}}. */
    private String extractBody(Map<String, Object> result) {
        Object raw = result.get(RESULT_BODY_KEY);
        if (raw == null) return "{}";
        return raw.toString();
    }

    /**
     * Determines the Content-Type for the response.
     * Priority order:
     *   A {@code content-type} entry in the script's headers map
     *   {@code application/json} as default
     */
    private String extractContentType(Map<String, Object> result) {
        Map<String, String> headers = extractHeaders(result);
        return headers.getOrDefault("content-type",
               headers.getOrDefault("Content-Type", CONTENT_TYPE_JSON));
    }

    /**
     * Extracts the headers sub-map from the script result. Returns an empty map when the script did not set any headers.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> extractHeaders(Map<String, Object> result) {
        Object raw = result.get(RESULT_HEADERS_KEY);
        if (raw instanceof Map) {
            try {
                return (Map<String, String>) raw;
            } catch (ClassCastException e) {
                logger.warn("Script returned headers map with non-String values; ignoring");
            }
        }
        return new HashMap<>();
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    /** Returns the invocation ID from the context without throwing. */
    private String safeInvocationId(ExecutionContext context) {
        if (context == null) return "unknown";
        try {
            String id = context.getInvocationId();
            return id != null ? id : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Minimal JSON string escaping – escapes double-quotes and backslashes so that error messages can be safely embedded in a JSON string literal.
     */
    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

