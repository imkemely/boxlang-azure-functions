package ortus.boxlang.runtime.azure;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AzureRequestMapper.
 * Covers 12 required test cases at 90%+ coverage target.
 */
@ExtendWith(MockitoExtension.class)
class AzureRequestMapperTest {

    @Mock private HttpRequestMessage<Optional<String>> request;

    private AzureRequestMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new AzureRequestMapper();

        // Sensible defaults — individual tests override what they need
        lenient().when(request.getHttpMethod()).thenReturn(HttpMethod.GET);
        lenient().when(request.getUri()).thenReturn(
                new URI("https://func.azurewebsites.net/api/hello"));
        lenient().when(request.getHeaders()).thenReturn(new HashMap<>());
        lenient().when(request.getBody()).thenReturn(Optional.empty());
    }

    // ---------------------------------------------------------------
    // RM-01  HTTP method is extracted and uppercased
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-01: HTTP method is extracted and stored in upper case")
    void RM01_httpMethod_extractedAndUpperCased() {
        when(request.getHttpMethod()).thenReturn(HttpMethod.POST);

        Map<String, Object> result = mapper.mapHttpRequest(request);

        assertEquals("POST", result.get("method"), "Method must be upper-case POST");
    }

    // ---------------------------------------------------------------
    // RM-02  URI, path, and queryString are present
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-02: URI, path, and queryString are extracted correctly")
    void RM02_uriPathAndQueryString_extracted() throws Exception {
        when(request.getUri()).thenReturn(
                new URI("https://func.azurewebsites.net/api/users?page=1&limit=10"));

        Map<String, Object> result = mapper.mapHttpRequest(request);

        assertEquals("https://func.azurewebsites.net/api/users?page=1&limit=10",
                result.get("uri"), "Full URI must be stored");
        assertEquals("/api/users", result.get("path"), "Path must exclude query string");
        assertEquals("page=1&limit=10", result.get("queryString"),
                "Query string must be stored separately");
    }

    // ---------------------------------------------------------------
    // RM-03  Headers are normalised to lower-case keys
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-03: Request headers are normalised to lower-case keys")
    void RM03_headers_normalisedToLowerCase() {
        Map<String, String> rawHeaders = new HashMap<>();
        rawHeaders.put("Content-Type", "application/json");
        rawHeaders.put("Authorization", "Bearer token");
        rawHeaders.put("X-Custom-Header", "value");
        when(request.getHeaders()).thenReturn(rawHeaders);

        Map<String, Object> result = mapper.mapHttpRequest(request);

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) result.get("headers");

        assertTrue(headers.containsKey("content-type"),    "content-type must be lower-case");
        assertTrue(headers.containsKey("authorization"),   "authorization must be lower-case");
        assertTrue(headers.containsKey("x-custom-header"), "x-custom-header must be lower-case");
        assertFalse(headers.containsKey("Content-Type"),   "Mixed-case key must not be present");
    }

    // ---------------------------------------------------------------
    // RM-04  Query parameters are parsed into a map
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-04: Query parameters are parsed into the queryParams map")
    void RM04_queryParams_parsedIntoMap() throws Exception {
        when(request.getUri()).thenReturn(
                new URI("https://host/api/search?q=boxlang&page=2&sort=name"));

        Map<String, Object> result = mapper.mapHttpRequest(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> qp = (Map<String, Object>) result.get("queryParams");

        assertEquals("boxlang", qp.get("q"));
        assertEquals("2",       qp.get("page"));
        assertEquals("name",    qp.get("sort"));
    }

    // ---------------------------------------------------------------
    // RM-05  Multi-value query params are stored as a List
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-05: Multi-value query parameters are stored as a List")
    void RM05_multiValueQueryParam_storedAsList() {
        Map<String, Object> qp = mapper.parseQueryString("tag=java&tag=azure&tag=boxlang");

        Object tagValue = qp.get("tag");
        assertInstanceOf(List.class, tagValue,
                "Multi-value param must be stored as a List, not a String");

        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) tagValue;
        assertEquals(3, tags.size(), "Must have 3 tag values");
        assertTrue(tags.contains("java"));
        assertTrue(tags.contains("azure"));
        assertTrue(tags.contains("boxlang"));
    }

    // ---------------------------------------------------------------
    // RM-06  Request body is extracted
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-06: Request body is extracted and stored under 'body' key")
    void RM06_requestBody_extracted() {
        when(request.getBody()).thenReturn(Optional.of("{\"name\":\"Alice\"}"));

        Map<String, Object> result = mapper.mapHttpRequest(request);

        assertEquals("{\"name\":\"Alice\"}", result.get("body"),
                "Body must be stored as-is under the 'body' key");
    }

    // ---------------------------------------------------------------
    // RM-07  Empty body stored as null / empty string — does not crash
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-07: Empty body does not cause a NullPointerException")
    void RM07_emptyBody_doesNotCrash() {
        when(request.getBody()).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> mapper.mapHttpRequest(request),
                "Mapping a request with no body must not throw");

        Map<String, Object> result = mapper.mapHttpRequest(request);
        assertNotNull(result, "Result must not be null even with empty body");
    }

    // ---------------------------------------------------------------
    // RM-08  JSON body is parsed into parsedBody map
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-08: JSON body is parsed into the parsedBody map")
    void RM08_jsonBody_parsedIntoParsedBody() {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getBody()).thenReturn(Optional.of("{\"name\":\"Alice\",\"age\":\"30\"}"));

        Map<String, Object> result = mapper.mapHttpRequest(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) result.get("parsedBody");

        assertNotNull(parsed, "parsedBody must not be null for JSON content");
        assertEquals("Alice", parsed.get("name"));
        assertEquals("30",    parsed.get("age"));
    }

    // ---------------------------------------------------------------
    // RM-09  Form-encoded body is parsed into parsedBody
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-09: URL-encoded form body is parsed into parsedBody")
    void RM09_formEncodedBody_parsed() {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/x-www-form-urlencoded");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getBody()).thenReturn(Optional.of("username=bob&role=admin&active=true"));

        Map<String, Object> result = mapper.mapHttpRequest(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) result.get("parsedBody");

        assertNotNull(parsed);
        assertEquals("bob",   parsed.get("username"));
        assertEquals("admin", parsed.get("role"));
        assertEquals("true",  parsed.get("active"));
    }

    // ---------------------------------------------------------------
    // RM-10  CGI variables are populated
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-10: CGI variables map contains REQUEST_METHOD, PATH_INFO, and QUERY_STRING")
    void RM10_cgiVariables_populated() throws Exception {
        when(request.getHttpMethod()).thenReturn(HttpMethod.GET);
        when(request.getUri()).thenReturn(
                new URI("https://func.azurewebsites.net/api/users?id=5"));

        Map<String, Object> result = mapper.mapHttpRequest(request);

        @SuppressWarnings("unchecked")
        Map<String, String> cgi = (Map<String, String>) result.get("cgiVariables");

        assertNotNull(cgi, "cgiVariables must not be null");
        assertEquals("GET",    cgi.get("REQUEST_METHOD"));
        assertEquals("id=5",   cgi.get("QUERY_STRING"));
        assertTrue(cgi.containsKey("PATH_INFO"), "CGI must contain PATH_INFO");
    }

    // ---------------------------------------------------------------
    // RM-11  mergePathParameters adds pathParams to the mapped request
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-11: mergePathParameters adds route params to the mapped request")
    void RM11_mergePathParameters_addsToMap() {
        Map<String, Object> mappedRequest = new HashMap<>();

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("id",   "42");
        pathParams.put("type", "user");

        mapper.mergePathParameters(mappedRequest, pathParams);

        @SuppressWarnings("unchecked")
        Map<String, String> stored = (Map<String, String>) mappedRequest.get("pathParams");

        assertNotNull(stored);
        assertEquals("42",   stored.get("id"));
        assertEquals("user", stored.get("type"));
    }

    @Test
    @DisplayName("RM-11 (variant): mergePathParameters with null params stores empty map")
    void RM11_mergePathParameters_nullInput_storesEmptyMap() {
        Map<String, Object> mappedRequest = new HashMap<>();
        mapper.mergePathParameters(mappedRequest, null);

        @SuppressWarnings("unchecked")
        Map<String, String> stored = (Map<String, String>) mappedRequest.get("pathParams");
        assertNotNull(stored);
        assertTrue(stored.isEmpty());
    }

    // ---------------------------------------------------------------
    // RM-12  Null request throws NullPointerException
    // ---------------------------------------------------------------

    @Test
    @DisplayName("RM-12: Null request throws NullPointerException")
    void RM12_nullRequest_throwsNPE() {
        assertThrows(NullPointerException.class,
                () -> mapper.mapHttpRequest(null),
                "Null request must throw NullPointerException");
    }

    // ---------------------------------------------------------------
    // Null URI is handled gracefully
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Null URI is handled gracefully — path defaults to '/'")
    void nullUri_handledGracefully() {
        when(request.getUri()).thenReturn(null);

        assertDoesNotThrow(() -> mapper.mapHttpRequest(request));

        Map<String, Object> result = mapper.mapHttpRequest(request);
        assertEquals("/", result.get("path"), "Null URI must default path to '/'");
    }

    // ---------------------------------------------------------------
    // Null HttpMethod defaults to GET
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Null HttpMethod defaults to GET")
    void nullHttpMethod_defaultsToGet() {
        when(request.getHttpMethod()).thenReturn(null);

        Map<String, Object> result = mapper.mapHttpRequest(request);

        assertEquals("GET", result.get("method"), "Null method must default to GET");
    }

    // ---------------------------------------------------------------
    // parseQueryString — empty and null inputs
    // ---------------------------------------------------------------

    @Test
    @DisplayName("parseQueryString: null query string returns empty map")
    void parseQueryString_null_returnsEmptyMap() {
        Map<String, Object> result = mapper.parseQueryString(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("parseQueryString: URL-encoded values are decoded")
    void parseQueryString_urlEncodedValues_decoded() {
        Map<String, Object> result = mapper.parseQueryString("name=hello+world&path=%2Fapi%2Ftest");
        assertEquals("hello world", result.get("name"), "Plus sign must decode to space");
        assertEquals("/api/test",   result.get("path"),  "Percent-encoding must be decoded");
    }
}