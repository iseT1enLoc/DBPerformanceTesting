# DBPerformanceTesting

Benchmarks Oracle NoSQL operations through the Spring Data Oracle NoSQL client:

| Client | Artifact | Path to the store |
|---|---|---|
| `spring-data` | `com.oracle.nosql.sdk:spring-data-oracle-nosql` | App → HTTP proxy (port 8180) |

## Operations benchmarked

| Operation | Spring Data method |
|---|---|
| `put_single` | `NosqlRepository.save` |
| `put_batch` | `NosqlRepository.saveAll` |
| `get_single` | `NosqlRepository.findById` |
| `get_batch` | `findById_CustomerId` (derived query) |
| `query_shard` | `findById_CustomerId` (derived query) |
| `query_amount` | `findByAmountBetween` (derived query, uses secondary index) |

## Test data

All data comes from `data/orders.csv` (1,000,000 rows, 50,000 customers × 20 orders):

```
customerId,orderId,amount,payload
c0,0,6394.27,TpigTHKbfoLISRAB
```

Regenerate it deterministically with:
```bash
python3 scripts/generate_orders_csv.py
```

## Prerequisites

- JDK 17 and Maven
- Docker

## Setup

Start KVLite with the HTTP proxy via Docker Compose:
```bash
docker compose up -d oracle-nosql
```
KVLite listens on port **5100** (internal) and the proxy on **8180** (mapped from container port 8080).

## Run

```bash
mvn spring-boot:run
```

Or build a fat jar and run it:
```bash
mvn package -DskipTests
java -jar target/db-perform-test-0.0.1-SNAPSHOT.jar
```

## Configuration

Edit the constants at the top of `Main.java`:

| Constant | Default | Description |
|---|---|---|
| `RECORDS` | `100_000` | Rows loaded from CSV and used in the benchmark |
| `WARMUP` | `200` | Ops discarded before measuring (per-operation benchmark) |
| `OPS` | `1_000` | Ops measured per operation |
| `INSERT_RUNS` | `5` | Repetitions for the sequential-vs-getBatch comparison |

## Output

```
client        operation            ops/s     avg ms/op
------------------------------------------------------
spring-data   put_single               ?             ?
spring-data   put_batch                ?             ?
spring-data   get_single               ?             ?
spring-data   get_batch                ?             ?
spring-data   query_shard              ?             ?
spring-data   query_amount             ?             ?

--- Read 100000 records: sequential vs getBatch [spring-data, 5 runs] ---
method                          rows/s      total ms       calls
----------------------------------------------------------------
sequential (get_single)              ?              ?     100,000
getBatch (per customer)              ?              ?       5,000
```

Run the benchmark to see real numbers. Update this section with your results.

## Call dataflow

```
Main.main()  →  SpringApplication.run()  →  Spring context starts
│                 NosqlConfig.nosqlDBConfig()  →  NosqlDbConfig (HTTP proxy :8180)
│                 OrderRepository bean created (NosqlRepository)
│                 SpringDataClientImpl bean wired with OrderRepository
│
Main.run()
├── nosqlTemplate.runTableRequest()   CREATE INDEX IF NOT EXISTS idx_amount ON orders (amount)
│                                     (table auto-created by Spring Data on first use)
│
├── OrderCsvReader.read()             load first RECORDS rows from data/orders.csv
├── springData.put() × RECORDS        seed the table
│
├── groupByCustomer()   build per-customer batches (for put_batch)
│
├── measure()       put_single    →  repository.save(order)
├── measureBatch()  put_batch     →  repository.saveAll(orders)
├── measure()       get_single    →  repository.findById(OrderKey)
├── measure()       get_batch     →  repository.findById_CustomerId(customerId)
├── measure()       query_shard   →  repository.findById_CustomerId(customerId)
└── measure()       query_amount  →  repository.findByAmountBetween(lo, hi)
│
└── compareTotal()  sequential vs getBatch — total time to read RECORDS rows
    ├── sequential: RECORDS × springData.get()
    └── batch:      customers × springData.getBatch()
```

## Code structure

```
src/main/java/com/example/dbperformtest/
├── Main.java                      Spring Boot CommandLineRunner: seed table, run benchmarks
│
├── client/
│   ├── NoSqlClient.java           interface: put, putBatch, get, getBatch, query*
│   └── SpringDataClientImpl.java  Spring Data Oracle NoSQL implementation
│
├── config/
│   └── NosqlConfig.java           NosqlDbConfig bean + @EnableNosqlRepositories
│
├── model/
│   ├── Order.java                 @NosqlTable entity (customerId, orderId, amount, payload)
│   ├── OrderKey.java              composite primary key (@NosqlKey on customerId + orderId)
│   ├── ConsistencyLevel.java      enum: NONE_REQUIRED | ABSOLUTE
│   └── DurabilityLevel.java       enum: COMMIT_NO_SYNC | COMMIT_SYNC
│
├── repository/
│   └── OrderRepository.java       NosqlRepository<Order, OrderKey> + derived queries
│
└── workload/
    └── OrderCsvReader.java        reads data/orders.csv into List<Order>

data/
└── orders.csv                     1,000,000 static rows (50,000 customers × 20 orders)

scripts/
└── generate_orders_csv.py         regenerates data/orders.csv deterministically
```
