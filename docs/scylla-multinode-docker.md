# ScyllaDB Multi-node Docker Setup

---

## Tổng quan

File `docker-compose.two-nodes.yml` mô phỏng một 2-node ScyllaDB cluster để test `alternator-client-java` trong điều kiện gần với production hơn so với single-node.

---

## Tại sao phải chạy test từ bên trong Docker?

`alternator-client-java` tự động **discover node** qua endpoint `/localnodes`:

```
GET http://scylla-node1:8000/localnodes
→ ["172.29.0.2", "172.29.0.3"]
```

Thư viện lưu các IP này làm danh sách live node và route request đến chúng **trực tiếp theo IP**, bỏ qua hoàn toàn port mapping của Docker.

Trên WSL2, Docker bridge IP (`172.x.x.x`) **không routable** từ host machine. Nếu chạy test từ host, thư viện discover được `172.29.0.3` nhưng không kết nối được → `No live nodes available`.

**Giải pháp:** Chạy test-runner container trên **cùng Docker network** với ScyllaDB. Trong Docker network, container giao tiếp thẳng với nhau qua IP — không cần port mapping, không có vấn đề routing.

---

## Kiến trúc

```
Docker network: scylla-net
┌─────────────────────────────────────────────┐
│                                             │
│  scylla-node1 (172.29.0.2)                  │
│  scylla-node2 (172.29.0.3)  ←seeds node1   │
│                                             │
│  test-runner (maven:3.9-eclipse-temurin-17) │
│    ├── mount: .:/workspace                  │
│    ├── mount: maven-cache:/root/.m2         │
│    └── connects to scylla-node1:8000        │
│                                             │
└─────────────────────────────────────────────┘
```

- **Auth tắt** — không cần `--authenticator PasswordAuthenticator`, access-key/secret-key dùng giá trị bất kỳ.
- **node2 dùng node1 làm seed** — Docker DNS tự resolve `scylla-node1` thành IP của container.
- **Maven cache** — volume `maven-cache` giữ lại `.m2` giữa các lần chạy, tránh download lại dependencies.

---

## Cách chạy

```bash
docker compose -f docker-compose.two-nodes.yml up \
  --abort-on-container-exit \
  --exit-code-from test-runner
```

| Flag | Ý nghĩa |
|---|---|
| `--abort-on-container-exit` | Dừng toàn bộ stack khi bất kỳ container nào exit |
| `--exit-code-from test-runner` | Exit code của lệnh compose = exit code của test-runner (0 = pass, 1 = fail) |

---

## Flow khởi động

```
1. scylla-node1 khởi động
2. scylla-node2 đợi node1 healthy, rồi khởi động và join cluster (--seeds scylla-node1)
3. test-runner đợi cả 2 node healthy, rồi:
   a. Poll GET /localnodes cho đến khi trả về 2 IP (node2 đã join xong)
   b. mvn test -Dtest=TwoNodeClusterTest
4. test-runner exit → toàn bộ stack dừng
```

---

## Tests (`TwoNodeClusterTest.java`)

| Test | Kiểm tra gì |
|---|---|
| `two_nodes_are_discovered` | `/localnodes` trả về 2 IP (xác nhận cluster 2 node) |
| `user_crud` | Put / Get / Delete trên bảng `users` |
| `order_query_returns_all_orders_for_customer` | Put nhiều orders, Query theo customerId |
| `hundred_writes_all_succeed` | 100 PutItem liên tiếp, verify library route thành công qua cả 2 node |

---

## So sánh single-node vs two-node

| | `docker-compose.yml` | `docker-compose.two-nodes.yml` |
|---|---|---|
| Số node | 1 | 2 |
| Auth | Bật (`PasswordAuthenticator`) | Tắt |
| Chạy test từ | Host (`mvn test`) | Trong Docker (`test-runner` container) |
| broadcast-address | `127.0.0.1` (fix routing WSL2) | Không cần (test-runner cùng network) |
| Mục đích | Dev hàng ngày, auth flow | Test load balancing, cluster behavior |

---

## Vấn đề broadcast-address trong multi-node

Trong single-node, fix routing WSL2 bằng cách set `--broadcast-address 127.0.0.1` để `/localnodes` trả về địa chỉ reachable từ host.

Trong multi-node, **không thể dùng trick này** vì:
- Nếu node1 broadcast `127.0.0.1` và node2 broadcast `127.0.0.2` — node1 sẽ gossip đến `127.0.0.2` (loopback của chính node1), không phải node2 → cluster không thể hình thành.
- Nếu cả 2 đều broadcast `127.0.0.1` — `/localnodes` trả về `["127.0.0.1", "127.0.0.1"]` → deduplicate còn 1 node.

Giải pháp dứt điểm: **đặt client vào cùng network với DB**, như `test-runner` container ở trên. Đây cũng là cách deploy production thực tế (app server cùng VPC/subnet với DB).

---

## Dọn dẹp

```bash
# Xóa containers + volumes (data sẽ mất)
docker compose -f docker-compose.two-nodes.yml down -v
```
