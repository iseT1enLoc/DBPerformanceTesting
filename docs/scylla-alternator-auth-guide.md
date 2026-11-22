# ScyllaDB Alternator Authentication Guide

---

## Cách 1 — Auth tắt (default, dùng cho local dev)

Không cần làm gì thêm. `scylla.yaml` mặc định không có `alternator_enforce_authorization`.

Trong code Java dùng bất kỳ giá trị nào cho access key / secret key:

```java
AwsBasicCredentials.create("any", "any")
```

Hoặc trong `application.properties`:

```properties
scylla.access-key=any
scylla.secret-key=any
```

---

## Cách 2 — Auth bật (dùng cho production / staging)

### Bước 1 — Bật auth trong `scylla.yaml`

Mở `scylla.yaml`, uncomment dòng:

```yaml
alternator_enforce_authorization: true
```

Restart container:

```bash
docker compose restart scylla
```

---

### Bước 2 — Tạo key bằng cqlsh

Khi auth bật, cqlsh cần login bằng superuser mặc định `cassandra`:

```bash
docker exec -it scylla cqlsh -u cassandra -p cassandra
```

Tạo role — `role name` = access key ID, `password` = secret key:

```sql
CREATE ROLE "MY_ACCESS_KEY"
    WITH PASSWORD = 'MY_SECRET_KEY'
    AND LOGIN = true;
```

Cấp quyền theo nhu cầu:

```sql
-- quyền đọc ghi một table cụ thể
GRANT SELECT, MODIFY ON TABLE alternator_orders.orders TO "MY_ACCESS_KEY";

-- quyền toàn bộ (admin)
GRANT ALL PERMISSIONS ON ALL KEYSPACES TO "MY_ACCESS_KEY";
```

Kiểm tra role đã tạo:

```sql
SELECT role, can_login FROM system_auth.roles;
```

Đổi password mặc định của superuser (khuyến nghị):

```sql
ALTER ROLE cassandra WITH PASSWORD = 'new-strong-password';
```

---

### Bước 3 — Cập nhật config trong `application.properties`

```properties
scylla.endpoint=http://localhost:8000
scylla.access-key=MY_ACCESS_KEY
scylla.secret-key=MY_SECRET_KEY
```

---

### Xóa / đổi key

```sql
-- đổi password (secret key)
ALTER ROLE "MY_ACCESS_KEY" WITH PASSWORD = 'NEW_SECRET_KEY';

-- xóa role
DROP ROLE "MY_ACCESS_KEY";
```

---

## Tóm tắt

| | Auth tắt | Auth bật |
|---|---|---|
| Config | Mặc định | `alternator_enforce_authorization: true` |
| Access key | Bất kỳ | Tạo bằng `CREATE ROLE` trong cqlsh |
| Secret key | Bất kỳ | `WITH PASSWORD = '...'` |
| Key lưu ở đâu | Không có | `system_auth.roles` trong ScyllaDB |
| Dùng khi | Local dev | Production / staging |
