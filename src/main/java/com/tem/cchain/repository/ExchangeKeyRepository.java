package com.tem.cchain.repository;

import com.tem.cchain.entity.ExchangeKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExchangeKeyRepository extends JpaRepository<ExchangeKey, Long> {

    Optional<ExchangeKey> findByMemberEmailAndExchange(String memberEmail, String exchange);

    boolean existsByMemberEmailAndExchange(String memberEmail, String exchange);

    void deleteByMemberEmailAndExchange(String memberEmail, String exchange);
}
