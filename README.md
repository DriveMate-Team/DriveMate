# DriveMate

초보 운전자를 위한 차량용(Android Automotive, Pleos Connect) 운전 연수 코치 앱입니다.
내비 경로를 보고 회전·교차로·고속도로 합류가 다가오면 미리 음성으로 알려 주고,
주행이 끝나면 깜빡이·급가속·급감속 기록을 리포트로 보여 줍니다.

## 주요 기능

- **연수 로드맵**: 좌회전 → 우회전 → 교차로 → 고속도로 합류 순서로 연습 단계를 보여 주고, 실제 주행 기록을 기준으로 단계를 완료 처리합니다.
- **주행 중 음성 코칭**: 다음 회전 지점과 거리를 큰 화살표로 보여 주고, 지점 전에 짧은 음성으로 안내합니다. 운전 중에는 최소한의 정보만 표시합니다.
- **음성 질문**: "비보호 좌회전은 어떻게 해?"처럼 물어보면 도로교통법 기준으로 검토한 답을 들려줍니다.
- **주행 기록**: 회전할 때 깜빡이를 켰는지, 회전·교차로 앞 급감속, 출발할 때 급가속을 기록합니다.
- **주행 리포트**: 지난 주행과 이번 주행을 막대로 비교하고, 다음 연습 목표를 정해 줍니다.

## 기술 구성

- Kotlin, Jetpack Compose
- Hilt (의존성 주입), Room (주행 기록 저장)
- Pleos Playground SDK: Vehicle(방향지시등·속도·기어), Navi(다음 회전 안내), TTS(음성 안내), STT(음성 질문)
- minSdk 29 / targetSdk 36, 모듈은 `automotive` 하나

## 실행 방법

1. Android Studio에서 프로젝트를 엽니다.
2. Pleos Connect 에뮬레이터를 켭니다.
3. `automotive` 모듈을 실행합니다.
4. Virtual Console에서 기어와 속도, 방향지시등을 바꿔 주행을 흉내 낼 수 있습니다.

테스트:

```bash
./gradlew :automotive:testDebugUnitTest   # 단위 테스트
adb forward tcp:8088 tcp:8088
node tools/e2e.js                         # 데모 경로로 주행 전체를 확인 (소리가 납니다)
```

## 스크린샷

| 홈 (연수 로드맵) | 주행 중 (내비와 반반 분할) | 주행 리포트 |
|---|---|---|
| ![홈](screenshots/home_roadmap.png) | ![주행 중](screenshots/drive_split.png) | ![리포트](screenshots/report.png) |

## 라이선스

글꼴 Pretendard는 SIL Open Font License 1.1을 따릅니다 (`LICENSE_Pretendard.txt`).
