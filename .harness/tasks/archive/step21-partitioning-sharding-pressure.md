# Step 21. Partitioning & Sharding Pressure Experiment

## 목표

Sharding을 정답으로 가정하지 않고 고정 데이터셋과 실제 부하를 사용해 분할 이득, 정합성 비용과 현재 병목을 비교한 뒤 `PROCEED` 또는 `DEFER`를 결정한다.

## 완료 기준

- [x] Step 20과 같은 capacity 환경과 측정 기준 재사용
- [x] 100,000건 결정적 데이터셋 구성
- [x] 균등, 80/20 merchant skew, single hot merchant, burst 구성
- [x] merchantId, orderId, paymentId, Kafka aggregate, Outbox key 비교
- [x] 4, 6, 8, 12 partition 분포와 hot partition 비율 계산
- [x] 6→8, 6→12 partition 변경 시 key remap 비율 계산
- [x] merchant 단위 reconciliation fan-out 계산
- [x] 실제 앱에서 100·200 req/s와 100→400 burst를 각 3회 측정
- [x] DB pool, lock wait, provider pressure, Kafka 분포와 lag 측정
- [x] 모든 실행 뒤 Outbox와 consumer 정합성 수렴 확인
- [x] 실제 논리 shard와 운영 routing은 구현하지 않음
- [x] `PROCEED/DEFER` 판정 근거 기록
- [x] History, Study, Blog, evidence 작성
- [x] 전체 실험과 인수 판정 통과

## 검증 결과

- partition simulation: 100,000건, 4개 분포, 5개 후보 키
- 실제 부하: 21회
- merchantId hot partition: 균등 20.00%, 80/20 83.03%, single-hot 95.76%
- paymentId hash hot partition: 모든 분포 약 16.7%
- paymentId hash의 merchant 대사 fan-out: 평균 약 6개 partition
- 6→12 partition 변경 시 paymentId key 약 49.9% remap
- 전체 실행 Hikari pending 0, DB lock wait 0
- 80/20·single-hot 200 req/s와 burst에서 provider pressure 3/3
- 최종 Kafka lag 0, 원장 차이와 중복 0
- Acceptance Result: PASS
- Architecture Decision: DEFER

## Completion
- Completed At: 2026-08-12T03:27:33Z
- Verify Result: pass
- Rework Count: 0
- Last Failure: none
