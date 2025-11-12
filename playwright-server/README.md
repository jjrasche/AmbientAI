# Playwright Browser Automation Server

REST API server for browser automation, designed to integrate with AmbientAI workflows.

## Installation

```bash
npm install
```

Playwright will automatically download Chromium on first install.

## Running the Server

```bash
npm start
```

Server runs on `http://localhost:3000` by default. Set `PORT` environment variable to change.

## On-Device Setup (Android via Termux)

1. Install [Termux](https://f-droid.org/en/packages/com.termux/) from F-Droid
2. In Termux:
   ```bash
   pkg install nodejs
   cd /path/to/AmbientAI/playwright-server
   npm install
   npm start
   ```
3. AmbientAI will connect to `http://127.0.0.1:3000`

## API Endpoints

### `POST /browser/navigate`
Navigate to a URL. Creates new session if not provided.

**Request:**
```json
{
  "url": "https://example.com",
  "sessionId": "optional_session_id"
}
```

**Response:**
```json
{
  "success": true,
  "sessionId": "session_123",
  "url": "https://example.com",
  "title": "Example Domain"
}
```

### `POST /browser/click`
Click an element by selector or text.

**Request:**
```json
{
  "sessionId": "session_123",
  "selector": "button.submit"
  // OR
  "text": "Submit"
}
```

### `POST /browser/scrape`
Extract text content from an element.

**Request:**
```json
{
  "sessionId": "session_123",
  "selector": ".price"
}
```

**Response:**
```json
{
  "success": true,
  "text": "$29.99"
}
```

### `POST /browser/extract`
Get full page HTML and text (for LLM processing).

**Request:**
```json
{
  "sessionId": "session_123"
}
```

**Response:**
```json
{
  "success": true,
  "html": "<html>...</html>",
  "text": "Visible page text..."
}
```

### `POST /browser/screenshot`
Capture screenshot as base64.

**Request:**
```json
{
  "sessionId": "session_123",
  "fullPage": false
}
```

### `POST /browser/type`
Type text into an input field.

**Request:**
```json
{
  "sessionId": "session_123",
  "selector": "input[name='email']",
  "text": "user@example.com"
}
```

### `POST /browser/waitFor`
Wait for element to appear.

**Request:**
```json
{
  "sessionId": "session_123",
  "selector": ".loading-complete",
  "timeout": 30000
}
```

### `POST /browser/close`
Close browser session and cleanup.

**Request:**
```json
{
  "sessionId": "session_123"
}
```

### `GET /health`
Health check.

**Response:**
```json
{
  "status": "ok",
  "sessions": 2
}
```

## Session Management

- Sessions auto-cleanup after 10 minutes of inactivity
- Each action resets the timeout
- Sessions maintain cookies, local storage, etc.
- Use same `sessionId` for multi-step workflows
- Call `/browser/close` to immediately cleanup

## Security Notes

- Server has no authentication - run on localhost or secure network only
- Sessions are ephemeral - no persistent storage
- Recommended for personal/local use only
