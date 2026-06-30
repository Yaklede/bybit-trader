<!-- OPENDOCK:START id=files:docs/rules/backend/SECURITY_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/SECURITY_RULES.md -->
# Backend Security Rules

## 목표

백엔드 인증, 인가, 세션, 비밀값, 민감정보 처리가 코드 규모와 작업자에 따라 약해지지 않게 한다.

AI 에이전트는 이 문서를 기준으로 security config, auth controller, session, password, OAuth, CORS, secret, logging 변경을 판단한다.

## 적용 범위

- 인증/인가, visitor session, admin session, password handling, OAuth provider integration, CORS, cookie, security filter, secret config 변경에 적용한다.
- public blog API와 CMS admin API에 모두 적용한다.
- API shape는 [API_RULES.md](API_RULES.md), 운영 설정은 [OPERATIONS_RULES.md](OPERATIONS_RULES.md), 테스트는 [TESTING_RULES.md](TESTING_RULES.md)를 따른다.

## 정의

- Authentication: 요청 주체가 누구인지 확인하는 절차.
- Authorization: 확인된 주체가 해당 작업을 수행할 수 있는지 판단하는 절차.
- Visitor session: 공개 블로그 사용자를 위한 session context.
- Admin session: CMS 관리자를 위한 session context.
- Secret: password, client secret, signing key, token, API key처럼 저장소에 노출되면 안 되는 값.
- Sensitive data: password, token, cookie, session id, email, provider raw response, 개인정보 또는 인증 우회에 쓰일 수 있는 값.

## 규칙

- SEC-AUTHN-001: 인증이 필요한 endpoint는 명시적으로 보호한다.
- SEC-AUTHN-002: 인증 실패는 fail closed로 처리한다.
- SEC-AUTHN-003: public visitor session과 CMS admin session을 공유하지 않는다.
- SEC-AUTHN-004: OAuth provider token, authorization code, client secret을 로그나 응답에 노출하지 않는다.
- SEC-AUTHZ-001: 관리자 API는 관리자 인증과 권한을 요구한다.
- SEC-AUTHZ-002: client가 보낸 role, user id, admin flag를 신뢰하지 않는다.
- SEC-SESSION-001: session attribute key는 기능별로 분리하고 충돌하지 않게 관리한다.
- SEC-SESSION-002: logout, 인증 실패, 권한 실패 시 session cleanup 요구를 검토한다.
- SEC-COOKIE-001: cookie 보안 속성은 환경별 배포 조건을 고려한다.
- SEC-PASSWORD-001: password는 plain text로 저장하거나 비교하지 않는다.
- SEC-PASSWORD-002: password verification은 security 책임을 가진 service 또는 port를 통해 수행한다.
- SEC-SECRET-001: secret은 코드, 문서, `.http`, test fixture에 실제 값으로 커밋하지 않는다.
- SEC-SECRET-002: secret은 environment, secret manager, deployment config 등 외부 주입으로 관리한다.
- SEC-LOG-001: 민감정보를 application log, access log, exception message에 남기지 않는다.
- SEC-LOG-002: security failure는 원인 추적이 가능하되 공격자에게 내부 판단 근거를 과도하게 노출하지 않는다.
- SEC-CORS-001: CORS origin, credential 허용 여부는 명시적으로 설정한다.
- SEC-TEST-001: security 변경은 인증 성공, 인증 실패, 권한 실패, 세션 분리 테스트를 포함한다.
- SEC-LEGACY-001: 기존 security 우회 코드가 있으면 새 코드에서 복제하지 않는다.

## 우선순위

- SEC-PRIORITY-001: 보안 규칙은 API 편의, 테스트 편의, 기존 코드 관례보다 우선한다.
- SEC-PRIORITY-002: 인증/인가 정책이 불명확하면 구현하지 말고 사용자에게 확인한다.
- SEC-PRIORITY-003: secret 노출 가능성이 있으면 기능 구현을 멈추고 제거 또는 회전 필요성을 보고한다.
- SEC-PRIORITY-004: security rule과 operations rule이 충돌하면 보안을 우선하고 운영 대안을 찾는다.
<!-- OPENDOCK:END id=files:docs/rules/backend/SECURITY_RULES.md dock=wooyongdev/backend-engineering-kit path=docs/rules/backend/SECURITY_RULES.md -->
