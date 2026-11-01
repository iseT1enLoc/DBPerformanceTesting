# DBPerformanceTesting

Benchmarks the two Oracle NoSQL Java clients against the same store and workload:

| Client | Artifact | Path to the store |
|---|---|---|
| `kvclient` | `com.oracle.kv:kvclient` (KV direct driver) | App → storage nodes (TCP, port 5100) |
| `sdk` | `com.oracle.nosql.sdk:nosqldriver` (NoSQL SDK) | App → HTTP proxy (port 8180) |

## Operations benchmarked

| Operation | kvclient | sdk |
|---|---|---|
| `put_single` | `TableAPI.put` | `PutRequest` |
| `put_batch` | `TableAPI.execute` (atomic, one shard key) | `WriteMultipleRequest` |
| `put_bulk` | `TableAPI.put(streams)` (cross-shard, parallel) | not supported |
| `get_single` | `TableAPI.get` | `GetRequest` |
| `get_batch` | `TableAPI.multiGet` by shard key | prepared shard-key query |
| `query_shard` | `SELECT * … WHERE customerId = $cid` | same SQL |

`put_bulk` is kvclient-only — it streams rows across all shards in parallel and is significantly faster than sequential inserts for bulk data loading.

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

1. Start KVLite with the HTTP proxy via Docker Compose:
   ```bash
   docker compose up -d oracle-nosql
   ```
   KVLite listens on port **5100** (mapped from container 5000) and the proxy on **8180** (mapped from container 8080).

2. Add the container hostname to `/etc/hosts` so kvclient can resolve it:
   ```bash
   # Get the actual container IP (may change after Docker restarts)
   docker inspect dbperformtest-oracle-nosql --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'

   echo "<container-ip> kvlite" | sudo tee -a /etc/hosts
   ```

3. Install kvclient into the local Maven repo (not on Maven Central):
   ```bash
   ./scripts/install-kvclient.sh
   ```

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
| `BULK_BATCH` | `1_000` | Rows per `putBulk` call |
| `INSERT_RUNS` | `5` | Repetitions for the sequential-vs-bulk comparison |

## Output

```
client      operation            ops/s     avg ms/op
----------------------------------------------------
kvclient    put_single           5,906         0.169
kvclient    put_batch            2,478         0.403
kvclient    put_bulk               157         6.366
kvclient    get_single           6,407         0.156
kvclient    get_batch            3,376         0.296
kvclient    query_shard          3,211         0.311

sdk         put_single           1,630         0.613
sdk         put_batch            1,137         0.879
sdk         put_bulk               n/a           n/a
sdk         get_single           2,104         0.475
sdk         get_batch            1,496         0.668
sdk         query_shard          1,482         0.675


--- Insert 100000 records: sequential vs putBulk(batch=1000) [kvclient, 5 runs] ---
method                          rows/s      total ms       calls
----------------------------------------------------------------
sequential (put_single)          5,589       17890.9     100,000
putBulk (batch=1000)           163,188         612.8         100

--- Read 100000 records: sequential vs getBatch [kvclient, 5 runs] ---
method                          rows/s      total ms       calls
----------------------------------------------------------------
sequential (get_single)          6,225       16065.0     100,000
getBatch (per customer)         64,945        1539.8       5,000

--- Read 100000 records: sequential vs getBatch [sdk, 5 runs] ---
method                          rows/s      total ms       calls
----------------------------------------------------------------
sequential (get_single)          2,048       48824.1     100,000
getBatch (per customer)         27,252        3669.5       5,000
```

## Results (100,000 records — i7-13650HX, WSL2, Docker bridge)

### kvclient vs SDK — per-operation throughput

| operation | kvclient ops/s | sdk ops/s | kvclient advantage |
|---|---|---|---|
| put_single | 5,906 | 1,630 | **3.6×** |
| put_batch | 2,478 | 1,137 | **2.2×** |
| get_single | 6,407 | 2,104 | **3.0×** |
| get_batch | 3,376 | 1,496 | **2.3×** |
| query_shard | 3,211 | 1,482 | **2.2×** |

kvclient is consistently faster because it talks directly to the storage nodes over TCP. The SDK adds an extra HTTP proxy hop for every call.

### Write: sequential vs putBulk (kvclient)

| method | rows/s | total ms | calls | speedup |
|---|---|---|---|---|
| sequential (put_single) | 5,589 | 17,891 ms | 100,000 | — |
| putBulk (batch=1,000) | 163,188 | 613 ms | 100 | **29×** |

putBulk sends 1,000 rows per round-trip. Reducing 100,000 blocking calls to 100 eliminates network-latency stacking and lets the server write shards in parallel.

### Read: sequential vs getBatch

| client | method | rows/s | total ms | calls | speedup |
|---|---|---|---|---|---|
| kvclient | sequential (get_single) | 6,225 | 16,065 ms | 100,000 | — |
| kvclient | getBatch (per customer) | 64,945 | 1,540 ms | 5,000 | **10×** |
| sdk | sequential (get_single) | 2,048 | 48,824 ms | 100,000 | — |
| sdk | getBatch (per customer) | 27,252 | 3,670 ms | 5,000 | **13×** |

getBatch fetches all 20 orders for a customer in one round-trip. 5,000 calls instead of 100,000 — each call returns 20 rows. The SDK gains more from batching (13×) than kvclient (10×) because its per-call overhead (HTTP proxy) is higher, so reducing call count matters more.

## Call dataflow

```
Main.main()
├── retry()  →  KVStoreFactory.getStore()        connect kvclient (localhost:5100)
├── retry()  →  NoSQLHandleFactory.createNoSQLHandle()  connect sdk (localhost:8180)
│
├── kv.executeDdl()   CREATE TABLE IF NOT EXISTS orders (...)
├── kv.executeDdl()   CREATE INDEX IF NOT EXISTS idx_amount ON orders (amount)
│
├── OrderCsvReader.read()   load first RECORDS rows from data/orders.csv
├── kv.put() × RECORDS      seed the table
│
├── groupByCustomer()   build per-customer batches  (for put_batch)
├── partition()         build fixed-size batches    (for put_bulk)
│
├── for each client [kvclient, sdk]:
│   ├── measure()       put_single
│   ├── measureBatch()  put_batch
│   ├── measureBatch()  put_bulk      (kvclient only; prints n/a for sdk)
│   ├── measure()       get_single
│   ├── measure()       get_batch
│   └── measure()       query_shard
│
├── compareTotal()  sequential vs putBulk — total time to insert RECORDS rows (kvclient)
│   ├── sequential: RECORDS × kv.put()
│   └── bulk:       (RECORDS/BULK_BATCH) × kv.putBulk()
│
└── for each client [kvclient, sdk]:
    compareTotal()  sequential vs getBatch — total time to read RECORDS rows
    ├── sequential: RECORDS × client.get()
    └── batch:      customers × client.getBatch()
```

## Code structure

```
src/main/java/com/example/dbperformtest/
├── Main.java                    entry point: connect, seed table, run all benchmarks
│
├── client/
│   ├── NoSqlClient.java         interface: put, putBatch, putBulk, get, getBatch, query*
│   ├── KvClientImpl.java        kvclient (KV direct driver) implementation
│   ├── SdkClientImpl.java       NoSQL SDK (HTTP proxy) implementation
│   └── Sql.java                 SQL shared by both clients (byCustomer, byAmount)
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
├── generate_orders_csv.py       regenerates data/orders.csv deterministically
└── install-kvclient.sh          copies kvclient jar from the Docker image into local Maven repo
```
