package ortus.boxlang.runtime.azure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * BoxLangAzureConfig
 *
 * Loads BoxLang Azure Functions configuration from the bundled
 * {@code boxlang-azure.properties} file, then applies any environment-variable
 * overrides so that runtime values can be injected at deployment time without
 * repackaging the JAR.
 *
 * Environment variables take precedence over property-file values.
 * Property keys use dots; the corresponding environment-variable names use
 * upper-case with underscores (e.g. {@code boxlang.runtime.logLevel} ->
 * {@code BOXLANG_RUNTIME_LOGLEVEL}).
 *
 * Usage:
 * {@code BoxLangAzureConfig config = BoxLangAzureConfig.getInstance();
 * String scriptPath = config.getScriptPath();
 * }
 */

public class BoxLangAzureConfig {

    private static final Logger logger = LoggerFactory.getLogger(BoxLangAzureConfig.class);

    /** Classpath location of the default properties file. */
    private static final String PROPERTIES_FILE = "/boxlang-azure.properties";

    // -----------------------------------------------------------------------
    // Property keys (mirrors boxlang-azure.properties)
    // -----------------------------------------------------------------------

    public static final String KEY_SCRIPT_PATH = "boxlang.runtime.scriptPath";
    public static final String KEY_CLASS_PATH = "boxlang.runtime.classPath";
    public static final String KEY_LOG_LEVEL = "boxlang.runtime.logLevel";
    public static final String KEY_COLD_START_OPTIMIZATION = "boxlang.azure.coldStartOptimization";
    public static final String KEY_RUNTIME_CACHE_ENABLED = "boxlang.azure.runtimeCacheEnabled";
    public static final String KEY_MAX_CONCURRENT_REQUESTS = "boxlang.azure.maxConcurrentRequests";

    // -----------------------------------------------------------------------
    // Defaults (used when neither the properties file nor env var supplies a value)
    // -----------------------------------------------------------------------

    private static final String DEFAULT_SCRIPT_PATH = "/home/site/wwwroot/scripts";
    private static final String DEFAULT_CLASS_PATH = "/home/site/wwwroot/classes";
    private static final String DEFAULT_LOG_LEVEL = "INFO";
    private static final String DEFAULT_COLD_START_OPTIMIZATION = "true";
    private static final String DEFAULT_RUNTIME_CACHE_ENABLED = "true";
    private static final String DEFAULT_MAX_CONCURRENT_REQUESTS = "100";

    // -----------------------------------------------------------------------
    // Singleton
    // -----------------------------------------------------------------------

    private static volatile BoxLangAzureConfig instance;

    /** Resolved configuration values. */
    private final Properties resolved;

    // -----------------------------------------------------------------------
    // Constructor - private, load-on-first-use
    // -----------------------------------------------------------------------

    private BoxLangAzureConfig() {
        resolved = new Properties();
        loadDefaults();
        loadPropertiesFile();
        applyEnvironmentOverrides();
        logEffectiveConfig();
    }

    /**
     * Returns the singleton instance, creating it on first call (double-checked locking).
     * @return the shared {@link BoxLangAzureConfig}
     */
    public static BoxLangAzureConfig getInstance() {
        if (instance == null) {
            synchronized (BoxLangAzureConfig.class) {
                if (instance == null) {
                    instance = new BoxLangAzureConfig();
                }
            }
        }
        return instance;
    }

    // -----------------------------------------------------------------------
    // Load helpers
    // -----------------------------------------------------------------------

