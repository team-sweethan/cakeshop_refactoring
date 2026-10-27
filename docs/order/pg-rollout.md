# PG 전환 배포와 장애 대응

이 문서는 [기능 명세](pg-integration-spec.md)와 [ADR 003](pg-decisions/003-payment-provider-routing.md)의
정책을 배포·점검하는 절차다. 공용 DB 변경 권한이나 실제 자체 PG 활성화를 승인하는 문서가 아니다.
자체 PG API 계약을 기다리는 동안에는 신규 선택을 `toss`로 유지한다.

## 1. API 계약 없이 완료하는 준비

| 작업 | 완료 기준 | 상태 |
|---|---|---|
| 경쟁 요청 보호 | 고객 취소·관리자 승인·반려에서 주문·환불·재고 일관성 유지 | 검증 완료 |
| 중복 요청 보호 | 일반·장바구니·수제 주문, 유료·0원 결제 재전송에서 중복 반영 방지 | 검증 완료 |
| PG 식별 저장 | 기존 READY·DONE·만료·취소 기록을 TOSS로 보존, 새 결제는 선택값 저장 | 구현·기존 DB 업그레이드 검증 완료 |
| 결제별 라우팅 | 승인·조회·환불·보상·결제창 정보·실패 안내가 저장한 PG 사용 | 구현·검증 완료 |
| 장애 후 재처리 | DB 재조회로 원 PG·멱등 키 복원, 동일 배치의 다른 PG 처리, 환불·재고 단일 반영 | 통합 검증 완료 |
| 진단 | 복구 실패 로그에서 PG와 내부 주문·요청 식별자를 확인하고 아래 조회로 상태 확인 | 구현 |
| 배포·설정 복귀 | migration 먼저 적용, 원 PG 설정 유지, 기존 결제 소유 PG 불변 | 이 문서로 절차 정리 |

API 없이 만드는 대역은 테스트 안에서 공통 `PaymentGateway` 결과를 제공한다. 운영용 자체 PG 대역이나
추정한 HTTP 요청·응답은 추가하지 않는다. 아래 검증은 계약 호환성이나 실제 결제를 증명하지 않는다.

## 2. 현재 변경 배포 전

1. 전체 테스트와 애플리케이션 패키징을 실행한다.

   ```bash
   ./gradlew test bootJar
   ```

2. 대상 DB의 migration 이력과 현재 신규 PG 선택값을 확인한다. 이 단계에서는 `toss`를 유지한다.
   읽기 조회 결과에 접속 정보·결제 키·개인정보를 섞어 외부에 공유하지 않는다.

   ```sql
   SELECT version, description, success
   FROM flyway_schema_history
   ORDER BY installed_rank DESC
   LIMIT 10;

   SELECT status, COUNT(*) AS payment_count
   FROM payments
   GROUP BY status;

   SELECT request_type, status, COUNT(*) AS cancellation_count
   FROM payment_cancellations
   GROUP BY request_type, status;
   ```

3. 백업·복구 가능 여부와 기존 데이터가 모두 Toss 기록이라는 전제를 확인하고, 팀의 공용 DB 절차로
   `V20261026_195117__add_payment_provider.sql`을 적용한다. 공유된 과거 migration은 수정하지 않는다.
   서버·공용 RDS에서는 애플리케이션 시작으로 Flyway를 실행하지 않는다.
4. 아래 조회에서 누락 PG가 0건이고, 기존 결제의 상태별 건수가 적용 전과 같은지 확인한다.
   기존 행은 모두 `TOSS`여야 한다. 이미 `OWN` 결제가 있는 환경에서는 일괄 backfill을 다시 하지 않는다.

   ```sql
   SELECT provider, status, COUNT(*) AS payment_count
   FROM payments
   GROUP BY provider, status;

   SELECT COUNT(*) AS invalid_provider_count
   FROM payments
   WHERE provider IS NULL OR provider NOT IN ('TOSS', 'OWN');
   ```

5. PG별 라우팅을 아는 애플리케이션을 배포한다. Toss 결제창·승인·환불·미완료 복구 동작을 점검한다.
   이 배포는 자체 PG로의 실제 전환이 아니며 `own` 구현이 없는 상태의 `own` 선택은 시작 실패가 정상이다.

