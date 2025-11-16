# Unified "Play" Workflow Design

## Triggers
- "play"
- "put on"
- "listen to"

## Workflow Steps

### 1. Intent Analysis (LLM)
Classify user's request:
- Content type (song/album/artist/podcast/educational/specific memory)
- Specificity (exact/partial/vague)
- Extract entities (artist, title, topic)
- Generate semantic search query
- Determine if recency matters

### 2. Library Semantic Search
Search downloaded media using semantic query from step 1.
- Max 5 results
- Min score 0.3

### 3. Match Quality Evaluation (LLM)
LLM evaluates if library results satisfy intent:
- "excellent" = play immediately
- "good" = offer choice (library + YouTube)
- "poor" = skip to YouTube
- "none" = skip to YouTube

Apply heuristics:
- Exact match + score > 0.8 → excellent
- Partial match + score 0.5-0.8 → good
- Vague match + score < 0.5 → poor
- No results → none

### 4. Decision Tree

#### Path A: Excellent Match
- Play immediately from library
- TTS: "Playing [title]"

#### Path B: Good Match
- YouTube search with same query
- Combine results: library results + YouTube results
- Present unified selection
- User chooses (library = instant play, YouTube = download then play)

#### Path C: Poor/None Match
- YouTube search
- Present YouTube results only
- User selects
- Download
- Play

### 5. Playback History Intelligence (Future)
For requests like "episode I haven't heard yet":
- Check MediaPlaybackSession table
- Filter out previously played content
- Only present unheard episodes

---

## Example Flows

### Example 1: "play Noah Kahan"
1. Intent: `{content_type: "artist", artist: "Noah Kahan", semantic_query: "Noah Kahan music"}`
2. Library search: Found "Stick Season - Noah Kahan" (score 0.94)
3. Match eval: `{match_quality: "excellent", best_match_id: 123, confidence: 0.94}`
4. **Action:** Play immediately
5. **TTS:** "Playing Stick Season by Noah Kahan"

### Example 2: "play Taylor Swift's newest album"
1. Intent: `{content_type: "album", artist: "Taylor Swift", needs_recency: true, semantic_query: "Taylor Swift latest album"}`
2. Library search: Found "1989 - Taylor Swift" (score 0.82)
3. Match eval: `{match_quality: "good", should_search_youtube: true, reasoning: "User wants newest album, library only has 1989 from 2014"}`
4. **Action:** Search YouTube for "Taylor Swift newest album"
5. Combine results:
   - Option 1: [Library] 1989 - Taylor Swift (2014) - Already downloaded
   - Option 2: [YouTube] The Tortured Poets Department - Taylor Swift (2024)
   - Option 3: [YouTube] Midnights - Taylor Swift (2022)
6. **TTS:** "I found 1989 in your library from 2014. I also found newer albums on YouTube: The Tortured Poets Department from 2024, and Midnights from 2022. Which would you like?"
7. User selects option 2
8. Download + Play

### Example 3: "play that tomato picking video"
1. Intent: `{content_type: "specific_memory", semantic_query: "tomatoes picking harvesting agriculture", query_specificity: "vague"}`
2. Library search: Found "The Science of Tomato Harvesting" (score 0.78)
3. Match eval: `{match_quality: "excellent", reasoning: "Strong semantic match to specific memory"}`
4. **Action:** Play immediately
5. **TTS:** "Playing The Science of Tomato Harvesting"

### Example 4: "play something about how LLMs work at a statistical level"
1. Intent: `{content_type: "educational", semantic_query: "language models statistics mathematics probability machine learning", query_specificity: "vague"}`
2. Library search: Found "Neural Networks Explained - 3Blue1Brown" (score 0.68)
3. Match eval: `{match_quality: "good", reasoning: "Related but not specifically about LLM statistics"}`
4. **Action:** Search YouTube for "how language models work statistics"
5. Combine results:
   - Option 1: [Library] Neural Networks Explained - 3Blue1Brown (score 0.68)
   - Option 2: [YouTube] Statistical Foundations of LLMs - Andrej Karpathy
   - Option 3: [YouTube] The Math Behind ChatGPT - 3Blue1Brown
6. User selects option 2
7. Download + Play

### Example 5: "play Matt Shane's Secret Podcast episode I haven't heard"
1. Intent: `{content_type: "podcast", needs_recency: true, semantic_query: "Matt Shane Secret Podcast"}`
2. Library search: Found 3 episodes (scores 0.92, 0.91, 0.90)
3. Check playback history: 2 episodes already played
4. Match eval: `{match_quality: "good", best_match_id: 456, reasoning: "Found unheard episode in library"}`
5. **Action:** Play unheard episode
6. **TTS:** "Playing Matt Shane's Secret Podcast Episode 142"

---

## Heuristics Summary

### Excellent Match (Play Immediately)
- Exact artist/album/song match + score > 0.8
- Specific memory recall + score > 0.75
- Unambiguous intent + high semantic similarity

### Good Match (Offer Choice)
- Partial match + score 0.5-0.8
- User wants "newest" but we have old version
- Related content but not exact topic

### Poor Match (Skip to YouTube)
- Score < 0.5
- No semantic relevance
- Empty library results

---

## Implementation Priority

### Phase 1: Basic Unified Play
1. Single workflow: library search → YouTube search → download → play
2. Simple threshold: score > 0.7 = play, else YouTube
3. No combined results yet (either library OR YouTube)

### Phase 2: LLM-Enhanced Match Quality
1. LLM evaluates intent
2. LLM evaluates match quality
3. Smarter decision making

### Phase 3: Combined Results
1. Show library + YouTube results together
2. User can choose between cached and new content

### Phase 4: Playback History
1. Filter out heard content
2. Smart recency handling

---

## Actions Needed

### New Actions to Implement
1. `media.play` - Play downloaded media by media_id (replaces music.play for YouTube content)
2. `media.getPlaybackHistory` - Get list of played media_ids
3. `media.searchCombined` - Search library + YouTube, return unified results

### Workflows to Remove
1. `play_music` (replaced by unified play)
2. `pause_music` (keep, but update to work with media)
3. `next_song` (keep, update to work with media)
4. `previous_song` (keep, update to work with media)
5. `whats_playing` (keep, update to work with media)
6. `search_youtube` (merged into unified play)
7. `search_library` (merged into unified play)
8. `download_media` (merged into unified play, but could keep for explicit downloads)

### Music Workflows to Keep/Update
- pause_music → pause (works for any media)
- resume_music → resume
- next_song → next
- previous_song → previous
- whats_playing → whats_playing
- list_all_songs → update to list all media (not just MP3s)

---

## Question: Should we start with Phase 1 (simple) or go straight to Phase 2 (LLM-heavy)?

Phase 1 pros:
- Faster to implement
- Easier to test
- Can iterate based on real usage

Phase 2 pros:
- Better UX from day 1
- Handles your complex use cases
- More "wow" factor

Your call!
