# DynamoDB → ScyllaDB Migration Investigation

## Two Paths

| Path | When to use | Code change |
|---|---|---|
| **Alternator** (DynamoDB-compatible API) | Minimal risk, keep DynamoDB SDK | Endpoint URL only |
| **Native CQL** (Cassandra/ScyllaDB driver) | Full migration, higher performance ceiling | Driver + query rewrite |

---

## Alternator (DynamoDB SDK compatibility layer)

ScyllaDB exposes a DynamoDB-compatible HTTP API. The AWS SDK works with just an endpoint override:

```java
DynamoDbClient client = DynamoDbClient.builder()
    .endpointOverride(URI.create("http://localhost:8000"))
    .region(Region.US_EAST_1)
    .build();
```

### Works but behaves differently

| Feature | Difference |
|---|---|
| Provisioning | `BillingMode` / `ProvisionedThroughput` ignored; always pay-per-request |
| Scan ordering | Different order (Murmur3 vs DynamoDB hash) — do not rely on Scan order |
| TTL | Expiration delayed up to 24h by default (configurable) |
| Metrics | No CloudWatch; use Prometheus/Grafana |
| Load balancing | No built-in; need external load balancer or client-side routing |

### Write isolation — critical setting

| Policy | Safety | Speed |
|---|---|---|
| `always_use_lwt` | Always safe, serializes writes per item | Slower (~5× vs non-LWT) |
| Other modes | Only safe for single-writer workloads | Faster |

Without `always_use_lwt`, concurrent writes to the same item can produce a mix of attributes from both writes. Set this explicitly before going to production.

### Not supported (returns errors)

| Feature | Alternative |
|---|---|
| `TransactWriteItems` / `TransactGetItems` | Single-item conditional writes work; no multi-item transactions |
| Adding/removing GSI via `UpdateTable` | Define GSIs at table creation only |
| PartiQL (`ExecuteStatement` etc.) | Use CQL instead |
| On-demand backups / PITR | ScyllaDB snapshots or ScyllaDB Manager |
| Kinesis Streams integration | Not supported |
| Export/import to S3 | Not supported |
| DAX | Not available |

### DynamoDB Streams (experimental)

Requires `--experimental-features=alternator-streams`:
- Far more shards than DynamoDB
- ~10s delay before records appear
- A single `PutItem` can appear as `REMOVE` + `MODIFY` events

### Authentication

- Off by default — enable with `alternator_enforce_authorization: true`
- Uses AWS Signature V4 (same as DynamoDB SDK)
- Roles created in CQL act as access key IDs; permissions set via CQL `GRANT`

---

## Native CQL Migration

### Data Model & Schema

| DynamoDB | ScyllaDB |
|---|---|
| Partition key + optional sort key | Partition key + clustering columns |
| Schema-less per item | Strict schema; all columns declared at `CREATE TABLE` |
| GSI / LSI | Materialized views (preferred) or secondary indexes |

- Map sort keys → clustering columns with explicit `WITH CLUSTERING ORDER BY`
- Replace GSI/LSI with materialized views; avoid secondary indexes on high-cardinality columns
- No `ALLOW FILTERING` in production — every `WHERE` must start with full partition key

### Query Translation

| DynamoDB | ScyllaDB CQL |
|---|---|
| `GetItem` | `SELECT … WHERE pk = ? AND ck = ?` |
| `PutItem` | `INSERT INTO … VALUES (…)` |
| `UpdateItem` | `UPDATE … SET col = ? WHERE pk = ?` |
| `DeleteItem` | `DELETE FROM … WHERE pk = ? AND ck = ?` |
| `Query` (sort key range) | `SELECT … WHERE pk = ? AND ck >= ? AND ck <= ?` |
| `Scan` | Avoid — expensive full scan |
| `BatchGetItem` | `IN` clause (small sets) or async parallel `SELECT` (large sets) |
| `BatchWriteItem` | Unlogged `BATCH` (same partition) |
| `TransactWriteItems` | LWT (`IF NOT EXISTS` / `IF col = ?`) — single partition only |

### Consistency

| DynamoDB | ScyllaDB |
|---|---|
| Eventually consistent read | `CONSISTENCY ONE` |
| Strongly consistent read | `CONSISTENCY QUORUM` |
| Conditional write | LWT `UPDATE … IF col = ?` |

### Data Types

| DynamoDB | CQL |
|---|---|
| `S` | `text` |
| `N` | `int` / `bigint` / `decimal` — choose explicitly, `N` is a string in DynamoDB |
| `B` | `blob` |
| `BOOL` | `boolean` |
| `L` | `list<T>` |
| `M` | `map<K,V>` or denormalized columns |
| `SS` / `NS` / `BS` | `set<T>` |

### Pagination

| DynamoDB | ScyllaDB |
|---|---|
| `LastEvaluatedKey` token | `PagingState` token |
| `Limit` | `fetchSize` on the query |

### TTL

```sql
-- ScyllaDB: set at write time per row
INSERT INTO orders (id, data) VALUES (?, ?) USING TTL 86400;
UPDATE orders USING TTL 3600 SET data = ? WHERE id = ?;
```

### Transactions

- ScyllaDB LWT is **single-partition only** — no cross-partition or cross-table transactions
- For multi-row consistency: unlogged batch (same partition) or redesign to avoid cross-partition writes

### Driver

```xml
<dependency>
    <groupId>com.scylladb</groupId>
    <artifactId>java-driver-core</artifactId>
    <version>4.18.0.0</version>
</dependency>
```

Connection is CQL native protocol on port **9042**, not HTTP.

---

## Performance Traps

- Hot partitions from low-cardinality keys → hash or add a bucket component to the partition key
- Large partitions (>100 MB) → add a time/bucket component to split them
- `IN` queries scatter across nodes → prefer async parallel `SELECT` for latency-sensitive paths
- Prepared statements are mandatory for throughput

---

## Data Migration

No built-in DynamoDB → ScyllaDB tool.

| Approach | When |
|---|---|
| Scan → transform → batch insert | Small datasets, acceptable downtime |
| DynamoDB Streams → dual-write consumer | Zero-downtime cutover |

Always validate: row count match + spot-check field values after migration.

---

## References

- [Alternator compatibility](https://opensource.docs.scylladb.com/stable/alternator/compatibility.html)
