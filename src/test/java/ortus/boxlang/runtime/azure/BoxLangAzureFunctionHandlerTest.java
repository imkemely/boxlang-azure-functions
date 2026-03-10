package ortus.boxlang.runtime.azure;

import ortus.boxlang.runtime.azure.BoxLangFunctionExecutor.BoxLangExecutionException;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.FileNotFoundException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeastOnce;

/**
 * Unit tests for BoxLangAzureFunctionHandler.
 * Coverage target: 90%.
 */
@ExtendWith(MockitoExtension.class)
class BoxLangAzureFunctionHandlerTest {

    @Mock private BoxLangFunctionExecutor              executor;
    @Mock private AzureRequestMapper                   requestMapper;
    @Mock private AzureContextAdapter                  contextAdapter;
    @Mock private HttpRequestMessage<Optional<String>> request;
    @Mock private ExecutionContext                     context;
    @Mock private HttpResponseMessage.Builder          responseBuilder;
    @Mock private HttpResponseMessage                  response;

    private BoxLangAzureFunctionHandler handler;

    private static final String FIXED_SCRIPT_PATH   = "hello.bx";
    private static final String TEST_INVOCATION_ID  = "test-invocation-abc123";

    @BeforeEach
    void setUp() throws Exception {
        handler = spy(new BoxLangAzureFunctionHandler(executor, requestMapper, contextAdapter));

        // Bypass real resolveScriptPath (no file system needed in unit tests)
        lenient().doReturn(FIXED_SCRIPT_PATH).when(handler).resolveScriptPath(any());

        lenient().when(context.getInvocationId()).thenReturn(TEST_INVOCATION_ID);
        lenient().when(request.getUri()).thenReturn(
                new URI("https://func.azurewebsites.net/api/hello"));
        lenient().when(request.getHttpMethod()).thenReturn(HttpMethod.GET);
        lenient().when(requestMapper.mapHttpRequest(any())).thenReturn(new HashMap<>());
        lenient().when(contextAdapter.createBoxLangContext(any())).thenReturn(new HashMap<>());
        lenient().when(request.createResponseBuilder(any(HttpStatus.class)))
                .thenReturn(responseBuilder);
        lenient().when(responseBuilder.header(anyString(), anyString()))
                .thenReturn(responseBuilder);
        lenient().when(responseBuilder.body(any())).thenReturn(responseBuilder);
        lenient().when(responseBuilder.build()).thenReturn(response);
    }

    // ---------------------------------------------------------------
    // H-01  GET request → 200 OK
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-01: GET request flows through to BoxLang and returns 200 OK")
    void H01_getRequest_returns200() throws Exception {
        when(executor.executeScript(eq(FIXED_SCRIPT_PATH), any(), any()))
                .thenReturn(scriptResult(200, "{\"status\":\"ok\"}"));

        HttpResponseMessage actual = handler.run(request, context);

        assertNotNull(actual);
        verify(executor).executeScript(eq(FIXED_SCRIPT_PATH), any(), any());
        verify(request).createResponseBuilder(HttpStatus.valueOf(200));
    }

    // ---------------------------------------------------------------
    // H-02  POST with JSON body → body forwarded to BoxLang
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-02: POST with JSON body passes body to BoxLang")
    void H02_postWithJsonBody_bodyPassedToBoxLang() throws Exception {
        when(request.getHttpMethod()).thenReturn(HttpMethod.POST);

        Map<String, Object> requestData = new HashMap<>();
        requestData.put("method", "POST");
        requestData.put("body", "{\"name\":\"Alice\",\"age\":30}");
        when(requestMapper.mapHttpRequest(request)).thenReturn(requestData);

        when(executor.executeScript(eq(FIXED_SCRIPT_PATH), eq(requestData), any()))
                .thenReturn(scriptResult(200, "{}"));

        HttpResponseMessage actual = handler.run(request, context);

        assertNotNull(actual);
        verify(executor).executeScript(eq(FIXED_SCRIPT_PATH), eq(requestData), any());
        assertTrue(requestData.containsKey("body"));
    }

