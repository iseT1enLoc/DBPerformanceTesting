# Spring Bean Initialization Flow

## Phase 1 — `mvn clean install` (compile time)

```
mvn clean install
│
├── clean        → xóa thư mục target/
│
├── compile      → javac biên dịch tất cả .java → .class
│                  Spring annotations lúc này chỉ là metadata
│                  được ghi vào bytecode, CHƯA xử lý gì
│
├── test         → chạy unit tests (nếu có)
│
└── package      → đóng gói thành fat jar
                   target/db-perform-test-0.0.1-SNAPSHOT.jar
```

> Spring **chưa làm gì** ở bước này. Annotation chỉ là nhãn dán trên class.

---

## Phase 2 — `java -jar ...` hoặc `mvn spring-boot:run` (runtime)

```
JVM start
│
└── SpringApp.main()
    │
    └── SpringApplication.run(SpringApp.class)
        │
        ├── 1. TẠO ApplicationContext (cái hộp chứa tất cả beans)
        │
        ├── 2. COMPONENT SCAN
        │   Quét toàn bộ package com.example.dbperformtest.api.*
        │   Tìm class có @Component / @RestController
        │   → Phát hiện:
        │       InMemoryUserRepository  (@Component)
        │       PostgresUserRepository  (@Component)
        │       MySqlUserRepository     (@Component)
        │       UserService             (@Component)
        │       UserController          (@RestController)
        │
        ├── 3. ĐỌC applicationContext.xml  (do @ImportResource)
        │   → Phát hiện thêm:
        │       accessTokenProvider
        │       noSqlHandleConfig
        │       noSqlHandle
        │       userCrudTest
        │
        ├── 4. XÂY DỰNG DEPENDENCY GRAPH
        │   Spring phân tích: thằng nào cần thằng nào?
        │
        │   UserController    →  cần userService
        │   UserService       →  cần inMemoryUserRepository
        │   PostgresRepo      →  cần ${db.postgres.url}  (từ application.properties)
        │   MySqlRepo         →  cần ${db.mysql.url}, username, password
        │   noSqlHandle       →  cần noSqlHandleConfig
        │   noSqlHandleConfig →  cần accessTokenProvider
        │
        ├── 5. KHỞI TẠO BEANS (theo đúng thứ tự dependency)
        │   Thằng nào không phụ thuộc ai → tạo trước
        │
        │   [1] new StoreAccessTokenProvider()
        │   [2] new NoSQLHandleConfig("http://localhost:8180", accessTokenProvider)
        │   [3] NoSQLHandleFactory.createNoSQLHandle(noSqlHandleConfig)
        │   [4] new InMemoryUserRepository()
        │   [5] new PostgresUserRepository("jdbc:postgresql://...")
        │   [6] new MySqlUserRepository("jdbc:mysql://...", "root", "secret")
        │   [7] new UserService()
        │          └── @Resource → inject inMemoryUserRepository vào field
        │   [8] new UserController()
        │          └── @Resource → inject userService vào field
        │   [9] new UserCrudTest(noSqlHandle)
        │
        └── 6. START EMBEDDED TOMCAT
            Đăng ký UserController để handle HTTP request
            Server lắng nghe port 8080
            "Started SpringApp in X seconds"
```

---

## Singleton — beans chỉ được tạo 1 lần

Tất cả beans là singleton — Spring tạo mỗi bean **đúng 1 lần** lúc startup,
lưu vào ApplicationContext. Mỗi lần `@Resource` inject, Spring lấy ra
**cùng 1 object đó**, không tạo mới.

```
ApplicationContext (cái kho)
├── "inMemoryUserRepository"  →  object InMemoryUserRepository@3a4b5c
├── "postgresUserRepository"  →  object PostgresUserRepository@7f8e9d
├── "mySqlUserRepository"     →  object MySqlUserRepository@1b2c3d
├── "userService"             →  object UserService@4d5e6f
│                                  field inMemoryUserRepository
│                                    = object @3a4b5c (cùng object trên)
└── "userController"          →  object UserController@9a0b1c
                                   field userService
                                     = object @4d5e6f (cùng object trên)
```

---

## Khi có HTTP request đến

Bean **không được tạo lại** mỗi request — tất cả đã sẵn sàng từ lúc startup.

```
Request đến port 8080
│
└── Tomcat nhận request
    └── Spring MVC tìm UserController (đã có sẵn trong context)
        └── gọi userController.listAll()
            └── gọi userService.listAll()               (field đã inject từ startup)
                └── gọi inMemoryUserRepository.findAll() (field đã inject từ startup)
                    └── trả về data
```

---

## Annotation cheat sheet

