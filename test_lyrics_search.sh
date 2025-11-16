#!/bin/bash
# Test lyrics search infrastructure

echo "=== Enrichment Status ==="
curl -s http://localhost:8080/api/enrichment/status | python -c "import sys, json; data = json.load(sys.stdin); print(f\"Enriched: {data['enriched_count']}/{data['total_media']}\"); print(f\"Running: {data['is_running']}\")"

echo ""
echo "=== Testing lyrics search ==="
echo "Query: 'love and heartbreak'"
curl -s -X POST http://localhost:8080/api/lyrics/search \
  -H "Content-Type: application/json" \
  -d '{"query":"love and heartbreak","max_results":5}' | python -m json.tool

echo ""
echo "Query: 'happiness'"
curl -s -X POST http://localhost:8080/api/lyrics/search \
  -H "Content-Type: application/json" \
  -d '{"query":"happiness","max_results":3}' | python -m json.tool

echo ""
echo "=== Check logs for details ==="
echo "adb logcat -s LyricsSemanticSearch:D SmartSegmenter:D -d"
