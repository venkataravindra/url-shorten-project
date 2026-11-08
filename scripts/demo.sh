#!/usr/bin/env sh
# Short curl-only walkthrough (greenfield). NOT executed on the authoring machine; scripts/demo.ps1 is the verified one.
BASE="${1:-http://localhost:8080}"; ENG="engineer:engineer123"; APR="approver:approver123"
json() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -n1; }
ID=$(curl -s -u $ENG -H 'Content-Type: application/json' -X POST "$BASE/api/v1/workflows" \
  -d '{"requirement":"Build a URL shortener that supports custom aliases, expiration, redirects, and click analytics."}' | json id)
echo "workflow $ID"
curl -s -u $ENG -X POST "$BASE/api/v1/workflows/$ID/execute" | json status
curl -s -u $ENG "$BASE/api/v1/workflows/$ID/tasks"; echo
curl -s -u $APR -H 'Content-Type: application/json' -X POST "$BASE/api/v1/workflows/$ID/approve" -d '{"comment":"ok"}' | json status
curl -s -u $ENG "$BASE/api/v1/workflows/$ID/summary"; echo
curl -s -u $ENG "$BASE/api/v1/metrics/summary"; echo