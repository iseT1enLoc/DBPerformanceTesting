# JVM Startup Flow

## 1. Các giai đoạn khởi động

```
JVM start
   │
   ├── 1. Class Loading
   ├── 2. Bytecode Interpretation
   ├── 3. JIT Compilation (C1 → C2)
   └── 4. Steady State
```

---

## 2. Class Loading

Khi JVM khởi động, nó chưa load tất cả class vào memory. Class chỉ được load **lazy** — khi code chạy đến lần đầu tiên.

```
Thread gọi KvClientImpl.put()
   → ClassLoader tìm KvClientImpl.class trên classpath
   → Verify bytecode (kiểm tra type safety)
   → Resolve các dependency (Table, Row, ...)
   → Link & khởi tạo static fields
```

Lần đầu tiên gọi một method → class chưa được load → tốn thêm thời gian so với các lần sau.

---

## 3. Bytecode Interpretation

Mặc định JVM **không compile Java bytecode thành native code ngay**. Nó dùng **interpreter** để đọc và chạy từng bytecode instruction.

- Interpreter linh hoạt nhưng chậm hơn native code ~10–50x
- JVM đếm số lần mỗi method được gọi (invocation counter)

---

## 4. JIT Compilation — hai tầng

Khi một method được gọi đủ nhiều lần, JVM kích hoạt JIT (Just-In-Time) compiler.

### C1 (Client Compiler) — ~1.500 lần gọi

- Compile nhanh, tối ưu nhẹ
- Kết quả: native code cơ bản, nhanh hơn interpreter ~3–5x
- Vẫn insert instrumentation để đo tiếp

### C2 (Server Compiler) — ~10.000 lần gọi

- Compile chậm hơn, tối ưu sâu
- Áp dụng: inlining, loop unrolling, escape analysis, dead code elimination
- Kết quả: native code tối ưu, nhanh hơn interpreter ~20–50x

```
Interpreter → (1.500 calls) → C1 → (10.000 calls) → C2 (fully optimized)
```

> Ngưỡng mặc định trên Java 17+ với `-XX:+TieredCompilation` (bật mặc định).

---

## 5. Các yếu tố "lạnh" khác ngoài JIT

| Yếu tố | Trạng thái lạnh | Trạng thái warm |
|---|---|---|
| JIT compilation | Interpreter / C1 | C2 optimized |
| Connection pool | Chưa có connection nào | Pool đầy, reuse connection |
| DB buffer cache | Data chưa ở RAM | Data đã được cache |
| OS page cache | File chưa được read | File đã ở page cache |
| CPU branch predictor | Chưa học pattern | Đã học, predict đúng |

---

## 6. Tại sao benchmark cần warmup

Nếu đo từ đầu không có warmup:

```
op 1–10:    class loading + interpreter           → latency rất cao (outlier)
op 11–200:  C1 compiled, pool đang fill           → latency cao
op 201–500: C2 compiling (pause)                  → latency spike
op 501+:    C2 steady, pool warm, cache warm      → latency đại diện thực tế
```

Đo tất cả từ op 1 → p99 bị kéo lên bởi noise từ giai đoạn cold start, không phản ánh performance thật của DB hay driver.

**Warmup đúng cách:**

```java
// Bỏ kết quả
for (int i = 0; i < WARMUP; i++) client.put(...);

// Đo thật
for (int i = 0; i < OPS; i++) {
    long t0 = System.nanoTime();
    client.put(...);
    record(System.nanoTime() - t0);
}
```

---

## 7. Flags hữu ích khi debug JIT

```bash
# Xem method nào được JIT compile
-XX:+PrintCompilation

# Xem C2 có inline method nào không
-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining

# Force C2 ngay từ đầu (bỏ C1), dùng để test
-XX:-TieredCompilation -XX:CompileThreshold=1
```

---

## 8. Tóm tắt

```
Cold JVM
  └── Interpreter (chậm)
        └── C1 after ~1.500 calls (nhanh hơn)
              └── C2 after ~10.000 calls (tối ưu nhất)
                    └── Steady state → đây mới là con số benchmark đáng tin
```

Warmup trong benchmark = chờ JVM đạt steady state trước khi bắt đầu đo.
