package com.edrdog.apiservice.access;

import com.edrdog.apiservice.auth.AppUser;
import com.edrdog.apiservice.auth.AuthException;
import com.edrdog.apiservice.auth.AuthValidation;
import com.edrdog.apiservice.auth.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** admin 이 같은 조직에 읽기 전용(viewer) 계정을 만든다. 가입은 항상 admin 이라 viewer 를 만드는 길은 여기뿐이다. */
@Service
public class UserAccountService {

    private final UserRepository users;
    private final BCryptPasswordEncoder encoder;

    public UserAccountService(UserRepository users, BCryptPasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public AppUser createViewer(Long tenantId, String email, String password) {
        if (!AuthValidation.validEmail(email)) {
            throw AuthException.invalidInput("이메일 형식이 올바르지 않습니다");
        }
        if (!AuthValidation.validPassword(password)) {
            throw AuthException.invalidInput("비밀번호가 규칙에 맞지 않습니다");
        }
        if (users.existsByEmail(email)) {
            throw AuthException.duplicate("이미 사용 중인 이메일입니다");
        }
        try {
            return users.save(AppUser.of(email, encoder.encode(password), tenantId, Role.VIEWER.value(), Instant.now()));
        } catch (DataIntegrityViolationException e) {
            // existsByEmail 통과 후 동시 요청이 unique 제약을 밟은 경우
            throw AuthException.duplicate("이미 사용 중인 이메일입니다");
        }
    }
}
