# BoxLang Azure Functions Runtime

A runtime adapter that enables [BoxLang](https://boxlang.io) to run on Microsoft Azure Functions. BoxLang developers can now deploy serverless functions to Azure using the same language and patterns they already know — achieving cloud-agnostic serverless deployment across both AWS Lambda and Azure Functions.

---

## Features

- HTTP trigger support for all standard methods (GET, POST, PUT, DELETE, PATCH, OPTIONS)
- Wildcard route handling — any path under `/api/` is captured and routed to the matching BoxLang script
- Automatic request mapping from Azure's `HttpRequestMessage` to BoxLang-compatible request structures
- CGI variable compatibility — BoxLang scripts written for CFML/BoxLang servers run unmodified on Azure
- Azure context bridging — access function name, invocation ID, and distributed tracing from BoxLang code
- Logging bridge between Azure Application Insights and SLF4J
- Script compilation caching for fast warm execution
- Cold-start optimization with eager runtime initialization
- Thread-safe concurrent execution with full scope isolation between requests
- Graceful shutdown with JVM shutdown hook
- Docker-based development environment for consistent tooling across the team

---

## Architecture

```
[HTTP Request]
       |
[Azure Functions Host]
       |
[Azure Functions Java Worker]
       |
[BoxLangAzureFunctionHandler]  — entry point, error mapping, response building
       |
       |--- [AzureRequestMapper]      — transforms HttpRequestMessage to BoxLang request map
       |--- [AzureContextAdapter]     — bridges ExecutionContext to BoxLang context
       |
[BoxLangFunctionExecutor]      — manages runtime lifecycle, compiles and executes .bx scripts
       |
[BoxLang Runtime Core]
       |
[Developer's BoxLang Code (.bx files)]
       |
[HTTP Response]
```

### Core Components

**BoxLangAzureFunctionHandler** — Main entry point annotated with `@FunctionName`. Receives every HTTP request, orchestrates the pipeline through the mapper, adapter, and executor, then builds the Azure `HttpResponseMessage`. Maps exceptions to appropriate HTTP status codes (400, 404, 500).

**AzureRequestMapper** — Transforms Azure's `HttpRequestMessage` into a plain `Map<String, Object>` containing method, URI, path, headers, query parameters, parsed body (JSON, form-encoded, multipart), and CGI variables. This normalization layer allows BoxLang scripts to work with familiar request structures.

**AzureContextAdapter** — Bridges Azure's `ExecutionContext` to a BoxLang-compatible context map. Exposes function name, invocation ID, trace context, and environment variables. Provides a dual logging bridge that routes log statements to both SLF4J and the Azure host logger for Application Insights correlation.

**BoxLangFunctionExecutor** — Singleton that manages the BoxLang runtime lifecycle. Handles runtime initialization (with optional eager cold-start optimization), script compilation and caching via `RunnableLoader`, thread-safe concurrent execution with per-request context isolation, and graceful shutdown. Scripts follow the `run(event, context, response)` convention matching the AWS Lambda runner pattern.

**BoxLangAzureConfig** — Loads configuration from `boxlang-azure.properties` with environment variable overrides for runtime settings like script paths, log levels, and performance options.

**FunctionConfiguration** — Manages Azure Function-specific configuration including trigger bindings, route templates, and authorization levels.

---

## Prerequisites

- [Docker Desktop](https://docs.docker.com/get-docker/)
- [Git](https://git-scm.com/downloads)
- An editor (IntelliJ IDEA recommended)

You do **not** need to install Java, Maven, or Azure tools on your local machine. Everything runs inside the Docker container.

---

## Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/ortus-boxlang/boxlang-azure-functions.git
cd boxlang-azure-functions
```

### 2. Create your local environment file

On Mac/Linux:
```bash
cp .env.example .env
```

On Windows:
```bash
copy .env.example .env
```

### 3. Build the Docker container

```bash
docker compose build
```

This takes 5-10 minutes the first time. Docker downloads and installs Java 21, Maven 3.9.6, Azure Functions Core Tools 4.7.0, and Azure CLI into a reproducible Linux container.

### 4. Start the container

```bash
docker compose up -d
```

### 5. Open a shell inside the container

```bash
docker compose exec dev bash
```

### 6. Verify the environment

```bash
java --version        # Should show Java 21 (Eclipse Temurin)
mvn --version         # Should show Maven 3.9.6
func --version        # Should show Azure Functions Core Tools 4.x
az --version          # Should show Azure CLI 2.x
```

### 7. Build the project

```bash
mvn clean compile
```

You should see `Compiling 6 source files` followed by `BUILD SUCCESS`.

### 8. When you're done

```bash
exit                  # Leave the container
docker compose down   # Stop the container
```

---

## Daily Workflow

```bash
docker compose up -d              # Start container
docker compose exec dev bash      # Get inside
mvn clean compile                 # Build
mvn test                          # Run tests
exit                              # Leave container
docker compose down               # Stop container
```

---

## Project Structure

```
boxlang-azure-functions/
├── Dockerfile                    # Dev environment definition
├── docker-compose.yml            # Container orchestration
├── pom.xml                       # Maven build configuration
├── .env.example                  # Environment variable template
├── .gitignore                    # Git exclusions
├── .dockerignore                 # Docker build exclusions
├── DEV_SETUP.md                  # Team setup instructions
├── README.md                     # This file
├── src/
│   ├── main/
│   │   ├── java/ortus/boxlang/runtime/azure/
│   │   │   ├── BoxLangAzureFunctionHandler.java
│   │   │   ├── AzureContextAdapter.java
│   │   │   ├── BoxLangFunctionExecutor.java
│   │   │   ├── AzureRequestMapper.java
│   │   │   └── config/
│   │   │       ├── BoxLangAzureConfig.java
│   │   │       └── FunctionConfiguration.java
│   │   └── resources/
│   │       ├── function.json           # Azure Functions trigger config
│   │       ├── host.json               # Function app settings
│   │       └── boxlang-azure.properties # BoxLang runtime config
│   └── test/
│       └── java/ortus/boxlang/runtime/azure/
│           ├── BoxLangAzureFunctionHandlerTest.java
│           ├── AzureContextAdapterTest.java
│           ├── BoxLangFunctionExecutorTest.java
│           ├── AzureRequestMapperTest.java
│           └── integration/
│               └── EndToEndIntegrationTest.java
└── docs/
    ├── architecture.md
    ├── getting-started.md
    └── troubleshooting.md
```

---

## Configuration

The file `src/main/resources/boxlang-azure.properties` controls runtime behavior:

| Property | Default | Description |
|----------|---------|-------------|
| `boxlang.runtime.scriptPath` | `/home/site/wwwroot/scripts` | Directory where BoxLang `.bx` scripts are located on Azure |
| `boxlang.runtime.classPath` | `/home/site/wwwroot/classes` | Directory for compiled BoxLang classes |
| `boxlang.runtime.logLevel` | `INFO` | Logging verbosity (TRACE, DEBUG, INFO, WARN, ERROR) |
| `boxlang.azure.coldStartOptimization` | `true` | Pre-warm the BoxLang runtime on first class load |
| `boxlang.azure.runtimeCacheEnabled` | `true` | Cache compiled scripts for faster warm execution |
| `boxlang.azure.maxConcurrentRequests` | `100` | Thread pool size for concurrent request handling |

Azure deployment settings are read from environment variables (configured in `.env`):

| Variable | Default | Description |
|----------|---------|-------------|
| `AZURE_FUNCTION_APP_NAME` | `boxlang-func-dev` | Azure Function App name |
| `AZURE_REGION` | `eastus` | Azure deployment region |
| `AZURE_RESOURCE_GROUP` | `boxlang-rg-dev` | Azure resource group |

---

## Tech Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 21 (Eclipse Temurin) | Runtime language |
| Maven | 3.9.6 | Build system |
| Azure Functions Java Library | 3.1.0 | Azure Functions API |
| BoxLang | 1.11.0 | Script runtime |
| Docker | Latest | Development environment |
| Azure Functions Core Tools | 4.7.0 | Local testing |
| Azure CLI | 2.84.0 | Azure deployment |
| JUnit 5 | 5.10.0 | Unit testing |
| Mockito | 5.5.0 | Test mocking |
| AssertJ | 3.24.0 | Test assertions |
| JaCoCo | 0.8.10 | Code coverage (80% minimum) |
| SLF4J / Logback | 2.0.9 / 1.4.11 | Logging |

---

## Writing BoxLang Functions

BoxLang scripts follow the `run(event, context, response)` convention, matching the AWS Lambda runner pattern:

```java
// hello.bx
class {
    function run(event, context, response) {
        response.statusCode = 200;
        response.body = serializeJSON({
            "message": "Hello from Azure Functions!",
            "method": event.method,
            "path": event.path,
            "functionName": context.azure.functionName,
            "invocationId": context.azure.invocationId
        });
    }
}
```

Place `.bx` files in the configured script path. A request to `/api/hello` will execute `hello.bx`. A request to `/api/users/profile` will execute `users/profile.bx`.

---

## Documentation

See the `docs/` folder for detailed guides:

- [Architecture Overview](docs/architecture.md) — system design, request flow, component interactions
- [Getting Started Guide](docs/getting-started.md) — step-by-step setup and first function tutorial
- [Troubleshooting Guide](docs/troubleshooting.md) — common issues and fixes encountered during development

---

## Team

| Person | Role | Responsibilities |
|--------|------|-----------------|
| Arnold | Infrastructure Lead | Docker environment, Maven scaffold, project structure, build verification, BoxLangFunctionExecutor |
| Shreya | Core Runtime Developer | BoxLangAzureFunctionHandler, AzureContextAdapter, AzureRequestMapper, config classes |
| Kemely | Testing Lead | Unit tests for Handler, Mapper, Adapter, and Executor; collaborated with Edward on Handler tests |
| Edward | Testing Lead | Test plan for Handler and Executor, EndToEndIntegrationTest, collaborated with Kemely on Handler tests |

---

## Performance Targets

| Metric | Target |
|--------|--------|
| Cold start | < 5 seconds |
| Warm execution (p95) | < 500 ms |
| Throughput | > 50 requests/second |
| Memory usage | < 512 MB average |
| Code coverage | > 80% |

---

## License

This project is part of the [BoxLang](https://boxlang.io) ecosystem by [Ortus Solutions](https://www.ortussolutions.com).
