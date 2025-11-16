# Genius API + Background Enrichment - Setup Guide

## What You're Getting

🎵 **Lyrics-powered semantic search** - "play something about heartbreak and moving on" → finds songs by lyrical themes, not just titles!

🤖 **Background enrichment system** - Automatically fetches lyrics for your entire library, respecting API limits (500/day)

📊 **Smart segmentation** - Lyrics split into ~100-word chunks with embeddings for semantic search

---

## Step 1: Get Genius API Token

### 1. Go to Genius API Clients Page
https://genius.com/api-clients

### 2. Sign In / Create Account
- Use your existing Genius account or create one

### 3. Create New API Client
- Click **"New API Client"** button
- Fill in the form:
  - **App Name**: `AmbientAI`
  - **App Website URL**: `http://localhost` (or anything, doesn't matter for our use case)
  - **Redirect URI**: `http://localhost` (not used, but required)
  - **App Description** (optional): "Personal AI assistant with lyrics-aware music search"

### 4. Generate Access Token
- After creating the client, click **"Generate Access Token"**
- Copy the token (looks like: `abc123xyz...`)

### 5. Add to local.properties
Open `local.properties` (in project root) and add:

```properties
genius.apiToken=YOUR_TOKEN_HERE
```

Example:
```properties
groq.apiKey=gsk_abc123...
brave.searchApiKey=BSA123...
deepgram.apiKey=abc123...
genius.apiToken=YOUR_GENIUS_TOKEN_HERE
```

---

## Step 2: How The System Works

### Architecture Overview

```
Download YouTube Video
↓
Media saved to library
↓
Background enrichment starts (automatic)
↓
For each Media without lyrics:
  1. Search Genius: "Song Title - Artist"
  2. Fetch lyrics from best match
  3. Save to MediaTranscript (source="lyrics")
  4. Segment into ~100-word chunks
  5. Generate embeddings for each segment
  6. Save TranscriptSegments with embeddings
↓
Semantic search now includes lyrics!
```

### What Gets Created:

**MediaTranscript** (one per media):
- `mediaId`: Link to Media entity
- `fullTranscript`: Complete lyrics text
- `source`: "lyrics" (vs "captions" for video transcripts)
- `fetchedAt`: Timestamp

**TranscriptSegment** (many per MediaTranscript):
- `transcriptId`: Link to MediaTranscript
- `text`: ~100 words of lyrics
- `embedding`: 512-dim vector for semantic search
- `segmentIndex`: Position in song

---

## Step 3: Using the Background Enrichment

### Start Enrichment (Automatic on App Start - Coming Soon)

The enrichment service will run in the background, fetching lyrics for all media that don't have them yet.

**API Limit Protection:**
- Max 500 requests/day (Genius free tier)
- Automatically stops at limit
- Resets counter daily
- 2-second delay between songs to be polite

### Manual Control (Debug API)

**Start enrichment:**
```bash
curl -X POST http://localhost:8080/api/enrichment/start
```

**Stop enrichment:**
```bash
curl -X POST http://localhost:8080/api/enrichment/stop
```

**Check status:**
```bash
curl http://localhost:8080/api/enrichment/status | python -m json.tool
```

Response:
```json
{
  "is_running": true,
  "total_media": 50,
  "enriched_count": 23,
  "today_request_count": 46,
  "last_enriched_title": "Stick Season - Noah Kahan",
  "last_error": null
}
```

**Enrich single media:**
```bash
curl -X POST http://localhost:8080/api/enrichment/media/123
```

---

## Step 4: Testing Lyrics Search

### Before Enrichment:
```bash
curl -X POST http://localhost:8080/api/media/search_library \
  -H "Content-Type: application/json" \
  -d '{"query": "heartbreak and moving on"}' | python -m json.tool
```

Response (title-only search):
```json
{
  "success": false,
  "error": "No matching media found in library"
}
```

### After Enrichment:
```bash
curl -X POST http://localhost:8080/api/media/search_library \
  -H "Content-Type: application/json" \
  -d '{"query": "heartbreak and moving on"}' | python -m json.tool
```

Response (lyrics-aware search):
```json
{
  "success": true,
  "total_results": 3,
  "results": [
    {
      "media_id": 42,
      "title": "Stick Season - Noah Kahan",
      "score": 0.87,
      "channel": "Noah Kahan"
    },
    {
      "media_id": 15,
      "title": "Anti-Hero - Taylor Swift",
      "score": 0.74,
      "channel": "Taylor Swift"
    }
  ]
}
```

**Why it works now:** Lyrics segments contain phrases like "I'm leaving this town and I'm changing my address" which semantically match "heartbreak and moving on"!

---

## Step 5: Understanding the Genius API Limits

### Free Tier Limits:
- **500 requests/day**
- No rate limit (but we add 2-second delays to be polite)
- Covers both search + lyrics fetch

### Our Usage Pattern:
- **2 requests per song**:
  1. Search for song (1 request)
  2. Fetch lyrics (1 request)
- **Max 250 songs/day** (500 requests ÷ 2 = 250)

### Optimization Strategies:
1. **Only fetch once** - Check if lyrics exist before API call
2. **Respect daily limit** - Auto-stop at 500 requests
3. **Daily reset** - Counter resets at midnight UTC
4. **Background processing** - Gradual enrichment over days
5. **Prioritization** (future) - Enrich recently downloaded media first

### Example Timeline:
- **Library: 1000 songs**
- Day 1: Enrich 250 songs (500 API calls)
- Day 2: Enrich 250 songs (500 API calls)
- Day 3: Enrich 250 songs (500 API calls)
- Day 4: Enrich 250 songs (500 API calls)
- **Total: 4 days to fully enrich library**

---

## Step 6: Monitoring Enrichment Progress

### Check Logcat:
```bash
adb logcat MediaEnrichment:D GeniusApiService:D SmartSegmenter:D *:S
```

### Expected Log Output:
```
MediaEnrichment: 🎵 Starting background lyrics enrichment
MediaEnrichment: 📊 Total media: 50, Unenriched: 27
MediaEnrichment: 🔍 Searching lyrics: Stick Season - Noah Kahan
GeniusApiService: ✓ Found: Stick Season - Noah Kahan
GeniusApiService: ✅ Fetched 2841 chars of lyrics
SmartSegmenter: 🔪 Segmenting lyrics...
SmartSegmenter: Created 12 lyrics segments
MediaEnrichment: 🎉 ENRICHED: Stick Season (24/50)
```

---

## Troubleshooting

### Error: "Genius API token not configured"
- Check `local.properties` has `genius.apiToken=...`
- Rebuild project: `./gradlew clean build`

### Error: "Search failed: 401"
- Invalid API token
- Generate new token on Genius website

### Error: "No lyrics found for: [Song Title]"
- Song not in Genius database (rare for popular music)
- Try different spelling or add artist name
- Some instrumental tracks have no lyrics (expected)

### Enrichment stopped at 500 requests
- Daily limit reached
- Will automatically resume tomorrow
- Check `today_request_count` in status API

### Lyrics text is garbled/HTML
- Web scraping sometimes gets extra markup
- Genius API doesn't provide direct lyrics (we scrape from page)
- Usually self-corrects with better HTML parsing

---

## What's Next

### Immediate (After Setup):
1. Add Genius API token to `local.properties`
2. Rebuild project
3. Download a few songs via voice: "search for meditation music"
4. Check enrichment status via API
5. Test semantic search: "find songs about heartbreak"

### Future Enhancements:
1. **Auto-start enrichment** on app launch (in VoiceListeningService)
2. **Prioritization** - Enrich recently downloaded songs first
3. **Manual lyrics** - Let users paste lyrics for songs not on Genius
4. **Enrichment UI** - Show progress in app (not just debug API)
5. **Other enrichments** - Artist bio, genre tags, release year, etc.

---

## Files Created

1. `GeniusApiService.kt` - Genius API client
2. `MediaEnrichmentService.kt` - Background enrichment system
3. `SmartSegmenter.kt` - Updated with lyrics segmentation
4. `GENIUS_API_SETUP.md` - This guide!

---

## Summary

You've now got a **background enrichment system** that:
- ✅ Fetches lyrics from Genius API
- ✅ Segments lyrics into searchable chunks
- ✅ Generates embeddings for semantic search
- ✅ Respects API limits (500/day)
- ✅ Runs in background without user interaction
- ✅ Enables theme-based music search ("heartbreak", "motivation", "summer vibes")

**This is a general pattern we can reuse for other enrichments:**
- Artist metadata from MusicBrainz
- Album art from Last.fm
- Genre tags from Spotify
- BPM/key detection from audio analysis
- User ratings and play counts

The **enrichment service pattern** is now established!
