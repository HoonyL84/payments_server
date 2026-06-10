docs/project/PLANS.md를 읽고 현재 로드맵을 최대 10개의 순서 있는 구현 티켓으로 분해하세요.

아래 JSON 형식만 출력하세요.

{"feature":"summary","tasks":[{"name":"kebab-case","type":"feat","description":"goal","risk":"low"}]}

규칙:
- PLANS.md가 아직 placeholder라면 목표를 임의로 만들지 마세요.
- 각 티켓은 독립적으로 검증 가능한 최소 단위여야 합니다.
- type은 feat, fix, refactor, docs, chore, test, experiment 중 하나여야 합니다.
