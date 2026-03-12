package ortus.boxlang.runtime.azure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value object representing the configuration for a single Azure
 * Function invocation.
 *
 * An instance is constructed once per cold-start by
 * {@link BoxLangAzureConfig} and then passed through the handler chain.
 * Because it is immutable after construction, it can be shared freely across
 * concurrent request threads without synchronisation.
 *
 * Build instances via the nested {@link Builder}:
 * {@code FunctionConfiguration cfg = FunctionConfiguration.builder()
 *     .functionName("MyHttpFunction")
 *     .scriptPath("/home/site/wwwroot/scripts")
 *     .logLevel("DEBUG")
 *     .build();
 * }
 */
public final class FunctionConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(FunctionConfiguration.class);

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** Azure-assigned function name (from the {@code @FunctionName} annotation). */
    private final String functionName;

    /** Resolved path to BoxLang scripts on the host filesystem. */
    private final String scriptPath;

    /** Resolved additional classpath for BoxLang class loading. */
    private final String classPath;

    /** Log level forwarded to the BoxLang runtime for this function. */
    private final String logLevel;

    /** Whether cold-start pre-warming is active for this function. */
    private final boolean coldStartOptimizationEnabled;

    /** Whether the compiled-script in-memory cache is active for this function. */
    private final boolean runtimeCacheEnabled;

    /** Soft concurrency limit for this function instance. */
    private final int maxConcurrentRequests;

    /**
     * Arbitrary additional settings (e.g. custom environment properties) that
     * don't map to a first-class field. Stored as an unmodifiable map so
     * callers cannot mutate the object's state.
     */
    private final Map<String, String> additionalSettings;

    // -------------------------------------------------------------------------
    // Constructor (private – use Builder)
    // -------------------------------------------------------------------------

    private FunctionConfiguration(Builder builder) {
        this.functionName = Objects.requireNonNull(builder.functionName, "functionName must not be null");
        this.scriptPath = Objects.requireNonNull(builder.scriptPath, "scriptPath must not be null");
        this.classPath = Objects.requireNonNull(builder.classPath, "classPath must not be null");
        this.logLevel = Objects.requireNonNull(builder.logLevel, "logLevel must not be null");
        this.coldStartOptimizationEnabled = builder.coldStartOptimizationEnabled;
        this.runtimeCacheEnabled = builder.runtimeCacheEnabled;
        this.maxConcurrentRequests = builder.maxConcurrentRequests;
        this.additionalSettings = Collections.unmodifiableMap(new HashMap<>(builder.additionalSettings));

        logger.debug("FunctionConfiguration created – function={}, scriptPath={}, logLevel={}, maxConcurrent={}",
                      functionName, scriptPath, logLevel, maxConcurrentRequests);
    }

    // -------------------------------------------------------------------------
    // Static factory
    // -------------------------------------------------------------------------

    /**
     * Convenience factory that builds a {@link FunctionConfiguration} from the
     * global {@link BoxLangAzureConfig} singleton, overriding the function name.
     * @param functionName the Azure function name for this configuration
     * @return a populated {@link FunctionConfiguration}
     */
    public static FunctionConfiguration fromGlobalConfig(String functionName) {
        BoxLangAzureConfig global = BoxLangAzureConfig.getInstance();
        return builder()
                .functionName(functionName)
                .scriptPath(global.getScriptPath())
                .classPath(global.getClassPath())
                .logLevel(global.getLogLevel())
                .coldStartOptimizationEnabled(global.isColdStartOptimizationEnabled())
                .runtimeCacheEnabled(global.isRuntimeCacheEnabled())
                .maxConcurrentRequests(global.getMaxConcurrentRequests())
                .build();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Azure-assigned name of the function (matches the {@code @FunctionName}
     * annotation value).
     * @return function name; never {@code null}
     */
    public String getFunctionName() {
        return functionName;
    }

    /**
     * Path to the directory containing BoxLang scripts.
     * @return absolute path string; never {@code null}
     */
    public String getScriptPath() {
        return scriptPath;
    }

    /**
     * Additional classpath entries for BoxLang class loading.
     * @return path string; never {@code null}
     */
    public String getClassPath() {
        return classPath;
    }

    /**
     * Log level that the BoxLang runtime should use for this function.
     * @return level string (e.g. {@code "INFO"}); never {@code null}
     */
    public String getLogLevel() {
        return logLevel;
    }

    /**
     * Whether cold-start pre-warming is active.
     * @return {@code true} if enabled
     */
    public boolean isColdStartOptimizationEnabled() {
        return coldStartOptimizationEnabled;
    }

    /**
     * Whether the in-memory script compilation cache is active.
     * @return {@code true} if enabled
     */
    public boolean isRuntimeCacheEnabled() {
        return runtimeCacheEnabled;
    }

    /**
     * Soft per-instance concurrency limit.
     * @return maximum concurrent requests
     */
    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    /**
     * Returns an additional setting by key, or {@code null} if not present.
     * @param key the setting key
     * @return the value or {@code null}
     */
    public String getAdditionalSetting(String key) {
        return additionalSettings.get(key);
    }

    /**
     * Returns an unmodifiable view of all additional (non-standard) settings.
     * @return unmodifiable map; never {@code null}
     */
    public Map<String, String> getAdditionalSettings() {
        return additionalSettings;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Returns a new {@link Builder} instance.
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link FunctionConfiguration}.
     */
    public static final class Builder {
        private String functionName = "UnknownFunction";
        private String scriptPath = "/home/site/wwwroot/scripts";
        private String classPath = "/home/site/wwwroot/classes";
        private String logLevel = "INFO";
        private boolean coldStartOptimizationEnabled = true;
        private boolean runtimeCacheEnabled = true;
        private int maxConcurrentRequests = 100;
        private Map<String, String> additionalSettings = new HashMap<>();

        private Builder() {}
        /** Sets the Azure function name. */
        public Builder functionName(String functionName) {
            this.functionName = functionName;
            return this;
        }

        /** Sets the BoxLang script root path. */
        public Builder scriptPath(String scriptPath) {
            this.scriptPath = scriptPath;
            return this;
        }

        /** Sets the additional BoxLang classpath. */
        public Builder classPath(String classPath) {
            this.classPath = classPath;
            return this;
        }

        /** Sets the log level forwarded to BoxLang. */
        public Builder logLevel(String logLevel) {
            this.logLevel = logLevel;
            return this;
        }

        /** Enables or disables cold-start optimisation. */
        public Builder coldStartOptimizationEnabled(boolean enabled) {
            this.coldStartOptimizationEnabled = enabled;
            return this;
        }

        /** Enables or disables the runtime compilation cache. */
        public Builder runtimeCacheEnabled(boolean enabled) {
            this.runtimeCacheEnabled = enabled;
            return this;
        }

        /** Sets the soft concurrency limit. */
        public Builder maxConcurrentRequests(int max) {
            this.maxConcurrentRequests = max;
            return this;
        }

        /** Adds a single additional setting. */
        public Builder additionalSetting(String key, String value) {
            this.additionalSettings.put(key, value);
            return this;
        }

        /** Bulk-sets additional settings (replaces any existing additional settings). */
        public Builder additionalSettings(Map<String, String> settings) {
            this.additionalSettings = new HashMap<>(settings);
            return this;
        }

        /**
         * Builds and returns an immutable {@link FunctionConfiguration}.
         * @return configured instance
         * @throws NullPointerException if any required field is {@code null}
         */
        public FunctionConfiguration build() {
            return new FunctionConfiguration(this);
        }
    }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "FunctionConfiguration{" +
               "functionName='" + functionName + '\'' +
               ", scriptPath='" + scriptPath + '\'' +
               ", logLevel='" + logLevel + '\'' +
               ", coldStartOpt=" + coldStartOptimizationEnabled +
               ", runtimeCache=" + runtimeCacheEnabled +
               ", maxConcurrent=" + maxConcurrentRequests +
               '}';
    }
}
