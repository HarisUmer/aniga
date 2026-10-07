#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
B="https://localhost"

MID=$(docker exec aniga-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -c \
  "SELECT media_id FROM rating WHERE user_id = 1 AND score >= 8 ORDER BY media_id LIMIT 1;")
echo "kenji's high-rated media id: $MID"

TOK2=$(python3 mint-session.py 2)
echo "mina rates media $MID a 9:"
curl -sk -X PUT "$B/api/users/2/ratings/$MID" -H "Authorization: Bearer $TOK2" \
  -H 'Content-Type: application/json' --data '{"score":9}'; echo

TOK1=$(python3 mint-session.py 1)
echo "kenji matches now:"
curl -sk "$B/api/people/matches" -H "Authorization: Bearer $TOK1"; echo
