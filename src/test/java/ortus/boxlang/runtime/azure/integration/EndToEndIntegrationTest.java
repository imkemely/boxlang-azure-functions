package ortus.boxlang.runtime.azure.integration;

import ortus.boxlang.runtime.azure.AzureContextAdapter;
import ortus.boxlang.runtime.azure.AzureRequestMapper;
import ortus.boxlang.runtime.azure.BoxLangAzureFunctionHandler;
import ortus.boxlang.runtime.azure.BoxLangFunctionExecutor;
import ortus.boxlang.runtime.azure.BoxLangFunctionExecutor.BoxLangExecutionException;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.FileNotFoundException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration tests for the BoxLang Azure Functions pipeline.
 */
@ExtendWith(MockitoExtension.class)
@Tag("integration")
@DisplayName("End-to-End Integration Tests — BoxLang Azure Functions Pipeline")
class EndToEndIntegrationTest {

    // -----------------------------------------------------------------------
    // Infrastructure
    // -----------------------------------------------------------------------

    private AzureRequestMapper          realMapper;
    private AzureContextAdapter         realContextAdapter;
    private BoxLangFunctionExecutor     mockExecutor;
    private BoxLangAzureFunctionHandler handler;
    private ExecutionContext            sharedContext;

    private static final String BASE_HOST     = "https://my-app.azurewebsites.net";
    private static final String INVOCATION_ID = "e2e-inv-0001";

    /**
     * Stores the builder mock per request so {@link #getBuilderFor} never needs to
     * call {@code createResponseBuilder()} again, keeping Mockito verify counts accurate.
     */
    private final IdentityHashMap<HttpRequestMessage<?>, HttpResponseMessage.Builder>
            builderRegistry = new IdentityHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        realMapper         = new AzureRequestMapper();
        realContextAdapter = new AzureContextAdapter();
        mockExecutor       = mock(BoxLangFunctionExecutor.class);
        handler = spy(new BoxLangAzureFunctionHandler(
                mockExecutor, realMapper, realContextAdapter));

        // Stub mirrors the real handler exactly:
        //   replaceFirst("^/api", "") → strip leading/trailing slashes → append .bx if no extension
        lenient().doAnswer(inv -> {
            HttpRequestMessage<?> r = inv.getArgument(0);
            if (r == null || r.getUri() == null) return "index.bx";
            String p = r.getUri().getPath();
            if (p == null || p.isBlank()) return "index.bx";
            p = p.replaceFirst("^/api", "").replaceAll("^/+|/+$", "");
            if (p.isBlank()) return "index.bx";
            return p.contains(".") ? p : p + ".bx";
        }).when(handler).resolveScriptPath(any());

