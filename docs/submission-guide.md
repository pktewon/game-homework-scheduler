# 기말 제출 안내 · 앱 0.8.0

## 교수자 확인 순서

1. [README](../README.md)에서 프로젝트의 문제인식과 최종 결과를 확인한다.
2. Android 휴대폰에서 [APK](https://github.com/pktewon/game-homework-scheduler/releases/download/v0.8.0/GameHomeworkScheduler-0.8.0-debug.apk)를 다운로드해 설치한다. 최소 Android 8.0이며 기존 설치에는 업데이트로 설치한다.
3. [최종 결과 보고서](final-report.md), [초기 설계와 현재 화면](screen-design.md), [DB와 실제 구현](android-implementation.md)을 확인한다.
4. [휴대폰 시험 결과](phone-test-checklist.md)의 사용자 보고 근거와 자동화 결과를 구분해 읽는다.
5. [시연 대본](demo-guide.md)의 순서로 기능을 확인한다. 명조·소전2 공식 수집과 앱 배경 갱신은 후속 범위다.

## 배포 파일

[GitHub Releases v0.8.0](https://github.com/pktewon/game-homework-scheduler/releases/tag/v0.8.0)에 다음 파일을 제공한다.

| 파일 | 용도 |
| --- | --- |
| GameHomeworkScheduler-0.8.0-debug.apk | 설치·시연용 debug 서명 APK. 기존 검증 파일 유지 |
| game-homework-scheduler-v0.8.0-source.zip | 최종 문서를 포함한 Android·수집기·테스트·설계 소스 |
| GameHomeworkScheduler-v0.8.0-submission.zip | APK·소스 ZIP·최종 보고서·시연 대본·이 안내·파일 명세를 한 번에 받는 제출 묶음 |
| SUBMISSION-SHA256SUMS.txt | APK·소스·제출 묶음의 SHA-256 비교값 |

제출 묶음 안의 submission-manifest.json에는 앱 버전·APK 기준 코드 커밋·최종 문서 커밋·검증 결과와 파일 무결성을 기록한다. 배포 태그 v0.8.0은 처음 검증한 앱 코드 커밋을 유지하며, 문서·결과 기록의 최신본은 소스 ZIP과 main에 포함한다.

학교 사이트가 요구하는 형식에 맞춰 APK·소스 ZIP·최종 보고서·저장소 URL을 제출한다. 보고서가 PDF/Word 형식이어야 한다면 학교 안내에 맞게 변환·확인한다. 기존 이름·학번 포함 중간고사 Word·PDF는 별도의 학교 제출용 로컬 사본을 사용한다. 공개 저장소·릴리스·ZIP에는 포함하지 않는다.

## 소스 실행·검사

Android Studio에서 소스 ZIP의 android 폴더를 연다. JDK 17, SDK Platform 35, Build Tools 36.0.0 환경으로 검증했다. 개인 SDK 경로는 각자의 환경에서 설정한다.

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

수집기 검사는 저장소 루트에서 실행한다.

```console
python -m unittest discover -s tests -v
```

## 제출 전 최종 확인

- [x] 최종 구현과 초기 설계의 차이, 미구현 확장 범위를 명시
- [x] Android 105개 성공 결과·Lint 오류 0건·APK 빌드 및 Python 27개 검사 확인
- [x] 휴대폰 기본 26개·추가 4개 사용자 정상 확인 기록
- [x] 최종 결과 보고서와 시연 대본 준비
- [x] 이름·학번 포함 학교 제출물과 공개 소스 분리
- [ ] 수업 요구 형식과 시연 녹화/발표 여부 최종 확인
- [ ] 학교 제출 사이트 업로드와 제출 완료 화면 확인

[README](../README.md) · [최종 보고서](final-report.md)
