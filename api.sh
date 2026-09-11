#!/usr/bin/env bash
#
# Ad-hoc API client. apitest.sh asserts; this one lets you drive the API by
# hand - for exploring, for demoing, and for answering "show me" live.
#
#   export BASE=http://localhost:8000          # or the deployed URL
#   ./api.sh wallet alice                      # get-or-create alice's wallet
#   ./api.sh wallet bob
#   ./api.sh transfer alice <from> <to> 50000 key-1
#   ./api.sh transfer alice <from> <to> 50000 key-1     # same key -> replay
#   ./api.sh balance <wallet-id>
#   ./api.sh invariants
#   ./api.sh watch                             # live log stream
#   ./api.sh demo                              # scripted tour of every endpoint
#
set -uo pipefail
BASE="${BASE:-http://localhost:8080}"
BOLD=$'\033[1m'; DIM=$'\033[2m'; CYAN=$'\033[36m'; GREEN=$'\033[32m'; RESET=$'\033[0m'

pretty() { python3 -m json.tool 2>/dev/null || cat; }

# Prints the status line and headers we care about, then the pretty body.
call() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  local args=(-sS -X "$method" "$BASE$path" -D /tmp/.api_hdr.$$ -H 'Content-Type: application/json')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body"  ]] && args+=(-d "$body")

  echo "${CYAN}${method} ${path}${RESET}"
  [[ -n "$body" ]] && echo "${DIM}  -> $body${RESET}"
  local out; out=$(curl "${args[@]}")
  local code; code=$(head -1 /tmp/.api_hdr.$$ | awk '{print $2}')
  local interesting
  interesting=$(grep -iE '^(x-idempotent-replay|x-wallet-created|x-instance-id|x-correlation-id|retry-after):' \
                 /tmp/.api_hdr.$$ | tr -d '\r' | sed 's/^/  /')
  echo "${BOLD}  <- $code${RESET}"
  [[ -n "$interesting" ]] && echo "${DIM}$interesting${RESET}"
  echo "$out" | pretty | sed 's/^/  /'
  rm -f /tmp/.api_hdr.$$
  echo
}

# Extracts one field from the last JSON body - handy for scripting.
field() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1',''))" 2>/dev/null; }

cmd="${1:-help}"; shift || true
case "$cmd" in
  wallet)     call POST "/wallets" "${1:?usage: api.sh wallet <token>}" ;;
  balance)    call GET  "/wallets/${1:?usage: api.sh balance <wallet-id> [token]}" "${2:-viewer}" ;;
  transfer)
      t="${1:?usage: api.sh transfer <token> <from> <to> <paise> <key>}"
      call POST "/transfers" "$t" \
        "{\"from\":\"${2:?}\",\"to\":\"${3:?}\",\"amount_paise\":${4:?},\"idempotency_key\":\"${5:?}\"}" ;;
  status)     call GET  "/transfers/${1:?usage: api.sh status <transfer-id> [token]}" "${2:-viewer}" ;;
  invariants) call GET  "/admin/invariants" ;;
  health)     call GET  "/health" ;;
  info)       call GET  "/admin/status" ;;
  logs)       curl -sS "$BASE/debug/logs?n=${1:-40}" | sed 's/^/  /' ;;
  watch)      echo "${BOLD}streaming $BASE/debug/logs/stream  (Ctrl-C to stop)${RESET}"
              curl -N -sS "$BASE/debug/logs/stream" ;;
  metrics)    curl -sS "$BASE/metrics" | grep -E "${1:-^wallet_}" | grep -v '^#' ;;

  demo)
      echo "${BOLD}=== A scripted tour of every endpoint against $BASE ===${RESET}"; echo
      A="alice-$RANDOM"; B="bob-$RANDOM"
      echo "${GREEN}1. get-or-create two wallets${RESET}"
      AW=$(curl -sS -X POST "$BASE/wallets" -H "Authorization: Bearer $A" | field wallet_id)
      BW=$(curl -sS -X POST "$BASE/wallets" -H "Authorization: Bearer $B" | field wallet_id)
      echo "   alice -> $AW"; echo "   bob   -> $BW"; echo
      echo "${GREEN}2. the same call again is idempotent (X-Wallet-Created: false)${RESET}"
      call POST "/wallets" "$A"
      echo "${GREEN}3. a transfer${RESET}"
      K="demo-$RANDOM"
      call POST "/transfers" "$A" "{\"from\":\"$AW\",\"to\":\"$BW\",\"amount_paise\":50000,\"idempotency_key\":\"$K\"}"
      echo "${GREEN}4. the same key again - one debit, identical body, replay header${RESET}"
      call POST "/transfers" "$A" "{\"from\":\"$AW\",\"to\":\"$BW\",\"amount_paise\":50000,\"idempotency_key\":\"$K\"}"
      echo "${GREEN}5. the same key with a DIFFERENT body - 409, and no second debit${RESET}"
      call POST "/transfers" "$A" "{\"from\":\"$AW\",\"to\":\"$BW\",\"amount_paise\":99999,\"idempotency_key\":\"$K\"}"
      echo "${GREEN}6. overdraft - declined cleanly, nothing partially applied${RESET}"
      call POST "/transfers" "$A" "{\"from\":\"$AW\",\"to\":\"$BW\",\"amount_paise\":999999999,\"idempotency_key\":\"od-$RANDOM\"}"
      echo "${GREEN}7. money hygiene - 12.5 paise is refused, not truncated${RESET}"
      call POST "/transfers" "$A" "{\"from\":\"$AW\",\"to\":\"$BW\",\"amount_paise\":12.5,\"idempotency_key\":\"f-$RANDOM\"}"
      echo "${GREEN}8. you may not debit a wallet you do not own${RESET}"
      call POST "/transfers" "$B" "{\"from\":\"$AW\",\"to\":\"$BW\",\"amount_paise\":1,\"idempotency_key\":\"x-$RANDOM\"}"
      echo "${GREEN}9. balances${RESET}"
      call GET "/wallets/$AW" "$A"; call GET "/wallets/$BW" "$B"
      echo "${GREEN}10. and the invariants still hold${RESET}"
      call GET "/admin/invariants"
      ;;

  *) sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
