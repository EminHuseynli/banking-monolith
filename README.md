# Banking Monolith

**This project is Part 1 of a two-part architectural journey.**                                                                                                                 
  > The monolith was built first to establish a working system and a measurable performance baseline.                                                                               
  > It was then decomposed into independent microservices. See [banking-microservice](https://github.com/EminHuseynli/banking-microservice) for where the story continues.
  > A production-shaped banking backend built as a **single deployable unit**, not as a toy REST API, but as a real system with auth, business rules, observability, and a frontend UI.
  > The goal was never to keep it a monolith. The goal was to **understand the system deeply before splitting it**, and to have concrete numbers to compare against after the split.


## Overview

Users register and log in. Authentication is **JWT-based**; the token carries the user identity through every request. Once authenticated, a user can open a checking or savings account. They can have multiple accounts. Each account has a balance, and only active accounts can be used for transactions. From there, three core operations are available. A user can deposit money into an account, increasing the balance. They can withdraw, which checks whether the balance is sufficient and rejects the request if not. And they can transfer between accounts, money leaves one and arrives in the other atomically, both changes happen in a single database transaction, so there is no state where one side is updated and the other is not. To prevent race conditions under concurrent load, the source account is fetched with a pessimistic write lock before any balance is touched.                                                                                                                             
Every one of these operations automatically generates a notification. In this version, that happens synchronously, the notification is written in the same request cycle as the transaction. It works, but it means the user waits for both. **This is one of the things the microservice version fixes.**
Users can retrieve their notification history and filter transaction history by date range on any of their accounts. Everything is secured end-to-end. Every request goes through JWT validation, and the user identity is extracted from the token, never trusted from the request body. A user cannot touch another person's accounts.
The system is fully containerized. Docker Compose brings up the app, PostgreSQL, Prometheus, and Grafana together. Metrics are exposed via Spring Actuator and scraped by Prometheus. A Swagger UI is available for exploring the API directly.

## Tech stack and Architecture

The codebase is organized into five modules: **user, account, transaction, notification, and security.** The first four follow the same layered structure - a controller that handles HTTP, a service that owns the business logic, a repository that talks to the database. Security sits outside this pattern; it is a cross-cutting concern made up of a JWT filter, a utility class, and Spring Security configuration that applies globally across all requests.     
  
**Java 17** and **Spring Boot 3.5** for the core framework. **Spring Web** for the REST layer, **Spring Data JPA** for persistence, **Spring Security** for authentication, **Spring Validation** for request validation, and **Spring Actuator** for health and metrics exposure. **PostgreSQL** as the database. JWT handling via **JJWT 0.12.6**. **Lombok** to reduce boilerplate. **SpringDoc OpenAPI 2.8** for Swagger UI. Micrometer with **Prometheus and Grafana** for metrics collection and visualization. **Maven** as the build tool. Everything containerized with **Docker and Docker Compose**.

<img width="1296" height="662" alt="image" src="https://github.com/user-attachments/assets/6165e570-a594-4c10-83f8-da145c810f56" />

## Getting started

The entire system — app, database, Prometheus, and Grafana comes up in a single command. Because this is a monolith, there is only one process to monitor. One dashboard covers everything: request rate, response times, JVM memory, and active connections, all in a single view. No service topology to trace, no inter-service latency to account for. This simplicity is intentional, and it is exactly what makes the contrast visible when you look at the microservice version, where each service has its own dashboard and distributed tracing becomes necessary.

<img width="1448" height="140" alt="Screenshot (194)" src="https://github.com/user-attachments/assets/0a91a834-d94c-47c8-844f-2c4b97c1a6c6" />
<img width="1899" height="432" alt="Screenshot (195)" src="https://github.com/user-attachments/assets/7e1ed6a0-5969-40fb-a499-d4db5624ddcd" />
<img width="1634" height="671" alt="Screenshot (193)" src="https://github.com/user-attachments/assets/496f83c9-0fb6-4468-b1bd-43b4371af967" />

## Load Testing with Jmeter
This section documents the performance characterization of the monolithic architecture under concurrent load, and the diagnostic process that identified the true bottleneck. The results establish the empirical motivation for the microservice migration documented in banking-microservices.

### Test Methodology
> ⚠️ **Important caveat:** the application, database, monitoring stack, and load generator all share the same host. This is both a constraint and, as the findings below show, the root cause of the observed behavior.

---

## Throughput Collapse After Initial Peak

The first issue surfaced under a **50-thread load test**. The system exhibited a clear and reproducible degradation pattern across three correlated signals.

### 1. HTTP Request Throughput — Peak and Collapse

At the start of the test, HTTP request throughput spiked sharply as JMeter ramped up. Shortly after, throughput collapsed and, critically, **never recovered to its initial peak**. Subsequent oscillations stayed below **a quarter of the initial peak**, despite JMeter continuing to send requests at the same rate.
This is not a graceful degradation curve. The system hits a ceiling, falls off it, and stays down.

<img width="929" height="413" alt="image" src="https://github.com/user-attachments/assets/f5955916-f78a-4ee0-ae33-db32ccb6d90b" />

### 2. Response Time — Inverse Mirror of Throughput

Response time traced the **inverse** of throughput: low during the initial high-throughput window, and sharply elevated in the degraded region. This rules out client-side limitations (JMeter backing off)? the server *is* receiving requests, it is just processing them slowly.

<img width="929" height="413" alt="image" src="https://github.com/user-attachments/assets/932334c9-0385-40c8-8881-299257cdf93e" />

### 3. GC Allocation/Promotion — The Correlation

The most telling signal: the GC **allocated/promoted bytes** graph is nearly **identical in shape** to the HTTP request throughput graph. When the application processes requests, it allocates memory; when it stops processing, allocation drops. The two curves move together.

<img width="929" height="413" alt="image" src="https://github.com/user-attachments/assets/ce42346f-f889-4b98-9838-536857ebbbc5" />

This correlation matters because it confirms the bottleneck is **not memory-related**, the GC is responsive and scales directly with load. If GC were the problem, allocation would continue while throughput collapsed (objects piling up, collection lagging). Instead, allocation *follows* throughput, meaning some other resource is gating the system.

---

## The Investigation: Eliminating Suspects, Finding the Root Cause
 
With the symptom characterized, the next step was diagnosis. Several standard monolith tuning interventions were attempted:
 
- **HikariCP pool size increase to 80** — no measurable effect on the throughput collapse.
- **Notification module converted to async execution** — decoupled a secondary concern, but did not restore peak throughput.
None of these interventions moved the needle. This was itself a strong signal: the bottleneck was not at the application layer.

---

### System-Level CPU Investigation

Shifting the investigation from application metrics to **host-level system CPU** produced the decisive evidence. The system CPU utilization curve traces **the exact same shape** as the HTTP request throughput curve. System CPU and request throughput rise together, peak together, and collapse together. The host runs out of CPU headroom at the same moment throughput collapses.

<img width="1013" height="435" alt="image" src="https://github.com/user-attachments/assets/b744ee71-90df-459b-a6c6-e20c171b0743" />

###Test — 50 vs 100 Concurrent Users

The most conclusive evidence came from comparing the **50-thread and 100-thread** scenarios. Doubling the concurrent load did **not** double the peak throughput. Both scenarios:
 
- Ramped up to **the same HTTP request peak**
- Collapsed at **the same point**
- Stabilized at **the same reduced throughput**

A properly scaling system should show increasing throughput with increasing load, up to its saturation point. The fact that 50 and 100 users produce identical curves means the system is **already saturated at 50 users** on this hardware. The extra 50 threads have nowhere to go, they sit in queues, waiting for CPU time that does not exist.

<img width="1013" height="429" alt="image" src="https://github.com/user-attachments/assets/929aa18c-6e85-4201-845c-ed971cd19f45" />

---

## Interpretation — Why Application-Layer Fixes Could Not Help

The root cause is structural, not algorithmic. On a single physical machine, **every component competes for the same CPU cores**:
 
- The Spring Boot application (request handling, business logic, serialization, JWT verification, BCrypt)
- PostgreSQL (query execution, WAL writes, index maintenance)
- Prometheus scraping and Micrometer instrumentation
- JMeter load generator itself
- The OS, Docker runtime, and container networking

When JMeter ramps up, it takes CPU. When the application handles requests, it takes CPU. When PostgreSQL writes, it takes CPU. When Prometheus scrapes, it takes CPU. All of these compete, and the 50-vs-100 comparison proves that the ceiling is reached long before the application itself becomes the bottleneck.





