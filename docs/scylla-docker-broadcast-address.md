# Tại sao cần `--broadcast-address` và `--broadcast-rpc-address` khi chạy Docker

---

## Vấn đề

`alternator-client-java` không hoạt động như raw AWS SDK. Khi khởi tạo client, thư viện **tự động hỏi cluster** "mày có bao nhiêu node?" qua endpoint `/localnodes`:

```
GET http://localhost:8000/localnodes
→ ["172.26.0.2"]
```

ScyllaDB trả về IP của chính nó — và đó là IP **nội bộ Docker bridge** (`172.26.x.x`), không phải `localhost`. Thư viện lưu địa chỉ này làm node duy nhất, rồi route **toàn bộ request tiếp theo** đến `172.26.0.2:8000`.

Từ host machine, địa chỉ đó không reach được → tất cả request fail với:

```
java.lang.IllegalStateException: No live nodes available
```

---

## Tại sao lại có 2 loại broadcast address?

ScyllaDB có **2 network plane** tách biệt, mỗi cái có địa chỉ broadcast riêng:

| Flag | Dùng cho | Ảnh hưởng đến |
|---|---|---|
| `--broadcast-address` | Giao tiếp node-to-node (gossip, replication) | `/localnodes` endpoint → alternator-client-java dùng cái này |
| `--broadcast-rpc-address` | Giao tiếp client-to-node (CQL driver) | CQL driver dùng để redirect client sau handshake |

Hai flag tách nhau vì trong production multi-DC, node-to-node traffic thường đi qua mạng internal (10.x.x.x), còn client traffic đi qua mạng public hoặc VIP khác.

---

## Flow khi không có 2 flag này

```
Host machine                    Docker container
                                (172.26.0.2)
Java app
  │
  ├─► GET localhost:8000/localnodes    ──► ScyllaDB
  │                                   ◄── ["172.26.0.2"]   ← IP nội bộ Docker
  │
  │   (thư viện lưu 172.26.0.2:8000 là live node, bỏ localhost)
  │
  ├─► PUT 172.26.0.2:8000/...         ──► ??? Connection refused
  │                                       (172.26.0.2 không reach được từ host)
  │
  └─► IllegalStateException: No live nodes available
```

---

## Flow sau khi thêm 2 flag

```
Host machine                    Docker container
                                (broadcast = 127.0.0.1)
Java app
  │
  ├─► GET localhost:8000/localnodes    ──► ScyllaDB
  │                                   ◄── ["127.0.0.1"]    ← đã override
  │
  │   (thư viện lưu 127.0.0.1:8000 là live node)
  │
  ├─► PUT 127.0.0.1:8000/...          ──► Docker port mapping
  │                                   ──► ScyllaDB ✓
  └─► OK
```

---

## Tại sao safe cho single-node dev?

`--broadcast-address` thường ảnh hưởng đến node-to-node gossip. Với single-node, không có node nào khác để gossip → set `127.0.0.1` hoàn toàn vô hại.

Nếu sau này scale lên multi-node cluster thật, cần bỏ 2 flag này và để ScyllaDB tự detect IP thật của từng node.

---

## Raw AWS SDK không bị vấn đề này

Vì raw SDK **không có node discovery**. Nó chỉ gọi thẳng đến `endpointOverride` bạn truyền vào và không bao giờ hỏi `/localnodes`:

```java
// Raw SDK — luôn gọi đến localhost:8000, không discover gì cả
DynamoDbClient.builder()
    .endpointOverride(URI.create("http://localhost:8000"))
    .build();

// alternator-client-java — gọi /localnodes trước, rồi dùng địa chỉ trả về
AlternatorDynamoDbClient.builder()
    .endpointOverride(URI.create("http://localhost:8000"))  // chỉ dùng để seed ban đầu
    .build();
```

Đánh đổi: raw SDK không có load balancing, không tự failover khi node chết. `alternator-client-java` có nhưng cần địa chỉ broadcast reachable.