| Annotation | Dùng để | Ví dụ |
|---|---|---|
| `@Component("name")` | Đăng ký class là bean với tên cụ thể | `@Component("inMemoryUserRepository")` |
| `@Component` | Đăng ký bean, tên = tên class viết thường | `@Component` → bean name `userService` |
| `@RestController` | Đăng ký bean + xử lý HTTP request | `@RestController` trên `UserController` |
| `@Resource` | Inject bean, dùng **tên field** làm bean name | `private UserService userService` |
| `@Resource(name="...")` | Inject bean theo tên cụ thể | `@Resource(name = "postgresUserRepository")` |
| `@Value("${key}")` | Inject giá trị từ `application.properties` | `@Value("${db.postgres.url}")` |
| `@ImportResource` | Load thêm file XML bean vào context | `@ImportResource("classpath:applicationContext.xml")` |

---

## Debug Checklist — Trace bean wiring

Khi gặp lỗi injection (NoSuchBeanDefinitionException, NullPointerException trên field inject),
đi theo đúng thứ tự này để tìm root cause.

```
@Resource field
↓
Interface
↓
implements ?
↓
Bean đăng ký ở XML hay @Component ?
↓
Context có load bean đó không ?
↓
Bean đó lại inject tiếp cái gì ?
↓
Root cause
```

---

### Trace 1 — `UserController` → `UserService`

```
@Resource field
└── UserController.java:28
    @Resource
    private UserService userService;         ← field name = "userService"
        ↓
Interface?
└── UserService là class thường, không phải interface
        ↓
implements?
└── không có, Spring tìm thẳng bean tên "userService"
        ↓
Bean đăng ký ở đâu?
└── UserService.java → @Component           ← annotation, không phải XML
    tên mặc định = "userService" (lowercase class name)
        ↓
Context có load không?
└── SpringApp.java → @SpringBootApplication
    component scan quét com.example.dbperformtest.api.*
    → thấy @Component trên UserService → LOAD ✓
        ↓
Bean đó inject tiếp cái gì?
└── UserService.java:32
    @Resource
    private UserRepository inMemoryUserRepository;
        ↓
Root cause của chain này: inMemoryUserRepository (xem Trace 2)
```

---

### Trace 2 — `UserService` → `UserRepository`

```
@Resource field
└── UserService.java:32
    @Resource
    private UserRepository inMemoryUserRepository;   ← field name = "inMemoryUserRepository"
        ↓
Interface?
└── UserRepository.java
    api/repository/UserRepository.java
        ↓
implements?
├── InMemoryUserRepository  implements UserRepository
├── PostgresUserRepository  implements UserRepository
└── MySqlUserRepository     implements UserRepository
        ↓
Bean đăng ký ở đâu?
├── InMemoryUserRepository.java  → @Component("inMemoryUserRepository")  ← annotation ✓
├── PostgresUserRepository.java  → @Component("postgresUserRepository")  ← annotation
└── MySqlUserRepository.java     → @Component("mySqlUserRepository")     ← annotation
        ↓
Context có load không?
└── SpringApp component scan thấy cả 3 → LOAD cả 3 ✓
    field name "inMemoryUserRepository" khớp @Component("inMemoryUserRepository")
    → Spring inject InMemoryUserRepository
        ↓
Bean đó inject tiếp cái gì?
├── InMemoryUserRepository  → không inject gì thêm       ← ROOT CAUSE ✓
├── PostgresUserRepository  → @Value("${db.postgres.url}")
└── MySqlUserRepository     → @Value("${db.mysql.url}"), username, password
        ↓
Root cause: HashMap trong InMemoryUserRepository
```

---

### Trace 3 — XML beans (Oracle NoSQL)

```
@Resource field
└── không có @Resource, lấy qua context.getBean() trong Main.java
        ↓
Interface?
└── NoSQLHandle (Oracle interface)
        ↓
implements?
└── Oracle SDK tạo qua factory, không phải new trực tiếp
        ↓
Bean đăng ký ở đâu?
└── applicationContext.xml                   ← XML, không phải annotation
    vì Oracle class không thể thêm @Component
    <bean id="noSqlHandle"
          class="oracle.nosql.driver.NoSQLHandleFactory"
          factory-method="createNoSQLHandle">
        <constructor-arg ref="noSqlHandleConfig"/>
    </bean>
        ↓
Context có load không?
└── SpringApp.java → @ImportResource("classpath:applicationContext.xml")
    → Spring đọc XML và merge vào context → LOAD ✓
        ↓
Bean đó inject tiếp cái gì?
└── noSqlHandle ← noSqlHandleConfig ← accessTokenProvider
        ↓
Root cause: new StoreAccessTokenProvider()   ← ROOT CAUSE ✓
```

---

## Switching implementation

Đổi database chỉ cần đổi tên field trong `UserService.java`:

```java
// Đang dùng in-memory
@Resource
private UserRepository inMemoryUserRepository;

// Muốn đổi sang Postgres → đổi tên field
@Resource
private UserRepository postgresUserRepository;

// Muốn đổi sang MySQL → đổi tên field
@Resource
private UserRepository mySqlUserRepository;
```

`@Resource` không có `name` → Spring dùng **tên field** để tìm bean.
Tên field = tên đã đăng ký trong `@Component("...")`.
