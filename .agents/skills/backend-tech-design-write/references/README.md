# Backend TDD 참조 문서 맵

## 목적

`backend-tech-design-write` 스킬의 상세 절차와 문서 템플릿을 분리해 `SKILL.md`를 고수준 운영 문서로 유지한다.

## 적용 범위

이 디렉토리는 백엔드 기술설계문서 작성에 필요한 세부 흐름과 산출물 구조를 소유한다. 스킬 호출 조건과 최상위 책임은 [../SKILL.md](../SKILL.md)가 소유한다.

## 문서 계층

```text
SKILL.md
  -> references/README.md
  -> references/backend-tdd-workflow.md
  -> references/backend-tdd-template.md
```

## 문서 역할

- [backend-tdd-workflow.md](backend-tdd-workflow.md): 설계 대상 식별, 근거 수집, 판단 순서, 저장 절차
- [backend-tdd-template.md](backend-tdd-template.md): 최종 TDD 섹션 구조, 섹션별 필수 내용, 작성 기준

## 검증

- 상세 절차나 템플릿이 `SKILL.md` 상단 설명을 밀어내지 않는다.
- workflow 문서는 처리 순서를, template 문서는 산출물 구조를 각각 소유한다.
- backend 프로젝트 규칙은 고정 가정이 아니라 `docs/rules/backend/**`와 실제 코드에서 확인한 근거로 다룬다.
