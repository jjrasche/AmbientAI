# Prompt for Generating Embedding Test Cases

## Instructions

You have **52 enriched songs** with lyrics embedded using a 100-dimensional Universal Sentence Encoder model.

Based on your knowledge of your music library (primarily Zach Bryan songs), generate test cases to validate semantic search quality.

## Prompt to Give to Claude/GPT-4/Gemini:

```
I have a semantic lyrics search system for my music library with 52 songs, primarily by Zach Bryan.

Generate 15-20 test cases in the following JSON format:

{
  "test_cases": [
    {
      "query": "heartbreak and losing someone you love",
      "expected_themes": ["loss", "grief", "heartbreak"],
      "expected_artists": ["Zach Bryan"],
      "min_results": 3,
      "description": "Should find songs about heartbreak and loss"
    },
    {
      "query": "driving at night feeling free",
      "expected_themes": ["freedom", "night driving", "escape"],
      "expected_artists": ["Zach Bryan"],
      "min_results": 2,
      "description": "Should find songs about night driving and freedom"
    }
  ]
}

Include diverse queries covering:
1. **Emotional themes**: heartbreak, joy, nostalgia, anger, peace
2. **Imagery**: nature scenes, city life, road trips, home, water/ocean
3. **Relationships**: love, breakups, friendship, family, loneliness
4. **Time/Place**: summer, winter, specific locations (Oklahoma, etc.), past vs present
5. **Abstract concepts**: hope, regret, growth, fear, courage

For each test case, specify:
- `query`: The search query (natural language)
- `expected_themes`: List of themes that should appear in results
- `expected_artists`: Artists you expect to see (if known)
- `min_results`: Minimum number of relevant results expected
- `description`: What this test validates

Generate test cases that would help evaluate whether the embedding model captures semantic meaning well.
```

## How to Use

1. Copy the prompt above
2. Paste into Claude/GPT-4/Gemini
3. Save the generated JSON to `test_cases.json`
4. Load it into the test harness (to be built next)

## Why This Approach

- Avoids copyright issues (no need to export actual lyrics)
- Uses your knowledge of your own music library
- Focuses on validating semantic understanding
- Creates reusable test cases for comparing different embedding models

## Next Steps

After generating test cases:
1. Build test harness API endpoint (`/api/test/embeddings`)
2. Run tests with current 100D model
3. Compare with upgraded model (768D EmbeddingGemma)
4. Make data-driven decision on which model to use
