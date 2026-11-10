package com.edrdog.apiservice.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 키는 토큰의 해시다({@link UserSession}). */
public interface SessionRepository extends JpaRepository<UserSession, String> {

    // save 로 갱신하면 그사이 로그아웃으로 지워진 세션이 병합으로 되살아난다. UPDATE 는 없는 행을 만들지 않는다.
    @Modifying
    @Transactional
    @Query("update UserSession s set s.lastUsedAt = :now where s.tokenHash = :hash")
    void touch(@Param("hash") String hash, @Param("now") Instant now);

    /** 그 유저의 세션을 모두 끊는다(중복 접속 차단). 끊은 개수를 돌려준다. */
    @Modifying
    @Transactional
    @Query("delete from UserSession s where s.userId = :userId")
    int deleteAllOfUser(@Param("userId") Long userId);

    /** 패스워드를 바꾼 세션만 남기고 그 유저의 나머지 세션을 끊는다. */
    @Modifying
    @Transactional
    @Query("delete from UserSession s where s.userId = :userId and s.tokenHash <> :keep")
    int deleteOthersOfUser(@Param("userId") Long userId, @Param("keep") String keep);

    /** 해시 도입 전에 평문 토큰으로 저장된 행(64자가 아니다)을 지운다. 그대로 두면 DB 에 쓸 수 있는 토큰이 남는다. */
    @Modifying
    @Transactional
    @Query("delete from UserSession s where length(s.tokenHash) <> 64")
    int deleteLegacyPlaintext();
}
