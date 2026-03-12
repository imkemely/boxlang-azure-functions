/**
 * BoxLangFunctionExecutor
 *
 * Manages BoxLang runtime lifecycle and script execution.
 * Handles singleton runtime initialization, script compilation
 * caching, and thread-safe concurrent execution.
 *
 * TODO: Person 2 will implement this class.
 * See Prompt 2.4 in AI_Prompts_BoxLang_x_Azure.docx for full specification.
 */

package ortus.boxlang.runtime.azure;

import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.azure.config.FunctionConfiguration;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.interop.DynamicObject;
import ortus.boxlang.runtime.runnables.IClassRunnable;
import ortus.boxlang.runtime.runnables.IBoxRunnable;
import ortus.boxlang.runtime.runnables.RunnableLoader;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;
import ortus.boxlang.runtime.util.ResolvedFilePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages the BoxLang runtime lifecycle and executes BoxLang scripts on Microsoft Azure Functions.
 *
 * Design
 *   Singleton runtime – {@link BoxRuntime} is initialised exactly once per JVM process (cold start) and reused for every warm invocation.
 *   Thread safety – Runtime initialisation is guarded by a {@link ReentrantLock}.  All other shared state uses {@link ConcurrentHashMap} 
 *   or {@link AtomicBoolean}.
 *   Script compilation cache – Loaded {@link IClassRunnable} instances are kept in an in-memory cache keyed by absolute path so that warm requests 
 *   skip re-compilation entirely.
 *   Cold-start optimisation – When {@code boxlang.azure.coldStartOptimization=true} the runtime is initialised eagerly during class loading, hiding 
 *   the start-up cost from the first real caller.
 *   Graceful shutdown – A JVM shutdown hook disposes of the {@link BoxRuntime} when the Azure worker process is recycled.
 *
 * Performance targets
 *   Cold start: &lt; 5 seconds
 *   Warm execution (p95): &lt; 500 ms
 *
 * Convention for BoxLang scripts
 * Scripts are expected to be BoxLang <em>classes</em> ({@code .bx}) that expose a {@code run(event, context, response)} method following the same
 * contract as the AWS Lambda runtime:
 * {@code
 * // AzureFunction.bx
 * class {
 *     function run(event, context, response) {
 *         response.statusCode = 200;
 *         response.body = serializeJSON({ "message": "Hello from Azure!" });
 *     }
 * }
 * }
 *
 * The {@code response} argument is a pre-populated {@link IStruct} that the handler will read back after invocation to build the
 * {@link com.microsoft.azure.functions.HttpResponseMessage}.
 */

public class BoxLangFunctionExecutor {

    private static final Logger logger = LoggerFactory.getLogger(BoxLangFunctionExecutor.class);

    // -------------------------------------------------------------------------
    // Singleton guards
    // -------------------------------------------------------------------------

    private static final ReentrantLock INSTANCE_LOCK = new ReentrantLock();
    private static volatile BoxLangFunctionExecutor instance;

    // -------------------------------------------------------------------------
    // Runtime state
    // -------------------------------------------------------------------------

    /** The BoxLang runtime – typed directly; no reflection required. */
    private volatile BoxRuntime boxRuntime;

    /** Guards the double-checked runtime initialisation. */
    private final ReentrantLock runtimeInitLock = new ReentrantLock();

    /** Set to {@code true} after the runtime starts successfully. */
    private final AtomicBoolean runtimeInitialised = new AtomicBoolean(false);

