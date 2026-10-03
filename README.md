# CampusLoop student marketplace

CampusLoop is a runnable full-stack learning prototype for a campus resale marketplace. Students can browse and search available items, publish listings, and complete a demo purchase. The same Spring Boot process serves the browser interface and the JSON API. PostgreSQL stores relational users and purchase orders; MongoDB stores marketplace listing documents.

## Vercel deployment configuration (October 3, 2026)

`Dockerfile.vercel` builds and tests the original Java 21 application, then runs its executable Spring Boot JAR as a non-root user. `vercel.json` explicitly selects that container service and routes requests to it. Vercel's Container Images beta supports this deployment; there is no JavaScript replacement backend. The same Java process serves the UI and API. See [Vercel Container Images](https://vercel.com/docs/functions/container-images).

The container enables the `production` profile. Set `PORT=8080` in Vercel. Connect a dedicated Neon Postgres resource to supply `POSTGRES_HOST`, `POSTGRES_DATABASE`, `POSTGRES_USER`, and `POSTGRES_PASSWORD`; `PGHOST_UNPOOLED` is used when available. The profile constructs a JDBC connection with TLS and limits each instance to three Postgres connections. Connect the dedicated MongoDB Atlas resource to supply `MONGODB_URI`; the cloud profile explicitly uses the `campusloop` MongoDB database. Keep credentials in Vercel environment variables, never in Git. The existing local `scripts/dev.sh` workflow retains its original Docker Compose databases.

The cloud profile hides Actuator connection details and internal error messages, and allows graceful shutdown. `.gitignore`, `.dockerignore`, and `.vercelignore` exclude environment files and local build artifacts.

The public UI clearly labels demo purchases, selects a demo buyer other than the listing's seller, and disables Publish while its request is pending. This remains a learning prototype with shared demo identities, without authentication or payment processing; it is not a real-money marketplace.

Validation before release: run `bash scripts/test.sh`, check the Vercel build, then verify `/actuator/health`, browsing, search, publication, purchase, duplicate-purchase rejection, and persistence after restart. A successful build alone does not establish a working deployment.

This document is deliberately detailed. It explains what every maintained project file does, how the files call one another, how data crosses the browser/server/database boundaries, what can fail, and how to discuss the design under interview pressure.

