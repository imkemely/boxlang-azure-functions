# BoxLang Azure Functions — Dev Environment Setup

## Prerequisites

You need two things installed on your local machine:

- **Docker Desktop** — [Install here](https://docs.docker.com/get-docker/)
- **Git** — to clone the repo

That's it. Java, Maven, and Azure tools are all inside the container.

---

## Quick Start

### 1. Clone the repository
```bash
git clone https://github.com/<your-org>/boxlang-azure-functions.git
cd boxlang-azure-functions
```

### 2. Create your local environment file
```bash
cp .env.example .env
```

### 3. Build and start the container
```bash
docker compose up -d --build
```

### 4. Open a shell inside the container
```bash
docker compose exec dev bash
```

### 5. Verify everything works
```bash
java --version
mvn --version
func --version
az --version
```

---

## Daily Workflow
```bash
# Start your dev container
docker compose up -d

# Open a shell
docker compose exec dev bash

# Build the project
mvn clean package

# Run tests
mvn test

# Stop the container when done
docker compose down
```

---

## Troubleshooting

**Container won't start?**
Make sure Docker Desktop is running and nothing else is on port 7071.

**Maven downloads are slow?**
First run downloads dependencies. They're cached in a Docker volume after that.

**Need to rebuild after Dockerfile changes?**
```bash
docker compose up -d --build
```

**Want a completely fresh start?**
```bash
docker compose down -v
docker compose up -d --build
```