    // ---------------------------------------------------------------
    // H-03  PUT with form data → fields accessible in BoxLang
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-03: PUT request with form data makes form fields accessible")
    void H03_putWithFormData_formFieldsAccessible() throws Exception {
        when(request.getHttpMethod()).thenReturn(HttpMethod.PUT);

        Map<String, Object> requestData = new HashMap<>();
        requestData.put("method", "PUT");
        requestData.put("formFields", Map.of("username", "bob", "role", "admin"));
        when(requestMapper.mapHttpRequest(request)).thenReturn(requestData);
        when(executor.executeScript(eq(FIXED_SCRIPT_PATH), eq(requestData), any()))
                .thenReturn(scriptResult(200, "{}"));

        handler.run(request, context);

        verify(executor).executeScript(eq(FIXED_SCRIPT_PATH), eq(requestData), any());
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) requestData.get("formFields");
        assertEquals("bob",   fields.get("username"));
        assertEquals("admin", fields.get("role"));
    }

    // ---------------------------------------------------------------
    // H-04  DELETE routed correctly
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-04: DELETE request is routed correctly")
    void H04_deleteRequest_routedCorrectly() throws Exception {
        when(request.getHttpMethod()).thenReturn(HttpMethod.DELETE);

        Map<String, Object> requestData = new HashMap<>();
        requestData.put("method", "DELETE");
        when(requestMapper.mapHttpRequest(request)).thenReturn(requestData);
        when(executor.executeScript(eq(FIXED_SCRIPT_PATH), eq(requestData), any()))
                .thenReturn(scriptResult(200, "{}"));

        handler.run(request, context);

        verify(executor).executeScript(eq(FIXED_SCRIPT_PATH), eq(requestData), any());
        assertEquals("DELETE", requestData.get("method"));
    }

    // ---------------------------------------------------------------
    // H-05  Query params forwarded
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-05: Query params are mapped and forwarded to BoxLang")
    void H05_queryParams_forwardedToBoxLang() throws Exception {
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("queryParams", Map.of("page", "2", "limit", "25"));
        when(requestMapper.mapHttpRequest(request)).thenReturn(requestData);
        when(executor.executeScript(eq(FIXED_SCRIPT_PATH), eq(requestData), any()))
                .thenReturn(scriptResult(200, "{}"));

        handler.run(request, context);

        @SuppressWarnings("unchecked")
        Map<String, String> qp = (Map<String, String>) requestData.get("queryParams");
        assertEquals("2",  qp.get("page"));
        assertEquals("25", qp.get("limit"));
    }

    // ---------------------------------------------------------------
    // H-06  Request headers forwarded
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-06: Request headers are forwarded to BoxLang")
    void H06_requestHeaders_forwardedToBoxLang() throws Exception {
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("headers", Map.of("Authorization", "Bearer token", "X-Custom", "val"));
        when(requestMapper.mapHttpRequest(request)).thenReturn(requestData);
        when(executor.executeScript(eq(FIXED_SCRIPT_PATH), eq(requestData), any()))
                .thenReturn(scriptResult(200, "{}"));

        handler.run(request, context);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) requestData.get("headers");
        assertTrue(headers.containsKey("Authorization"));
        assertTrue(headers.containsKey("X-Custom"));
    }

    // ---------------------------------------------------------------
    // H-07  Empty body does not crash
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-07: Request with empty body does not crash and completes the full pipeline")
    void H07_emptyBody_doesNotThrow() throws Exception {
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("body", null);
        when(requestMapper.mapHttpRequest(request)).thenReturn(requestData);
        when(executor.executeScript(anyString(), any(), any()))
                .thenReturn(scriptResult(200, "{}"));

        assertDoesNotThrow(() -> handler.run(request, context));
        verify(executor).executeScript(eq(FIXED_SCRIPT_PATH), any(), any());
        verify(responseBuilder).build();
    }

    // ---------------------------------------------------------------
    // H-08  Custom status codes reflected
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-08: BoxLang script returning 201 Created is reflected in the Azure response")
    void H08_statusCode201_reflected() throws Exception {
        when(executor.executeScript(anyString(), any(), any()))
                .thenReturn(scriptResult(201, "{\"id\":42}"));

        handler.run(request, context);

        verify(request).createResponseBuilder(HttpStatus.valueOf(201));
    }

    @Test
    @DisplayName("H-08 (variant): Non-numeric statusCode defaults to 200")
    void H08_nonNumericStatusCode_defaultsTo200() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("statusCode", "not-a-number");
        result.put("body", "{}");
        when(executor.executeScript(anyString(), any(), any())).thenReturn(result);

        handler.run(request, context);

        verify(request).createResponseBuilder(HttpStatus.valueOf(200));
    }

    @Test
    @DisplayName("H-08 (variant): Null statusCode defaults to 200")
    void H08_nullStatusCode_defaultsTo200() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("statusCode", null);
        result.put("body", "{}");
        when(executor.executeScript(anyString(), any(), any())).thenReturn(result);

        handler.run(request, context);

        verify(request).createResponseBuilder(HttpStatus.valueOf(200));
    }

    // ---------------------------------------------------------------
    // H-09  BoxLang runtime error → 500
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-09: BoxLang runtime error causes the handler to return 500")
    void H09_boxLangRuntimeError_returns500() throws Exception {
        when(executor.executeScript(anyString(), any(), any()))
                .thenThrow(new BoxLangExecutionException("Uncaught BoxLang exception", null));

        handler.run(request, context);

        verify(request).createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("H-09 (variant): 500 response body contains error JSON")
    void H09_boxLangRuntimeError_bodyContainsError() throws Exception {
        when(executor.executeScript(anyString(), any(), any()))
                .thenThrow(new BoxLangExecutionException("boom", null));

        handler.run(request, context);

        verify(responseBuilder).body(argThat(b -> b != null && b.toString().contains("\"error\"")));
    }

    @Test
    @DisplayName("H-09 (variant): Unexpected exception also causes 500")
    void H09_unexpectedException_returns500() throws Exception {
        when(executor.executeScript(anyString(), any(), any()))
                .thenThrow(new RuntimeException("something unexpected"));

        handler.run(request, context);

        verify(request).createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ---------------------------------------------------------------
    // H-10  Script not found → 404
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-10: Missing script file causes 404 Not Found")
    void H10_scriptNotFound_returns404() throws Exception {
        when(executor.executeScript(anyString(), any(), any()))
                .thenThrow(new FileNotFoundException("/functions/scripts/missing.bx"));

        handler.run(request, context);

        verify(request).createResponseBuilder(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("H-10 (variant): 404 response body contains error JSON")
    void H10_scriptNotFound_bodyContainsError() throws Exception {
        when(executor.executeScript(anyString(), any(), any()))
                .thenThrow(new FileNotFoundException("gone.bx"));

        handler.run(request, context);

        verify(responseBuilder).body(argThat(b -> b != null && b.toString().contains("\"error\"")));
    }

    // ---------------------------------------------------------------
    // H-11  20 concurrent requests → no race conditions
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-11: 20 concurrent requests all complete without errors")
    void H11_twentyConcurrentRequests_noRaceConditions() throws Exception {
        final int THREAD_COUNT = 20;
        ExecutorService pool  = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch  ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch  done  = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount   = new AtomicInteger(0);
        List<Future<?>> futures    = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    @SuppressWarnings("unchecked")
                    HttpRequestMessage<Optional<String>> req = mock(HttpRequestMessage.class);
                    ExecutionContext             ctx     = mock(ExecutionContext.class);
                    AzureRequestMapper          mapper  = mock(AzureRequestMapper.class);
                    AzureContextAdapter         adapter = mock(AzureContextAdapter.class);
                    BoxLangFunctionExecutor     exec    = mock(BoxLangFunctionExecutor.class);
                    HttpResponseMessage.Builder builder = mock(HttpResponseMessage.Builder.class);
                    HttpResponseMessage         resp    = mock(HttpResponseMessage.class);

                    when(ctx.getInvocationId()).thenReturn("inv-" + idx);
                    when(req.getUri()).thenReturn(new URI("https://func.azurewebsites.net/api/hello"));
                    when(req.getHttpMethod()).thenReturn(HttpMethod.GET);
                    when(req.createResponseBuilder(any(HttpStatus.class))).thenReturn(builder);
                    when(builder.header(anyString(), anyString())).thenReturn(builder);
                    when(builder.body(any())).thenReturn(builder);
                    when(builder.build()).thenReturn(resp);
                    when(mapper.mapHttpRequest(any())).thenReturn(new HashMap<>());
                    when(adapter.createBoxLangContext(any())).thenReturn(new HashMap<>());
                    when(exec.executeScript(anyString(), any(), any()))
                            .thenReturn(scriptResult(200, "{}"));

                    BoxLangAzureFunctionHandler h =
                            spy(new BoxLangAzureFunctionHandler(exec, mapper, adapter));
                    doReturn("hello.bx").when(h).resolveScriptPath(any());

                    ready.countDown();
                    ready.await();

                    HttpResponseMessage result = h.run(req, ctx);
                    if (result != null) successCount.incrementAndGet();
                } catch (Exception ex) {
                    errorCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }));
        }

        boolean finished = done.await(15, TimeUnit.SECONDS);
        pool.shutdown();

        for (Future<?> f : futures) {
            assertDoesNotThrow(() -> f.get(0, TimeUnit.SECONDS));
        }

        assertTrue(finished, "All threads must finish within timeout");
        assertEquals(0, errorCount.get(), "No thread must throw");
        assertEquals(THREAD_COUNT, successCount.get(), "All requests must return a response");
    }

    // ---------------------------------------------------------------
    // H-12  Invocation ID in response header
    // ---------------------------------------------------------------

    @Test
    @DisplayName("H-12: Invocation ID is set on the X-Invocation-Id response header")
    void H12_invocationId_inResponseHeader() throws Exception {
        when(context.getInvocationId()).thenReturn("unique-xyz-9999");
        when(executor.executeScript(anyString(), any(), any()))
                .thenReturn(scriptResult(200, "{}"));

        handler.run(request, context);

        verify(responseBuilder).header("X-Invocation-Id", "unique-xyz-9999");
    }

    @Test
    @DisplayName("H-12 (variant): Null context defaults invocation ID to 'unknown'")
    void H12_nullContext_invocationIdDefaultsToUnknown() throws Exception {
        when(executor.executeScript(anyString(), any(), any()))
                .thenReturn(scriptResult(200, "{}"));

        assertDoesNotThrow(() -> handler.run(request, null));
        verify(responseBuilder).header("X-Invocation-Id", "unknown");
    }

    @Test
    @DisplayName("H-12 (variant): Invocation ID is included in error responses too")
    void H12_invocationId_inErrorResponse() throws Exception {
        when(context.getInvocationId()).thenReturn("error-path-777");
        when(executor.executeScript(anyString(), any(), any()))
                .thenThrow(new BoxLangExecutionException("boom", null));

        handler.run(request, context);

        verify(responseBuilder).header("X-Invocation-Id", "error-path-777");
    }

    // ---------------------------------------------------------------
    // 400 BAD REQUEST path
    // ---------------------------------------------------------------

    @Test
    @DisplayName("IllegalArgumentException from mapper causes 400 Bad Request")
    void illegalArgument_fromMapper_returns400() throws Exception {
        when(requestMapper.mapHttpRequest(any()))
                .thenThrow(new IllegalArgumentException("Malformed URI parameter"));

        handler.run(request, context);

        verify(request).createResponseBuilder(HttpStatus.BAD_REQUEST);
        verify(responseBuilder).body(argThat(b -> b != null && b.toString().contains("\"error\"")));
    }

    @Test
    @DisplayName("IllegalArgumentException from resolveScriptPath causes 400 Bad Request")
    void illegalArgument_fromScriptPath_returns400() throws Exception {
        doThrow(new IllegalArgumentException("Request URI is null"))
                .when(handler).resolveScriptPath(any());

        handler.run(request, context);

        verify(request).createResponseBuilder(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------
    // extractHeaders() branch coverage
    // ---------------------------------------------------------------

    @Test
    @DisplayName("extractHeaders: non-String values in headers map falls back gracefully")
    void extractHeaders_nonStringValues_fallsBack() throws Exception {
        Map<String, Object> badHeaders = new HashMap<>();
        badHeaders.put("X-Count", 42);

        Map<String, Object> result = new HashMap<>();
        result.put("statusCode", 200);
        result.put("body", "{}");
        result.put("headers", badHeaders);
        when(executor.executeScript(anyString(), any(), any())).thenReturn(result);

        assertDoesNotThrow(() -> handler.run(request, context));
        verify(request).createResponseBuilder(HttpStatus.valueOf(200));
    }

    @Test
    @DisplayName("extractHeaders: non-Map headers value is ignored")
    void extractHeaders_nonMapValue_ignored() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("statusCode", 200);
        result.put("body", "{}");
        result.put("headers", "this-is-not-a-map");
        when(executor.executeScript(anyString(), any(), any())).thenReturn(result);

        assertDoesNotThrow(() -> handler.run(request, context));
        verify(request).createResponseBuilder(HttpStatus.valueOf(200));
    }

    // ---------------------------------------------------------------
    // extractContentType() branch coverage
    // ---------------------------------------------------------------

    @Test
    @DisplayName("extractContentType: Content-Type from script headers is forwarded")
    void extractContentType_titleCase_honoured() throws Exception {
        Map<String, String> scriptHeaders = new HashMap<>();
        scriptHeaders.put("Content-Type", "text/html; charset=utf-8");

        Map<String, Object> result = new HashMap<>();
        result.put("statusCode", 200);
        result.put("body", "<h1>Hello</h1>");
        result.put("headers", scriptHeaders);
        when(executor.executeScript(anyString(), any(), any())).thenReturn(result);

        handler.run(request, context);

        verify(responseBuilder, atLeastOnce()).header(eq("Content-Type"), eq("text/html; charset=utf-8"));
    }

    @Test
    @DisplayName("extractContentType: lowercase content-type key is also recognised")
    void extractContentType_lowercase_honoured() throws Exception {
        Map<String, String> scriptHeaders = new HashMap<>();
        scriptHeaders.put("content-type", "application/xml");

        Map<String, Object> result = new HashMap<>();
        result.put("statusCode", 200);
        result.put("body", "<root/>");
        result.put("headers", scriptHeaders);
        when(executor.executeScript(anyString(), any(), any())).thenReturn(result);

        handler.run(request, context);

        verify(responseBuilder).header(eq("Content-Type"), eq("application/xml"));
    }

    // ---------------------------------------------------------------
    // resolveScriptPath() — tested directly on a real (non-spy) handler
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("resolveScriptPath() unit tests")
    class ResolveScriptPathTests {

        // These tests call resolveScriptPath() directly without needing the
        // spy stub from setUp(), so they use a plain handler instance.
        private final BoxLangAzureFunctionHandler realHandler =
                new BoxLangAzureFunctionHandler(
                        mock(BoxLangFunctionExecutor.class),
                        mock(AzureRequestMapper.class),
                        mock(AzureContextAdapter.class));

        @Test
        @DisplayName("resolveScriptPath: /api prefix is stripped")
        void stripApiPrefix() throws Exception {
            HttpRequestMessage<?> req = mockRequestWithUri("https://host/api/hello");
            String path = realHandler.resolveScriptPath(req);
            assertFalse(path.contains("/api/"), "Should not retain /api/ prefix");
            assertTrue(path.endsWith("hello.bx"));
        }

        @Test
        @DisplayName("resolveScriptPath: root path defaults to index.bx")
        void rootPathDefaultsToIndex() throws Exception {
            HttpRequestMessage<?> req = mockRequestWithUri("https://host/api/");
            String path = realHandler.resolveScriptPath(req);
            assertTrue(path.endsWith("index.bx"), "Root URI must resolve to index.bx");
        }

        @Test
        @DisplayName("resolveScriptPath: .bx extension is appended when absent")
        void appendsBxExtension() throws Exception {
            HttpRequestMessage<?> req = mockRequestWithUri("https://host/api/users/profile");
            String path = realHandler.resolveScriptPath(req);
            assertTrue(path.endsWith(".bx"));
        }

        @Test
        @DisplayName("resolveScriptPath: existing extension is not double-appended")
        void preservesExistingExtension() throws Exception {
            HttpRequestMessage<?> req = mockRequestWithUri("https://host/api/render.cfm");
            String path = realHandler.resolveScriptPath(req);
            assertFalse(path.endsWith(".cfm.bx"), "Should not double-append .bx");
            assertTrue(path.endsWith(".cfm"));
        }

        @Test
        @DisplayName("resolveScriptPath: trailing slashes are stripped")
        void trailingSlashesRemoved() throws Exception {
            HttpRequestMessage<?> req = mockRequestWithUri("https://host/api/hello///");
            String path = realHandler.resolveScriptPath(req);
            assertFalse(path.contains("///"));
            assertTrue(path.endsWith("hello.bx"));
        }

        @Test
        @DisplayName("resolveScriptPath: null URI throws IllegalArgumentException")
        void nullUri_throws() {
            HttpRequestMessage<?> req = mock(HttpRequestMessage.class);
            when(req.getUri()).thenReturn(null);
            assertThrows(IllegalArgumentException.class,
                    () -> realHandler.resolveScriptPath(req));
        }

        @Test
        @DisplayName("resolveScriptPath: blank URI path throws IllegalArgumentException")
        void blankPath_throws() throws Exception {
            HttpRequestMessage<?> req = mockRequestWithUri("https://host");
            assertThrows(IllegalArgumentException.class,
                    () -> realHandler.resolveScriptPath(req));
        }

        private HttpRequestMessage<?> mockRequestWithUri(String uriString) throws Exception {
            HttpRequestMessage<?> req = mock(HttpRequestMessage.class);
            when(req.getUri()).thenReturn(new URI(uriString));
            return req;
        }
    }

    // ---------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------

    private static Map<String, Object> scriptResult(int statusCode, String body) {
        Map<String, Object> result = new HashMap<>();
        result.put("statusCode", statusCode);
        result.put("body", body);
        return result;
    }
}