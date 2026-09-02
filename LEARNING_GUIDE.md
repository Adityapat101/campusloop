# Learn the stack in six weeks

This guide teaches the ideas in the order they appear in the prototype. Do not try to memorize annotations. Learn which problem each layer solves, change the code, and inspect what happens.

## First: the complete mental model

A browser sends an HTTP request. Tomcat accepts it. Spring MVC selects a controller method. Validation checks the JSON. The controller calls a service, which applies business rules. A repository translates Java operations into SQL or MongoDB commands. The controller's return value becomes JSON.

```text
request → controller → service → repository → database
response ← JSON serialization ← returned Java object
```

Spring Boot is not a separate server you install. It is a set of Java libraries and conventions declared in `pom.xml`. Maven downloads those libraries and builds the application. The resulting JAR contains the app and an embedded web server.

## Week 1 — Java and the build

### Learn

- A class groups state and behavior; an object is one instance of a class.
- An interface specifies behavior without choosing its implementation. Spring Data creates repository implementations at runtime.
- A `record` is a compact immutable data carrier, useful for request DTOs.
- An `enum` limits a value to known choices such as `AVAILABLE` and `SOLD`.
- Exceptions stop the normal path and communicate a failure.
- Generics such as `List<Listing>` preserve the element type.
- `Optional<T>` makes “there may be no result” explicit.
- Maven reads `pom.xml`, resolves libraries, compiles, tests, and packages.

### Inspect

- `Listing.java`: class, constructor, fields, enum values, getters.
- `ListingRequest.java`: a record plus Bean Validation annotations.
- `ListingRepository.java`: an interface whose implementation is generated.
- `pom.xml`: the Spring parent, Java version, starters, driver, and build plugin.

### Practice

1. Add a `pickupLocation` string to `Listing` and `ListingRequest`.
2. Run `./scripts/test.sh` after every small change.
3. Run `./mvnw package`, inspect `target/`, and identify the executable JAR.

You are ready to move on when you can explain compile time versus runtime and class versus object.

## Week 2 — Spring Boot, dependency injection, and REST

### Learn

- `@SpringBootApplication` turns on component discovery and auto-configuration.
- `@RestController` declares an HTTP boundary whose return values become JSON.
- `@Service` marks business logic.
- Constructor injection means Spring creates dependencies and passes them in. This makes code testable and keeps object construction in one place.
- HTTP methods express intent: `GET` reads, `POST` creates.
- Status codes matter: `201 Created`, `400 Bad Request`, `404 Not Found`, and `409 Conflict` have different meanings.
- A DTO is the public API shape. An entity/document is the stored shape. Keeping them separate prevents callers from setting internal fields such as `status`.
- Bean Validation rejects malformed input before business logic runs.

### Follow one request

For `POST /api/listings`:

1. Spring parses JSON into `ListingRequest`.
2. `@Valid` checks required fields, sizes, and price.
3. `ListingController.create` calls `ListingService.create`.
4. The service verifies that the SQL seller exists.
5. `ListingRepository.save` inserts a MongoDB document.
6. The controller returns `201 Created` and a `Location` header.

### Practice

1. Submit an empty title and inspect the Problem Details JSON.
2. Add `DELETE /api/listings/{id}` but allow deletion only while available.
3. Add pagination instead of loading every available listing before filtering.

You are ready to move on when you can trace a request without guessing where a rule belongs.

## Week 3 — SQL, PostgreSQL, JPA, and transactions

### Relational fundamentals

A table has typed columns and rows. A primary key uniquely identifies a row. A unique constraint prevents duplicate values. A foreign key can require a referenced row to exist. A transaction groups operations into an all-or-nothing unit. An index speeds reads but costs storage and write work.

Useful SQL to run inside the container:

```bash
docker compose exec postgres psql -U marketplace -d marketplace
```

```sql
\dt
SELECT id, name, email, campus FROM users;
SELECT id, buyer_id, listing_id, price, status FROM purchase_orders;
SELECT buyer_id, COUNT(*) FROM purchase_orders GROUP BY buyer_id;
```

### JPA mapping

- `@Entity` maps a Java class to a table.
- `@Id` marks its primary key.
- `@GeneratedValue` delegates ID creation.
- `@Column` expresses nullability, uniqueness, length, and numeric precision.
- `JpaRepository<UserAccount, UUID>` supplies CRUD and query methods.
- A method named `findByBuyerIdOrderByCreatedAtDesc` becomes a query from its name.

`spring.jpa.hibernate.ddl-auto=update` is convenient for a prototype. In production, use versioned migrations such as Flyway so schema changes are reviewed and repeatable.

### Practice

1. Add a SQL `campuses` table and relate users to it.
2. Add an index for order lookup by `buyer_id`.
3. Write SQL joins that show buyer names next to orders.

You are ready to move on when you understand primary keys, constraints, transactions, and why money uses `BigDecimal`/`NUMERIC` instead of floating point.

## Week 4 — MongoDB and document modeling

### Learn

