#!/bin/bash
# Test LLM Intent Analysis for Play Workflow

echo "=== Testing LLM Intent Analysis ==="
echo ""

# Test 1: Artist match
echo "Test 1: play Noah Kahan"
curl -s -X POST http://localhost:8080/api/test/llm/intent \
  -H "Content-Type: application/json" \
  -d '{"transcript": "play Noah Kahan"}' | python -m json.tool
echo ""
echo "---"
echo ""

# Test 2: Newest album (recency check)
echo "Test 2: play Taylor Swift's newest album"
curl -s -X POST http://localhost:8080/api/test/llm/intent \
  -H "Content-Type: application/json" \
  -d '{"transcript": "play Taylor Swift'"'"'s newest album"}' | python -m json.tool
echo ""
echo "---"
echo ""

# Test 3: Specific album
echo "Test 3: play Stick Season album"
curl -s -X POST http://localhost:8080/api/test/llm/intent \
  -H "Content-Type: application/json" \
  -d '{"transcript": "play Stick Season album"}' | python -m json.tool
echo ""
echo "---"
echo ""

# Test 4: Specific song
echo "Test 4: play Stick Season song"
curl -s -X POST http://localhost:8080/api/test/llm/intent \
  -H "Content-Type: application/json" \
  -d '{"transcript": "play Stick Season song"}' | python -m json.tool
echo ""
echo "---"
echo ""

# Test 5: Educational content
echo "Test 5: play something that will teach me about how LLMs work at a statistical level"
curl -s -X POST http://localhost:8080/api/test/llm/intent \
  -H "Content-Type: application/json" \
  -d '{"transcript": "play something that will teach me about how LLMs work at a statistical level"}' | python -m json.tool
echo ""
echo "---"
echo ""

# Test 6: Specific memory
echo "Test 6: play that video I heard last week that involved how tomatoes should be picked"
curl -s -X POST http://localhost:8080/api/test/llm/intent \
  -H "Content-Type: application/json" \
  -d '{"transcript": "play that video I heard last week that involved how tomatoes should be picked"}' | python -m json.tool
echo ""
echo "---"
echo ""

# Test 7: Podcast with playback history
echo "Test 7: play an episode of Matt Shane's Secret Podcast that I haven't heard yet"
curl -s -X POST http://localhost:8080/api/test/llm/intent \
  -H "Content-Type: application/json" \
  -d '{"transcript": "play an episode of Matt Shane'"'"'s Secret Podcast that I haven'"'"'t heard yet"}' | python -m json.tool
echo ""
echo "---"
echo ""

# Test 8: NOT media (idiom)
echo "Test 8: play it safe"
curl -s -X POST http://localhost:8080/api/test/llm/intent \
  -H "Content-Type: application/json" \
  -d '{"transcript": "play it safe"}' | python -m json.tool
echo ""
echo "---"
echo ""

# Test 9: NOT media (activity)
echo "Test 9: I need to play with my kids later"
curl -s -X POST http://localhost:8080/api/test/llm/intent \
  -H "Content-Type: application/json" \
  -d '{"transcript": "I need to play with my kids later"}' | python -m json.tool
echo ""
echo "---"
echo ""

# Test 10: Vague mood request
echo "Test 10: play some chill background music"
curl -s -X POST http://localhost:8080/api/test/llm/intent \
  -H "Content-Type: application/json" \
  -d '{"transcript": "play some chill background music"}' | python -m json.tool
echo ""
echo "---"
echo ""

echo "=== All Tests Complete ==="
