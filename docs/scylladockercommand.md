# ScyllaDB Docker Commands

## Start / Stop

```bash
docker compose up -d          # start in background
docker compose down           # stop, keep data
docker compose down -v        # stop and wipe data volume
docker compose logs -f scylla # tail logs
```

## Enter CQL shell

```bash
# from inside the container
docker exec -it scylla cqlsh

# specifying host/port explicitly
docker exec -it scylla cqlsh localhost 9042
```

## Useful cqlsh commands

```sql
-- list all keyspaces
DESCRIBE KEYSPACES;

-- Alternator tables live under alternator_<tablename>
USE alternator_orders;
DESCRIBE TABLES;

SELECT * FROM alternator_orders.orders LIMIT 10;

-- check cluster status
DESCRIBE CLUSTER;
```

## Check node status

```bash
docker exec -it scylla nodetool status
```

## Run from host (if cqlsh installed locally)

```bash
cqlsh localhost 9142
```
