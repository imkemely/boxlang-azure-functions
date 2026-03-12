package ortus.boxlang.runtime.azure;

import com.microsoft.azure.functions.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AzureContextAdapter.
 * Covers 7 required test cases at 85%+ coverage target.
 */
@ExtendWith(MockitoExtension.class)
class AzureContextAdapterTest {

    @Mock private ExecutionContext context;

    private AzureContextAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AzureContextAdapter();
        lenient().when(context.getFunctionName()).thenReturn("MyFunction");
        lenient().when(context.getInvocationId()).thenReturn("inv-abc-123");
        lenient().when(context.getLogger()).thenReturn(Logger.getLogger("test"));
    }

    // ---------------------------------------------------------------
    // CA-01  createBoxLangContext returns a non-null map with required keys
    // ---------------------------------------------------------------

    @Test
    @DisplayName("CA-01: createBoxLangContext returns map with azure, env, and traceContext keys")
    void CA01_createBoxLangContext_hasRequiredKeys() {
        Map<String, Object> ctx = adapter.createBoxLangContext(context);

        assertNotNull(ctx, "Context map must not be null");
        assertTrue(ctx.containsKey("azure"),        "Must contain 'azure' key");
        assertTrue(ctx.containsKey("env"),           "Must contain 'env' key");
        assertTrue(ctx.containsKey("traceContext"),  "Must contain 'traceContext' key");
    }

    // ---------------------------------------------------------------
    // CA-02  Azure metadata contains functionName and invocationId
    // ---------------------------------------------------------------

    @Test
    @DisplayName("CA-02: Azure metadata contains correct functionName and invocationId")
    void CA02_azureMetadata_containsFunctionNameAndInvocationId() {
        Map<String, Object> ctx = adapter.createBoxLangContext(context);

        @SuppressWarnings("unchecked")
        Map<String, String> azure = (Map<String, String>) ctx.get("azure");

        assertEquals("MyFunction",  azure.get("functionName"),  "functionName must match");
        assertEquals("inv-abc-123", azure.get("invocationId"),  "invocationId must match");
    }

    // ---------------------------------------------------------------
    // CA-03  Null executionContext throws NullPointerException
    // ---------------------------------------------------------------

    @Test
    @DisplayName("CA-03: createBoxLangContext throws NullPointerException for null context")
    void CA03_nullContext_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> adapter.createBoxLangContext(null),
                "Null context must throw NullPointerException");
    }

    // ---------------------------------------------------------------
    // CA-04  getAzureMetadata returns the same values as the context map
    // ---------------------------------------------------------------

    @Test
    @DisplayName("CA-04: getAzureMetadata returns functionName and invocationId")
    void CA04_getAzureMetadata_returnsExpectedValues() {
        Map<String, String> meta = adapter.getAzureMetadata(context);

        assertNotNull(meta);
        assertEquals("MyFunction",  meta.get("functionName"));
        assertEquals("inv-abc-123", meta.get("invocationId"));
    }

    // ---------------------------------------------------------------
    // CA-05  injectScopeVariables populates the given scope map
    // ---------------------------------------------------------------

    @Test
    @DisplayName("CA-05: injectScopeVariables adds azure, env, and traceContext to the scope")
    void CA05_injectScopeVariables_populatesScope() {
        Map<String, Object> scope = new HashMap<>();
        adapter.injectScopeVariables(scope, context);

        assertTrue(scope.containsKey("azure"),       "Scope must contain 'azure'");
        assertTrue(scope.containsKey("env"),          "Scope must contain 'env'");
        assertTrue(scope.containsKey("traceContext"), "Scope must contain 'traceContext'");
    }

    // ---------------------------------------------------------------
    // CA-06  areContextsIsolated returns true for different invocations
    // ---------------------------------------------------------------

    @Test
    @DisplayName("CA-06: Two different invocation contexts are isolated from each other")
    void CA06_areContextsIsolated_differentContexts_returnsTrue() {
        ExecutionContext contextA = mock(ExecutionContext.class);
        ExecutionContext contextB = mock(ExecutionContext.class);
        when(contextA.getFunctionName()).thenReturn("FuncA");
        when(contextA.getInvocationId()).thenReturn("inv-111");
        when(contextB.getFunctionName()).thenReturn("FuncB");
        when(contextB.getInvocationId()).thenReturn("inv-222");

        Map<String, Object> mapA = adapter.createBoxLangContext(contextA);
        Map<String, Object> mapB = adapter.createBoxLangContext(contextB);

        assertTrue(adapter.areContextsIsolated(mapA, mapB),
                "Contexts from different invocations must be isolated");
    }

    @Test
    @DisplayName("CA-06 (variant): Same context map is not isolated from itself")
    void CA06_areContextsIsolated_sameMap_returnsFalse() {
        Map<String, Object> map = adapter.createBoxLangContext(context);
        assertFalse(adapter.areContextsIsolated(map, map),
                "A context map compared with itself must not be considered isolated");
    }

    // ---------------------------------------------------------------
    // CA-07  log() forwards message without throwing
    // ---------------------------------------------------------------

    @Test
    @DisplayName("CA-07: log() at INFO level completes without throwing")
    void CA07_log_info_doesNotThrow() {
        assertDoesNotThrow(() -> adapter.log(context, "INFO", "Test log message"),
                "log() must not throw for a valid context and message");
    }

    @Test
    @DisplayName("CA-07 (variant): log() at ERROR level with throwable completes without throwing")
    void CA07_log_errorWithThrowable_doesNotThrow() {
        assertDoesNotThrow(() ->
                        adapter.log(context, "ERROR", "Something went wrong", new RuntimeException("oops")),
                "log() with a Throwable must not throw");
    }

    @Test
    @DisplayName("CA-07 (variant): log() with null level defaults to INFO without throwing")
    void CA07_log_nullLevel_defaultsToInfo() {
        assertDoesNotThrow(() -> adapter.log(context, null, "Null level message"),
                "log() with null level must not throw");
    }

    // ---------------------------------------------------------------
    // extractTraceContext — invocationId is always present
    // ---------------------------------------------------------------

    @Test
    @DisplayName("extractTraceContext: trace map always contains invocationId")
    void extractTraceContext_alwaysContainsInvocationId() {
        Map<String, String> trace = adapter.extractTraceContext(context);
        assertNotNull(trace);
        assertTrue(trace.containsKey("invocationId"),
                "Trace context must always contain invocationId");
        assertEquals("inv-abc-123", trace.get("invocationId"));
    }

    @Test
    @DisplayName("extractTraceContext: null context returns empty map without throwing")
    void extractTraceContext_nullContext_returnsEmptyMap() {
        Map<String, String> trace = adapter.extractTraceContext(null);
        assertNotNull(trace);
        assertTrue(trace.isEmpty(), "Null context must return an empty trace map");
    }

    // ---------------------------------------------------------------
    // env scope — environment variables are present
    // ---------------------------------------------------------------

    @Test
    @DisplayName("env scope is populated with system environment variables")
    void envScope_isPopulated() {
        Map<String, Object> ctx = adapter.createBoxLangContext(context);

        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) ctx.get("env");

        assertNotNull(env, "env map must not be null");
        // We can't assert specific keys since env vars vary, but the map must not be null
        // and must reflect what System.getenv() returns.
        assertEquals(System.getenv().size(), env.size(),
                "env map must contain all system environment variables");
    }
}