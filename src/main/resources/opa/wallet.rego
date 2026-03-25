# ============================================================
# C-CHAIN Wallet Policy (OPA Rego)
# 파일 위치: src/main/resources/opa/wallet.rego
#
# OPA 서버에 로드하면 /v1/data/wallet/allow 엔드포인트로 평가 가능
# ============================================================
package wallet

import future.keywords.if
import future.keywords.in

# ---- 기본값: Fail-Close (명시적으로 허용하지 않으면 거부) ----
default allow = false
default deny_reason = "정책 미정의"

# ============================================================
# 메인 허용 규칙: 아래 모든 조건이 true 여야 허용
# ============================================================
allow if {
    not is_blacklisted_address
    not exceeds_single_limit
    not exceeds_daily_limit
    has_valid_role
    is_valid_address
}

# ---- 거부 사유 (디버깅 및 감사 로그용) ----
deny_reason := "블랙리스트 주소" if is_blacklisted_address
deny_reason := "1회 전송 한도 초과" if exceeds_single_limit
deny_reason := "일일 전송 한도 초과" if exceeds_daily_limit
deny_reason := "역할 권한 없음" if not has_valid_role
deny_reason := "유효하지 않은 주소" if not is_valid_address

# ============================================================
# 개별 정책 규칙
# ============================================================

# RULE-01: 1회 최대 전송량 (OMT 기준)
MAX_SINGLE_TRANSFER := 10000

exceeds_single_limit if {
    input.operation_type in {"TRANSFER", "REWARD"}
    input.amount > MAX_SINGLE_TRANSFER
}

# RULE-02: 일일 누적 전송량
MAX_DAILY_TRANSFER := 100000

exceeds_daily_limit if {
    input.operation_type in {"TRANSFER", "REWARD"}
    (input.daily_total + input.amount) > MAX_DAILY_TRANSFER
}

# RULE-03: 블랙리스트 주소
BLACKLIST := {
    "0x0000000000000000000000000000000000000000"
}

is_blacklisted_address if {
    lower(input.to_address) in BLACKLIST
}

# RULE-04: 역할 기반 접근
# MASTER: 전송 가능
# REWARD: 보상 지급만 가능
# READ_ONLY: 잔액 조회만 가능
has_valid_role if {
    input.caller_role == "MASTER"
}

has_valid_role if {
    input.caller_role == "REWARD"
    input.operation_type == "REWARD"
}

has_valid_role if {
    input.caller_role in {"MASTER", "REWARD", "READ_ONLY"}
    input.operation_type == "BALANCE_CHECK"
}

# RULE-05: 주소 형식 (0x + 40 hex)
is_valid_address if {
    regex.match(`^0x[0-9a-fA-F]{40}$`, input.to_address)
}

# ---- BALANCE_CHECK는 주소 검증 불필요 ----
is_valid_address if {
    input.operation_type == "BALANCE_CHECK"
}
