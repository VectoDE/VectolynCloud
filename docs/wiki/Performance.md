# Performance

[Deutsch](Performance-DE)

**There are no performance measurements yet.** No benchmark and no load test has been run, so this page has no numbers and we do not claim any. It states what we want to measure, how, and where the reports will appear once they exist.

## Targets

The project sets these targets. They are targets, not results. Each is stated relative to a baseline that is measured first with the same method on the same hardware ([#138](https://github.com/VectoDE/VectolynCloud/issues/138)).

| Measure | Target |
|---|---|
| Cold start until the cloud is ready | half of the baseline |
| Memory of an idle cloud with no services | half of the baseline |
| Time from start command until a service is reachable | half of the baseline |
| Throughput and API latency | twice as good as the baseline |
| Load test | at least 1,000 simultaneous players and 200 services |
| Live updates in the panel over WebSocket | under one second |

## How it will be measured

1. A fixed measurement environment (hardware, Java version, configuration), written down with the results ([#138](https://github.com/VectoDE/VectolynCloud/issues/138)).
2. A harness and benchmarks that anyone can repeat, with the raw data and the scripts in the repository ([#139](https://github.com/VectoDE/VectolynCloud/issues/139)).
3. Optimisation of start time and memory ([#140](https://github.com/VectoDE/VectolynCloud/issues/140)) and of service start and throughput ([#141](https://github.com/VectoDE/VectolynCloud/issues/141)).
4. A load test with [k6](https://k6.io/) against the API and the WebSocket, run in CI against a test environment, never against a system we do not own. The report is a CI artifact and is linked here ([#142](https://github.com/VectoDE/VectolynCloud/issues/142)).
5. An acceptance run that checks every target against the report ([#143](https://github.com/VectoDE/VectolynCloud/issues/143)).

## What exists today

These are tests of behaviour, not of speed:

- unit and integration tests on Linux and Windows with JDK 25, and the tests again on Java 21;
- an end-to-end test with the real `VectolynCloud.jar` and fake servers in CI, and a weekly run with real downloads and servers;
- fuzz tests for the parsers that read outside input;
- limits that protect the node under load: connection and request limits of the cloud channel and the API, and a memory budget for autoscaling ([scaling](https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/scaling.md)).

Reports will be linked from here and from the README when [#142](https://github.com/VectoDE/VectolynCloud/issues/142) and [#143](https://github.com/VectoDE/VectolynCloud/issues/143) are done.
