# 제출 폼 (Submit Project) — 2026-10-27 확인

| # | 항목 | 필수 | 형식 | 우리 답변 방향 |
|---|---|---|---|---|
| 1 | Team Name | ✅ | 텍스트 | (미정 — 나중에 결정) |
| 2 | Team Members | — | 자동 (ggrraayy · Yehun Chang) | 1인 |
| 3 | Which track | ✅ | 선택 | **Indie Developer** (확정) |
| 4 | Which problem statement | ✅ | 선택 | **Build for Your World** (확정) |
| 5 | Most Viral X post link | — | URL | 11/4 제출 전 X 게시 후 링크 (반응 집계 11/8까지) |
| 6 | Project Description | ✅ | 장문 | 문제(경보 순간 어디로 가야 할지 모름, 재난마다 정답이 다름, 통신 두절) → 해결(범용 재난 규칙 + 보행망 오프라인 경로 + 생활 경로 에이전트) → 검증 수치 |
| 7 | Public GitHub Repository | ✅ | URL | 공개 레포 (대회 중 작성 코드만) |
| 8 | 3-Minute Demo Video | ✅ | URL (YouTube 예시) | YouTube 일부공개 업로드 — 결선 심사가 이 영상으로 진행됨 |
| 9 | Describe your use of Meta products | ✅ | 장문 | Muse Spark tool calling 계획 에이전트 / Muse Glimmer(오픈웨이트) 오프라인 답변 사전 생성·검수 / Voice Transcribe / Muse Code |
| 10 | Why are Meta's models important to your solution? | ✅ | 장문 | 아래 "핵심 논리" |
| 11 | Feedback on Meta products | — | 장문 | 실제 겪은 점: 추론 토큰이 `max_tokens`에 포함(빈 응답), `tool_choice` auto만 지원, Glimmer `reasoning_effort` 등 (V11b·V20) |
| 12 | Feedback on partner tech (Fireworks, Modal, Baseten) | — | 장문 | 사용 시에만 작성 |
| — | 홍보 사용 동의 | 체크 | — | Meta·Cerebral Valley가 제출물 사용 가능 |

## 폼에서 확인된 사실
- **보너스(Open weights, Agentic, Creative) 전용 체크 항목이 없음.** Most Viral만 별도 링크 칸. → 보너스는 **9·10번 서술과 영상**에서 드러나야 함: "Muse Glimmer 오픈웨이트로 오프라인 답변을 만든다", "Muse Spark가 도구를 호출하며 계획을 세운다"를 명시적으로 쓰기.
- 영상은 URL 제출 → YouTube 업로드 필요(일부공개 가능 여부는 폼 예시상 YouTube 링크면 충분).
- 9·10번이 필수라 **Meta 모델이 왜 꼭 필요한지**를 설득해야 함 — 단순 연동이면 감점 위험.

## 10번 "Why Meta's models" 핵심 논리 (초안)
1. 오프라인 안전 답변은 **오픈웨이트라서 가능**: Glimmer를 로컬에서 돌려 "의도 × 재난 × 언어" 답변을 대량 생성·검수하고 기기에 내장 — 망이 끊겨도 동작. 실측: 골든셋 23/23 안전 정답, 소형 온디바이스 모델은 8/23(위험 오답 포함).
2. 개인화 계획은 **tool calling이 있어서 가능**: Muse Spark가 검증된 대피소·경로 도구만 호출해 생활 구간별 계획을 세우고, 앱이 결과를 검증(지어낸 장소 0).
3. 다국어·음성: Voice Transcribe로 손이 자유롭지 않은 상황의 음성 질문.

## 제출 전 체크리스트
- [ ] 레포 공개 + README(데이터·모델 크레딧, Built with Muse Code, 대회 중 작성 명시)
- [ ] YouTube 3분 영상(내레이션) 링크 동작 확인
- [ ] X 게시 → 링크 입력
- [ ] 9·10번에 Glimmer(오픈웨이트)·Spark 에이전트 명시
- [ ] 마감 6시간 전 제출 (11/4 15:00 ET = 11/5 05:00 KST)
