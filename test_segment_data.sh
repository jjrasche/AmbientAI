#!/bin/bash
# Check if segments have embeddings

echo "=== Checking segment embedding status ==="
adb logcat -c

# Trigger a search to see detailed logs
curl -s -X POST http://localhost:8080/api/lyrics/search \
  -H "Content-Type: application/json" \
  -d '{"query":"test","max_results":1}' > /dev/null

sleep 2

echo ""
echo "=== Recent logs ==="
adb logcat -s LyricsSemanticSearch:* TranscriptSegmentRepository:* -d | tail -30

echo ""
echo "=== Enrichment status ==="
curl -s http://localhost:8080/api/enrichment/status | python -c "import sys, json; data = json.load(sys.stdin); print(f\"Enriched: {data['enriched_count']}/{data['total_media']}\")"
