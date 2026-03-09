package ortus.boxlang.runtime.azure;

import com.microsoft.azure.functions.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Bridges Microsoft Azure's {@link ExecutionContext} to a BoxLang-compatible context representation. Azure Functions provides invocation metadata and 
 * logging through {@link ExecutionContext}. BoxLang expects its own {@code IBoxContext} / scope model. This adapter translates between the two so that 
 * BoxLang scripts can access Azure-specific metadata (function name, invocation ID, tracing headers) through standard BoxLang scope variables, while 
 * also routing their log statements to the Azure Functions host logger.
 *
 * Scope variables injected into BoxLang
 * azure.functionName – name registered with the Functions host
 * azure.invocationId – unique ID for this invocation (for distributed tracing)
 * azure.traceContext – map of tracing headers / parent spans (when present)
 * env.* – copies of all process environment variables
 * 
 * Logging bridge
 * BoxLang log calls are forwarded to the {@link java.util.logging.Logger} obtained from {@link ExecutionContext#getLogger()}. This ensures that all
 * function output appears in Application Insights / Azure Monitor under the correct invocation ID.
 */

public class AzureContextAdapter {

    private static final Logger slf4jLogger = LoggerFactory.getLogger(AzureContextAdapter.class);

    // -------------------------------------------------------------------------
    // Context creation
    // -------------------------------------------------------------------------

    /**
     * Creates a BoxLang-compatible context map from the Azure {@link ExecutionContext}. The returned map is used by {@link BoxLangFunctionExecutor} to 
     * populate the BoxLang runtime's request-scoped variables before script execution.
     *
     * @param executionContext the Azure invocation context; must not be {@code null}
     * @return mutable map of context variables (never {@code null})
     */
    public Map<String, Object> createBoxLangContext(ExecutionContext executionContext) {
        Objects.requireNonNull(executionContext, "executionContext must not be null");

        Map<String, Object> context = new HashMap<>();

        // --- Azure metadata ---------------------------------------------------
        Map<String, String> azureMeta = buildAzureMetadata(executionContext);
        context.put("azure", azureMeta);

        // --- Trace context ----------------------------------------------------
        Map<String, String> traceContext = extractTraceContext(executionContext);
        context.put("traceContext", traceContext);

        // --- Environment variables --------------------------------------------
        Map<String, String> envVars = Collections.unmodifiableMap(new HashMap<>(System.getenv()));
        context.put("env", envVars);

        slf4jLogger.debug("BoxLang context created – function={}, invocationId={}",
                          executionContext.getFunctionName(), executionContext.getInvocationId());

        return context;
    }

    // -------------------------------------------------------------------------
    // Azure metadata helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a simple string-to-string map of the Azure-specific metadata that BoxLang scripts are most likely to need.
     *
     * @param ctx the Azure execution context
     * @return metadata map; never {@code null}
     */
    public Map<String, String> getAzureMetadata(ExecutionContext ctx) {
        Objects.requireNonNull(ctx, "ExecutionContext must not be null");
        return buildAzureMetadata(ctx);
    }

    private Map<String, String> buildAzureMetadata(ExecutionContext ctx) {
        Map<String, String> meta = new HashMap<>();
        meta.put("functionName", safeFunctionName(ctx));
        meta.put("invocationId", safeInvocationId(ctx));
        return Collections.unmodifiableMap(meta);
    }

    // -------------------------------------------------------------------------
    // Logging bridge
    // -------------------------------------------------------------------------

    /**
     * Logs a message at the given SLF4J level through both:
     *   The SLF4J logger (appears in the application's own log pipeline)
     *   The Azure {@link java.util.logging.Logger} obtained from the {@link ExecutionContext} (appears in Application Insights under the correct invocation ID).
     *
     * @param executionContext the current Azure invocation context
     * @param level            SLF4J level name ({@code "DEBUG"}, {@code "INFO"}, {@code "WARN"}, {@code "ERROR"})
     * @param message          the log message
     */
  
    public void log(ExecutionContext executionContext, String level, String message) {
        Objects.requireNonNull(executionContext, "executionContext must not be null");

        // Forward to the Azure host logger so the message is correlated with this invocation in Application Insights.
        java.util.logging.Logger azureLogger = executionContext.getLogger();
        Level julLevel = toJulLevel(level);
        azureLogger.log(julLevel, "[{0}] {1}", new Object[]{executionContext.getInvocationId(), message});

        // Also forward to SLF4J for local dev / console output.
        switch (level != null ? level.toUpperCase() : "INFO") {
            case "DEBUG" -> slf4jLogger.debug(message);
            case "WARN" -> slf4jLogger.warn(message);
            case "ERROR" -> slf4jLogger.error(message);
            default -> slf4jLogger.info(message);
        }
    }

    /**
     * Convenience overload that logs an exception in addition to a message.
     *
     * @param executionContext the current Azure invocation context
     * @param level            SLF4J level name
     * @param message          the log message
     * @param throwable        the exception to log
     */
    public void log(ExecutionContext executionContext, String level,
                    String message, Throwable throwable) {
        Objects.requireNonNull(executionContext, "executionContext must not be null");

        java.util.logging.Logger azureLogger = executionContext.getLogger();
        azureLogger.log(toJulLevel(level),
                "[" + executionContext.getInvocationId() + "] " + message + " – " + throwable.getMessage());

        switch (level != null ? level.toUpperCase() : "INFO") {
            case "DEBUG" -> slf4jLogger.debug(message, throwable);
            case "WARN" -> slf4jLogger.warn(message, throwable);
            case "ERROR" -> slf4jLogger.error(message, throwable);
            default -> slf4jLogger.info(message, throwable);
        }
    }

    // -------------------------------------------------------------------------
    // Distributed tracing
    // -------------------------------------------------------------------------

    /**
     * Extracts distributed-tracing context from the Azure {@link ExecutionContext}. Azure Functions propagates W3C TraceContext and (optionally) Correlation
     * ID headers through the execution context. When present, these values are surfaced here so that BoxLang scripts can forward them to downstream
     * services to maintain end-to-end trace continuity.
     *
     * @param ctx the Azure execution context
     * @return trace context map; may be empty but never {@code null}
     */
    public Map<String, String> extractTraceContext(ExecutionContext ctx) {
        Map<String, String> trace = new HashMap<>();
        if (ctx == null) return trace;

        // invocationId can double as a correlation ID when no explicit trace header is provided by the caller.
        trace.put("invocationId", safeInvocationId(ctx));
        trace.put("functionName", safeFunctionName(ctx));

        // Azure Functions >= 4.x exposes a TraceContext via the ExecutionContext. We attempt to access it reflectively so that this adapter compiles
        // against older SDK versions that don't have it.
        try {
            java.lang.reflect.Method getTraceContext =
                    ctx.getClass().getMethod("getTraceContext");
            Object traceCtx = getTraceContext.invoke(ctx);
            if (traceCtx != null) {
                tryPutString(trace, "traceParent",
                        traceCtx.getClass().getMethod("getTraceparent").invoke(traceCtx));
                tryPutString(trace, "traceState",
                        traceCtx.getClass().getMethod("getTracestate").invoke(traceCtx));
                tryPutString(trace, "attributes",
                        traceCtx.getClass().getMethod("getAttributes").invoke(traceCtx));
            }
        } catch (NoSuchMethodException ignored) {
            // Older SDK – TraceContext not available; that's acceptable.
        } catch (Exception e) {
            slf4jLogger.debug("Could not extract TraceContext from ExecutionContext: {}", e.getMessage());
        }

        return trace;
    }

    // -------------------------------------------------------------------------
    // Scope variable injection
    // -------------------------------------------------------------------------

    /**
     * Injects Azure-specific variables into a BoxLang scope map so that scripts can access them via standard scope lookups (
     * e.g. {@code variables.azure.invocationId}).
     *
     * @param scope           the BoxLang scope map to populate
     * @param executionContext the Azure context for this invocation
     */
    public void injectScopeVariables(Map<String, Object> scope,
                                     ExecutionContext executionContext) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(executionContext, "executionContext must not be null");

        Map<String, String> azureMeta = buildAzureMetadata(executionContext);
        scope.put("azure", azureMeta);
        scope.put("env", Collections.unmodifiableMap(new HashMap<>(System.getenv())));
        scope.put("traceContext", extractTraceContext(executionContext));

        slf4jLogger.debug("Injected Azure scope variables for invocation {}",
                executionContext.getInvocationId());
    }

    /**
     * Verifies that two context maps are isolated from one another (i.e. modifying one does not affect the other). Used by the executor to validate that
     * concurrent requests do not bleed state across invocations.
     *
     * @param contextA first context map
     * @param contextB second context map
     * @return {@code true} if the maps share no mutable references
     */
    public boolean areContextsIsolated(Map<String, Object> contextA,
                                       Map<String, Object> contextB) {
        if (contextA == contextB) return false;
        // Check that the 'azure' sub-maps are distinct instances
        Object azureA = contextA.get("azure");
        Object azureB = contextB.get("azure");
        return azureA != azureB;
    }

    // -------------------------------------------------------------------------
    // Null-safe helpers
    // -------------------------------------------------------------------------

    private String safeFunctionName(ExecutionContext ctx) {
        try {
            String name = ctx.getFunctionName();
            return name != null ? name : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String safeInvocationId(ExecutionContext ctx) {
        try {
            String id = ctx.getInvocationId();
            return id != null ? id : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Converts a SLF4J level name to a {@link java.util.logging.Level}. */
    private Level toJulLevel(String level) {
        return switch (level != null ? level.toUpperCase() : "INFO") {
            case "DEBUG", "TRACE" -> Level.FINE;
            case "WARN" -> Level.WARNING;
            case "ERROR" -> Level.SEVERE;
            default -> Level.INFO;
        };
    }

    /** Adds a value to the map only when it is a non-null String. */
    private void tryPutString(Map<String, String> map, String key, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            map.put(key, s);
        }
    }
}