> Repository facts in this README were verified against the source on September 1, 2026. Current-practice notes and interview questions were checked against the official documentation linked in [Official research references](#official-research-references-checked-september-1-2026).

## Contents

- [What the application does](#what-the-application-does)
- [Run and verify it](#run-and-verify-it)
- [Architecture at a glance](#architecture-at-a-glance)
- [Exact startup sequence](#exact-startup-sequence)
- [Complete runtime flows](#complete-runtime-flows)
- [Data ownership and stored models](#data-ownership-and-stored-models)
- [API contract](#api-contract)
- [Complete file-by-file guide](#complete-file-by-file-guide)
- [How Spring wires the Java files together](#how-spring-wires-the-java-files-together)
- [Configuration explained](#configuration-explained)
- [Concurrency and consistency](#concurrency-and-consistency)
- [Testing and coverage gaps](#testing-and-coverage-gaps)
- [Known limitations and production roadmap](#known-limitations-and-production-roadmap)
- [Official research references](#official-research-references-checked-september-1-2026)
- [50 interview questions and answers](#50-project-specific-interview-questions-and-answers)

## What the application does

On a fresh or existing local database, the application ensures that two demo students exist and adds any missing item from a 15-listing demo catalog. The catalog includes textbooks, multiple lamps, dorm storage, a fan, a chair cushion, a calculator, a monitor, a USB-C hub, a keyboard, clothing, and a bike lock.

The browser can:

- Load all currently available listings.
- Search listing titles and descriptions.
- Filter by Books, Dorm, Electronics, Clothing, or Other.
- Open a modal form and publish a validated listing.
- Buy an item as a demo user other than its seller.
- Display success and server-error notices without reloading the page.

The server can:

- Create and list PostgreSQL-backed user accounts.
- Create, retrieve, search, and filter MongoDB-backed listings.
- Reject listings for seller IDs that do not exist.
- Atomically change one available listing to sold before writing an order.
- Reject self-purchases and purchases of already-sold listings.
- Return standardized HTTP Problem Details for validation, missing resources, and conflicts.
- Report PostgreSQL, MongoDB, disk, liveness, and readiness health through Spring Boot Actuator.

This is intentionally a prototype. It does **not** contain login, authorization, real payments, images, chat, moderation, shipping, notifications, production migrations, or a fully reliable cross-database transaction protocol.

## Run and verify it

### Requirements

- Docker Desktop, which runs PostgreSQL and MongoDB.
- OpenJDK 21.
- A POSIX shell on macOS or Linux for the helper scripts.
- No global Maven installation is required because the Maven Wrapper is included.

### Start

Start Docker Desktop, then run from this directory:

```bash
./scripts/dev.sh
```

Open <http://localhost:8080>.

The command remains attached to the Spring Boot process so its logs stay visible. Press `Control-C` to stop the Java server. The database containers continue running unless you stop them separately.

### Verify the three running layers

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/users
curl http://localhost:8080/api/listings
```

A healthy Actuator response has overall status `UP` and includes `db` and `mongo` components. The listing endpoint should return the seeded catalog unless items have already been purchased.

### Test

```bash
./scripts/test.sh
```

The script selects the local Java 21 installation when possible and invokes `./mvnw test`.

### Stop or reset Docker data

Stop the containers while preserving PostgreSQL and MongoDB data:

```bash
docker compose stop
```

Remove the containers and intentionally erase the named database volumes:

```bash
docker compose down --volumes
```

The seeder is idempotent at the demo-title level. On every application start, it checks for each demo title and inserts only missing demo listings. It does not delete user-created listings, restore sold demo listings, or create a duplicate for an existing title.

## Architecture at a glance

```text
Browser tab
index.html + styles.css + app.js
        |
        | same-origin HTTP + JSON on localhost:8080
        v
Embedded Tomcat / Spring MVC
        |
        +--> UserController ---------> UserRepository --------+
        |                                                   |
        +--> ListingController ------> ListingService        | PostgreSQL
        |                                |       |            | users + orders
        |                                |       +--> UserRepository
        |                                v
        |                         ListingRepository ---------- MongoDB
        |                                                   listings
        +--> OrderController --------> OrderService
                                         |  |  |  |
                                         |  |  |  +--> MongoTemplate atomic claim
                                         |  |  +-----> OrderRepository
                                         |  +--------> ListingRepository
                                         +-----------> UserRepository

Host macOS process: Java 21 + Spring Boot + embedded Tomcat
Docker containers: PostgreSQL 18 and MongoDB 8 only
```

The project is a **modular monolith**: it produces one deployable Java application, but its code is separated into `user`, `listing`, `order`, `common`, and `bootstrap` packages. It is also **full stack** because this repository owns the browser UI, HTTP API, business rules, persistence mapping, local infrastructure, and tests.

## Exact startup sequence

1. You execute [`scripts/dev.sh`](scripts/dev.sh).
2. The script resolves the repository root from its own location, so it works even if called from another directory.
3. It selects Homebrew OpenJDK 21 when `JAVA_HOME` is not already set and adds that Java installation to `PATH`.
4. It calls `docker info`. If the Docker engine is unavailable, it exits with a clear message.
5. It runs `docker compose up -d --wait` using [`compose.yaml`](compose.yaml).
6. Compose creates or reuses the project network and the two named volumes.
7. Compose starts `postgres:18-alpine` on host port `5432` and `mongo:8.0` on host port `27017`.
8. `pg_isready` and `mongosh ... ping` health checks must pass before Compose's `--wait` command returns.
9. The script replaces itself with `./mvnw spring-boot:run` by using `exec`, so signals such as `Control-C` reach Maven and Spring directly.
10. [`mvnw`](mvnw) reads [`.mvn/wrapper/maven-wrapper.properties`](.mvn/wrapper/maven-wrapper.properties) and uses Maven 3.9.16, downloading it if it is not cached.
11. Maven reads [`pom.xml`](pom.xml), resolves the Spring Boot starters and PostgreSQL driver, compiles changed code, and runs the Spring Boot Maven plugin.
12. [`StudentMarketplaceApplication.main`](src/main/java/com/example/marketplace/StudentMarketplaceApplication.java) calls `SpringApplication.run`.
13. `@SpringBootApplication` activates component scanning and auto-configuration from the root package `com.example.marketplace` downward.
14. Spring creates controllers, services, repositories, database clients, Jackson JSON support, validation support, Actuator health contributors, and embedded Tomcat.
15. Hibernate connects to PostgreSQL and updates the `users` and `purchase_orders` schema because `spring.jpa.hibernate.ddl-auto=update` is configured.
16. Spring Data MongoDB connects to the `marketplace` MongoDB database and creates the runtime implementation of `ListingRepository`.
17. Embedded Tomcat starts on port `8080`; Spring maps the controllers and recognizes `static/index.html` as the welcome page.
18. [`DemoDataInitializer.run`](src/main/java/com/example/marketplace/bootstrap/DemoDataInitializer.java) executes as a `CommandLineRunner`, ensures Maya and Noah exist, and inserts each missing demo listing.
19. A browser request to `/` receives [`index.html`](src/main/resources/static/index.html), then loads [`styles.css`](src/main/resources/static/styles.css) and [`app.js`](src/main/resources/static/app.js).
20. `app.js` concurrently requests `/api/users` and `/api/listings`; the returned JSON populates the seller selector and listing grid.

## Complete runtime flows

### 1. Initial page load

```text
Browser GET /
  -> Tomcat
  -> Spring static-resource handler
  -> index.html
  -> browser requests /styles.css and /app.js
  -> Promise.all(loadUsers(), loadListings())
       |                              |
       | GET /api/users               | GET /api/listings
       v                              v
  UserController.all          ListingController.browse
       |                              |
  UserRepository.findAll       ListingService.browse
       |                              |
  PostgreSQL                    ListingRepository.findByStatus...
                                      |
                                   MongoDB
```

`UserController` sorts users by name. `ListingService` requests all available listings ordered newest-first, then performs optional category and text filtering in Java. Jackson serializes the returned objects into JSON. The browser escapes text and renders cards.

### 2. Search and category filtering

1. Typing in `#search` resets a 250 ms debounce timer.
2. After typing pauses, JavaScript trims the query, stores it in `state.query`, and calls `loadListings()`.
3. A category chip stores its exact category value in `state.category` and immediately reloads.
4. `URLSearchParams` creates query parameters such as `/api/listings?category=Dorm&q=lamp`.
5. `ListingController.browse` passes both optional strings to `ListingService.browse`.
6. `normalize` converts blank input to `null` and nonblank input to trimmed lowercase text using `Locale.ROOT`.
7. MongoDB returns all `AVAILABLE` documents newest-first.
8. Java streams apply exact lowercase category matching and substring title/description matching.
9. The result becomes JSON, and the browser replaces the grid and count.

This design is correct for a tiny demo but does not scale because the server loads every available listing before filtering. A production query should filter and paginate inside MongoDB.

### 3. Publish a listing

```text
Sell button -> dialog.showModal()
form submit -> FormData -> plain object -> JSON
POST /api/listings
  -> ListingController.create
  -> Bean Validation on ListingRequest
  -> ListingService.create
  -> UserRepository.existsById(sellerId) in PostgreSQL
  -> new Listing(...), forcing AVAILABLE + Instant.now()
  -> ListingRepository.save in MongoDB
  -> HTTP 201 + Location header + listing JSON
  -> close/reset dialog -> reload available listings
```

The client supplies only seller ID, title, description, category, price, and condition. It cannot choose an ID, status, or timestamp because those fields are absent from `ListingRequest`. The service trims text and verifies the cross-database seller reference before saving.

### 4. Buy a listing

```text
Buy button -> POST /api/orders { buyerId, listingId }
  -> OrderController.buy
  -> Bean Validation on OrderRequest
  -> OrderService.buy
       1. Verify buyer exists in PostgreSQL
       2. Read listing from MongoDB
       3. Reject buyer == seller
       4. Atomic MongoDB findAndModify where id matches AND status == AVAILABLE
       5. Change status to SOLD and return the updated document
       6. Save PurchaseOrder in PostgreSQL with copied final price
       7. If SQL save throws, attempt to compensate by restoring AVAILABLE
  -> HTTP 201 + Location header + order JSON
  -> browser shows success and reloads listings
```

The atomic status predicate is the double-purchase guard. Two callers can both read the listing, but only one `findAndModify` can match it while its status is `AVAILABLE`. The loser receives `null` and the service throws `ConflictException`.

### 5. Error flow

1. `@Valid` detects malformed request fields before the controller body runs.
2. Missing users/listings cause `NotFoundException`.
3. duplicate emails, self-purchases, and already-claimed listings cause `ConflictException`.
4. [`ApiExceptionHandler`](src/main/java/com/example/marketplace/common/ApiExceptionHandler.java) converts those failures into `ProblemDetail` JSON with HTTP `400`, `404`, or `409`.
5. The JavaScript `api` helper checks `response.ok`, reads the response body, and throws a browser `Error` using `body.detail`.
6. The relevant UI handler displays the message in `#notice` and restores any disabled button when appropriate.

## Data ownership and stored models

### Why PostgreSQL owns users and orders

Users and orders have fixed fields, exact numeric values, uniqueness requirements, and natural relational/reporting needs. JPA maps Java entities to PostgreSQL tables. The database is the durable authority for user identity records and completed-order records.

### Why MongoDB owns listings

A listing is read and updated as one aggregate document. Future categories could gain different optional attributes without immediately normalizing many category-specific tables. MongoDB also lets the purchase workflow atomically claim one listing document with a conditional update.

### PostgreSQL `users`

| Java field | Database meaning | Constraint/source |
|---|---|---|
| `id: UUID` | Stable user primary key | Generated by `GenerationType.UUID` |
| `name: String` | Display name | Required, maximum 100 characters |
| `email: String` | Login/contact identifier in a future version | Required, unique, maximum 180 characters |
| `campus: String` | Demo location label | Required, maximum 120 characters |
| `createdAt: Instant` | Creation timestamp | Assigned in `@PrePersist`, not updatable |

### PostgreSQL `purchase_orders`

| Java field | Database meaning | Constraint/source |
|---|---|---|
| `id: UUID` | Order primary key | Generated UUID |
| `buyerId: UUID` | Buyer reference by value | Required, but not declared as a JPA relationship/foreign key |
| `listingId: String` | MongoDB listing identifier snapshot | Required, maximum 40 characters |
| `price: BigDecimal` | Purchase-time price snapshot | `NUMERIC(10,2)`-style mapping through precision 10, scale 2 |
| `status: OrderStatus` | Current order state | Stored as string; prototype only has `COMPLETED` |
| `createdAt: Instant` | Order timestamp | Assigned in `@PrePersist` |

### MongoDB `listings`

| Java field | Document meaning | Source/invariant |
|---|---|---|
| `id: String` | MongoDB document ID | `@Id`; generated by MongoDB/Spring Data |
| `sellerId: UUID` | PostgreSQL user ID copied into the document | Seller existence checked at creation time |
| `title: String` | Listing headline | Request required, maximum 120, trimmed |
| `description: String` | Item details | Request required, maximum 2,000, trimmed |
| `category: String` | Filter label | Request required, maximum 60, trimmed |
| `price: BigDecimal` | Asking price | Minimum 0.01, at most 8 integer and 2 fraction digits |
| `condition: ItemCondition` | `NEW`, `LIKE_NEW`, `GOOD`, or `FAIR` | Enum parsing plus request `@NotNull` |
| `status: ListingStatus` | `AVAILABLE` or `SOLD` | Constructor always starts at `AVAILABLE`; purchase changes it |
| `createdAt: Instant` | Publish time | Constructor assigns `Instant.now()` |

There is no database-enforced relationship from MongoDB `sellerId` to PostgreSQL `users`, or from PostgreSQL `listingId` to MongoDB `listings`. Those are application-level references and can drift if records are manually deleted or if a process crashes during a multi-database workflow.

## API contract

| Method and path | Input | Success | Main failures |
|---|---|---|---|
| `GET /api/users` | None | `200` array sorted by name | Unexpected database/server failure |
| `POST /api/users` | `UserRequest` JSON | `201`, `Location`, user JSON | `400` validation, `409` duplicate email |
| `GET /api/listings` | Optional `category`, optional `q` | `200` available listing array | Unexpected database/server failure |
| `GET /api/listings/{id}` | Listing ID path segment | `200` listing JSON | `404` missing listing |
| `POST /api/listings` | `ListingRequest` JSON | `201`, `Location`, listing JSON | `400` validation, `404` seller missing |
| `POST /api/orders` | `OrderRequest` JSON | `201`, `Location`, order JSON | `400` validation, `404` buyer/listing missing, `409` self-buy/already sold |
| `GET /api/orders?buyerId=...` | UUID query parameter | `200` newest-first order array | `400` malformed UUID, `404` buyer missing |
| `GET /actuator/health` | None | Health JSON; normally `200`/`UP` | Non-UP status when a contributor fails |

### `POST /api/users`

```json
{
  "name": "Avery Johnson",
  "email": "avery@campus.edu",
  "campus": "West Campus"
}
```

### `POST /api/listings`

```json
{
  "sellerId": "copy-a-UUID-from-GET-api-users",
  "title": "Dorm table lamp",
  "description": "Warm LED bulb included; pickup near the student union.",
  "category": "Dorm",
  "price": 17.50,
  "condition": "LIKE_NEW"
}
```

### `POST /api/orders`

```json
{
  "buyerId": "copy-a-different-user-UUID",
  "listingId": "copy-an-id-from-GET-api-listings"
}
```

### Validation error shape

```json
{
  "type": "https://student-marketplace.local/problems/400",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "errors": {
    "title": "must not be blank",
    "price": "must be greater than or equal to 0.01"
  }
}
```

## Complete file-by-file guide

The files below are the maintained source and project-support files. `target/`, `.next/`, IDE metadata, Docker volumes, and downloaded dependencies are generated artifacts rather than source files and should not be edited as application code.

### Root, build, documentation, and operations files

| File | Exactly what it does | When it matters and what it connects to |
|---|---|---|
| [`README.md`](README.md) | The primary operating manual, architecture map, source walkthrough, limitation list, official-reference index, and interview bank. | Read before changing the system; it links every important source file and describes observed behavior rather than configuring runtime behavior. |
| [`LEARNING_GUIDE.md`](LEARNING_GUIDE.md) | A six-week beginner curriculum covering Java, Spring, SQL/JPA, MongoDB, cross-database consistency, frontend work, tests, security, and operations. | Educational only; it proposes exercises that intentionally go beyond the current prototype. |
| [`HELP.md`](HELP.md) | Spring Initializr-generated links for the exact Spring Boot 4.1.0 stack and an explanation of empty POM metadata overrides. | Reference-only. It is ignored by the generated `.gitignore`, so do not assume it will be committed unless the ignore rule changes. |
| [`pom.xml`](pom.xml) | Maven's project model: inherits Spring Boot 4.1.0 dependency/plugin management, selects Java 21, declares web, validation, JPA, MongoDB, Actuator, PostgreSQL, and test dependencies, and enables the Spring Boot Maven plugin. | Maven reads it for `test`, `package`, and `spring-boot:run`. The starters drive most Spring auto-configuration at runtime. |
| [`mvnw`](mvnw) | POSIX Maven Wrapper launcher. It reads wrapper properties, downloads/caches the selected Maven distribution when needed, then delegates all supplied Maven goals. | Used by both helper scripts on macOS/Linux; this is generated wrapper infrastructure and normally should not be hand-edited. |
| [`mvnw.cmd`](mvnw.cmd) | Windows command-shell equivalent of `mvnw`. | Allows a Windows developer to run the same Maven version; not called by the macOS scripts. |
| [`.mvn/wrapper/maven-wrapper.properties`](.mvn/wrapper/maven-wrapper.properties) | Pins Maven 3.9.16, uses the `only-script` wrapper distribution, and defines the official Maven Central download URL. | Read by `mvnw`/`mvnw.cmd` before Maven itself starts. |
| [`compose.yaml`](compose.yaml) | Defines the PostgreSQL 18 and MongoDB 8 containers, local demo credentials, host-port mappings, named volumes, and readiness health checks. | `scripts/dev.sh` runs it. The Java application is **not** a Compose service; it runs directly on the host and reaches `localhost:5432` and `localhost:27017`. |
| [`.env.example`](.env.example) | Documents the four environment variables that can override local connection settings. Values are local-demo defaults, not production secrets. | It is a template only; `dev.sh` does not automatically copy or source it. Spring reads actual exported environment variables through property placeholders. |
| [`scripts/dev.sh`](scripts/dev.sh) | Resolves the root, selects Java 21, verifies Docker, starts/waits for both database containers, then replaces the shell with `./mvnw spring-boot:run`. | The normal one-command development entry point. A failure before `exec` prevents the Java app from starting. |
| [`scripts/test.sh`](scripts/test.sh) | Resolves the root, selects Java 21 when available, prepares `PATH`, and calls `./mvnw test`. | Runs unit tests without starting Compose; current tests use mocks and do not need real databases. |
| [`.gitignore`](.gitignore) | Excludes generated Maven output, IDE metadata, and generated helper material such as `HELP.md`. It includes exceptions for nested source `target`/`build` names. | Affects Git discovery only, not compilation or runtime. This nested project is also under an ignored parent directory in the portfolio repository. |
| [`.gitattributes`](.gitattributes) | Normalizes `mvnw` to LF line endings and Windows `.cmd` files to CRLF. | Prevents cross-platform wrapper breakage after Git checkout. |
| `target/` | Maven-generated compiled classes, copied resources, reports, metadata, and packaged artifacts. | Rebuilt by Maven. Never treat it as the source of truth; edit `src/` and rerun Maven instead. |

### Application entry and bootstrap

| File | Exactly what it does | Inputs, outputs, and collaborators |
|---|---|---|
| [`StudentMarketplaceApplication.java`](src/main/java/com/example/marketplace/StudentMarketplaceApplication.java) | Defines the root Spring Boot application. `main(String[])` hands command-line arguments and the root class to `SpringApplication.run`. | Input: process arguments and classpath/configuration. Output: a running Spring `ApplicationContext` and embedded web server. Its package location causes component scanning to include every subpackage. |
| [`DemoDataInitializer.java`](src/main/java/com/example/marketplace/bootstrap/DemoDataInitializer.java) | Holds the 15 immutable demo definitions, implements `CommandLineRunner`, ensures two demo users exist, and inserts only catalog titles not already present. Its private `DemoListing` record converts string prices to exact `BigDecimal` values and constructs `AVAILABLE` listings for Maya. | Runs once after Spring has created the context. Reads/writes `UserRepository` and `ListingRepository`. It produces database records, not HTTP responses. Per-title existence checks preserve an existing volume while allowing newly added demo items to appear after restart. |

### Shared error files

| File | Exactly what it does | Inputs, outputs, and collaborators |
|---|---|---|
| [`ApiExceptionHandler.java`](src/main/java/com/example/marketplace/common/ApiExceptionHandler.java) | Global REST exception translator. It maps `NotFoundException` to 404, `ConflictException` to 409, and `MethodArgumentNotValidException` to 400. It builds RFC-style `ProblemDetail` bodies and attaches a field-to-message `errors` map for validation failures. | Receives exceptions thrown before or during controller execution. Returns objects Spring/Jackson serialize as HTTP error JSON. It centralizes the public failure shape so controllers remain focused on routing. |
| [`NotFoundException.java`](src/main/java/com/example/marketplace/common/NotFoundException.java) | Small domain-specific `RuntimeException` for an identifier that should resolve but does not. | Thrown by listing/order services; caught globally by `ApiExceptionHandler`. Its message becomes the public `detail`. |
| [`ConflictException.java`](src/main/java/com/example/marketplace/common/ConflictException.java) | Small domain-specific `RuntimeException` for a request that is well-formed but conflicts with current state or a uniqueness rule. | Thrown for duplicate email, self-purchase, or sold listing; translated to HTTP 409. |

### User feature

| File | Exactly what it does | Inputs, outputs, and collaborators |
|---|---|---|
| [`UserAccount.java`](src/main/java/com/example/marketplace/user/UserAccount.java) | JPA entity mapped to PostgreSQL table `users`. Declares UUID ID generation, required/length/unique column metadata, an insert-only timestamp hook, a protected no-arg persistence constructor, a public creation constructor, and getters used by Jackson. | Created by `UserController` and `DemoDataInitializer`; persisted through `UserRepository`; serialized in user API responses. It contains no password or authentication behavior. |
| [`UserRequest.java`](src/main/java/com/example/marketplace/user/UserRequest.java) | Immutable request DTO record for name, email, and campus. Bean Validation requires nonblank values, valid email syntax, and maximum sizes. | Jackson constructs it from `POST /api/users`; `@Valid` checks it before `UserController.create` runs. It prevents callers from supplying ID/timestamp fields. |
| [`UserRepository.java`](src/main/java/com/example/marketplace/user/UserRepository.java) | Extends `JpaRepository<UserAccount, UUID>` for generated CRUD behavior and declares case-insensitive `exists`/`find` queries by email. | Spring creates the runtime proxy. Called by bootstrap, user creation, seller validation, buyer validation, and order history checks. It issues SQL through JPA/Hibernate. |
| [`UserController.java`](src/main/java/com/example/marketplace/user/UserController.java) | Maps `/api/users`. `all` returns users sorted by `name`. `create` validates JSON, checks duplicate email, normalizes strings, saves an entity, and returns `201 Created` with a `Location` header. | Inputs: HTTP requests and `UserRequest`. Collaborator: `UserRepository`. Outputs: user JSON or `ConflictException`/validation errors. Unlike listings/orders, this tiny feature currently puts its duplicate rule directly in the controller rather than a service. |

### Listing feature

| File | Exactly what it does | Inputs, outputs, and collaborators |
|---|---|---|
| [`ItemCondition.java`](src/main/java/com/example/marketplace/listing/ItemCondition.java) | Closed set of allowed item-quality values: `NEW`, `LIKE_NEW`, `GOOD`, and `FAIR`. | Jackson parses request strings into it; MongoDB stores its value; the browser formats underscores/spelling for display. An unknown value causes JSON binding failure rather than a valid request. |
| [`ListingStatus.java`](src/main/java/com/example/marketplace/listing/ListingStatus.java) | Closed lifecycle set with `AVAILABLE` and `SOLD`. | `Listing` constructor forces `AVAILABLE`; repository browse queries only available records; `OrderService` conditionally changes one document to `SOLD`. It is not accepted from create requests. |
| [`Listing.java`](src/main/java/com/example/marketplace/listing/Listing.java) | MongoDB document mapped to collection `listings`. Holds seller reference, display fields, exact price, condition, internal status, and timestamp. Declares an intended compound browse index and exposes read-only getters. | Constructed by `ListingService`/bootstrap, persisted by `ListingRepository`, conditionally updated by `MongoTemplate`, and serialized in API responses. The protected constructor exists for object mapping. `@CompoundIndex` is metadata only unless automatic or explicit index creation is enabled. |
| [`ListingRequest.java`](src/main/java/com/example/marketplace/listing/ListingRequest.java) | Immutable listing-creation DTO. Validates seller UUID, text presence/length, price minimum/precision/scale, and condition presence. | Jackson and Bean Validation create/check it; `ListingService.create` consumes it. It intentionally omits server-owned ID, status, and timestamp. |
| [`ListingRepository.java`](src/main/java/com/example/marketplace/listing/ListingRepository.java) | Extends `MongoRepository<Listing, String>`. Declares a case-insensitive title-existence query for idempotent seeding and an available/newest-first browse query. | Spring parses method names and creates the Mongo queries at startup. Called by bootstrap, listing service, and order service. |
| [`ListingService.java`](src/main/java/com/example/marketplace/listing/ListingService.java) | Owns listing business logic. Creation verifies that the PostgreSQL seller exists, trims text, constructs the document, and saves it. Browse normalizes filters, requests available listings, filters them in Java, and returns an immutable list. `get` converts an empty repository result to `NotFoundException`. | Inputs: controller DTOs/parameters. Collaborators: Mongo `ListingRepository` and SQL `UserRepository`. Outputs: listing objects or domain exceptions. It implements an application-level cross-database reference check. |
| [`ListingController.java`](src/main/java/com/example/marketplace/listing/ListingController.java) | Maps `/api/listings` GET collection, GET one, and POST create routes. Delegates all rules to `ListingService`; builds `201 Created` and `Location` for creation. | Translates HTTP parameters/path/body into Java arguments and service results back into HTTP/JSON. It does not directly query either database. |

### Order feature

| File | Exactly what it does | Inputs, outputs, and collaborators |
|---|---|---|
| [`OrderStatus.java`](src/main/java/com/example/marketplace/order/OrderStatus.java) | Defines the current order lifecycle; the prototype has only `COMPLETED`. | Stored by JPA as a string because the entity field uses `EnumType.STRING`. A real system would likely add pending, cancelled, refunded, or failed states. |
| [`PurchaseOrder.java`](src/main/java/com/example/marketplace/order/PurchaseOrder.java) | JPA entity mapped to `purchase_orders`. Captures generated UUID, buyer UUID, Mongo listing ID, purchase-time price snapshot, status, and creation time. | Constructed only by `OrderService`; saved/read through `OrderRepository`; serialized by `OrderController`. Copying price protects order history from future listing-price changes. It does not declare JPA relationships because the listing is in MongoDB and buyer is represented as a UUID field. |
| [`OrderRequest.java`](src/main/java/com/example/marketplace/order/OrderRequest.java) | Immutable purchase DTO with required buyer UUID and nonblank listing ID. | Jackson/validation input for `POST /api/orders`. It does not accept price or status, preventing the client from choosing either. |
| [`OrderRepository.java`](src/main/java/com/example/marketplace/order/OrderRepository.java) | Extends `JpaRepository<PurchaseOrder, UUID>` and declares a newest-first lookup by buyer ID. | Spring creates the JPA proxy/query. `OrderService` uses it to save a completed purchase and return buyer history. |
| [`OrderService.java`](src/main/java/com/example/marketplace/order/OrderService.java) | Coordinates the hardest workflow. It validates the SQL buyer, loads the Mongo listing, rejects self-purchase, atomically claims an available document with `MongoTemplate.findAndModify`, writes the SQL order, and attempts a Mongo compensation if the SQL write fails. It also validates buyer existence before order-history lookup. | Collaborators: `UserRepository`, `ListingRepository`, `OrderRepository`, and `MongoTemplate`. Outputs: `PurchaseOrder`/history or domain exceptions. The SQL and Mongo operations do not share one ACID transaction, so the compensation reduces but does not eliminate inconsistency risk. |
| [`OrderController.java`](src/main/java/com/example/marketplace/order/OrderController.java) | Maps `POST /api/orders` and `GET /api/orders?buyerId=...`. It validates purchase JSON, delegates to the service, and returns `201 Created`/`Location` or an order array. | HTTP boundary only; it contains no purchase concurrency logic. |

### Configuration and browser files

| File | Exactly what it does | Inputs, outputs, and collaborators |
|---|---|---|
| [`application.properties`](src/main/resources/application.properties) | Names the app, binds PostgreSQL and MongoDB connection settings from environment variables with local defaults, lets Hibernate update the prototype schema, disables Open Session in View, chooses standard Mongo UUID representation, exposes Actuator `health`/`info`, and shows health/error details. | Loaded by Spring Boot before beans are fully created. Values drive auto-configured `DataSource`, JPA, Mongo client, Actuator, and error behavior. Public health/error detail settings are convenient locally but too revealing for production. |
| [`static/index.html`](src/main/resources/static/index.html) | Semantic page structure: sticky navigation, hero art, listing/search/filter area, notices, sell dialog, footer, stylesheet reference, and script reference. Includes filter chips for every seeded category and all validated form fields. | Served at `/` by Spring's static handler. IDs/classes are the contract consumed by `app.js` and `styles.css`; renaming one requires updating those consumers. |
| [`static/styles.css`](src/main/resources/static/styles.css) | Defines color tokens, typography, header/hero art, buttons, filters, responsive listing grid, cards, dialog, notices, and mobile breakpoints. | Browser-only presentation. It does not call the API or enforce business rules. Google Fonts are an external runtime dependency, while fallback sans-serif still renders if fonts fail. |
| [`static/app.js`](src/main/resources/static/app.js) | Owns browser state and behavior: DOM references, output escaping, temporary notices, the common `fetch` wrapper, user/listing loading, card rendering, category clicks, debounced search, event-delegated purchases, sell-dialog controls, form-to-JSON conversion, and initial concurrent loading. | Calls the `/api` routes and manipulates elements declared by `index.html`. It is not a trusted security boundary; every important rule must still be checked on the server. |

### Test files

| File | Exactly what it does | Inputs, outputs, and collaborators |
|---|---|---|
| [`StudentMarketplaceApplicationTests.java`](src/test/java/com/example/marketplace/StudentMarketplaceApplicationTests.java) | Minimal smoke-style unit assertion that the entry-point class exists. | It does **not** start Spring, open a port, or connect to databases, so its name should not be mistaken for a full application integration test. |
| [`OrderServiceTests.java`](src/test/java/com/example/marketplace/order/OrderServiceTests.java) | Mockito unit tests for the purchase service. One test stubs a successful atomic claim and verifies order creation; another returns `null` from `findAndModify` and verifies an already-sold conflict. | Uses JUnit 5, AssertJ, Mockito mocks, and a real `OrderService` instance. It checks isolated decision logic without SQL/Mongo/network infrastructure. |
| [`org.mockito.plugins.MockMaker`](src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker) | Selects Mockito's subclass-based mock maker. | Loaded by Mockito from the test classpath; it changes how mocks are generated and avoids requiring inline bytecode instrumentation for these tests. |

## How Spring wires the Java files together

Spring creates one object, called a bean, for each detected controller, service, component, repository proxy, and infrastructure configuration. Constructor injection makes every dependency explicit:

```text
DemoDataInitializer(UserRepository, ListingRepository)

UserController(UserRepository)

ListingController(ListingService)
ListingService(ListingRepository, UserRepository)

OrderController(OrderService)
OrderService(UserRepository, ListingRepository, OrderRepository, MongoTemplate)

ApiExceptionHandler()
```

No application class manually calls `new ListingService(...)` in production. Spring sees the constructor, finds one bean of each parameter type, constructs the object, and retains it in the application context. Unit tests *do* call the constructor directly with mocks, which is why constructor injection improves testability.

Repository interfaces have no handwritten implementations. Spring Data inspects the generic types and method names, creates proxy objects, and translates calls into store-specific operations. For example:

- `existsByEmailIgnoreCase` becomes a case-insensitive SQL existence query.
- `findByBuyerIdOrderByCreatedAtDesc` becomes a SQL query constrained by `buyerId` with descending timestamp order.
- `existsByTitleIgnoreCase` becomes a MongoDB existence query.
- `findByStatusOrderByCreatedAtDesc` becomes a MongoDB query constrained by status and sorted by time.

## Configuration explained

### Connection placeholders

This pattern:

```properties
spring.datasource.url=${POSTGRES_URL:jdbc:postgresql://localhost:5432/marketplace}
```

means “use environment variable `POSTGRES_URL` when it is defined; otherwise use the value after the colon.” The same pattern configures PostgreSQL username/password and MongoDB URI. Spring Boot's external configuration rules let deployment-specific values override packaged defaults without modifying source.

### `spring.jpa.hibernate.ddl-auto=update`

Hibernate compares entity metadata to the database and attempts to update the schema. That is convenient for a disposable prototype but is not a controlled migration history. Production should use reviewed, versioned migrations such as Flyway and set Hibernate to validate rather than mutate the schema.

### `spring.jpa.open-in-view=false`

This prevents the JPA persistence context from remaining open for the entire web request. It reduces accidental database access during JSON rendering. These entities have no lazy relationships, but disabling the pattern still makes database boundaries clearer.

### `spring.mongodb.representation.uuid=standard`

This selects the standard BSON UUID representation for Java UUID values such as `sellerId`, avoiding legacy representation ambiguity.

### Actuator and error detail settings

`management.endpoints.web.exposure.include=health,info` exposes two management endpoints. `management.endpoint.health.show-details=always` makes database and disk details visible. `server.error.include-message=always` can expose server messages. These settings are helpful for a local teaching app and should be restricted in production.

### Index caveat

`Listing` declares `@CompoundIndex(name = "listing_browse", def = "{'status': 1, 'category': 1, 'createdAt': -1}")`. Current Spring Data MongoDB documentation states that annotation-driven index creation must be explicitly enabled or handled programmatically. This project does neither, so the annotation documents the intended index but does not guarantee that a fresh database contains it. Even if created, the current status-only query plus global `createdAt` sort cannot fully exploit an index with `category` between those fields. A production version should create verified indexes through migrations/startup code and align each index with measured query shapes.

## Concurrency and consistency

### What the purchase workflow guarantees

- A buyer ID must exist when the request runs.
- A listing ID must exist during the initial read.
- The buyer cannot equal the listing's seller ID.
- Only one concurrent request can change a specific listing from `AVAILABLE` to `SOLD` through the conditional single-document update.
- The price saved in the order is copied from the document returned by that successful atomic update, not trusted from the browser.
- If `orders.save` throws inside the running process, the service attempts to restore the listing to `AVAILABLE`.

### What it does not guarantee

- PostgreSQL and MongoDB do not participate in one shared ACID transaction.
- A process crash after MongoDB marks sold but before PostgreSQL commits can leave a sold listing with no order.
- A process crash after PostgreSQL commits but before the response returns can make the client uncertain even though the order exists.
- The endpoint has no idempotency key, so a client retry cannot ask “is this the same purchase attempt?”
- The compensation itself can fail, and its failure is not separately logged, queued, or reconciled.
- Cross-database user/listing references have no foreign keys.
- There is no unique SQL constraint on `listingId`; the MongoDB status guard is currently the only duplicate-order barrier.

A production design should usually choose one of these paths:

1. Put users, listings, and orders in PostgreSQL and use one local transaction.
2. Keep separate stores but introduce a durable saga/outbox, idempotency keys, retries with bounded policies, and a reconciliation job.
3. Use a supported multi-resource transaction design only after measuring its operational and availability costs.

## Testing and coverage gaps

### What is tested now

- The entry-point type exists.
- A successful MongoDB claim produces a SQL order with the correct buyer and price.
- A `null` atomic-claim result becomes an already-sold conflict.

### What is not tested now

- Spring context startup.
- Controller routing, JSON serialization, status codes, and `Location` headers.
- Bean Validation and Problem Details bodies.
- JPA entity/schema behavior and unique email enforcement.
- MongoDB repository queries and actual BSON mappings.
- Demo seeding/idempotence.
- SQL-save failure compensation.
- seller-cannot-buy rule and missing buyer/listing branches.
- Real simultaneous purchase requests.
- Browser rendering, search, modal submission, or mobile behavior.
- Container startup and health checks.
- End-to-end behavior across both real databases.

The next meaningful tests would use Testcontainers or Compose-backed integration tests for both databases, `MockMvc`/HTTP tests for controllers, and a browser tool such as Playwright for end-to-end flows.

## Known limitations and production roadmap

Prioritized improvements:

1. **Authentication and authorization:** derive buyer/seller identity from the authenticated principal, never request JSON; restrict mutations to the correct user.
2. **Reliable purchase semantics:** add idempotency, a unique order constraint, durable workflow state, reconciliation, and explicit recovery behavior.
3. **Versioned schemas and indexes:** replace `ddl-auto=update`, explicitly create/verify Mongo indexes, and review data migrations.
4. **Database-backed filtering and pagination:** stop loading every available listing into memory; add query limits, stable cursors, and suitable indexes.
5. **Integration and concurrency tests:** prove actual SQL/Mongo/HTTP behavior, not only mocked calls.
6. **Security hardening:** add Spring Security, CSRF strategy, output/content policies, rate limits, audit events, secret management, and dependency/container scanning.
7. **Product capabilities:** images in object storage, moderation/reporting, saved items, order history UI, seller dashboard, notifications, and accessibility testing.
8. **Operations:** structured logs, metrics, traces, dashboards, alerts, health-detail access control, backups, restore drills, graceful shutdown, and deployment automation.

## Official research references checked September 1, 2026

These primary sources informed the current-practice notes and interview bank:

- [Spring Boot 4.1 externalized configuration](https://docs.spring.io/spring-boot/4.1-SNAPSHOT/reference/features/external-config.html)
- [Spring Boot Actuator HTTP monitoring](https://docs.spring.io/spring-boot/4.1-SNAPSHOT/reference/actuator/monitoring.html)
- [Spring Boot health endpoints and contributors](https://docs.spring.io/spring-boot/4.1-SNAPSHOT/reference/actuator/endpoints.html)
- [Spring MVC controller advice](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html)
- [Spring Data JPA derived query methods](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html)
- [Spring Data MongoDB `findAndModify`](https://docs.spring.io/spring-data/data-mongodb/reference/mongodb/template-crud-operations.html#mongo-template.find-and-modify)
- [Spring Data MongoDB index creation](https://docs.spring.io/spring-data/mongodb/reference/mongodb/mapping/mapping-index-management.html)
- [MongoDB transactions and single-document atomicity](https://www.mongodb.com/docs/manual/core/transactions/)
- [MongoDB consistency and transaction deployment requirements](https://www.mongodb.com/docs/manual/data-modeling/enforce-consistency/transactions/)
- [PostgreSQL 18 transaction isolation](https://www.postgresql.org/docs/18/sql-set-transaction.html)
- [PostgreSQL exact numeric types](https://www.postgresql.org/docs/18/datatype-numeric.html)
- [Docker Compose service and health-check reference](https://docs.docker.com/reference/compose-file/services/)
- [Docker Compose named volumes](https://docs.docker.com/reference/compose-file/volumes/)
- [Apache Maven build lifecycle](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle)
- [Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/index.html)
- [Java 21 UUID API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/UUID.html)
- [Spring Security authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/index.html)
- [Spring Security request authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html)
- [Spring Security CSRF protection](https://docs.spring.io/spring-security/reference/7.0/servlet/exploits/csrf.html)

## 50 project-specific interview questions and answers

The questions below progress from explanation to code tracing, concurrency, failure analysis, security, testing, and production design. The answers distinguish what the repository does today from what a production system should do.

### 1. Give me a 30-second technical explanation of CampusLoop.

**Strong answer:** CampusLoop is a Java 21 and Spring Boot 4.1 modular monolith for a student resale marketplace. Spring MVC exposes JSON endpoints and serves a vanilla HTML/CSS/JavaScript interface from the same process. PostgreSQL, accessed through JPA, stores structured users and completed purchase orders. MongoDB stores listing documents. The most important workflow is purchasing: the service conditionally changes one Mongo listing from `AVAILABLE` to `SOLD` in an atomic `findAndModify`, then records an immutable price snapshot in PostgreSQL.

I would also state the boundary honestly: it is a learning prototype with demo identities and no authentication or real payment. Its interesting engineering lesson is not feature count; it is how controllers, services, repositories, two persistence models, validation, error translation, Docker, and a concurrency guard work together.

**Grill follow-up:** Be ready to draw the browser → controller → service → repository → database path and name the exact class at each stop.

### 2. Why do you call it both full stack and a modular monolith?

**Strong answer:** It is full stack because one repository contains the user-facing browser files, server API, business logic, persistence mappings, database orchestration, and tests. A click in `app.js` can be traced all the way through a controller and service to PostgreSQL or MongoDB and back to rendered HTML.

It is a monolith because Maven builds one Java application and one embedded Tomcat server handles both static assets and APIs. “Modular” describes internal organization: `user`, `listing`, `order`, `common`, and `bootstrap` packages have distinct responsibilities. Those modules are not separately deployed services and do not communicate over a network. That distinction matters because a package boundary is far cheaper operationally than a microservice boundary.

**Grill follow-up:** If an interviewer calls it microservices because it uses two databases, correct them: database count does not determine deployment architecture.

### 3. What exactly happens when you run `./scripts/dev.sh`?

**Strong answer:** The script finds its project root, selects Homebrew Java 21 when necessary, and verifies that Docker is reachable. It runs `docker compose up -d --wait`, which starts PostgreSQL and MongoDB and waits for their health checks. It then uses `exec` to launch `./mvnw spring-boot:run`. The wrapper pins Maven 3.9.16; Maven resolves dependencies from `pom.xml`, compiles the project, and invokes the Spring Boot plugin.

Spring Boot scans the root package, auto-configures Tomcat, MVC, validation, JPA, MongoDB, Jackson, and Actuator, and creates repository proxies and application beans. Hibernate connects to PostgreSQL, the Mongo client connects to MongoDB, Tomcat binds port 8080, and the `CommandLineRunner` seeds missing demo data. Only the databases run in Docker; Java runs on the host.

**Grill follow-up:** Explain why `--wait` is stronger than merely starting containers: a running database process may not yet be ready to accept requests.

### 4. What does `@SpringBootApplication` actually contribute here?

**Strong answer:** `@SpringBootApplication` is the root convenience annotation on `StudentMarketplaceApplication`. In practical terms it enables configuration, component scanning, and Spring Boot auto-configuration. Because the class sits in `com.example.marketplace`, scanning reaches all subpackages and finds `@RestController`, `@Service`, `@Component`, and `@RestControllerAdvice` classes.

Auto-configuration reacts to the classpath and properties. The MVC starter leads to an embedded servlet application; JPA plus a JDBC driver produces a `DataSource`, Hibernate, and repository infrastructure; the Mongo starter produces a Mongo client, `MongoTemplate`, and Mongo repositories; Actuator contributes health indicators. It is conditional configuration, not magic that ignores dependencies—removing a starter or connection property changes what can be created.

**Grill follow-up:** If the application class were moved below the feature packages, default component scanning could miss them unless explicit scan packages were configured.

### 5. Explain dependency injection using a concrete CampusLoop class.

**Strong answer:** `OrderService` declares one constructor requiring `UserRepository`, `ListingRepository`, `OrderRepository`, and `MongoTemplate`. Spring owns runtime object construction. It finds one bean matching each constructor parameter, creates `OrderService`, and then injects that service into `OrderController` through its constructor.

This removes service-locator calls and hard-coded infrastructure construction from business code. Dependencies are visible, fields can be final, and a unit test can directly construct `OrderService` with Mockito substitutes. The test therefore controls whether a buyer exists, whether the listing is found, and whether the atomic claim succeeds without starting Spring or either database.

**Grill follow-up:** Constructor injection does not by itself make code thread-safe; singleton services must still avoid mutable request-specific instance state. These services do, because request data stays in local variables.

### 6. Why separate controller, service, and repository layers?

**Strong answer:** A controller owns the HTTP boundary: route mapping, parameter/body conversion, validation activation, status codes, headers, and response serialization. A service owns application rules and workflow, such as validating a seller, normalizing input, preventing self-purchase, or coordinating a listing claim and order write. A repository owns persistence access and translates domain operations into SQL or MongoDB commands.

That separation keeps transport concerns out of business rules and database mechanics out of controllers. `OrderController.buy` is intentionally small; `OrderService.buy` is testable without HTTP; and repository implementations are generated by Spring Data. A weakness is that `UserController.create` currently contains duplicate-email logic directly. If user behavior grows, a `UserService` would make that feature consistent with the other two.

**Grill follow-up:** More layers are not always better. For a trivial read, a controller calling a repository can be acceptable, but shared rules and multi-step workflows warrant a service.

### 7. Why use request DTOs instead of accepting `Listing` and `PurchaseOrder` entities directly?

**Strong answer:** The request records define an allowlist of client-controlled fields. `ListingRequest` permits seller, title, description, category, price, and condition but excludes ID, status, and creation time. The `Listing` constructor then forces `AVAILABLE` and assigns the server timestamp. `OrderRequest` excludes price and order status, so a buyer cannot submit a fake cheaper price or mark an arbitrary state.

DTOs also let API validation and storage rules evolve independently. Persistence annotations remain off the public request contract, and internal fields are not accidentally mass-assigned from JSON. The current responses still expose persistence objects directly; a production API might add response DTOs as well to stabilize versioning and avoid leaking future internal fields.

**Grill follow-up:** Bean Validation improves input quality, but authorization must still decide whether the authenticated user may perform the action.

### 8. Why are records, enums, UUIDs, `Instant`, and `BigDecimal` good fits here?

**Strong answer:** Java records make the request DTOs concise immutable data carriers with generated accessors and value semantics. Enums restrict lifecycle/condition values to known choices and prevent arbitrary status strings inside Java. UUIDs give decentralized 128-bit identifiers that are hard to guess sequentially and work across application instances. `Instant` represents an unambiguous UTC timeline moment, which is safer for persisted timestamps than a local date-time without an offset.

`BigDecimal` represents decimal values without binary floating-point surprises, and PostgreSQL's exact `numeric` type is specifically suitable for money. The entity fixes precision and scale, while request validation limits acceptable digits. In a real payments system I might use integer minor units or a money type including currency; this prototype assumes one currency and uses two decimal places.

**Grill follow-up:** A UUID is not an authorization mechanism. An unguessable ID does not grant permission to access the object.

### 9. Why do create endpoints return `201 Created` and a `Location` header?

**Strong answer:** `POST /api/users`, `/api/listings`, and `/api/orders` create new resources, so `201` communicates more precisely than a generic `200`. Each controller builds a resource URI containing the generated identifier and places it in `Location`; it also returns the created representation in the response body.

That makes the HTTP contract self-describing: a client learns both that creation succeeded and where the server considers the resource to live. One inconsistency is that orders have no implemented `GET /api/orders/{id}` route even though the `Location` points to that shape. Either that retrieval endpoint should be added or the response/header design should be reconsidered.

**Grill follow-up:** `POST` is not automatically idempotent. Retrying a create request can create another resource unless the server adds an idempotency mechanism or uniqueness rule.

### 10. Why serve the frontend and API from the same Spring process?

**Strong answer:** It keeps the prototype operationally simple: one origin, one port, one build, and one deployable artifact. The browser can call relative paths like `/api/listings` without CORS configuration, a separate frontend dev server, or environment-specific API base URLs. Spring automatically serves files under `src/main/resources/static`, including `index.html` as the welcome page.

The tradeoff is coupling: frontend and backend releases scale and deploy together, and vanilla files can become difficult to organize as UI complexity grows. A separate React/Vue frontend could improve component structure and independent delivery, but it would add a build/deployment boundary, CORS or reverse-proxy configuration, and more infrastructure than this learning scope needs.

**Grill follow-up:** Same-origin reduces CORS complexity but does not eliminate XSS, CSRF, authentication, or authorization concerns.

### 11. Trace validation from raw JSON to a `400` response.

**Strong answer:** Tomcat receives the request and Spring MVC selects a controller method based on path and HTTP method. Jackson parses the JSON into a request record. `@Valid` tells Spring to evaluate Jakarta Bean Validation annotations such as `@NotBlank`, `@Size`, `@Email`, `@DecimalMin`, and `@Digits` before entering the controller body.

If field validation fails, Spring throws `MethodArgumentNotValidException`. `ApiExceptionHandler.validation` creates a `ProblemDetail` with status 400, a stable title/detail/type, and a `LinkedHashMap` of field messages. Jackson serializes that object to JSON. A malformed UUID, invalid enum token, or syntactically invalid JSON may instead fail during message conversion, and the current advice has no custom handler for that path, so Spring's default error handling owns it. That is a useful coverage gap to test and standardize.

**Grill follow-up:** HTML `required` and `maxlength` improve UX but are bypassable; server validation is authoritative.

### 12. Why use `ProblemDetail` and global controller advice?

**Strong answer:** `ProblemDetail` provides a predictable machine-readable error shape with status, title, detail, type, and extension properties. `@RestControllerAdvice` applies its `@ExceptionHandler` methods across controllers and writes return values to response bodies, so route classes do not duplicate error-building logic.

CampusLoop uses semantic distinctions: malformed fields are 400, absent resources are 404, and state/uniqueness conflicts are 409. The validation handler adds an `errors` object while other failures use a clear detail string. For production I would add stable application error codes, request correlation IDs, handlers for JSON conversion and database constraint errors, localization rules, and logging that preserves internal diagnostics without exposing stack traces.

**Grill follow-up:** The current type URI is descriptive but does not serve human-readable problem documentation; a mature API could make those URIs resolvable.

### 13. There are no repository implementations. How do those interfaces execute queries?

**Strong answer:** At startup, Spring Data scans repository interfaces, reads their domain/ID generic types, parses method signatures, and creates proxy implementations. Base interfaces supply CRUD behavior. Derived method names encode predicates and ordering: `existsByEmailIgnoreCase`, `findByBuyerIdOrderByCreatedAtDesc`, and `findByStatusOrderByCreatedAtDesc` are parsed into store-specific queries.

The same repository abstraction pattern supports both stores, but the generated operations are not identical under the hood: JPA repositories use an entity manager/Hibernate/SQL, while Mongo repositories use the Mongo mapping layer and driver. Derived names are concise for simple queries; complex joins, projections, performance hints, or update semantics may need explicit JPQL, SQL, `MongoTemplate`, or a custom repository implementation. CampusLoop uses `MongoTemplate` for purchase claiming because a plain CRUD `save` would not express the conditional atomic operation safely.

**Grill follow-up:** Spring validates derived property paths during startup, so a typo such as `findByBuyerrId` should fail early instead of silently querying the wrong field.

### 14. Explain the JPA mapping and lifecycle of `UserAccount`.

**Strong answer:** `@Entity` marks it as persistent and `@Table(name = "users")` selects the table. `@Id` plus UUID generation defines the primary key. `@Column` metadata expresses nullability, length, uniqueness for email, and timestamp immutability. The protected no-argument constructor exists for the persistence provider, while application code uses the public constructor.

On `save`, Hibernate detects a new entity, generates an ID, calls the `@PrePersist` method to assign `createdAt`, and issues an insert. On reads, Hibernate builds objects and the controller/Jackson uses getters for JSON. The database unique constraint is the final duplicate-email authority; the controller's pre-check improves the message but is race-prone because two concurrent requests can both pass it before one insert loses the uniqueness race.

**Grill follow-up:** The application should catch and translate the database uniqueness exception too; “check then insert” alone is never a concurrency-safe uniqueness guarantee.

### 15. Explain the MongoDB mapping and lifecycle of `Listing`.

**Strong answer:** `@Document("listings")` maps the class to that collection, and the `@Id` string maps to MongoDB's document identifier. A new listing is constructed with seller-provided content plus server-owned `AVAILABLE` and `Instant.now()`. `ListingRepository.save` lets the mapping converter turn the Java object into BSON and MongoDB assign an ID when none exists.

Reads reverse that mapping, including enum, UUID, `BigDecimal`, and timestamp values. The class exposes getters but no public setters, which narrows ordinary mutation paths. `OrderService` deliberately bypasses object mutation and uses `MongoTemplate` to issue a conditional database update. The `@CompoundIndex` declares desired metadata, but current Spring Data requires explicit index creation enablement/control, so the annotation alone is not proof that the live index exists.

**Grill follow-up:** MongoDB schema flexibility does not mean “no schema.” Java types, validation, constructors, and business logic still define required invariants.

### 16. Why did you use both PostgreSQL and MongoDB, and would you do that in production?

**Strong answer:** PostgreSQL is a natural fit for users and orders because they have stable columns, uniqueness, exact numeric values, and reporting/relationship needs. MongoDB is a plausible fit for listings because each listing is an aggregate document that could gain category-specific fields and can be atomically claimed as one document.

The educational value is strong, but operational cost is real: two connection pools, backup systems, monitoring paths, schema/index strategies, failure modes, and no ordinary transaction spanning both. For this product size, I would seriously consider PostgreSQL alone, storing flexible category attributes in `jsonb`. That would allow listing status and order insertion to share a local transaction and would remove the most dangerous consistency gap. Technology variety is not by itself a sound production reason.

**Grill follow-up:** Call this “polyglot persistence,” then explain the ownership boundary and cost rather than presenting two databases as automatically scalable.

### 17. How are references enforced across the two databases?

**Strong answer:** They are enforced by application checks, not database constraints. Before saving a listing, `ListingService` asks the PostgreSQL `UserRepository` whether the seller UUID exists. Before purchasing or reading history, `OrderService` similarly verifies the buyer. The listing stores the user UUID as a BSON value, and the order stores the listing's Mongo ID as a SQL string.

That provides validation at the moment of the command but not durable referential integrity. Manual deletion, asynchronous changes, bugs, or partial failures can create dangling references. Production options include keeping related data in one relational database, using soft deletes, publishing reliable domain events, reconciling references, or accepting and explicitly handling eventual consistency.

**Grill follow-up:** A successful existence check can become stale immediately; it is not equivalent to a foreign key checked atomically with a write.

### 18. Analyze the time and memory behavior of listing search.

**Strong answer:** `ListingRepository.findByStatusOrderByCreatedAtDesc` fetches every available listing into application memory. `ListingService.browse` then performs up to two stream filters over that collection. If there are `n` available listings, application filtering is O(n) per request, response-independent database transfer is O(n), and memory allocation is also O(n), even when only a few records match.

That is acceptable for 15 demo items but fails at marketplace scale. I would move predicates into MongoDB, add pagination or cursor-based continuation, cap page size, choose indexes for the actual filter/sort combinations, and consider a text/search service only when requirements justify it. I would also measure explain plans and latency rather than assuming an index solves all problems.

**Grill follow-up:** Debouncing reduces request frequency from one browser; it does not fix the server's O(n) work or protect against many clients.

### 19. Does the declared compound index actually optimize the current query?

**Strong answer:** Not reliably. First, Spring Data MongoDB disables automatic annotation-driven index creation by default unless explicitly enabled or indexes are created programmatically, and this project does neither. A live database may therefore contain only `_id`. Second, the declared order is `(status, category, createdAt desc)`, while the current repository query constrains only status and sorts globally by `createdAt`; the unconstrained `category` field sits between the equality and sort fields, limiting the index's usefulness for that sort.

I would define observed query shapes first. A status-only newest feed likely needs `(status, createdAt desc)`. A category feed may need `(status, category, createdAt desc)`. Text search requires a separate strategy. I would create those indexes explicitly, verify them in an automated migration/startup step, and inspect MongoDB query plans under representative data.

**Grill follow-up:** Every index adds storage and write amplification, so “index every field” is not a responsible answer.

### 20. How does the expanded demo seeder avoid wiping or duplicating existing data?

**Strong answer:** `DemoDataInitializer` no longer returns just because the collection is nonempty. It holds immutable `DemoListing` definitions and, for each title, calls `existsByTitleIgnoreCase`. Only absent titles are converted to real `Listing` objects and saved. Existing user-created listings and purchase state remain untouched, while newly added catalog items appear on the next restart even with persistent Docker volumes.

This is idempotent enough for a local demo, not a general migration system. Title is not a unique database key, a user could independently create the same title, concurrent application instances could both see “absent” and insert duplicates, and a sold demo title is intentionally not re-created. Production seed/reference data should use stable identifiers, database uniqueness, versioned migrations, environment gating, and explicit ownership rules.

**Grill follow-up:** Idempotent means repeating the operation converges on the intended result; it does not mean the code is transactionally safe under every concurrency pattern.

### 21. Trace a purchase end to end and name every important method.

**Strong answer:** The browser's delegated click handler finds a `.buy` button, disables it, and posts `buyerId` plus `listingId` through the `api` helper. Spring maps that request to `OrderController.buy`; Jackson creates `OrderRequest`, and `@Valid` checks the required UUID and nonblank ID. The controller calls `OrderService.buy`.

The service calls `users.existsById`, then `listings.findById`, compares seller and buyer, builds a Mongo `Query` requiring both ID and `AVAILABLE`, and calls `mongo.findAndModify` with an update to `SOLD` and `returnNew(true)`. A null result becomes 409. A successful result supplies the authoritative ID and price for a new `PurchaseOrder`, which `orders.save` inserts in PostgreSQL. The controller returns 201; JavaScript shows a notice and reloads listings, so the sold document disappears from the available query.

**Grill follow-up:** The browser's demo buyer selection is only demo behavior. It is not authentication and must never determine real identity.

### 22. Why would a read-then-save implementation allow double purchasing?

**Strong answer:** Suppose buyer A and buyer B both read status `AVAILABLE` before either writes. If each application instance mutates its in-memory object and calls `save`, both decisions were based on stale state. Depending on write semantics, both may store `SOLD` and both may insert an order. The final listing document looks plausible even though two buyers were charged or promised one item.

The fix is to make the precondition part of the write that the database arbitrates: “update this ID only if status is still AVAILABLE.” MongoDB single-document writes are atomic, so only one update matches. This is a compare-and-set pattern. The loser must treat no matched document as a business conflict, not retry blindly until it wins.

**Grill follow-up:** `synchronized` in one Java process would not protect multiple instances and would disappear on restart; the concurrency guard belongs at the shared data authority.

### 23. What exactly does `findAndModify` guarantee in this code?

**Strong answer:** It sends one MongoDB operation containing both the predicate and update. The predicate selects the listing ID only while status equals `AVAILABLE`. MongoDB atomically evaluates and updates that single document. `returnNew(true)` asks for the post-update document, which provides the price/state that actually won the claim. Spring Data's API returns `null` when nothing matches.

It does not make PostgreSQL participate, guarantee that an order will later be written, or protect unrelated documents. Its guarantee is scoped to one Mongo document operation. That scope is enough to serialize claims on one listing, which is why document-level atomicity and aggregate modeling align well here.

**Grill follow-up:** A multi-document Mongo transaction is unnecessary for this single listing update, and it still would not automatically include PostgreSQL.

### 24. If `findAndModify` does the claim, why read the listing first?

**Strong answer:** The initial `findById` distinguishes a missing listing and supplies the seller ID needed for the self-purchase rule. The conditional claim then decides whether it is still available. Without that first read, the service could combine more checks into the atomic query, but a null result would conflate missing, sold, and self-owned cases unless it performed follow-up diagnosis.

The two-step design is understandable but accepts that state can change between reads. Status is rechecked atomically, which is the critical mutable field. There is no listing update endpoint today, so seller/price do not normally change concurrently; a future edit feature would require versioning or incorporating relevant expected values into the claim so the order cannot capture a surprising price or seller.

**Grill follow-up:** Avoid claiming the first read “locks” the document—it does not.

### 25. Why copy the listing price into `PurchaseOrder` instead of reading it later?

**Strong answer:** An order is a historical fact. If a listing is edited, archived, corrupted, or removed, the order should still record what the buyer agreed to pay. `OrderService` copies the price from the document returned by the successful atomic claim, and `OrderRequest` has no price field, so the browser cannot choose the amount.

This is intentional denormalization: duplication protects history and reporting. A production order would probably snapshot currency, tax/fees, item title or SKU, seller ID, and payment-provider references as well. The exact amount should be calculated and authorized on trusted server data before external payment capture.

**Grill follow-up:** A snapshot is not “stale bad data”; in an order, immutability is the requirement.

### 26. Explain the compensation when the SQL write fails.

**Strong answer:** Once MongoDB returns the sold document, `OrderService` attempts `orders.save`. If that call throws a runtime exception, the catch block issues a Mongo update that matches the same ID while its status is `SOLD` and sets it back to `AVAILABLE`, then rethrows the original error. That is a compensating action: it semantically reverses a previously committed step because no shared transaction can roll it back.

The compensation improves common failure handling but is best-effort. It can fail; the process can crash before it; and it does not carry a purchase-attempt token proving which workflow set `SOLD`. Mature sagas persist step state, make compensations idempotent and causally scoped, retry safely, alert on exhaustion, and reconcile data independently.

**Grill follow-up:** Compensation is not rollback. Other observers may see the intermediate sold state, and the reverse action is a new write that can fail.

### 27. Enumerate the dangerous failure windows in a purchase.

**Strong answer:** Before the Mongo claim, failures leave both stores unchanged and the client can usually retry. After the claim but before SQL save, the listing is sold without an order until compensation succeeds. A hard crash in that window bypasses the catch block entirely. During SQL save, a connection/timeout error can be ambiguous: the client may not know whether commit happened. After SQL commit but before response, retrying without idempotency could be confusing even though the Mongo status should block a second order.

The compensation can also fail, and a network partition can make the application unable to tell whether an operation took effect. The correct production response is durable workflow identity and state: an idempotency key, unique listing/order constraint, explicit attempt statuses, outbox/event delivery, bounded retries, and reconciliation that compares sold listings with orders.

**Grill follow-up:** “Catch every exception” cannot solve process death or ambiguous remote commits.

### 28. Would adding `@Transactional` to `OrderService.buy` fix cross-database consistency?

**Strong answer:** No. A normal Spring/JPA `@Transactional` boundary would manage the PostgreSQL transaction manager. The MongoDB `findAndModify` would still commit independently. Conversely, a Mongo transaction manager would not enlist PostgreSQL. One annotation does not create a distributed transaction across unrelated resource managers.

A service-level SQL transaction could still be useful if several SQL writes must commit together, and it makes boundaries explicit. But the cross-store workflow still needs architectural treatment. Also, the current Compose MongoDB is a standalone server; MongoDB multi-document transactions require a replica set or sharded cluster. Even configuring a Mongo transaction would not include PostgreSQL.

**Grill follow-up:** Always identify which transaction manager, resource, start/commit point, and failure semantics an annotation actually controls.

### 29. Why not use a distributed two-phase commit transaction?

**Strong answer:** Two-phase commit can provide strong atomicity when every resource and driver supports a common coordinator, but it adds protocol, lock duration, coordinator availability, recovery, latency, and operational complexity. MongoDB and PostgreSQL do not become one simple XA transaction in this application just because both are databases, and the current stack has no coordinator configuration.

For a small marketplace, the simpler and stronger option is usually one database for the purchase aggregate. If separate stores are an actual requirement, a durable saga/outbox and reconciliation often offers better availability and operability, with explicitly accepted eventual consistency. I would choose based on business invariants and failure tolerance, not on which pattern sounds more advanced.

**Grill follow-up:** State the invariant first—“at most one completed order per listing”—then compare mechanisms that enforce it.

### 30. Design a more reliable saga/idempotency flow for purchasing.

**Strong answer:** The client sends an idempotency key. PostgreSQL creates or returns a unique purchase-attempt row for `(buyer, key)` and enforces a unique constraint on `listingId` for active/completed orders. A durable command/event asks a listing worker to claim the Mongo document with the attempt ID. The worker performs a conditional update, records its result, and emits a durable result event. The order transitions from `PENDING` to `COMPLETED` or `REJECTED`; repeated messages are safe because handlers check attempt/version state.

An outbox written in the same SQL transaction as order-state changes ensures events are eventually published. A compensation can reopen only a listing whose `claimAttemptId` matches the failed attempt. Monitoring detects stuck attempts, and a reconciler compares claims with orders. The API can return the same attempt for retries and let clients poll or receive notification instead of guessing after a timeout.

**Grill follow-up:** “Use Kafka” is incomplete. Explain atomic outbox writes, delivery semantics, idempotent consumers, state transitions, and reconciliation.

### 31. How does the browser initialize, and what happens if one initial request fails?

**Strong answer:** The script caches important DOM elements, registers its event handlers, and then starts `loadUsers()` and `loadListings()` concurrently with `Promise.all`. `loadUsers` fetches the demo accounts and builds the seller `<select>`. `loadListings` builds query parameters from browser state, renders a loading message, fetches available listings, and replaces the grid with cards or an empty state. Running the calls concurrently reduces startup latency because neither response depends on the other.

There is an asymmetry in failure handling. `loadListings` catches its own error, renders the marketplace-break message, and resolves, whereas `loadUsers` lets its error reject. The final `.catch` shows a notice for an uncaught initialization error, but there is no retry or explicit degraded-state model. `Promise.allSettled`, per-resource status, a retry control, and disabling seller-dependent actions would make partial failure clearer.

**Grill follow-up:** Be precise: `Promise.all` fails fast as an aggregate, but it does not cancel the other `fetch`; that request may still finish and update the page.

### 32. How does the frontend reduce cross-site scripting risk, and where could it still improve?

**Strong answer:** Server-originated strings used inside listing-card HTML and seller options pass through `escapeHtml`. That helper assigns the value to a temporary element's `textContent` and reads its `innerHTML`, so characters such as `<`, `>`, and `&` become text rather than markup. Notices use `textContent`, which is also safe for untrusted text. Enum-derived condition text and IDs are not escaped, but their server-side types tightly constrain them today.

The design still relies on every future template author remembering to escape every interpolated value. Constructing elements and setting `textContent`/attributes directly, or using a framework with contextual escaping, is safer by default. Production defenses should also include a restrictive Content Security Policy, dependency hygiene, careful URL handling, and server-side validation. Output encoding is context-specific: HTML-text escaping is not automatically correct for JavaScript, CSS, or URL contexts.

**Grill follow-up:** Input validation and output encoding solve different problems; rejecting a long title does not make unsafe HTML interpolation safe.

### 33. Does disabling the Buy button prevent duplicate purchases?

**Strong answer:** It improves the experience for one browser by blocking a rapid second click while its request is pending. It is not a correctness boundary: another tab, another device, a scripted client, or two buyers can still send concurrent requests, and a client can remove the `disabled` attribute. The real at-most-one claim in this project is the server-side MongoDB conditional update matching both listing ID and `AVAILABLE` status.

After success, the page reloads available listings, so the sold card disappears. On a handled failure the button is re-enabled. For a stronger user experience, I would label the pending state, preserve focus, prevent repeated retries with an idempotency key, and distinguish conflicts from network ambiguity. None of those client behaviors replaces the database invariant.

**Grill follow-up:** Any rule enforced only in JavaScript is advisory because the client is controlled by the caller.

### 34. Walk through the `api` helper and identify its response-handling limitations.

**Strong answer:** `api(path, options)` calls `fetch`, supplies JSON content type by default, and allows the caller's headers/options to override defaults. For a non-2xx response, it tries to parse JSON and throws an `Error` containing the RFC 9457-style `detail` field or an HTTP-status fallback. For success, it returns `null` on 204 and otherwise parses JSON. Callers therefore share one basic success/error path.

It assumes every successful non-204 response is JSON, does not set `Accept`, has no timeout or abort signal, loses structured fields such as status/type/validation errors, and treats all failures as generic `Error`. A production client would inspect content type, preserve status and problem details in a typed error, handle empty 200 responses, support cancellation/timeouts, and decide explicitly which requests are safe to retry. Setting `Content-Type` on GET is harmless here but unnecessary.

**Grill follow-up:** `fetch` rejects for network failures, not for HTTP 404 or 500; checking `response.ok` is essential.

### 35. Why use event delegation and search debouncing?

**Strong answer:** Listing cards are replaced every time results reload. Attaching one click listener to the stable listings container means clicks bubble up and `closest('.buy')` identifies a button even for dynamically rendered cards; there is no need to rebind every card. Category buttons are handled similarly from their stable container. The handler also ignores clicks that do not originate in a relevant button.

The search input clears the previous timer and waits 250 milliseconds before assigning the trimmed query and fetching. That coalesces rapid keystrokes and reduces requests. Debouncing does not prevent out-of-order responses, however: an older slow request can overwrite a newer fast result. An `AbortController` or monotonically increasing request ID should cancel or ignore stale loads.

**Grill follow-up:** Debouncing limits how often work starts; throttling limits its rate. Neither automatically guarantees response ordering.

### 36. Evaluate the frontend's accessibility and responsive behavior.

**Strong answer:** The page uses semantic elements, native buttons and forms, associated labels, required inputs, a native `<dialog>`, visible status copy, and a responsive CSS grid. Those choices give keyboard and small-screen behavior a reasonable base. The native dialog also provides focus trapping and Escape behavior in supporting browsers.

Important gaps remain. The transient notice should be an appropriate `aria-live` status/alert region, loading and purchasing states should be announced, focus should move intentionally after dialog close and failed submission, and color contrast plus focus indicators need automated and manual checks. The card heading structure, error association, reduced-motion preference, touch targets, zoom at 200%, keyboard-only flow, and screen-reader labels should be tested rather than assumed from markup.

**Grill follow-up:** A Lighthouse score is a useful signal, not proof; accessibility requires automated checks plus keyboard and assistive-technology testing.

### 37. What do the current unit tests prove, and what do they not prove?

**Strong answer:** `OrderServiceTests` creates the service with Mockito mocks. One test stubs a valid buyer, listing lookup, successful Mongo claim, and SQL save, then verifies the buyer and price snapshot on the resulting order. The other makes `findAndModify` return `null` and verifies a `ConflictException`. These are fast tests of two important service branches and do not need Docker.

They do not prove the generated Mongo query contains the expected ID/status predicates, that the update really changes status, that compensation runs after SQL failure, or that Spring can map and persist either model. They also omit missing buyers/listings, self-purchase, request validation, controllers, error JSON, seeding, browsing, and genuine concurrency. Mocks confirm the behavior we programmed them to simulate; they cannot validate database semantics.

**Grill follow-up:** “The unit tests pass” means the isolated examples pass, not that PostgreSQL, MongoDB, HTTP mapping, and races work together.

### 38. What integration-test suite would you add?

**Strong answer:** I would use Spring Boot's test support with real PostgreSQL and MongoDB containers, starting the exact major versions used by Compose. Repository tests would verify JPA constraints, UUID/numeric mapping, Mongo enum/timestamp mapping, case-insensitive title existence, sorting, and query behavior. HTTP tests through `MockMvc` or a random server port would cover request validation, status codes, `Location`, problem details, filters, and the full create-listing/buy-order path.

Each test should own or clean its data deterministically. Container reuse may speed local runs, but CI must not depend on leaked state. I would also add a static-page smoke test and a small browser test for search, sell, and buy. Testcontainers exercises real engines and configuration; it still does not replace production monitoring or failure injection.

**Grill follow-up:** An embedded substitute can hide differences in SQL dialect, constraints, indexes, authentication, transaction behavior, and wire protocol.

### 39. How would you prove that two concurrent buyers cannot both win?

**Strong answer:** Seed one available listing and two valid buyers in real test databases. Use a barrier or latch so two independent HTTP requests or service invocations begin the buy operation together, then await both results. Assert exactly one succeeds, exactly one receives a conflict, the listing ends `SOLD`, and PostgreSQL contains exactly one order for that listing. Run the scenario repeatedly to increase the chance of exposing timing defects.

The test must exercise the real MongoDB `findAndModify`, not a synchronized mock. It should also test retry after an ambiguous response and SQL failure/compensation. For a production-grade invariant, add a PostgreSQL unique constraint on `listing_id`; then the database independently rejects duplicate orders even if application logic regresses.

**Grill follow-up:** A concurrent test demonstrates behavior under tested schedules; a database constraint is the durable last line of defense.

### 40. Why is `spring.jpa.hibernate.ddl-auto=update` convenient locally but risky in production?

**Strong answer:** `update` lets Hibernate compare mappings with the current schema and make some schema changes automatically, which keeps this demo easy to start. It does not provide a reviewed, ordered history of migrations, predictable rollback, controlled data backfills, or a reliable record that every environment reached the same schema. Some changes—renames, type conversions, constraint additions, or large table rewrites—need explicit operational planning.

For production I would use Flyway or Liquibase versioned migrations, validate mappings against the migrated schema, and run migrations as a controlled deployment step with backups and compatibility planning. Expand-and-contract changes let old and new application versions overlap safely. Seed/demo data should be separated from schema migration and disabled or environment-scoped outside development.

**Grill follow-up:** A schema migration is code: review it, test it on production-like data, measure locks/runtime, and design recovery before deployment.

### 41. What is the largest security flaw in the current identity model?

**Strong answer:** There is no authentication. The browser fetches every user and sends arbitrary `sellerId` and `buyerId` values, while the server checks only that those UUIDs exist. Any caller can impersonate another user, list on their behalf, or purchase as them. Hiding the selector or validating UUID shape would not solve that because neither establishes who made the request.

I would add Spring Security with a real login mechanism, store an internal user ID on the authenticated principal, and derive seller/buyer identity server-side. Authorization rules would then ensure only the owner can edit a listing and only eligible users can buy it. Public responses should expose a safe profile projection rather than account records by default, and sensitive actions need audit events and rate controls.

**Grill follow-up:** Authentication establishes identity; authorization decides whether that identity may perform this action on this resource. The application needs both.

### 42. How would CSRF considerations change after adding login?

**Strong answer:** If authentication uses a browser cookie that is sent automatically, a malicious site may cause the browser to submit a state-changing request with that cookie. Spring Security's CSRF protection should remain enabled, and the frontend should send the issued token for POST/PUT/PATCH/DELETE requests. `SameSite` cookies and origin checks add defense but should not be the only control. CORS is a separate browser policy and is not a substitute for CSRF protection.

If the API uses an authorization token that JavaScript explicitly attaches, classic cookie-based CSRF exposure changes, but token storage introduces XSS and theft risks. The choice needs a threat model, secure/HTTP-only cookie settings where applicable, TLS, token rotation/expiry, and a deliberate CORS policy. The current same-origin layout simplifies deployment, but same origin alone does not authenticate requests.

**Grill follow-up:** Explain which credential the browser sends automatically; that detail determines the CSRF analysis.

### 43. How does configuration work, and how should secrets be handled?

**Strong answer:** `application.properties` supplies local defaults, while `${POSTGRES_URL:...}`, `${POSTGRES_USER:...}`, `${POSTGRES_PASSWORD:...}`, and `${MONGODB_URI:...}` let environment variables override them. Spring Boot has an ordered external-configuration model, so command-line arguments, environment variables, profile files, and other property sources can replace packaged values. That makes one artifact configurable across environments.

The checked-in `marketplace` credentials are development-only. Production secrets should come from a managed secret store or orchestrator-mounted secret, be scoped to least-privilege database users, rotated, and never logged or embedded in images. I would add explicit development/test/production profiles, fail fast when required production values are missing, validate configuration with typed `@ConfigurationProperties`, and document precedence to avoid surprising overrides.

**Grill follow-up:** Environment variables are an injection mechanism, not automatically a secret-management system; process inspection, crash dumps, logs, and CI configuration still matter.

### 44. What do the Actuator settings expose, and what would you change for production?

**Strong answer:** The project exposes only `health` and `info` over HTTP. `management.endpoint.health.show-details=always` makes component-level health details visible, which is convenient during local development and how the startup was verified. The health response can distinguish the overall app from database contributors, while `info` is available for build/application metadata if configured.

In production I would avoid publicly exposing detailed dependency names and failure messages. Management endpoints should be on a protected network or separate management port, with role-based access where needed; public liveness should be minimal, while readiness can include critical dependencies. I would add metrics, structured logs, trace/correlation IDs, and alerting, but expose each endpoint deliberately rather than publishing every Actuator endpoint.

**Grill follow-up:** Liveness should answer whether the process must restart; readiness should answer whether it can currently serve traffic. Making liveness depend on a transient database outage can cause restart storms.

### 45. What exactly does Docker Compose run, and why do health checks and volumes matter?

**Strong answer:** Compose starts PostgreSQL 18 and MongoDB 8, publishes their ports to the host, creates named volumes for durable local data, and runs engine-specific health checks. The Spring Boot application itself runs as a host process through Maven, not as a Compose service. `docker compose up -d --wait` waits for those declared health checks before `mvnw spring-boot:run`, reducing—but not eliminating—the startup race.

Named volumes preserve data when containers are recreated, which is why restarting can retain users, orders, and listings. `docker compose down` removes containers and the network but retains named volumes; `docker compose down -v` also deletes them and is intentionally the reset command. Published default ports can conflict with locally installed databases and the development credentials must not be exposed beyond a trusted machine.

**Grill follow-up:** “The containers are running” is weaker than “the services are ready”; health checks test readiness conditions, while restart policy and application reconnection handle later failures.

### 46. How would you deploy and operate CampusLoop in production?

**Strong answer:** I would build a reproducible application image from the tested artifact, run it as a non-root user, pin image and dependency versions, and deploy it behind TLS and a reverse proxy/load balancer. PostgreSQL and MongoDB would be managed, private services with backups, point-in-time recovery where supported, encryption, least-privilege credentials, replica/failover planning, capacity limits, and migration procedures. Demo seeding and development error detail would be disabled.

The delivery pipeline would run unit, integration, concurrency, security, and migration tests; generate an SBOM; scan dependencies/images; and promote the same immutable artifact. Operations need structured logs, request and purchase-attempt correlation, latency/error/saturation metrics, traces across datastore calls, SLO-based alerts, readiness/liveness probes, graceful shutdown, rollback or roll-forward plans, restore drills, and runbooks for stuck cross-store workflows.

**Grill follow-up:** A backup is not a recovery strategy until restores are tested and recovery-time/recovery-point objectives are measured.

### 47. Where will this design hit scaling or performance limits first?

**Strong answer:** Browse currently loads every available listing from MongoDB, sorts, then applies category and text filtering in Java. Cost and response size therefore grow with the full active catalog, not the page displayed. There is no pagination, query limit, database-side text search, cache, or payload projection. One slow or broad request can consume memory and CPU, and repeated search keystrokes amplify it.

I would move predicates and stable cursor-based pagination into MongoDB, create indexes proven by `explain`, cap page sizes, and return only card fields. Text search could use MongoDB search capabilities or a dedicated engine when relevance requirements justify it. Then I would load-test before adding caches. The service can scale horizontally because browser state is client-side, but workflows need shared idempotency, connection-pool tuning, and no in-memory coordination assumptions.

**Grill follow-up:** Do not claim an index helps because it exists; show the query shape, selectivity, sort, winning plan, examined/returned counts, latency, and write/storage cost.

### 48. What technical debt would you prioritize, in order?

**Strong answer:** First, protect correctness and identity: add authentication/authorization, a durable unique purchase invariant, idempotency, and a recoverable single-store or saga design. Second, add real integration and concurrency tests around those invariants. Third, introduce versioned database migrations and production-safe configuration/secrets. These items reduce the highest business and security risks.

Next I would implement database-side search/pagination and verify indexes, improve structured error handling and frontend stale-request behavior, add observability/reconciliation, and harden accessibility. Finally I would improve product features such as images, user-created accounts, editing, favorites, messaging, and payments. Priority is based on impact times likelihood and dependency order, not visual appeal.

**Grill follow-up:** A good debt list names the failure it prevents, evidence of risk, expected effort, dependencies, and a measurable completion criterion.

### 49. Could the entire application use PostgreSQL instead of two databases?

**Strong answer:** Yes, and for this scope I would strongly consider it. Listings are structured enough for a relational table with typed columns, checks, foreign keys, indexes, and optional `jsonb` for genuinely flexible attributes. A purchase could atomically change a listing from available to sold and insert its order in one PostgreSQL transaction, with a conditional update or row lock plus a unique constraint enforcing one order per listing.

That removes cross-store compensation, two operational systems, duplicated identity validation, and reconciliation for the core invariant. MongoDB is defensible if document flexibility, independent scaling, or an established document workload provides measured value, but polyglot persistence has a continuing consistency/testing/operations cost. Technology choice should follow access patterns and invariants rather than a desire to display more technologies.

**Grill follow-up:** Show the SQL transaction and constraints that enforce the invariant; saying “ACID” without a concrete isolation/locking design is not enough.

### 50. How would you evolve this demo into a real campus marketplace, and how would you present it honestly in an interview?

**Strong answer:** I would first define product and trust boundaries: verified campus identities, listing ownership/moderation, image storage, location/privacy rules, safe messaging, reporting, and a clear purchase/reservation state machine. Then I would secure identity, consolidate or harden the purchase workflow, introduce migrations and integration tests, add paginated search, store images in object storage behind signed URLs/CDN delivery, and instrument the system. Payments would require a provider, webhook verification, idempotency, refund/dispute flows, and no storage of raw card data.

In an interview I would demonstrate the working path, draw both datastore boundaries, trace one request, and explain the conditional Mongo claim before discussing limitations. I would explicitly say the UI uses demo identities, the workflow uses best-effort compensation rather than distributed atomicity, and the current tests are narrow. Then I would explain why those choices kept the learning project runnable and what evidence would drive the next design. Honest tradeoff analysis is much stronger than pretending a prototype is production-ready.

**Grill follow-up:** Be ready to state one invariant, one observed bottleneck, one failure mode, one security gap, one test that proves behavior, and one redesign you rejected with reasons.