        sharedContext = buildContext(INVOCATION_ID, "E2EFunction");
    }

    @AfterEach
    void tearDown() {
        builderRegistry.clear();
        BoxLangFunctionExecutor.resetForTesting();
    }

    // ===================================================================
    // IT-01  Hello World — full pipeline runs
    // ===================================================================

    @Test
    @DisplayName("IT-01: GET /api/hello → 200; mapper + context adapter + executor all invoked")
    void IT01_helloWorld_fullPipelineRuns() throws Exception {
        HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/hello", "");

        when(mockExecutor.executeScript(eq("hello.bx"), any(), any()))
                .thenReturn(result(200, "{\"message\":\"Hello, World!\"}"));

        assertNotNull(handler.run(req, sharedContext));
        verify(req).createResponseBuilder(HttpStatus.valueOf(200));
        verify(mockExecutor).executeScript(eq("hello.bx"), any(), any());
        verify(handler).resolveScriptPath(req);
    }

    // ===================================================================
    // IT-02  Echo — real mapper forwards raw body unchanged
    // ===================================================================

    @Test
    @DisplayName("IT-02: POST /api/echo — real mapper stores raw body under 'body' key")
    void IT02_echo_rawBodyReachesExecutor() throws Exception {
        String payload = "{\"echo\":\"BoxLang integration test\"}";
        HttpRequestMessage<Optional<String>> req =
                buildPostRequest("/api/echo", payload, "application/json");

        when(mockExecutor.executeScript(eq("echo.bx"),
                argThat(d -> payload.equals(d.get("body"))), any()))
                .thenReturn(result(200, payload));

        assertNotNull(handler.run(req, sharedContext));
        verify(req).createResponseBuilder(HttpStatus.valueOf(200));
    }

    // ===================================================================
    // IT-03  User CRUD
    // ===================================================================

    @Nested
    @DisplayName("IT-03: User CRUD")
    class UserCrudTests {

        @Test
        @DisplayName("GET /api/users/42 → 200")
        void getUser_returns200() throws Exception {
            HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/users/42", "");
            doReturn("users/42.bx").when(handler).resolveScriptPath(req);

            when(mockExecutor.executeScript(eq("users/42.bx"), any(), any()))
                    .thenReturn(result(200, "{\"id\":42,\"name\":\"Alice\"}"));

            handler.run(req, sharedContext);
            verify(req).createResponseBuilder(HttpStatus.valueOf(200));
        }

        @Test
        @DisplayName("POST /api/users → 201 Created with Location header propagated")
        void createUser_returns201WithLocationHeader() throws Exception {
            HttpRequestMessage<Optional<String>> req =
                    buildPostRequest("/api/users", "{\"name\":\"Bob\"}", "application/json");
            doReturn("users.bx").when(handler).resolveScriptPath(req);

            Map<String, Object> sr = result(201, "{\"id\":99}");
            sr.put("headers", Map.of("Location", "/api/users/99"));
            when(mockExecutor.executeScript(eq("users.bx"), any(), any())).thenReturn(sr);

            handler.run(req, sharedContext);
            verify(req).createResponseBuilder(HttpStatus.valueOf(201));
            verify(getBuilderFor(req)).header("Location", "/api/users/99");
        }

        @Test
        @DisplayName("PUT /api/users/42 → real mapper records method=PUT")
        void updateUser_methodIsPutInMappedRequest() throws Exception {
            HttpRequestMessage<Optional<String>> req =
                    buildRequestWithMethod("/api/users/42", HttpMethod.PUT,
                            "{\"name\":\"Alice Updated\"}", "application/json");
            doReturn("users/42.bx").when(handler).resolveScriptPath(req);

            when(mockExecutor.executeScript(eq("users/42.bx"),
                    argThat(d -> "PUT".equals(d.get("method"))), any()))
                    .thenReturn(result(200, "{}"));

            handler.run(req, sharedContext);
            verify(req).createResponseBuilder(HttpStatus.valueOf(200));
        }

        @Test
        @DisplayName("DELETE /api/users/42 → 204 No Content")
        void deleteUser_returns204() throws Exception {
            HttpRequestMessage<Optional<String>> req =
                    buildRequestWithMethod("/api/users/42", HttpMethod.DELETE, null, null);
            doReturn("users/42.bx").when(handler).resolveScriptPath(req);

            when(mockExecutor.executeScript(eq("users/42.bx"), any(), any()))
                    .thenReturn(result(204, ""));

            handler.run(req, sharedContext);
            verify(req).createResponseBuilder(HttpStatus.valueOf(204));
        }

        @Test
        @DisplayName("GET /api/users/999 — script-returned 404 flows through unchanged")
        void getUser_scriptReturns404() throws Exception {
            HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/users/999", "");
            doReturn("users/999.bx").when(handler).resolveScriptPath(req);

            when(mockExecutor.executeScript(eq("users/999.bx"), any(), any()))
                    .thenReturn(result(404, "{\"error\":\"User 999 not found\"}"));

            handler.run(req, sharedContext);
            verify(req).createResponseBuilder(HttpStatus.valueOf(404));
        }
    }

    // ===================================================================
    // IT-04  Concurrent Requests
    // ===================================================================

    @Test
    @DisplayName("IT-04: 30 concurrent requests — no deadlock, no data corruption")
    void IT04_thirtyParallelRequests_noDeadlockOrCorruption() throws Exception {
        final int CONCURRENCY = 30;
        when(mockExecutor.executeScript(anyString(), any(), any()))
                .thenReturn(result(200, "{\"ok\":true}"));

        ExecutorService pool      = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch  startGate = new CountDownLatch(CONCURRENCY);
        CountDownLatch  done      = new CountDownLatch(CONCURRENCY);
        AtomicInteger   successes = new AtomicInteger(0);
        AtomicInteger   errors    = new AtomicInteger(0);
        List<Future<?>> futures   = new ArrayList<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    @SuppressWarnings("unchecked")
                    HttpRequestMessage<Optional<String>> req = mock(HttpRequestMessage.class);
                    ExecutionContext ctx = buildContext("inv-" + idx, "E2EFunction");

                    lenient().when(req.getUri())
                            .thenReturn(new URI(BASE_HOST + "/api/ping"));
                    lenient().when(req.getHttpMethod()).thenReturn(HttpMethod.GET);
                    lenient().when(req.getHeaders()).thenReturn(new HashMap<>());
                    lenient().when(req.getBody()).thenReturn(Optional.empty());

                    HttpResponseMessage.Builder b = mock(HttpResponseMessage.Builder.class);
                    HttpResponseMessage          r = mock(HttpResponseMessage.class);
                    lenient().when(req.createResponseBuilder(any(HttpStatus.class))).thenReturn(b);
                    lenient().when(b.header(anyString(), anyString())).thenReturn(b);
                    lenient().when(b.body(any())).thenReturn(b);
                    lenient().when(b.build()).thenReturn(r);

                    // Each thread gets its own handler spy to avoid shared spy state
                    BoxLangAzureFunctionHandler h = spy(new BoxLangAzureFunctionHandler(
                            mockExecutor, realMapper, realContextAdapter));
                    doReturn("ping.bx").when(h).resolveScriptPath(any());

                    startGate.countDown();
                    startGate.await();

                    if (h.run(req, ctx) != null) successes.incrementAndGet();
                } catch (Exception ex) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }));
        }

        assertTrue(done.await(20, TimeUnit.SECONDS), "All threads must finish within 20 s");
        pool.shutdown();
        for (Future<?> f : futures) assertDoesNotThrow(() -> f.get(0, TimeUnit.SECONDS));
        assertEquals(0,           errors.get());
        assertEquals(CONCURRENCY, successes.get());
    }

    // ===================================================================
    // IT-05  Error Handling
    // ===================================================================

    @Nested
    @DisplayName("IT-05: Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("BoxLangExecutionException → 500 with JSON error body and X-Invocation-Id")
        void runtimeException_returns500() throws Exception {
            ExecutionContext ctx = buildContext("err-inv-001", "E2EFunction");
            HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/crash", "");
            when(mockExecutor.executeScript(anyString(), any(), any()))
                    .thenThrow(new BoxLangExecutionException("Unhandled error", null));

            handler.run(req, ctx);

            verify(req).createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR);
            verify(getBuilderFor(req)).header("X-Invocation-Id", "err-inv-001");
            verify(getBuilderFor(req)).body(argThat(b ->
                    b != null
                    && b.toString().contains("\"error\"")
                    && b.toString().contains("err-inv-001")));
        }

        @Test
        @DisplayName("FileNotFoundException → 404 with JSON error body")
        void fileNotFound_returns404() throws Exception {
            HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/missing", "");
            when(mockExecutor.executeScript(anyString(), any(), any()))
                    .thenThrow(new FileNotFoundException("missing.bx"));

            handler.run(req, sharedContext);

            verify(req).createResponseBuilder(HttpStatus.NOT_FOUND);
            verify(getBuilderFor(req)).body(argThat(b ->
                    b != null && b.toString().contains("\"error\"")));
        }

        @Test
        @DisplayName("IllegalArgumentException → 400 with JSON error body")
        void illegalArgument_returns400() throws Exception {
            HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/bad", "");
            when(mockExecutor.executeScript(anyString(), any(), any()))
                    .thenThrow(new IllegalArgumentException("Bad param"));

            handler.run(req, sharedContext);

            verify(req).createResponseBuilder(HttpStatus.BAD_REQUEST);
            verify(getBuilderFor(req)).body(argThat(b ->
                    b != null && b.toString().contains("\"error\"")));
        }

        @Test
        @DisplayName("Unexpected RuntimeException → 500 via generic catch block")
        void unexpectedException_returns500() throws Exception {
            HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/boom", "");
            when(mockExecutor.executeScript(anyString(), any(), any()))
                    .thenThrow(new RuntimeException("unexpected"));

            handler.run(req, sharedContext);
            verify(req).createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ===================================================================
    // IT-06  Azure Context scopes — metadata, traceContext, env (Gap 2)
    // ===================================================================

    @Test
    @DisplayName("IT-06: Real adapter injects azure, traceContext (Gap-2), and env scopes into BoxLang context")
    void IT06_realAdapterPopulatesAllContextScopes() throws Exception {
        String funcName = "MyAzureFunction";
        String invId    = "meta-inv-9999";
        ExecutionContext ctx = buildContext(invId, funcName);
        HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/meta", "");

        when(mockExecutor.executeScript(anyString(), any(), anyMap()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> boxCtx = (Map<String, Object>) inv.getArgument(2);

                    // azure scope
                    @SuppressWarnings("unchecked")
                    Map<String, String> azure = (Map<String, String>) boxCtx.get("azure");
                    assertNotNull(azure);
                    assertEquals(funcName, azure.get("functionName"));
                    assertEquals(invId,    azure.get("invocationId"));

                    @SuppressWarnings("unchecked")
                    Map<String, String> trace = (Map<String, String>) boxCtx.get("traceContext");
                    assertNotNull(trace);
                    assertEquals(invId,    trace.get("invocationId"));
                    assertEquals(funcName, trace.get("functionName"));

                    // env scope
                    @SuppressWarnings("unchecked")
                    Map<String, String> env = (Map<String, String>) boxCtx.get("env");
                    assertNotNull(env);
                    assertEquals(System.getenv().size(), env.size());

                    return result(200, "{\"ok\":true}");
                });

        assertNotNull(handler.run(req, ctx));
        verify(req).createResponseBuilder(HttpStatus.valueOf(200));
    }

    // ===================================================================
    // IT-07  Multipart — raw body AND parsedBody field (Gap 3)
    // ===================================================================

    @Test
    @DisplayName("IT-07 + Gap-3: Real mapper stores raw body and extracts multipart field into parsedBody")
    void IT07_multipartRawBodyAndParsedFieldBothVerified() throws Exception {
        String b64       = Base64.getEncoder().encodeToString("file-content".getBytes());
        String multipart =
                "--boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
                "Content-Type: text/plain\r\n\r\n" +
                b64 + "\r\n" +
                "--boundary--";

        HttpRequestMessage<Optional<String>> req =
                buildPostRequest("/api/upload", multipart,
                        "multipart/form-data; boundary=boundary");

        when(mockExecutor.executeScript(anyString(), argThat(d -> {
            Object rawBody = d.get("body");
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) d.get("parsedBody");
            return rawBody != null
                    && rawBody.toString().contains("filename=\"test.txt\"")
                    && parsed != null
                    && parsed.containsKey("file");
        }), any())).thenReturn(result(200, "{\"uploaded\":true}"));

        assertNotNull(handler.run(req, sharedContext));
        verify(req).createResponseBuilder(HttpStatus.valueOf(200));
    }

    // ===================================================================
    // IT-08  Query Parameters
    // ===================================================================

    @Test
    @DisplayName("IT-08a: Repeated query param → List<String> (real mapper coalesces repeated keys)")
    void IT08a_multiValueQueryParam_storedAsList() throws Exception {
        HttpRequestMessage<Optional<String>> req =
                buildGetRequest("/api/filter", "tag=java&tag=azure&tag=boxlang");

        when(mockExecutor.executeScript(anyString(), argThat(d -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> qp = (Map<String, Object>) d.get("queryParams");
            if (!(qp != null && qp.get("tag") instanceof List)) return false;
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) qp.get("tag");
            return tags.size() == 3
                    && tags.contains("java")
                    && tags.contains("azure")
                    && tags.contains("boxlang");
        }), any())).thenReturn(result(200, "{}"));

        assertNotNull(handler.run(req, sharedContext));
        verify(req).createResponseBuilder(HttpStatus.valueOf(200));
    }

    @Test
    @DisplayName("IT-08b: Percent-encoded and plus-encoded query values decoded by real mapper")
    void IT08b_urlEncodedQueryParams_decoded() throws Exception {
        HttpRequestMessage<Optional<String>> req =
                buildGetRequest("/api/decode", "name=hello+world&path=%2Fapi%2Ftest");

        when(mockExecutor.executeScript(anyString(), argThat(d -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> qp = (Map<String, Object>) d.get("queryParams");
            return qp != null
                    && "hello world".equals(qp.get("name"))
                    && "/api/test".equals(qp.get("path"));
        }), any())).thenReturn(result(200, "{}"));

        assertNotNull(handler.run(req, sharedContext));
        verify(req).createResponseBuilder(HttpStatus.valueOf(200));
    }

    // ===================================================================
    // IT-09  Custom Headers
    // ===================================================================

    @Test
    @DisplayName("IT-09a: Real mapper lower-cases all inbound header keys")
    void IT09a_inboundHeaders_lowercasedByRealMapper() throws Exception {
        Map<String, String> inbound = new HashMap<>();
        inbound.put("Authorization",    "Bearer token");
        inbound.put("X-Correlation-Id", "corr-001");
        inbound.put("Accept",           "application/json");
        HttpRequestMessage<Optional<String>> req =
                buildGetRequestWithHeaders("/api/secure", "", inbound);

        when(mockExecutor.executeScript(anyString(), argThat(d -> {
            @SuppressWarnings("unchecked")
            Map<String, String> h = (Map<String, String>) d.get("headers");
            return h != null
                    && h.containsKey("authorization")
                    && h.containsKey("x-correlation-id")
                    && h.containsKey("accept")
                    && !h.containsKey("Authorization"); // original casing must be gone
        }), any())).thenReturn(result(200, "{}"));

        assertNotNull(handler.run(req, sharedContext));
        verify(req).createResponseBuilder(HttpStatus.valueOf(200));
    }

    @Test
    @DisplayName("IT-09b: Custom response headers from BoxLang script are set on the Azure response")
    void IT09b_customResponseHeaders_setOnAzureResponse() throws Exception {
        HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/headers", "");

        Map<String, Object> sr = result(200, "{}");
        sr.put("headers", Map.of(
                "X-Powered-By",  "BoxLang",
                "Cache-Control", "no-cache",
                "X-Request-Id",  "req-001"));
        when(mockExecutor.executeScript(anyString(), any(), any())).thenReturn(sr);

        handler.run(req, sharedContext);
        HttpResponseMessage.Builder b = getBuilderFor(req);
        verify(b).header("X-Powered-By",  "BoxLang");
        verify(b).header("Cache-Control", "no-cache");
        verify(b).header("X-Request-Id",  "req-001");
    }

    // ===================================================================
    // IT-10  X-Invocation-Id on every successful response
    // ===================================================================

    @Test
    @DisplayName("IT-10: X-Invocation-Id header is present on every successful response")
    void IT10_invocationId_presentOnSuccessResponse() throws Exception {
        String invId = "check-42";
        ExecutionContext ctx = buildContext(invId, "E2EFunction");
        HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/ping", "");

        when(mockExecutor.executeScript(anyString(), any(), any()))
                .thenReturn(result(200, "{\"pong\":true}"));

        handler.run(req, ctx);
        verify(getBuilderFor(req)).header("X-Invocation-Id", invId);
    }

    // ===================================================================
    // IT-11  Content-Type negotiation — both branches in extractContentType()
    // ===================================================================

    @Test
    @DisplayName("IT-11a: Lower-case 'content-type' key → primary branch in extractContentType")
    void IT11a_lowercaseContentType_primaryBranch() throws Exception {
        HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/feed", "");
        Map<String, Object> sr = result(200, "<?xml?><root/>");
        sr.put("headers", Map.of("content-type", "application/xml"));
        when(mockExecutor.executeScript(anyString(), any(), any())).thenReturn(sr);

        handler.run(req, sharedContext);
        // buildSuccessResponse sets Content-Type once; the headers loop may set it again
        verify(getBuilderFor(req), atLeastOnce()).header("Content-Type", "application/xml");
    }

    @Test
    @DisplayName("IT-11b: Title-case 'Content-Type' key → fallback branch in extractContentType")
    void IT11b_titleCaseContentType_fallbackBranch() throws Exception {
        HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/page", "");
        Map<String, Object> sr = result(200, "<html/>");
        sr.put("headers", Map.of("Content-Type", "text/html; charset=utf-8"));
        when(mockExecutor.executeScript(anyString(), any(), any())).thenReturn(sr);

        handler.run(req, sharedContext);
        verify(getBuilderFor(req), atLeastOnce())
                .header("Content-Type", "text/html; charset=utf-8");
    }

    // ===================================================================
    // IT-12  CGI variables
    // ===================================================================

    @Test
    @DisplayName("IT-12: Real mapper populates REQUEST_METHOD, PATH_INFO, QUERY_STRING, HTTPS='on'")
    void IT12_cgiVariables_populatedByRealMapper() throws Exception {
        HttpRequestMessage<Optional<String>> req =
                buildGetRequest("/api/cgi", "debug=true");

        when(mockExecutor.executeScript(anyString(), argThat(d -> {
            @SuppressWarnings("unchecked")
            Map<String, String> cgi = (Map<String, String>) d.get("cgiVariables");
            return cgi != null
                    && "GET".equals(cgi.get("REQUEST_METHOD"))
                    && cgi.containsKey("QUERY_STRING")
                    && cgi.containsKey("PATH_INFO")
                    && "on".equals(cgi.get("HTTPS")); // Azure always terminates TLS
        }), any())).thenReturn(result(200, "{}"));

        assertNotNull(handler.run(req, sharedContext));
        verify(req).createResponseBuilder(HttpStatus.valueOf(200));
    }

    // ===================================================================
    // IT-13  Null ExecutionContext — graceful degradation
    // ===================================================================

    @Test
    @DisplayName("IT-13: Null context → real adapter throws NPE; handler returns 500 with invocationId='unknown'")
    void IT13_nullContext_returns500WithUnknownInvocationId() throws Exception {
        // Real AzureContextAdapter.createBoxLangContext(null) calls Objects.requireNonNull → NPE.
        // The handler's generic catch(Exception) returns 500.
        // safeInvocationId(null) runs before the try block and produces "unknown".
        HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/ping", "");

        assertDoesNotThrow(() -> handler.run(req, null));
        verify(req).createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR);
        verify(getBuilderFor(req)).header("X-Invocation-Id", "unknown");
        verify(getBuilderFor(req)).body(argThat(b ->
                b != null && b.toString().contains("\"error\"")));
    }

    // ===================================================================
    // IT-14  Form-encoded body
    // ===================================================================

    @Test
    @DisplayName("IT-14: Real mapper parses application/x-www-form-urlencoded body into parsedBody")
    void IT14_formEncodedBody_parsedByRealMapper() throws Exception {
        HttpRequestMessage<Optional<String>> req =
                buildPostRequest("/api/form", "username=bob&role=admin&active=true",
                        "application/x-www-form-urlencoded");

        when(mockExecutor.executeScript(anyString(), argThat(d -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) d.get("parsedBody");
            return p != null
                    && "bob".equals(p.get("username"))
                    && "admin".equals(p.get("role"))
                    && "true".equals(p.get("active"));
        }), any())).thenReturn(result(200, "{}"));

        assertNotNull(handler.run(req, sharedContext));
        verify(req).createResponseBuilder(HttpStatus.valueOf(200));
    }

    // ===================================================================
    // IT-15  JSON parsedBody
    // ===================================================================

    @Test
    @DisplayName("IT-15: Real mapper parses flat application/json body into parsedBody map")
    void IT15_jsonBody_parsedByRealMapper() throws Exception {
        HttpRequestMessage<Optional<String>> req =
                buildPostRequest("/api/create",
                        "{\"name\":\"Alice\",\"age\":\"30\"}", "application/json");

        when(mockExecutor.executeScript(anyString(), argThat(d -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) d.get("parsedBody");
            return p != null
                    && "Alice".equals(p.get("name"))
                    && "30".equals(p.get("age"));
        }), any())).thenReturn(result(201, "{\"id\":1}"));

        assertNotNull(handler.run(req, sharedContext));
        verify(req).createResponseBuilder(HttpStatus.valueOf(201));
    }

    @Test
    @DisplayName("Gap-1: JSON body with escaped quotes — no crash; raw body intact; parsedBody truncated as documented")
    void GAP1_jsonEscapedQuotes_noCrash_rawBodyIntact_parsedBodyTruncated() throws Exception {
        String escapedJson = "{\"msg\":\"say \\\"hi\\\"\"}"; // JSON: {"msg":"say \"hi\""}

        // Call the real mapper directly — no stub, no handler.run() needed
        @SuppressWarnings("unchecked")
        HttpRequestMessage<Optional<String>> req = mock(HttpRequestMessage.class);
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        when(req.getUri()).thenReturn(new URI(BASE_HOST + "/api/echo"));
        when(req.getHttpMethod()).thenReturn(HttpMethod.POST);
        when(req.getHeaders()).thenReturn(headers);
        when(req.getBody()).thenReturn(Optional.of(escapedJson));

        Map<String, Object> mapped = assertDoesNotThrow(
                () -> realMapper.mapHttpRequest(req),
                "Real mapper must not throw for escaped-quote JSON");

        // (b) raw body intact
        assertEquals(escapedJson, mapped.get("body"),
                "Raw body must be preserved regardless of parse quality");

        // (c) parsedBody exists but value is wrong — documented limitation is observable
        @SuppressWarnings("unchecked")
        Map<String, Object> parsedBody = (Map<String, Object>) mapped.get("parsedBody");
        assertNotNull(parsedBody, "parsedBody map must not be null");
        assertNotEquals("say \"hi\"", parsedBody.get("msg"),
                "Documented limitation: escaped-quote value is truncated by the real tokeniser");
    }

    @Test
    @DisplayName("Gap-4: Real resolveScriptPath() preserves existing extension — .cfm never appended with .bx")
    void GAP4_existingExtension_notOverwrittenWithBx() throws Exception {
        BoxLangAzureFunctionHandler freshHandler = spy(
                new BoxLangAzureFunctionHandler(mockExecutor, realMapper, realContextAdapter));

        HttpRequestMessage<Optional<String>> req = buildGetRequest("/api/render.cfm", "");

        when(mockExecutor.executeScript(eq("render.cfm"), any(), any()))
                .thenReturn(result(200, "<html/>"));

        assertNotNull(freshHandler.run(req, sharedContext));
        verify(mockExecutor).executeScript(eq("render.cfm"), any(), any());
        verify(mockExecutor, never()).executeScript(eq("render.cfm.bx"), any(), any());
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private HttpRequestMessage<Optional<String>> buildGetRequest(
            String path, String query) throws Exception {
        return buildGetRequestWithHeaders(path, query, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    private HttpRequestMessage<Optional<String>> buildGetRequestWithHeaders(
            String path, String query, Map<String, String> headers) throws Exception {
        HttpRequestMessage<Optional<String>> req = mock(HttpRequestMessage.class);
        String uri = BASE_HOST + path + (query == null || query.isBlank() ? "" : "?" + query);
        lenient().when(req.getHttpMethod()).thenReturn(HttpMethod.GET);
        lenient().when(req.getUri()).thenReturn(new URI(uri));
        lenient().when(req.getHeaders()).thenReturn(headers);
        lenient().when(req.getBody()).thenReturn(Optional.empty());
        wireResponseBuilder(req);
        return req;
    }

    @SuppressWarnings("unchecked")
    private HttpRequestMessage<Optional<String>> buildPostRequest(
            String path, String body, String contentType) throws Exception {
        HttpRequestMessage<Optional<String>> req = mock(HttpRequestMessage.class);
        Map<String, String> headers = new HashMap<>();
        if (contentType != null) headers.put("content-type", contentType);
        lenient().when(req.getHttpMethod()).thenReturn(HttpMethod.POST);
        lenient().when(req.getUri()).thenReturn(new URI(BASE_HOST + path));
        lenient().when(req.getHeaders()).thenReturn(headers);
        lenient().when(req.getBody())
                .thenReturn(body != null ? Optional.of(body) : Optional.empty());
        wireResponseBuilder(req);
        return req;
    }

    @SuppressWarnings("unchecked")
    private HttpRequestMessage<Optional<String>> buildRequestWithMethod(
            String path, HttpMethod method, String body, String contentType) throws Exception {
        HttpRequestMessage<Optional<String>> req = mock(HttpRequestMessage.class);
        Map<String, String> headers = new HashMap<>();
        if (contentType != null) headers.put("content-type", contentType);
        lenient().when(req.getHttpMethod()).thenReturn(method);
        lenient().when(req.getUri()).thenReturn(new URI(BASE_HOST + path));
        lenient().when(req.getHeaders()).thenReturn(headers);
        lenient().when(req.getBody())
                .thenReturn(body != null ? Optional.of(body) : Optional.empty());
        wireResponseBuilder(req);
        return req;
    }

    private void wireResponseBuilder(HttpRequestMessage<Optional<String>> req) {
        HttpResponseMessage.Builder b = mock(HttpResponseMessage.Builder.class);
        HttpResponseMessage          r = mock(HttpResponseMessage.class);
        builderRegistry.put(req, b);
        lenient().when(req.createResponseBuilder(any(HttpStatus.class))).thenReturn(b);
        lenient().when(b.header(anyString(), anyString())).thenReturn(b);
        lenient().when(b.body(any())).thenReturn(b);
        lenient().when(b.build()).thenReturn(r);
    }

    private HttpResponseMessage.Builder getBuilderFor(
            HttpRequestMessage<Optional<String>> req) {
        HttpResponseMessage.Builder b = builderRegistry.get(req);
        assertNotNull(b, "No builder registered — was wireResponseBuilder() called?");
        return b;
    }

    /**
     * Real AzureContextAdapter calls getInvocationId(), getFunctionName(), and getLogger()
     * on every invocation — all three must be stubbed.
     */
    private ExecutionContext buildContext(String invocationId, String functionName) {
        ExecutionContext ctx = mock(ExecutionContext.class);
        lenient().when(ctx.getInvocationId()).thenReturn(invocationId);
        lenient().when(ctx.getFunctionName()).thenReturn(functionName);
        lenient().when(ctx.getLogger())
                .thenReturn(Logger.getLogger("integration." + functionName));
        return ctx;
    }

    private static Map<String, Object> result(int statusCode, String body) {
        Map<String, Object> m = new HashMap<>();
        m.put("statusCode", statusCode);
        m.put("body",       body);
        return m;
    }
}