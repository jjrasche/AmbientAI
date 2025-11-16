#!/bin/bash
# Test Lyrics Enrichment System

echo "=== Background Lyrics Enrichment Test Suite ==="
echo ""

# Test 1: Check enrichment status (should be stopped initially)
echo "Test 1: Check initial enrichment status"
curl -s http://localhost:8080/api/enrichment/status | python -m json.tool
echo ""
echo "---"
echo ""

# Test 2: Start enrichment
echo "Test 2: Start background enrichment"
curl -s -X POST http://localhost:8080/api/enrichment/start | python -m json.tool
echo ""
echo "---"
echo ""

# Test 3: Wait a bit and check status again
echo "Test 3: Wait 5 seconds, then check status (should show progress)"
sleep 5
curl -s http://localhost:8080/api/enrichment/status | python -m json.tool
echo ""
echo "---"
echo ""

# Test 4: Check logcat for enrichment activity
echo "Test 4: Checking logcat for enrichment logs..."
echo "(Run in separate terminal: adb logcat MediaEnrichment:D GeniusApiService:D SmartSegmenter:D *:S)"
echo ""
echo "---"
echo ""

# Test 5: Stop enrichment
echo "Test 5: Stop enrichment"
curl -s -X POST http://localhost:8080/api/enrichment/stop | python -m json.tool
echo ""
echo "---"
echo ""

# Test 6: Final status check
echo "Test 6: Final status (should show stopped)"
curl -s http://localhost:8080/api/enrichment/status | python -m json.tool
echo ""
echo "---"
echo ""

echo "=== All Tests Complete ==="
echo ""
echo "Next steps:"
echo "1. Download a few songs: voice command 'search for meditation music'"
echo "2. Start enrichment: curl -X POST http://localhost:8080/api/enrichment/start"
echo "3. Monitor progress: curl http://localhost:8080/api/enrichment/status"
echo "4. Test semantic search: curl -X POST http://localhost:8080/api/media/search_library -d '{\"query\": \"heartbreak and moving on\"}'"
