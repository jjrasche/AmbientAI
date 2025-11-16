#!/bin/bash

echo "====== TESTING 'play believer' END-TO-END ======"
echo

echo "Step 1: Checking MusicPlayerService.play() for query cleaning..."
grep -A 3 "val rawQuery" app/src/main/java/com/ambientai/core/music/MusicPlayerService.kt | head -5
echo

echo "Step 2: Trigger workflow via voice (say 'play believer' now)..."
echo "Waiting 15 seconds for you to speak..."
sleep 15

echo
echo "Step 3: Checking workflow execution logs..."
adb logcat -d -s WorkflowExecutor:D | tail -10

echo
echo "Step 4: What should have happened:"
echo "  Input: {\"query\":\"play believer\"}"
echo "  After cleaning: {\"query\":\"believer\"}"
echo "  Search result: NO MATCH (not in library)"
echo "  YouTube fallback: Searches for 'believer'"
echo "  Downloads and plays 'Believer' by Imagine Dragons"
echo

echo "Step 5: If it didn't work, check:"
echo "  - Was query cleaned? (should show 'believer' not 'play believer')"
echo "  - Did music.play return success=false?"
echo "  - Did YouTube search trigger?"
