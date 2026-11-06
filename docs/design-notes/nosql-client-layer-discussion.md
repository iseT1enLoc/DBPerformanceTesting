# NoSQL Client Layer — Design Discussion

## Context

Comparing two approaches for injecting a NoSQL client into a repository:

```java
// Option A — concrete generic impl
private final Nosqlclientimpl<Product, ProductKey> productClient;

// Option B — named client class (current approach)
private final ProductSdkClient client;
```

---

## Why `Nosqlclientimpl<Product, ProductKey>` directly is problematic

### Java Type Erasure

Generic type parameters exist only at compile time. At runtime, JVM erases them:

```
Nosqlclientimpl<Product, ProductKey>  ──┐
                                        ├── JVM sees: Nosqlclientimpl (same type!)
Nosqlclientimpl<User, UserKey>        ──┘
```

Spring injects beans by type. With two beans of the same erased type, Spring throws:
```
NoUniqueBeanDefinitionException: Expected single matching bean but found 2
```

This forces `@Qualifier("productClient")` — a magic string that is easy to typo and not refactor-safe.

### Named class solves this

```
ProductSdkClient  → JVM sees: ProductSdkClient  (unique!)
UserSdkClient     → JVM sees: UserSdkClient     (unique!)
```

No `@Qualifier` needed. The type itself is the identifier, checked by the compiler.

---

## Layer structure

```
Controller
    ↓
Service
    ↓
ProductRepository (interface)        ← boundary — where abstraction matters most
    ↓
NoSqlProductRepository (impl)        ← repository implementation
    ↓
ProductSdkClient                     ← client layer (implementation detail)
    ↓
SdkClientImpl<Product, ProductKey>   ← generic infrastructure
    ↓
NoSQLHandle (Oracle driver)
```

`ProductSdkClient` is below the repository layer — it is an implementation detail not visible to Service or above.

---

## Real issues identified

### 1. Inconsistency between Order vs Product/User

Order uses `@Bean` in a config class:
```java
@Bean("orderNoSqlClient")
public NoSqlClient<Order, OrderKey> orderNoSqlClient(NoSQLHandle handle) {
    return new SdkClientImpl<>(handle, new OrderRowMapper(), "orders");
}
```

Product/User use a dedicated class:
```java
@Component
public class ProductSdkClient extends SdkClientImpl<Product, ProductKey> { ... }
```

**Same problem, two different patterns in the same codebase.** This is the biggest real issue — new team members don't know which to follow.

### 2. `ProductSdkClient` is nearly empty

```java
@Component
public class ProductSdkClient extends SdkClientImpl<Product, ProductKey> {
    public ProductSdkClient(NoSQLHandle handle) {
        super(handle, new ProductRowMapper(), "products");  // only this
    }
}
```

The class has no logic. It exists only to give a concrete name and bake in generics. Worth asking: is this abstraction earning its place?

### 3. Testability is not fully clean

`NoSqlProductRepository` still depends on a concrete class. Unit testing without Oracle driver requires Mockito mocking a concrete class — it works, but is not best practice. A proper interface would fully decouple it.

---

## Two consistent options going forward

### Option A — Simpler, fewer files

```java
// Config
@Bean("productClient")
public NoSqlClient<Product, ProductKey> productClient(...) { ... }

// Repository
public NoSqlProductRepository(
        @Qualifier("productClient") NoSqlClient<Product, ProductKey> client) { ... }
```

### Option B — Cleaner for enterprise, fully testable

```java
// Interface
public interface ProductClient extends NoSqlClient<Product, ProductKey> {}

// Impl
@Component
public class ProductSdkClient extends SdkClientImpl<Product, ProductKey>
        implements ProductClient { ... }

// Repository depends on interface
private final ProductClient client;
```

---

## When is `ProductSdkClient` as a concrete class justified?

If `UserSdkClient` grows domain-specific methods:

```java
public class UserSdkClient extends SdkClientImpl<User, UserKey> {
    public List<User> findByEmail(String email) { ... }
    public List<User> findActiveUsersAfter(LocalDate date) { ... }
}
```

Then:
- If the **repository calls those extra methods** → depending on the concrete class is justified
- If the **repository only calls base interface methods** → still prefer an interface, let `UserSdkClient` grow independently without pulling the repository along