MongoDB stores BSON documents in collections. A listing is retrieved as one document rather than assembled from normalized tables. Documents in one collection can vary, though application validation should still protect important invariants.

Open the Mongo shell:

```bash
docker compose exec mongodb mongosh -u marketplace -p marketplace --authenticationDatabase admin marketplace
```

```javascript
db.listings.find({}, {title: 1, category: 1, price: 1, status: 1})
db.listings.find({status: "AVAILABLE"}).sort({createdAt: -1})
db.listings.getIndexes()
```

The compound index in `Listing.java` begins with `status` and `category`, matching common browse filters. Index field order matters because MongoDB can efficiently use left prefixes.

### SQL versus documents

| Need | PostgreSQL | MongoDB |
|---|---|---|
| Strong multi-row transactions | Excellent | Supported, with deployment requirements |
| Joins and reporting | Excellent | Usually model to avoid joins |
| Flexible category attributes | More migration work | Natural fit |
| Strict relational integrity | Native constraints | Usually enforced by application/schema rules |
| Read/write whole aggregate | Good | Natural fit |

Using two databases is not automatically better. This project does it to teach both and because the boundary is understandable. A smaller real product could reasonably use PostgreSQL alone with a JSON column for flexible listing attributes.

### Practice

1. Add category-specific `attributes`, such as ISBN for books.
2. Replace in-memory search filtering with indexed repository queries.
3. Explain why embedding a seller's full profile in every listing creates stale duplicated data.

## Week 5 — Cross-database workflows and the frontend

The hardest part is not syntax; it is maintaining truth across boundaries.

### Buying safely

Two buyers can click at once. Reading `AVAILABLE` and later writing `SOLD` is a race. `OrderService` uses one MongoDB `findAndModify` query that matches both listing ID and `AVAILABLE` status. Only one caller can change it and receive the sold document.

PostgreSQL and MongoDB do not share the normal local transaction manager. The prototype compensates by restoring the listing if the SQL order write fails. That is educational but not perfect: a crash between writes can still leave inconsistent state.

Production choices include:

- Use one database and one ACID transaction.
- Use an outbox and message broker, make handlers idempotent, and reconcile failures.
- Use a saga with explicit compensating actions and monitoring.
- Use distributed transactions only after understanding their operational cost and support constraints.

### Browser fundamentals

- HTML gives content semantic structure.
- CSS controls layout, visual hierarchy, and responsive breakpoints.
- JavaScript stores small UI state, calls the JSON API with `fetch`, escapes server text, and re-renders cards.
- Same-origin serving avoids CORS complexity: Spring serves both the UI and API from port 8080.

### Practice

1. Disable the buy button while its request is running and test double-click behavior.
2. Show a user's order history in the UI.
3. Add optimistic UI, then deliberately make the server fail and roll the UI back.

## Week 6 — testing, security, operations, and the path to production

### Testing layers

- Unit test: one class with dependencies replaced; fast and precise.
- Slice test: Spring loads one concern, such as MVC or JPA.
- Integration test: real components interact, ideally with disposable database containers.
- End-to-end test: exercises the system through HTTP or the browser.

The included order tests verify the atomic-claim success and already-sold conflict paths. Next, add Testcontainers for automatic PostgreSQL and MongoDB integration tests.

### Security checklist

Before real users:

- Add Spring Security, password hashing, sessions or standards-based tokens, and authorization checks.
- Derive seller/buyer identity from the authenticated principal—not request JSON.
- Add CSRF protection when using cookie sessions.
- Rate-limit abuse-prone endpoints.
- Scan uploads, restrict file types and sizes, and store them outside the application server.
- Put secrets in a secret manager; the Compose credentials are local-demo values only.
- Add moderation, reporting, audit events, privacy controls, and account deletion.
- Never handle card data directly; use a payment provider and understand marketplace payment regulations.

### Operations checklist

- Replace `ddl-auto=update` with Flyway migrations.
- Add structured logs, metrics, tracing, alerting, backups, and restore drills.
- Pin and routinely update container patch versions.
- Add readiness/liveness probes and graceful shutdown.
- Define timeouts, retry only safe operations, and use idempotency keys for purchases.
- Reconcile SQL orders and MongoDB listing states on a schedule.

### Capstone

Build these in order:

1. Authentication and “my listings.”
2. Image upload through object storage.
3. Pagination and database-backed search.
4. Order history and seller dashboard.
5. Testcontainers integration tests.
6. Flyway schema migrations.
7. Deployment with managed PostgreSQL, MongoDB, and object storage.

For every feature, write the user story, decide which database owns the truth, define failure behavior, implement the smallest API, test it, and only then add UI polish.

## Daily learning loop

Use 60–90 minutes:

1. Read one concept for 15 minutes.
2. Trace one existing request through the code for 10 minutes.
3. Make one observable change for 30–45 minutes.
4. Test it and write down why it worked for 10 minutes.

The key skill is not recalling every annotation. It is being able to trace data, assign responsibilities, predict failures, and verify the result.
