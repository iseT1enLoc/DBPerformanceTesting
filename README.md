# DBPerformanceTesting

Benchmarks Oracle NoSQL operations using the official Oracle NoSQL Java SDK (`nosqldriver`), connecting through the HTTP proxy.

| Client | Artifact | Path to the store |
|---|---|---|
| `sdk` | `com.oracle.nosql.sdk:nosqldriver` | App → HTTP proxy (port 8180) |

## Operations benchmarked

| Operation | SDK class |
|---|---|
| `put_single` | `PutRequest` |
| `put_batch` | `WriteMultipleRequest` (same shard key) |
| `get_single` | `GetRequest` |
| `get_batch` | prepared `SELECT … WHERE customerId = $cid` |
| `query_shard` | prepared `SELECT … WHERE customerId = $cid` |
| `query_amount` | prepared `SELECT … WHERE amount >= $lo AND amount <= $hi` (secondary index) |

## Test data

All data comes from `data/orders.csv` (1,000,000 rows, 50,000 customers × 20 orders each):

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
The proxy listens on port **8180** (mapped from container port 8080).

## Run

```bash
mvn compile exec:java -Dexec.mainClass=com.example.dbperformtest.Main
```

Or build a fat jar and run it:
```bash
mvn package
java -jar target/db-perform-test-0.0.1-SNAPSHOT.jar
```

## Configuration

Edit the constants at the top of `Main.java`:

| Constant | Default | Description |
|---|---|---|
| `RECORDS` | `100_000` | Rows loaded from CSV and used in the benchmark |
| `WARMUP` | `200` | Ops discarded before measuring |
| `OPS` | `1_000` | Ops measured per scenario |
| `INSERT_RUNS` | `5` | Repetitions for the sequential-vs-getBatch comparison |

## Output

```
client  operation            ops/s     avg ms/op
-------------------------------------------------
sdk     put_single               ?             ?
sdk     put_batch                ?             ?
sdk     get_single               ?             ?
sdk     get_batch                ?             ?
sdk     query_shard              ?             ?
sdk     query_amount             ?             ?

--- Read 100000 records: sequential vs getBatch [5 runs] ---
method                          rows/s      total ms       calls
----------------------------------------------------------------
sequential (get_single)              ?              ?     100,000
getBatch (per customer)              ?              ?       5,000
```

Run the benchmark and update this section with your results.

## Call dataflow

```
Main.main()
├── retry()  →  NoSQLHandleFactory.createNoSQLHandle()   connect (http://localhost:8180)
│
├── sdk.executeDdl()   CREATE TABLE IF NOT EXISTS orders (...)
├── sdk.executeDdl()   CREATE INDEX IF NOT EXISTS idx_amount ON orders (amount)
│
├── OrderCsvReader.read()   load first RECORDS rows from data/orders.csv
├── sdk.put() × RECORDS     seed the table
│
├── groupByCustomer()   build per-customer batches (for put_batch)
│
├── measure()       put_single    →  PutRequest
├── measureBatch()  put_batch     →  WriteMultipleRequest
├── measure()       get_single    →  GetRequest
├── measure()       get_batch     →  prepared query WHERE customerId = $cid
├── measure()       query_shard   →  prepared query WHERE customerId = $cid
└── measure()       query_amount  →  prepared query WHERE amount >= $lo AND amount <= $hi
│
└── compareTotal()  sequential vs getBatch — total time to read RECORDS rows
    ├── sequential: RECORDS × sdk.get()
    └── batch:      customers × sdk.getBatch()
```

## Code structure

```
src/main/java/com/example/dbperformtest/
├── Main.java                    entry point: connect, create table, seed, run benchmarks
│
├── client/
│   ├── NoSqlClient.java         interface: put, putBatch, get, getBatch, query*
│   └── SdkClientImpl.java       nosqldriver SDK implementation
│
├── model/
│   ├── Order.java               data record (customerId, orderId, amount, payload)
│   ├── ConsistencyLevel.java    enum: NONE_REQUIRED | ABSOLUTE
│   └── DurabilityLevel.java     enum: COMMIT_NO_SYNC | COMMIT_SYNC
│
└── workload/
    └── OrderCsvReader.java      reads data/orders.csv into List<Order>

data/
└── orders.csv                   1,000,000 static rows (50,000 customers × 20 orders)

scripts/
└── generate_orders_csv.py       regenerates data/orders.csv deterministically
```
