#!/usr/bin/env bash
# id: A03
# attack: T1555.001
# atomic: Atomic Red Team T1555.001 #4 (Copy Keychain using cat utility)
# expect: detected
# rule: NONE
# type: process
# probe: login.keychain-db
# 로그인 키체인 파일(암호화된 DB)을 임시 폴더로 복사한다. 사본은 소유자만 읽게 만들고 cleanup 에서 지운다.
# 번들 룰 중 명령행이 맞는 것이 없다(MACOS_KEYCHAIN_DUMP 는 ' login-keychain ' 이라 'login.keychain-db' 와 다르다). 미탐이 예상이다.
set -euo pipefail
case "${1:?run|cleanup}" in
  run)
    # 사본 권한을 600 으로 만든다
    umask 077
    cat ~/Library/Keychains/login.keychain-db > "$WORK/keychain" 2>/dev/null || true
    ;;
  cleanup)
    rm -f "$WORK/keychain"
    ;;
esac
