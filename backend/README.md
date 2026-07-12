# Gauntlet — Backend

A personal, single-user CS learning tool. This is the Spring Boot backend.

## Stack

- Java 21
- Maven
- Spring Boot 3.5
- PostgreSQL

## Package layout

All code lives under `com.gauntlet`:

| Package       | Responsibility                                         |
|---------------|--------------------------------------------------------|
| `execution/`  | Docker-based code execution                            |
| `submission/` | Submission handling and grading logic                  |
| `problem/`    | Problem / topic metadata                               |
| `user/`       | User data and progress tracking                        |
| `config/`     | Application configuration (DB, mail, etc.)             |

## Prerequisites

- JDK 21
- Maven 3.9+ (or use `./mvnw` once a wrapper is generated)
- Docker (for running PostgreSQL and, later, code execution)

## Running PostgreSQL

The app expects a database named `gauntlet` on `localhost:5432`. The quickest way:

```bash
docker run --name gauntlet-db \
  -e POSTGRES_DB=gauntlet \
  -e POSTGRES_USER=gauntlet \
  -e POSTGRES_PASSWORD=gauntlet \
  -p 5432:5432 \
  -d postgres:16
```

## Configuration

Credentials are read from environment variables (see `application.yml`). Set them
before running — do not commit secrets:

```bash
export DB_USERNAME=gauntlet
export DB_PASSWORD=gauntlet
```

Mail settings (`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`) are
optional until email features are added.

## Running the app

```bash
mvn spring-boot:run
```

The server starts on `http://localhost:8080`.

## Health check

```bash
curl http://localhost:8080/api/health
# {"status":"ok"}
```
