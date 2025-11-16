# Embedding Model Status

## Current Model

Using the official MediaPipe model:
- **Model**: Universal Sentence Encoder (100 dimensions)
- **Source**: MediaPipe official model repository
- **File**: universal_sentence_encoder.tflite (6MB)
- **Compatibility**: Full MediaPipe TextEmbedder support with metadata

## Required Model File

You need to download the TFLite model:

**Model**: `all-MiniLM-L6-v2`
- **Format**: TFLite
- **Dimensions**: 384
- **Size**: ~22MB

### Where to Get It

Option 1: **Hugging Face** (recommended)
```bash
# Download from sentence-transformers
https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2

# You'll need to convert to TFLite format or find a pre-converted version
```

Option 2: **Pre-converted TFLite models**
```bash
# Check these sources:
https://tfhub.dev/google/collections/lite/1
https://github.com/tensorflow/tfjs-models
```

Option 3: **Convert yourself using Python**
```python
from sentence_transformers import SentenceTransformer
import tensorflow as tf

# Load model
model = SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')

# Convert to TFLite (requires additional steps)
# ... conversion code ...
```

### Installation

Once you have `all_minilm_l6_v2.tflite`:

1. Place it in: `app/src/main/assets/all_minilm_l6_v2.tflite`
2. Delete old segments: `curl -X POST http://localhost:8080/api/enrichment/cleanup`
3. Rebuild: `./gradlew installDebug`
4. Restart enrichment: `curl -X POST http://localhost:8080/api/enrichment/start`

## What Changed

### Schema Updates
- Added `embeddingModel` field to track which model generated each embedding
- Changed HNSW index dimensions from 100 → 384
- All old 100-dim embeddings will be incompatible

### Cleanup Process
Run this to delete all old 100-dim segments:
```bash
curl -X POST http://localhost:8080/api/enrichment/cleanup
```

This will:
- Delete transcripts with 100-dim embeddings
- Keep any 384-dim embeddings (if they exist)
- Allow re-enrichment with new model

## Benefits of 384-Dim Model

- **Better semantic understanding**: More nuanced representation of meaning
- **Improved search quality**: More accurate similarity scores
- **Standard model**: all-MiniLM-L6-v2 is widely used and well-tested
- **Still mobile-friendly**: 22MB is reasonable for on-device inference

## Next Steps

1. **Get the model file** (see options above)
2. **Place in assets folder**
3. **Clean old embeddings**
4. **Rebuild and test**
5. **Re-run enrichment** (will take ~2 days for 500+ songs)

## Temporary Fallback

If you can't get the 384-dim model immediately, I can:
1. Revert dimensions back to 100
2. Keep using USE Lite
3. Track model name for future upgrade

Let me know which path you want to take!
