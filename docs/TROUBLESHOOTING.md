# Troubleshooting Guide

## 1. Rate Limit Exhaustion (Binance 429)
- **Symptom**: Console throws `TooManyRequests` or UI displays "WebClient Response Exception".
- **Resolution**: Let Resilience4J circuit breakers chill for 60s. AutoScanner will automatically mute. Check `backend.log` for half-open pings.

## 2. Clock Drift (Timestamp Error)
- **Symptom**: `Invalid Timestamp` from Binance.
- **Resolution**: Sync your local MacOS / Windows clock natively.

## 3. Database Migration Failures
- **Symptom**: Flyway checksum mismatch.
- **Resolution**: Drop local Docker DB and restart using `./stop-all.sh` then `docker-compose down -v` to destroy the volume. 

## 4. Database Authentication Failed (DB_DOWN)
- **Symptom**: API errors show `errorCode=DB_DOWN` and `password authentication failed for user`.
- **Resolution**:
  - Update `spring.datasource.url`, `spring.datasource.username`, and `spring.datasource.password` in `src/main/resources/application.yml`.
  - Ensure values match your local Postgres user/password.
  - Replace placeholder password values before starting backend.

## 5. UI CORS Errors
- **Symptom**: React throws Failed to fetch natively.
- **Resolution**: Make sure Spring WebConfig has origins explicitly trusting `http://localhost:5173`.
