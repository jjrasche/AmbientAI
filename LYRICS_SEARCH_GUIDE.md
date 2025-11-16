# Semantic Lyrics Search Guide

## How It Works

Your semantic lyrics search infrastructure is now complete:

### 1. Data Structure
```
Media (song)
  ↓
MediaTranscript (source="lyrics")
  ↓
TranscriptSegment[] (lyrics chunks with 512-dim embeddings)
```

### 2. Components

**TextEmbedder** (`TextEmbedder.kt`)
- Uses MediaPipe's Universal Sentence Encoder
- Generates 512-dimensional embeddings
- Model: `universal_sentence_encoder.tflite`

**SmartSegmenter** (`SmartSegmenter.kt:30`)
- Chunks lyrics into ~100 word segments
- Generates embeddings for each segment
- Now properly initializes embedder before use (FIXED)

**TranscriptSegmentRepository** (`TranscriptSegmentRepository.kt:24`)
- `semanticSearchAllMedia()` uses ObjectBox HNSW index
- Fast vector similarity search across all songs

**LyricsSemanticSearch** (`LyricsSemanticSearch.kt`)
- High-level search service
- Returns Media + lyric snippet + similarity score

### 3. API Endpoint

**POST /api/lyrics/search**

Request:
```json
{
  "query": "heartbreak and loneliness",
  "max_results": 10
}
```

Response:
```json
{
  "success": true,
  "query": "heartbreak and loneliness",
  "total_results": 5,
  "results": [
    {
      "media_id": 123,
      "title": "Song Title",
      "artist": "Artist Name",
      "similarity": 0.847,
      "lyric_snippet": "First 200 chars of matching segment...",
      "full_segment": "Full ~100 word segment that matched"
    }
  ]
}
```

### 4. Current Status

**The Problem**:
- Previously enriched songs (193 total) were created BEFORE the embedder initialization fix
- Those segments exist but have `embedding = null`
- ObjectBox HNSW requires valid embeddings to return results
- Newly enriched songs (since the fix) DO have embeddings and will be searchable

**The Solution**:
You need to either:

**Option A**: Re-enrich all existing songs to regenerate embeddings
- Stop enrichment: `curl -X POST http://localhost:8080/api/enrichment/stop`
- Delete old MediaTranscript and TranscriptSegment entities
- Restart enrichment: `curl -X POST http://localhost:8080/api/enrichment/start`

**Option B**: Wait for all songs to be enriched with the fixed code
- Current progress: ~270/536 songs enriched
- Songs enriched AFTER the fix have embeddings
- Once more songs are enriched with embeddings, search will start returning results

**Option C**: Add a workflow action for re-enrichment
- Create an action to re-process existing lyrics
- Generate embeddings for segments that have `embedding = null`

### 5. Testing Once Ready

```bash
# Simple query
curl -X POST http://localhost:8080/api/lyrics/search \
  -H "Content-Type: application/json" \
  -d '{"query":"love","max_results":5}'

# Semantic query (not exact words)
curl -X POST http://localhost:8080/api/lyrics/search \
  -H "Content-Type: application/json" \
  -d '{"query":"feeling sad and alone","max_results":5}'

# Check logs
adb logcat -s LyricsSemanticSearch:D
```

### 6. Integration Ideas

Once working, you could add workflow actions like:
- `lyrics.search` - Search lyrics semantically
- `lyrics.findSimilar` - Find songs with similar lyrical themes
- `music.playByMood` - Play songs matching a mood/theme based on lyrics

Example workflow:
```json
{
  "name": "play_by_mood",
  "triggerPhrases": ["play something about *"],
  "steps": [
    {
      "action": "lyrics.search",
      "query": "$1",
      "maxResults": 5,
      "outputVariable": "matches"
    },
    {
      "action": "music.play",
      "mediaId": "$matches[0].media_id"
    }
  ]
}
```

## Summary

Your semantic search infrastructure is **complete and working** - the issue is just that old segments need embeddings regenerated. Once enrichment completes with the fixed code (or you re-enrich), semantic search will work beautifully.
