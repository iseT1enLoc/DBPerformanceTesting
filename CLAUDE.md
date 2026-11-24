# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A Java performance test harness for ScyllaDB accessed via the **AWS DynamoDB SDK v2** through ScyllaDB's **Alternator** compatibility layer (DynamoDB-compatible HTTP API on port 8000). The goal is to benchmark ScyllaDB as a drop-in DynamoDB replacement.

Stack: Java 17, Maven, AWS SDK v2 (`software.amazon.awssdk:dynamodb`), JUnit 5, ScyllaDB 6.2 (Docker).

## Common commands

```bash
# Start ScyllaDB (wait for healthy before running code)
docker compose up -d
docker compose ps   # check health

# Build & run the demo scenario
mvn compile exec:java -Dexec.mainClass=com.example.dbperformtest.scylla.Main

# Run all tests (requires a running ScyllaDB)
mvn test

# Run a single test class
mvn test -Dtest=OrderRepositoryTest

# Run a single test method
mvn test -Dtest=OrderRepositoryTest#put_and_get

# Override connection config at runtime (bypasses application.properties)
mvn test -Dscylla.endpoint=http://localhost:8000 \
         -Dscylla.access-key=cassandra \
         -Dscylla.secret-key=cassandra
```

## Architecture

```
ScyllaConfig          — builds DynamoDbClient; reads application.properties,
                        overridable via -Dscylla.* JVM system properties
UserRepository        — DynamoDB table "Users" (PK: userId)
OrderRepository       — DynamoDB table "Orders" (PK: customerId, SK: orderId);
                        supports put / get / query-by-customer / delete
UserOrderService      — composes the two repos for higher-level operations
Main                  — demo entry point: seeds data, prints results
OrderRepositoryTest   — integration tests that hit a live ScyllaDB instance
```

All database interaction uses low-level `DynamoDbClient` (not DynamoDB Enhanced Client), so item shapes are plain `Map<String, AttributeValue>`.

## Credentials & auth

ScyllaDB is started with `PasswordAuthenticator` + `alternator-enforce-authorization 1`. Default superuser is `cassandra / cassandra`. The Alternator SDK treats the AWS access key as the CQL username and the secret key as the salted password hash. `application.properties` has placeholder values — supply real credentials before connecting.

The salted hash can be generated with the ScyllaDB tool or by querying `system_auth.roles` after initial login via `cqlsh`.

## Tests

Tests in `OrderRepositoryTest` require a live ScyllaDB instance (no mocks). They are ordered with `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` and share a single client/repo via `@BeforeAll`/`@AfterAll`.
