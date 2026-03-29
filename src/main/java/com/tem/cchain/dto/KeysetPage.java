package com.tem.cchain.dto;

import java.util.List;

/**
 * Keyset(커서) 페이지네이션 응답 래퍼.
 *
 * @param data       현재 페이지 데이터
 * @param hasMore    다음 페이지 존재 여부
 * @param nextCursor 다음 페이지 조회 시 before= 에 넘길 커서 (마지막 행의 id). hasMore=false 면 null.
 */
public record KeysetPage<T>(
        List<T> data,
        boolean hasMore,
        Long nextCursor
) {}