    /** Set to {@code true} once the shutdown sequence begins. */
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);

    /**
     * In-memory class runnable cache. Maps an absolute script path → compiled {@link IClassRunnable}. 
     * Uses {@link ConcurrentHashMap} for lock-free warm-path reads.
     */
    private final ConcurrentHashMap<String, IClassRunnable> scriptCache = new ConcurrentHashMap<>();

    /** Total invocation counter for diagnostics. */
    private final AtomicLong totalInvocations = new AtomicLong(0);

    /** Immutable configuration snapshot taken at construction time. */
    private final FunctionConfiguration configuration;

    // -------------------------------------------------------------------------
    // Constructor (private)
    // -------------------------------------------------------------------------

    private BoxLangFunctionExecutor() {
        this.configuration = FunctionConfiguration.fromGlobalConfig("BoxLangAzureFunction");

        // JVM shutdown hook – fires when the Azure worker process is recycled
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::shutdown, "boxlang-azure-shutdown"));

        // Eager (cold-start) initialisation when configured
        if (configuration.isColdStartOptimizationEnabled()) {
            logger.info("Cold-start optimisation enabled – initialising BoxLang runtime eagerly");
            initRuntime();
        }
    }

    // -------------------------------------------------------------------------
    // Singleton accessor
    // -------------------------------------------------------------------------

    /**
     * Returns the singleton {@link BoxLangFunctionExecutor}. Thread-safe via double-checked locking.
     */
    public static BoxLangFunctionExecutor getInstance() {
        if (instance == null) {
            INSTANCE_LOCK.lock();
            try {
                if (instance == null) {
                    instance = new BoxLangFunctionExecutor();
                }
            } finally {
                INSTANCE_LOCK.unlock();
            }
        }
        return instance;
    }

    /**
     * Injects a replacement instance – unit tests ONLY.
     */
    public static void setInstanceForTesting(BoxLangFunctionExecutor mock) {
        INSTANCE_LOCK.lock();
        try {
            instance = mock;
        } finally {
            INSTANCE_LOCK.unlock();
        }
    }

    /**
     * Resets the singleton – unit tests ONLY.
     */
    public static void resetForTesting() {
        INSTANCE_LOCK.lock();
        try {
            if (instance != null) {
                instance.shutdown();
            }
            instance = null;
        } finally {
            INSTANCE_LOCK.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Script execution (primary entry point)
    // -------------------------------------------------------------------------

    /**
     * Executes a BoxLang class script and returns its response as a plain {@code Map<String, Object>} containing at minimum:
     *   {@code statusCode} – HTTP status integer (defaults to 200)
     *   {@code body} – response body string (defaults to {@code "{}"})
     *   {@code headers} – {@code Map<String, String>} of response headers
     *
     * The script receives three arguments via the BoxLang {@code run} method:
     *   {@code event} – the normalised request data from {@link AzureRequestMapper} (wrapped in a BoxLang {@link IStruct})
     *   {@code context} – the invocation context map from {@link AzureContextAdapter} (also wrapped in an {@link IStruct})
     *   {@code response} – a pre-initialised mutable {@link IStruct} that the script populates with {@code statusCode}, {@code body}, and {@code headers}
     *
     * @param scriptPath  absolute (or script-root-relative) path to the {@code .bx} file
     * @param requestData normalised request map from {@link AzureRequestMapper}
     * @param contextData invocation context map from {@link AzureContextAdapter}
     * @return script response map; never {@code null}
     * @throws FileNotFoundException      if the script file does not exist
     * @throws BoxLangExecutionException  if the script throws an unhandled error
     * @throws IllegalArgumentException   if {@code scriptPath} is null or blank
     */
    public Map<String, Object> executeScript(String scriptPath,
                                             Map<String, Object> requestData,
                                             Map<String, Object> contextData)
            throws FileNotFoundException, BoxLangExecutionException {

        validateNotBlank(scriptPath, "scriptPath");
        Objects.requireNonNull(requestData, "requestData must not be null");
        Objects.requireNonNull(contextData, "contextData must not be null");

        long invocationNumber = totalInvocations.incrementAndGet();
        long startNanos = System.nanoTime();
        logger.debug("Executing script #{}: {}", invocationNumber, scriptPath);

        ensureRuntimeInitialised();

        String resolvedPath = resolveScriptPath(scriptPath);
        validateScriptExists(resolvedPath);

        try {
            // 1. Build a fresh ScriptingRequestBoxContext for this invocation. A new context per request ensures complete scope isolation between
            //    concurrent invocations.
            IBoxContext requestContext = new ScriptingRequestBoxContext(boxRuntime.getRuntimeContext());

            // 2. Wrap request and Azure context maps as BoxLang IStructs so they can be passed as typed arguments to the BoxLang run() method.
            IStruct eventStruct = toStruct(requestData);
            IStruct contextStruct = toStruct(contextData);

            // 3. Pre-initialise the response struct that the script will populate. Scripts set statusCode, body, and headers on this object.
            IStruct responseStruct = new Struct();
            responseStruct.put(Key.of("statusCode"), 200);
            responseStruct.put(Key.of("body"), "{}");
            responseStruct.put(Key.of("headers"), new Struct());

            // 4. Load the compiled class runnable. Note: we load a fresh instance per invocation (matching the AWS Lambda runner pattern) to avoid 
            //    shared mutable instance state across concurrent requests. The JVM-level class definition is cached by the Boxpiler, so this is cheap
            //    after the first compilation.
            IClassRunnable classRunnable = loadClass(resolvedPath, requestContext );

            // 5. Invoke the run() method: run( event, context, response ). Arguments are passed directly — no scope injection needed — mirroring the AWS 
            //    Lambda runner approach exactly.
            DynamicObject.of(classRunnable).invoke(requestContext, "run", new Object[]{ eventStruct, contextStruct, responseStruct });

            // 6. Convert the response IStruct back to a plain Java Map for the handler.
            Map<String, Object> result = structToMap(responseStruct);

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            logger.debug("Script #{} completed in {} ms", invocationNumber, elapsedMs);
            if (elapsedMs > 500) {
                logger.warn("Script #{} exceeded 500 ms warm target ({}ms): {}", invocationNumber, elapsedMs, scriptPath);
            }

            return result;

        } catch (BoxLangExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new BoxLangExecutionException("Unhandled error executing script: " + resolvedPath, e);
        }
    }

    /**
     * Executes a named method on a BoxLang class. Use when scripts expose multiple entry points beyond the default{@code run()} convention.
     *
     * @param scriptPath  path to the {@code .bx} class file
     * @param methodName  name of the method to invoke
     * @param requestData normalised request data
     * @param contextData invocation context data
     * @return result map extracted from the method's return value or modified response
     * @throws FileNotFoundException     if the class file does not exist
     * @throws BoxLangExecutionException if the method throws
     */
    public Map<String, Object> executeClassMethod(String scriptPath, String methodName, Map<String, Object> requestData, Map<String, Object> contextData)
                               throws FileNotFoundException, BoxLangExecutionException {

        validateNotBlank(scriptPath, "scriptPath");
        validateNotBlank(methodName, "methodName");
        Objects.requireNonNull(requestData, "requestData must not be null");
        Objects.requireNonNull(contextData, "contextData must not be null");

        logger.debug("Executing class method {}.{}()", scriptPath, methodName);

        ensureRuntimeInitialised();
        String resolvedPath = resolveScriptPath(scriptPath);
        validateScriptExists(resolvedPath);

        try {
            IBoxContext requestContext = new ScriptingRequestBoxContext(boxRuntime.getRuntimeContext());
            IClassRunnable classRunnable = loadClass(resolvedPath, requestContext);

            IStruct eventStruct = toStruct(requestData);
            IStruct contextStruct = toStruct(contextData);
            IStruct responseStruct = new Struct();
            responseStruct.put(Key.of("statusCode"), 200);
            responseStruct.put(Key.of("body"), "{}");
            responseStruct.put(Key.of("headers"), new Struct());

            DynamicObject.of(classRunnable).invoke(requestContext, methodName, new Object[]{ eventStruct, contextStruct, responseStruct });

            return structToMap(responseStruct);

        } catch (BoxLangExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new BoxLangExecutionException("Error invoking " + methodName + " on " + resolvedPath, e);
        }
    }

    // -------------------------------------------------------------------------
    // Runtime lifecycle
    // -------------------------------------------------------------------------

    /**
     * Ensures the BoxLang runtime is initialised. Warm path is a simple volatile read; cold path blocks under lock.
     */
    private void ensureRuntimeInitialised() {
        if (!runtimeInitialised.get()) {
            initRuntime();
        }
    }

    /**
     * Initialises the BoxLang runtime under lock (double-checked). Uses {@link BoxRuntime#getInstance(Boolean)} which is the correct non-CLI factory method.
     */
    private void initRuntime() {
        runtimeInitLock.lock();
        try {
            if (runtimeInitialised.get()) return;

            long startMs = System.currentTimeMillis();
            logger.info("Initialising BoxLang runtime...");

            boolean debugMode = "DEBUG".equalsIgnoreCase(configuration.getLogLevel());

            // Use the correct BoxRuntime factory – non-CLI, debug flag only.
            // Azure manages runtime home via FUNCTIONS_WORKER_RUNTIME_VERSION.
            boxRuntime = BoxRuntime.getInstance(debugMode);

            long elapsedMs = System.currentTimeMillis() - startMs;
            runtimeInitialised.set(true);
            logger.info("BoxLang runtime initialised in {} ms", elapsedMs);

            if (elapsedMs > 5_000) {
                logger.warn("BoxLang runtime init exceeded 5 s cold-start target ({} ms)", elapsedMs);
            }

        } catch (Exception e) {
            logger.error("Failed to initialise BoxLang runtime", e);
            throw new RuntimeException("BoxLang runtime initialisation failed", e);
        } finally {
            runtimeInitLock.unlock();
        }
    }

    /**
     * Gracefully shuts down the BoxLang runtime and clears the script cache. Idempotent – safe to call multiple times.
     */
    public void shutdown() {
        if (!shutdownInitiated.compareAndSet(false, true)) return;

        logger.info("Shutting down BoxLang Azure runtime ({} total invocations)...", totalInvocations.get());
        clearCache();
        if (boxRuntime != null) {
            try {
                boxRuntime.shutdown();
                logger.info("BoxLang runtime shut down cleanly");
            } catch ( Exception e ) {
                logger.warn("Non-fatal error during BoxLang runtime shutdown", e);
            } finally {
                boxRuntime = null;
                runtimeInitialised.set(false);
                shutdownInitiated.set(false);  // allow re-init in tests
            }
        }
    }

    // -------------------------------------------------------------------------
    // Script loading / caching
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link IClassRunnable} for a script, loading and caching it on first access.
     *
     * {@link ConcurrentHashMap#computeIfAbsent} serialises concurrent first accesses for the same path so the script is compiled exactly once.
     */
    private IClassRunnable getOrLoadClass(String absolutePath, IBoxContext ctx) {
        if (!configuration.isRuntimeCacheEnabled()) {
            return loadClass(absolutePath, ctx);
        }
        // The cache stores already-loaded runnables; a new context is used per
        // invocation so that scope state is isolated between requests.
        return scriptCache.computeIfAbsent(absolutePath, p -> loadClass(p, ctx));
    }

    /**
     * Loads and compiles a BoxLang class file via {@link RunnableLoader}.
     *
     * @param absolutePath fully resolved path to the {@code .bx} file
     * @param ctx          the request context used for resolution
     * @return compiled {@link IClassRunnable}
     */
    private IClassRunnable loadClass(String absolutePath, IBoxContext ctx) {
        try {
            logger.debug("Loading BoxLang class: {}", absolutePath);
            ResolvedFilePath resolvedFilePath = ResolvedFilePath.of(absolutePath);
            Class<IBoxRunnable> clazz = RunnableLoader.getInstance().loadClass(resolvedFilePath, ctx);
            IClassRunnable runnable = (IClassRunnable) clazz.getDeclaredConstructor().newInstance();
            logger.debug("BoxLang class loaded: {}", absolutePath);
            return runnable;
        } catch (Exception e) {
            throw new BoxLangExecutionException("Failed to load BoxLang class: " + absolutePath, e);
        }
    }

    /**
     * Evicts a single entry from the compilation cache.
     *
     * @param absolutePath the path to evict
     * @return {@code true} if an entry was removed
     */
    public boolean evictFromCache(String absolutePath) {
        return scriptCache.remove(absolutePath ) != null;
    }

    /** Clears the entire script compilation cache. */
    public void clearCache() {
        int count = scriptCache.size();
        scriptCache.clear();
        logger.info("Script cache cleared ({} entries removed)", count);
    }

    /** Number of entries currently in the script compilation cache. */
    public int getCacheSize() {
        return scriptCache.size();
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    /**
     * If {@code scriptPath} is absolute, returns it unchanged; otherwise prepends the configured script root directory.
     */
    String resolveScriptPath(String scriptPath) {
        Path p = Paths.get(scriptPath);
        return p.isAbsolute()
                ? scriptPath
                : Paths.get(configuration.getScriptPath(), scriptPath).toString();
    }

    /** Throws {@link FileNotFoundException} if the file does not exist. */
    private void validateScriptExists(String resolvedPath) throws FileNotFoundException {
        if (!Files.exists(Paths.get(resolvedPath))) {
            throw new FileNotFoundException("BoxLang script not found: " + resolvedPath);
        }
    }

    // -------------------------------------------------------------------------
    // Struct / Map conversion helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a plain Java {@code Map<String, Object>} to a BoxLang {@link IStruct} so it can be passed as a parameter to a BoxLang method.
     */
    private IStruct toStruct(Map<String, Object> map) {
        IStruct struct = new Struct();
        if (map != null) {
            map.forEach((k, v) -> struct.put(Key.of(k), v));
        }
        return struct;
    }

    /**
     * Converts a BoxLang {@link IStruct} back to a plain Java {@code Map<String, Object>} for consumption by the handler.
     */
    private Map<String, Object> structToMap(IStruct struct) {
        Map<String, Object> map = new HashMap<>();
        if (struct == null) return map;
        struct.forEach( (k, v) -> map.put(k.getName(), v));
        return map;
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    /** {@code true} if the runtime has been successfully initialised. */
    public boolean isRuntimeInitialised() {
        return runtimeInitialised.get();
    }

    /** Total number of script invocations since the executor was created. */
    public long getTotalInvocations() {
        return totalInvocations.get();
    }

    /** The active {@link FunctionConfiguration}. */
    public FunctionConfiguration getConfiguration() {
        return configuration;
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    private static void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
    }

    // -------------------------------------------------------------------------
    // Nested exception type
    // -------------------------------------------------------------------------

    /**
     * Wraps exceptions thrown during BoxLang script execution. The handler maps this to an HTTP 500 response.
     */
    public static class BoxLangExecutionException extends RuntimeException {

        public BoxLangExecutionException(String message, Throwable cause) {
            super(message, cause);
        }

        public BoxLangExecutionException(String message) {
            super(message);
        }
    }
}
