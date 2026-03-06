# Security Operations Procedure

## API Keys
1. Your Binance keys must be heavily restricted: **Enable Reading only**. Do not enable margin transfers or Spot placement. Futures must be checked for reading.
2. The UI never talks to Binance directly. Your frontend is statically served via Vite logic; the Backend handles IP whitelisting using Spring Boot securely.

## Logs
Do NOT paste `backend.log` or stack traces directly into public AI chatbots if they contain API keys.
The backend handles secrets parsing strictly but environment variables may be dumped during Spring Boot fail-fast scenarios.
