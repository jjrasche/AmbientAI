#!/bin/bash

echo "====== END-TO-END TEST: 'play believer' ======"
echo
echo "This test validates:"
echo "  1. Query cleaning strips 'play' trigger word"
echo "  2. Local library search with cleaned query 'believer'"
echo "  3. YouTube fallback when not found locally"
echo "  4. Download and playback of YouTube result"
echo

# Simulate STT utterance end
echo "Step 1: Simulating voice input 'play believer'..."
response=$(curl -s -X POST http://localhost:8080/api/test/utterance_end \
  -H "Content-Type: application/json" \
  -d '{"text": "play believer", "elapsed_ms": 4500}')

echo "$response" | python -m json.tool
echo

# Check routing logs
echo "Step 2: Checking WorkflowRouter logs for query cleaning..."
adb logcat -d -s WorkflowRouter:D | grep -A 2 "QUERY CLEANING" | tail -5
echo

# Check workflow execution logs
echo "Step 3: Checking WorkflowExecutor logs..."
adb logcat -d -s WorkflowExecutor:D | grep -A 10 "EXECUTING WORKFLOW: play_music" | tail -15
echo

# Check music.play action input
echo "Step 4: Checking music.play input (should show cleaned query)..."
adb logcat -d -s WorkflowExecutor:D | grep "music.play" -A 2 | tail -10
echo

# Check YouTube fallback trigger
echo "Step 5: Checking if YouTube fallback triggered..."
adb logcat -d -s WorkflowExecutor:D | grep "media.searchAndSelect" -A 2 | tail -10
echo

echo "====== EXPECTED BEHAVIOR ======"
echo "✓ WorkflowRouter should log: 'play believer' → 'believer'"
echo "✓ music.play should receive {\"query\":\"believer\"} NOT {\"query\":\"play believer\"}"
echo "✓ music.play should return success=false (not in library)"
echo "✓ media.searchAndSelect should trigger with query='believer'"
echo "✓ media.download should download YouTube result"
echo "✓ Second music.play should play downloaded song"
echo

echo "====== TROUBLESHOOTING ======"
echo "If query cleaning didn't work:"
echo "  - Check WorkflowRouter logs for 'QUERY CLEANING' message"
echo "  - Verify music.play input shows cleaned query"
echo
echo "If YouTube fallback didn't trigger:"
echo "  - music.play might have succeeded (wrong song in library)"
echo "  - Check if fuzzy matching found a false positive"
echo
