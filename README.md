# earllnRainOrNot

가까운 교통 CCTV 영상 속 **비를 맞는 자리에 있는 보행자 중 우산을 쓴 사람의 비율**로 지금 비가 오는지 알려주는 Android 앱입니다.
(기존 `NalCCTV` 앱을 재구성·개선한 버전)

## 주요 기능
- 현재 위치 주변 CCTV 검색: 서울시 TOPIS(실시간) + 국가교통정보센터 ITS(국도)
- 앱 실행 시 자동 분석 (즐겨찾기 → 교차로·영상 CCTV 우선), 목록에서 수동 분석
- 결과 카드 + 한국어 음성 안내(TTS), CCTV 영상 보기, 지도(osmdroid), 즐겨찾기(최대 5개)

## 우산 인식 개선 내용 (v2.0)
| 기존 NalCCTV | 개선 |
|---|---|
| 정지 프레임 1장만 분석 → 움직이는 사람 구분 불가 | 영상에서 약 0.8초 간격 **프레임 3장** 확보 (정지영상은 재요청) |
| 전체 화면만 전송 → 작은 보행자의 우산 놓침 | 앱이 **프레임 차분으로 움직임 영역**을 찾아 해당 부분을 **확대(최대 4배)** 해 함께 전송, 움직임이 없으면 2×2 확대 타일 전송 |
| "총 몇 명, 우산 몇 명" 숫자만 요청 | 보행자를 **한 명씩 위치·이동 여부·우산 상태(펼침/접음/없음/판단불가)·확신도**로 나열하게 하고(JSON 스키마 강제) 앱이 직접 집계 |
| — | 확대본과 전체 화면에서 **중복 기록된 사람 제거**(IoU), 확신도 낮은 항목·판단불가 인원 제외 |
| — | **지붕 아래(정류장·처마·실내) 사람은 제외**하고 걷는 사람과 신호 대기자를 모두 집계(설정에서 끌 수 있음). 여러 프레임에서 전혀 움직이지 않는 저확신 형상(광고판·마네킹)은 제외 |
| 우산 = 비 | 노면 젖음·빗줄기·물보라·렌즈 물방울 + 우산 비율을 점수로 종합, **해가 떠 있으면(햇빛 그림자 + 마른 노면) 우산 판단 없이 "날씨가 화창합니다"로 안내** |
| `gemini-2.0-flash` (2026-06-01 서비스 종료) | 기본 `gemini-3.5-flash`, 사용 불가 시 `gemini-flash-latest` → `gemini-2.5-flash` 자동 대체 |

결과 카드에 "전체 보행자 / 지붕 아래·판단불가 제외 / 이동 중 / 근거 / 분석 프레임 수"가 함께 표시됩니다.

## 공개 배포 (홈페이지 다운로드)
- 최신 APK 고정 링크: https://github.com/earlln/earllnRainOrNot/releases/latest/download/earllnRainOrNot.apk
- `v` 로 시작하는 태그(예: `v2.0.0`)를 푸시하면 **Release APK** 워크플로가 서명된 APK 를 만들어 GitHub Release 에 첨부합니다.
- 공개용 release APK 에는 **Gemini API 키가 들어가지 않습니다.** 사용자가 앱의 **설정 > API 키** 에 직접 입력합니다
  (Google AI Studio 무료 키). 첫 실행 시 안내 창이 뜹니다.
- 릴리스에 필요한 Secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`
  (선택 `ITS_API_KEY`). 키스토어를 잃어버리면 이후 버전을 기존 앱 위에 업데이트할 수 없으니 반드시 백업하세요.

## 빌드 방법

### GitHub Actions (권장)
1. 저장소 **Settings → Secrets and variables → Actions → New repository secret** 에 등록
   - `GEMINI_API_KEY` : Google AI Studio 에서 발급한 Gemini API 키 (개인용 debug APK 에만 들어감, 앱 설정에서 입력해도 됨)
   - `ITS_API_KEY` : 국가교통정보센터 OpenAPI 키 (없으면 TOPIS 서울 CCTV만 사용)
   - (선택) Variables 에 `GEMINI_MODEL` — 기본값 `gemini-3.5-flash`
2. 푸시하면 **Actions → Android Build** 가 실행되고, 완료된 실행의 **Artifacts → `earllnRainOrNot-debug-apk`** 에서 APK를 받습니다.

### 로컬
`local.properties` 에 아래를 추가한 뒤 `./gradlew assembleDebug` (이 파일은 git 에 올라가지 않습니다)
```
GEMINI_API_KEY=...
ITS_API_KEY=...
```

## 보안 주의
- API 키는 소스에 넣지 않습니다. 단, 빌드된 APK 안에는 키가 들어가므로 APK 를 공개 배포하지 마세요.
- 기존 NalCCTV APK 에 키가 하드코딩되어 있었으므로 **기존 Gemini/ITS 키는 재발급(폐기)** 을 권장합니다.
- Google Cloud 콘솔에서 Gemini 키에 Android 앱 제한(패키지명 `com.earlln.rainornot`)을 걸어두면 안전합니다.

## 비용/한도 참고
한 번 분석할 때 이미지 2~6장(전체 화면 + 확대본)을 보내므로 기존보다 토큰을 더 사용합니다.
무료 등급 분당 호출 제한을 고려해 호출 간격 6초, 같은 CCTV 결과는 5분간 캐시합니다.
