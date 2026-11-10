package com.edrdog.apiservice.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    // 아래 UPDATE 들은 읽고 쓰는 대신 DB 에서 한 번에 처리한다. 동시에 들어온 로그인 시도가 횟수를 덮어쓰면 잠금을 피해 갈 수 있다.

    /** 실패 횟수를 1 올린다. */
    @Modifying
    @Transactional
    @Query("update AppUser u set u.failedLogins = u.failedLogins + 1 where u.id = :id")
    void addFailure(@Param("id") Long id);

    /** 실패 횟수가 max 에 닿았으면 잠그고 횟수를 0 으로 돌린다. 잠근 쪽(1)만 통보하도록 영향 행 수를 돌려준다. */
    @Modifying
    @Transactional
    @Query("update AppUser u set u.failedLogins = 0, u.lockedUntil = :until where u.id = :id and u.failedLogins >= :max")
    int lockIfReached(@Param("id") Long id, @Param("max") int max, @Param("until") Instant until);

    /** 로그인에 성공하면 실패 횟수와 잠금을 지운다. */
    @Modifying
    @Transactional
    @Query("update AppUser u set u.failedLogins = 0, u.lockedUntil = null where u.id = :id")
    void resetFailures(@Param("id") Long id);

    /** 패스워드를 바꾸고 변경 필요 표시를 지운다. */
    @Modifying
    @Transactional
    @Query("update AppUser u set u.passwordHash = :hash, u.mustChangePassword = false where u.id = :id")
    void updatePassword(@Param("id") Long id, @Param("hash") String hash);
}
