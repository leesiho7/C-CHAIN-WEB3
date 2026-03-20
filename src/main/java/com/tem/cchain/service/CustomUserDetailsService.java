package com.tem.cchain.service;

import com.tem.cchain.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Spring Security UserDetailsService 구현체.
 *
 * - username = email (Member의 @Id)
 * - password = userpw (평문 저장, NoOpPasswordEncoder 사용)
 * - SecurityConfig의 SecurityFilterChain이 이 빈에 의존하므로
 *   MemberRepository → mysqlEntityManagerFactory → dataSource 순으로 초기화됨.
 *   MySqlDataSourceConfig의 @Lazy(false) 덕분에 DB 빈이 먼저 준비됨.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    public CustomUserDetailsService(@Lazy MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return memberRepository.findById(email)
                .map(member -> User.withUsername(member.getEmail())
                        .password(member.getUserpw())
                        .roles("USER")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + email));
    }
}
