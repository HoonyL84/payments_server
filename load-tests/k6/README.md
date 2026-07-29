# k6 consistency test

Run the application with the `k6` profile, then execute:

```bash
k6 run load-tests/k6/payment-consistency.js
```

The test-only reset and PG control endpoints are unavailable without the `k6` profile.
