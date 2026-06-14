# Load tests

The existing `run-load-test.ps1` and `run-benchmark.ps1` commands continue to cover the synchronous issue API.

The asynchronous runner pre-authenticates one distinct user per iteration, measures only the `202 Accepted` submission calls in k6, then polls MySQL until every request is `ISSUED` or `REJECTED`. Results are written as timestamped JSON and CSV files under `build/load-test`.

## Asynchronous smoke

Start the application and its asynchronous workers first, then run:

```powershell
.\load-test\run-async-load-test.ps1 -Mode DirectKafka -Vus 2 -Iterations 5 -DrainTimeoutSeconds 30
.\load-test\run-async-load-test.ps1 -Mode Outbox -Vus 2 -Iterations 5 -DrainTimeoutSeconds 30
```

The defaults expect the request table to be `coupon_issue_requests`, the Kafka container to be `coupon-kafka`, and the topic to be `coupon-issue-requests`. Override them with `-RequestTable`, `-KafkaContainer`, and `-KafkaTopic`.

Normal runs preserve the Kafka topic and the existing consumer-group offsets. The runner only creates the topic when it is missing. `-ResetKafkaTopic` is an explicit recovery option that deletes and recreates the topic; do not use it while the Spring consumer is running because topic deletion can trigger unknown-topic/partition errors, consumer position timeouts, and distorted drain measurements.

## Compare modes

```powershell
.\load-test\run-async-benchmark.ps1 -Vus 50 -Iterations 500 -Repeats 3
```

Omitting `-Modes` runs both modes. To run one mode, use `-Modes DirectKafka` or `-Modes Outbox`. The parameter also accepts a quoted comma-separated value such as `-Modes "DirectKafka,Outbox"`, including with `powershell -File`.

Each mode is reset and run separately. The comparison CSV contains submission count, req/s, avg/p90/p95/max latency, unexpected responses, terminal counts, stock integrity counts, validation results, and drain time. The JSON also preserves counts grouped by terminal status and `result_code` in each underlying run summary.

The comparison runner does not delete the Kafka topic between runs. Each run must fully drain before the next run starts, allowing the same consumer group to continue from its committed offsets.