컬럼·CHECK는 한 ALTER로 적용한다. 적용 실패 시 이력과 남은 스키마를 확인하고 공용 DB 담당 절차를
따른다. 임의 `repair`, 결제 행 삭제나 `provider` 일괄 수정으로 우회하지 않는다.

## 3. 자체 PG를 실제 활성화할 때의 별도 완료 조건

아래 항목은 계약 확인을 마지막 단계로 미룬 동안 보류한다.

- 승인·조회·취소의 인증·요청·응답과 상태 매핑, 조회 결과 없음·오류 의미를 확정한다.
- 멱등 키 보존 기간·동시 요청 처리·타임아웃 재조회와 결제·거래 키의 식별자 범위를 확인한다.
  기존 전역 UNIQUE와 충돌하면 별도 설계 결정과 migration이 필요하다.
- 실제 `OwnPaymentGateway`와 해당 결제창 흐름을 구현한다. 기존 Toss 구현체도 함께 유지한다.
- 테스트 환경에서 실제 프로토콜과 승인·환불·타임아웃·재전송을 검증한다.
- 웹훅을 사용할 경우 인증·중복·순서 역전 계약을 먼저 확정한다. 현재 Toss 웹훅 컨트롤러도 미구현이므로
  웹훅 복구가 가능한 것으로 간주하지 않는다.

이 조건을 충족한 후에만 신규 결제 선택을 `own`으로 바꾼다. 변경 전에 생성한 READY 결제는 Toss로
계속 처리한다. 전환 후 provider별 신규 기록과 미완료 요청을 아래 조회로 확인한다.

## 4. 장애 조회

```sql
SELECT payment.provider, cancellation.request_type,
       COUNT(*) AS pending_count, MIN(cancellation.requested_at) AS oldest_requested_at
FROM payment_cancellations AS cancellation
JOIN payments AS payment ON payment.id = cancellation.payment_id
WHERE cancellation.status = 'REQUESTED'
GROUP BY payment.provider, cancellation.request_type;

SELECT cancellation.id AS cancellation_id, payment.id AS payment_id,
       payment.order_id, payment.provider, payment.status AS payment_status,
       cancellation.request_type, cancellation.requested_at
FROM payment_cancellations AS cancellation
JOIN payments AS payment ON payment.id = cancellation.payment_id
WHERE cancellation.status = 'REQUESTED'
ORDER BY cancellation.requested_at, cancellation.id
LIMIT 50;
```

복구 실패 로그의 `provider`, `orderId`, `paymentId` 또는 `cancellationId`로 대상 요청을 찾는다.
결제 키·API 키·인증 헤더·환불 사유 원문·예외 원문을 로그에 추가하지 않는다.

- PG 미등록·접속 장애·인증 오류이면 원 PG 구현체와 설정을 복원한다. 다른 PG에 같은 요청을 보내지 않는다.
- 정상 복구 스케줄러가 DB의 `REQUESTED` 요청을 다시 읽게 한다. 새 멱등 키나 새 취소 요청을 만들지 않는다.
- 외부 취소 성공 후 DB 확정이 실패한 경우에도 원 멱등 키와 같은 PG의 상태 조회로 복구한다.
- 단일 실패가 같은 배치의 후속 요청을 중단하지 않는 것을 검증했다. 다만 대상 조회는 오래된 요청부터
  `batch-size`만큼이므로 실패가 누적되면 이후 요청 처리가 지연될 수 있다. 자동 backoff·PG별 배치
  공정성은 후속 작업으로 분리한다.

## 5. 신규 결제 선택을 Toss로 되돌리기

1. 신규 결제 선택만 `toss`로 바꾼다.
2. 이미 생성한 `OWN` 기록·요청·멱등 키는 유지한다. `provider`를 TOSS로 바꾸지 않는다.
3. 자체 PG 구현체·설정·인증 정보를 유지해 기존 OWN 조회·환불·복구를 계속 처리한다.
4. 신규 생성 건이 TOSS인지, 기존 OWN 미완료 요청이 감소하는지 확인한다.
5. 자체 PG 결제가 생긴 뒤에는 provider를 모르는 옛 바이너리로 되돌리지 않는다.
