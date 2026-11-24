# ScyllaDB Alternator Authentication Guide

---

## Cách 1 — Auth tắt (default, dùng cho local dev)

Không cần làm gì thêm. Bỏ các flag auth trong `docker-compose.yml`.

Trong `application.properties` dùng bất kỳ giá trị nào:

```properties
scylla.access-key=any
scylla.secret-key=any
```

---

## Cách 2 — Auth bật (dùng cho production / staging)

### Bước 1 — Bật auth trong `docker-compose.yml`

Thêm 3 flags vào `command`:

```yaml
command: >
  --smp 1
  --memory 512M
  --overprovisioned 1
  --developer-mode 1
  --alternator-port 8000
  --alternator-write-isolation always_use_lwt
  --alternator-enforce-authorization 1
  --authenticator PasswordAuthenticator
  --authorizer CassandraAuthorizer
```

> **Lưu ý:** Không mount custom `scylla.yaml` — phải dùng command flags để ScyllaDB khởi tạo đúng `PasswordAuthenticator`.

Restart sạch (bắt buộc dùng `-v` lần đầu):

```bash
docker compose down -v && docker compose up -d
```

---

### Bước 2 — Tạo role trong cqlsh

```bash
docker exec scylla cqlsh <container-ip> 9042 -u cassandra -p cassandra
```

> Lấy container IP: `docker inspect scylla | grep IPAddress`

> **Warning "password on the command line interface can be insecure":** Đây chỉ là cảnh báo, lệnh vẫn chạy bình thường. Để tắt warning, dùng file credentials thay vì truyền `-p` trực tiếp:
> ```bash
> # Tạo file credentials bên trong container (chạy 1 lần)
> docker exec scylla bash -c "mkdir -p /root/.cassandra && cat > /root/.cassandra/cqlshrc << 'EOF'
> [authentication]
> username = cassandra
> password = cassandra
> EOF"
> ```
> Sau đó vào cqlsh **không cần** `-u` và `-p`:
> ```bash
> docker exec scylla cqlsh <container-ip> 9042
> ```

Tạo role với `SUPERUSER = true`:

```sql
CREATE ROLE "MY_ACCESS_KEY"
    WITH PASSWORD = 'MY_SECRET_KEY'
    AND LOGIN = true
    AND SUPERUSER = true;
```

> Roles lưu trong `system.roles`, **không phải** `system_auth.roles`.

Kiểm tra:

```sql
SELECT role, can_login, is_superuser FROM system.roles;
```

---

### Bước 3 — Lấy secret key thực (salted_hash)

**Quan trọng:** Alternator không dùng plaintext password. Secret key thực là `salted_hash` trong `system.roles`:

```sql
SELECT salted_hash FROM system.roles WHERE role = 'MY_ACCESS_KEY';
```

Copy giá trị hash trả về, ví dụ:
```
$6$QW3llEwjG5lMNP50$jHwt1hx43Mhq4vPJ343I5k.d1vn25aI9OWJhI...
```

---

### Bước 4 — Cập nhật `application.properties`

```properties
scylla.endpoint=http://localhost:8000
scylla.access-key=MY_ACCESS_KEY
scylla.secret-key=<salted_hash từ bước 3>
```

---

### Đổi / xóa key

```sql
-- đổi password (phải lấy lại salted_hash mới sau khi đổi)
ALTER ROLE "MY_ACCESS_KEY" WITH PASSWORD = 'NEW_SECRET_KEY';

-- xóa role
DROP ROLE "MY_ACCESS_KEY";
```

---

## Tóm tắt

| | Auth tắt | Auth bật |
|---|---|---|
| docker-compose flags | Không cần | `--alternator-enforce-authorization 1` + `--authenticator PasswordAuthenticator` + `--authorizer CassandraAuthorizer` |
| Access key | Bất kỳ | Role name tạo bằng `CREATE ROLE` |
| Secret key | Bất kỳ | `salted_hash` từ `SELECT salted_hash FROM system.roles` |
| Key lưu ở đâu | Không có | `system.roles` (không phải `system_auth.roles`) |
| Restart cần `-v` | Không | Có (lần đầu enable auth) |
| Dùng khi | Local dev | Production / staging |

---

## Lưu ý quan trọng

- **`PasswordAuthenticator` lưu bcrypt hash** — Alternator dùng `salted_hash` này làm secret key, không dùng plaintext password
- **`system_auth.roles` không có tables** trong ScyllaDB 6.2 — roles được lưu trong `system.roles`
- **`SUPERUSER = true`** cần thiết để bypass permission checks khi chưa có keyspace nào tồn tại
- Sau khi `ALTER ROLE ... WITH PASSWORD`, phải query lại `salted_hash` mới và cập nhật `application.properties`