    /** Seed all properties with hard-coded defaults so no value is ever null. */
    private void loadDefaults() {
        resolved.setProperty(KEY_SCRIPT_PATH, DEFAULT_SCRIPT_PATH);
        resolved.setProperty(KEY_CLASS_PATH, DEFAULT_CLASS_PATH);
        resolved.setProperty(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL);
        resolved.setProperty(KEY_COLD_START_OPTIMIZATION, DEFAULT_COLD_START_OPTIMIZATION);
        resolved.setProperty(KEY_RUNTIME_CACHE_ENABLED, DEFAULT_RUNTIME_CACHE_ENABLED);
        resolved.setProperty(KEY_MAX_CONCURRENT_REQUESTS, DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    /** Overlay values from the bundled properties file (if present on the classpath). */
    private void loadPropertiesFile() {
        try (InputStream stream = BoxLangAzureConfig.class.getResourceAsStream(PROPERTIES_FILE)) {
            if (stream != null) {
                Properties fileProps = new Properties();
                fileProps.load(stream);
                for (String key : fileProps.stringPropertyNames()) {
                    resolved.setProperty(key, fileProps.getProperty(key));
                }
                logger.debug("Loaded configuration from {}", PROPERTIES_FILE);
            } else {
                logger.warn("Properties file {} not found on classpath; using defaults.", PROPERTIES_FILE);
            }
        } catch (IOException e) {
            logger.warn("Failed to read {}; using defaults. Cause: {}", PROPERTIES_FILE, e.getMessage());
        }
    }

    /**
     * Apply environment-variable overrides.
     * Converts each property key to an upper-case env-var name by replacing
     * dots with underscores (e.g. {@code boxlang.runtime.logLevel} ->
     * {@code BOXLANG_RUNTIME_LOGLEVEL}).
     */
    private void applyEnvironmentOverrides() {
        for (String key : resolved.stringPropertyNames()) {
            String envKey = key.replace(".", "_").toUpperCase();
            String envValue = System.getenv(envKey);
            if (envValue != null && !envValue.isBlank()) {
                resolved.setProperty(key, envValue);
                logger.debug("Config key '{}' overridden by environment variable '{}'", key, envKey);
            }
        }
    }

    /** Emit the final resolved configuration at DEBUG level. */
    private void logEffectiveConfig() {
        logger.debug("Effective BoxLang Azure configuration:");
        resolved.stringPropertyNames().stream().sorted().forEach(k -> logger.debug("  {} = {}", k, resolved.getProperty(k)));
    }

    // -----------------------------------------------------------------------
    // Public accessors
    // -----------------------------------------------------------------------

    /**
     * Path where BoxLang scripts are stored.
     * Corresponds to {@code boxlang.runtime.scriptPath}.
     */
    public String getScriptPath() {
        return resolved.getProperty(KEY_SCRIPT_PATH);
    }

    /**
     * Extra class-path directory for compiled BoxLang classes.
     * Corresponds to {@code boxlang.runtime.classPath}.
     */
    public String getClassPath() {
        return resolved.getProperty(KEY_CLASS_PATH);
    }

    /**
     * Log-level string (e.g. "INFO", "DEBUG").
     * Corresponds to {@code boxlang.runtime.logLevel}.
     */
    public String getLogLevel() {
        return resolved.getProperty(KEY_LOG_LEVEL);
    }

    /**
     * Whether cold-start optimizations are enabled.
     * Corresponds to {@code boxlang.azure.coldStartOptimization}.
     */
    public boolean isColdStartOptimizationEnabled() {
        return Boolean.parseBoolean(resolved.getProperty(KEY_COLD_START_OPTIMIZATION));
    }

    /**
     * Whether the runtime script-compilation cache is enabled.
     * Corresponds to {@code boxlang.azure.runtimeCacheEnabled}.
     */
    public boolean isRuntimeCacheEnabled() {
        return Boolean.parseBoolean(resolved.getProperty(KEY_RUNTIME_CACHE_ENABLED));
    }

    /**
     * Maximum number of concurrent requests this instance should handle.
     * Corresponds to {@code boxlang.azure.maxConcurrentRequests}.
     */
    public int getMaxConcurrentRequests() {
        try {
            return Integer.parseInt(resolved.getProperty( KEY_MAX_CONCURRENT_REQUESTS));
        } catch (NumberFormatException e) {
            logger.warn("Invalid value for {}; falling back to default 100.", KEY_MAX_CONCURRENT_REQUESTS);
            return 100;
        }
    }

    /**
     * Generic property accessor.
     * @param key          property key
     * @param defaultValue fallback value when key is absent
     * @return resolved value
     */
    public String getProperty(String key, String defaultValue) {
        return resolved.getProperty(key, defaultValue);
    }

    /**
     * Resets the singleton (test-only).
     * Allows unit tests to reload configuration without restarting the JVM.
     */
    static void resetForTesting() {
        instance = null;
    }
}
