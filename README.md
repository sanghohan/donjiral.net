# donjiral.net

돈지랄이 만든 안드로이드 앱을 소개하는 정적 사이트. 앱마다 소개 + 게시판(공지·질문답변·자유) 페이지가 있다.

- 서비스 주소: https://www.donjiral.net/

## 페이지 구성
```
index.html            # 홈 — 앱 목록 (참교육 · Now Playing CD · 마스크폴)
apps/chamedu/         # 🛡️ 참교육 소개 + 게시판 (board=chamedu)
apps/nowplayingcd/    # 💿 Now Playing CD 소개 + 게시판 (board=nowplayingcd)
apps/maskfall/        # 🛸 마스크폴 MASKFALL 소개 + 게시판 (board=maskfall)
apps/maskfall/tune/   # 마스크폴 난이도 단계 관리(관리자 암호, noindex) — docs/마스크폴-난이도-설정.md
apps/board.js         # 앱별 게시판 공용 코드 — 페이지의 <body data-board="앱"> 로 게시판을 고른다
apps/site.css, site.js, img/  # 공용 스타일 · 방문 수 · 앱 아이콘
nowplayingcd/index.html  # Now Playing CD 영어 소개·설명서 (앱 영어 화면의 About 링크)
nowplayingcd/ja/, es/    # 같은 글의 일본어 · 스페인어 (앱 일본어 · 스페인어 화면의 링크, 1.9부터)
dev/index.html        # 옛 '돈지랄 앱 게시판' 주소 → 앱 페이지로 옮겨 줌 (앱에 박힌 /dev/?post=68 · 130 링크용)
aicheck/index.html    # 옛 AI 영상 판별 주소 → 홈 (2026-10 서비스 종료)
lotto/index.html      # 🍀 로또 6/45 번호 생성기 (자동·반자동·수동·통계·추천)
pension/index.html    # 💰 연금복권720+ 번호 생성기 (자동·반자동·수동)
pingpong/index.html   # 🏓 탁구 커뮤니티
privacy/index.html    # 개인정보처리방침
privacy/nowplayingcd/ # Now Playing CD(안드로이드 앱) 개인정보처리방침 — 플레이 스토어 등록용
backend/              # 탁구 백엔드 (docs/탁구백엔드-배포가이드.md 참고)
docs/                 # 설정/배포 문서
```

## 로또 번호 생성기 (/lotto/)
- 자동·반자동(번호 고정)·수동 생성, 저장된 조합은 최신 회차와 자동 대조해 등수 표시
- 당첨번호 통계: 번호별 출현 빈도, 핫/콜드/장기 미출현, 회차 조회, 최근 10회
- 통계 기반 추천(핫넘버·콜드넘버·빈도 가중·밸런스) — 재미용, 확률 향상 아님
- 데이터: https://smok95.github.io/lotto/results/all.json 을 열 때마다 갱신, localStorage 캐시

## 연금복권720+ 생성기 (/pension/)
- 조(1~5)+6자리 자동·반자동(조/자릿수 고정)·수동 생성, localStorage 저장

## 마스크폴 MASKFALL
- 종스크롤 슈팅 게임(옛 이름 퀀텀 파이터)은 안드로이드 앱으로 옮겨 웹판(/quantumfighter/)을 내렸다 — 게임 원본은 앱 저장소(quantumfighter-app)에 있다

## 로컬 실행
파일을 브라우저로 열기만 하면 됩니다.

## 배포
`docs/배포가이드.md` 참고 (GitHub 푸시 → 정적 호스팅 → 카페24 도메인 연결).
