#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
B="https://localhost"

line() { echo; echo "=== $* ==="; }

line "seeded users + rating counts"
docker exec aniga-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "SELECT u.id, u.username, count(r.user_id) AS ratings FROM app_user u LEFT JOIN rating r ON r.user_id = u.id GROUP BY u.id, u.username ORDER BY u.id;"

line "signup probe (is Firebase Email/Password enabled?)"
curl -sk -X POST "$B/api/auth/signup" -H 'Content-Type: application/json' \
  --data '{"email":"probe-user@example.com","password":"probe123"}'; echo

line "mint a session for user 1 and call the locked routes"
TOK=$(python3 mint-session.py 1)
echo "token: ${TOK:0:24}..."
AUTH="Authorization: Bearer $TOK"

echo "- /api/auth/me"
curl -sk "$B/api/auth/me" -H "$AUTH"; echo
echo "- /api/people/matches (top 3)"
curl -sk "$B/api/people/matches" -H "$AUTH" | head -c 1200; echo
echo "- /api/people/personality"
curl -sk "$B/api/people/personality" -H "$AUTH"; echo
echo "- /api/me/recommendations?limit=3"
curl -sk "$B/api/me/recommendations?limit=3" -H "$AUTH" | head -c 500; echo

line "friend request flow: user 1 -> user 2, then user 2 accepts"
curl -sk -X POST "$B/api/people/requests" -H "$AUTH" -H 'Content-Type: application/json' --data '{"username":"mina"}'; echo
TOK2=$(python3 mint-session.py 2)
AUTH2="Authorization: Bearer $TOK2"
echo "- user 2 incoming requests"
REQ=$(curl -sk "$B/api/people/requests" -H "$AUTH2"); echo "$REQ"
RID=$(echo "$REQ" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
echo "- user 2 accepts request $RID"
curl -sk -X POST "$B/api/people/requests/$RID/accept" -H "$AUTH2"; echo
echo "- user 1 friends"
curl -sk "$B/api/people/friends" -H "$AUTH"; echo
echo "- user 1 views user 2 shelf (first 400 chars)"
curl -sk "$B/api/people/2/shelf" -H "$AUTH" | head -c 400; echo

line "global chat: user 1 opens, posts, reads"
ROOM=$(curl -sk "$B/api/chat/global" -H "$AUTH")
echo "room: $ROOM"
GID=$(echo "$ROOM" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
curl -sk -X POST "$B/api/chat/rooms/$GID/messages" -H "$AUTH" -H 'Content-Type: application/json' --data '{"body":"hello Aniga @mina"}'; echo
echo "- user 2 reads global"
curl -sk "$B/api/chat/global" -H "$AUTH2" > /dev/null
curl -sk "$B/api/chat/rooms/$GID/messages" -H "$AUTH2" | head -c 400; echo

line "security: path-user mismatch (user 1 token hitting /api/users/2/library -> expect 403)"
curl -sk -o /dev/null -w '%{http_code}\n' "$B/api/users/2/library" -H "$AUTH"
line "security: no token on /api/me/recommendations -> expect 401"
curl -sk -o /dev/null -w '%{http_code}\n' "$B/api/me/recommendations"
line "security: admin route POST /api/users without admin token -> expect 403"
curl -sk -o /dev/null -w '%{http_code}\n' -X POST "$B/api/users" -H "$AUTH" -H 'Content-Type: application/json' --data '{"username":"nope"}'
