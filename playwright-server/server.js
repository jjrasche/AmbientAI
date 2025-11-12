const express = require('express');
const { chromium } = require('playwright');
const cors = require('cors');

const app = express();
app.use(cors());
app.use(express.json());

const sessions = new Map();
const PORT = process.env.PORT || 3000;
const SESSION_TIMEOUT = 10 * 60 * 1000;

const cleanupSession = async (sessionId) => {
  const session = sessions.get(sessionId);
  if (session) {
    await session.context.close();
    sessions.delete(sessionId);
    console.log(`Session ${sessionId} cleaned up`);
  }
};

const getOrCreateSession = async (sessionId) => {
  if (sessionId && sessions.has(sessionId)) {
    const session = sessions.get(sessionId);
    clearTimeout(session.timeout);
    session.timeout = setTimeout(() => cleanupSession(sessionId), SESSION_TIMEOUT);
    return session;
  }
  const newSessionId = sessionId || `session_${Date.now()}`;
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();
  const session = {
    sessionId: newSessionId,
    browser,
    context,
    page,
    timeout: setTimeout(() => cleanupSession(newSessionId), SESSION_TIMEOUT)
  };
  sessions.set(newSessionId, session);
  console.log(`Session ${newSessionId} created`);
  return session;
};

app.post('/browser/navigate', async (req, res) => {
  try {
    const { url, sessionId } = req.body;
    if (!url) return res.status(400).json({ error: 'url is required' });
    const session = await getOrCreateSession(sessionId);
    await session.page.goto(url, { waitUntil: 'networkidle' });
    const title = await session.page.title();
    res.json({ success: true, sessionId: session.sessionId, url, title });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/browser/click', async (req, res) => {
  try {
    const { selector, text, sessionId } = req.body;
    if (!sessionId) return res.status(400).json({ error: 'sessionId is required' });
    if (!selector && !text) return res.status(400).json({ error: 'selector or text is required' });
    const session = sessions.get(sessionId);
    if (!session) return res.status(404).json({ error: 'Session not found' });
    if (text) {
      await session.page.click(`text=${text}`);
    } else {
      await session.page.click(selector);
    }
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/browser/scrape', async (req, res) => {
  try {
    const { selector, sessionId } = req.body;
    if (!sessionId) return res.status(400).json({ error: 'sessionId is required' });
    if (!selector) return res.status(400).json({ error: 'selector is required' });
    const session = sessions.get(sessionId);
    if (!session) return res.status(404).json({ error: 'Session not found' });
    const element = await session.page.$(selector);
    if (!element) return res.status(404).json({ error: `Element not found: ${selector}` });
    const text = await element.textContent();
    res.json({ success: true, text: text.trim() });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/browser/screenshot', async (req, res) => {
  try {
    const { sessionId, fullPage = false } = req.body;
    if (!sessionId) return res.status(400).json({ error: 'sessionId is required' });
    const session = sessions.get(sessionId);
    if (!session) return res.status(404).json({ error: 'Session not found' });
    const screenshot = await session.page.screenshot({ fullPage, encoding: 'base64' });
    res.json({ success: true, screenshot });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/browser/extract', async (req, res) => {
  try {
    const { sessionId } = req.body;
    if (!sessionId) return res.status(400).json({ error: 'sessionId is required' });
    const session = sessions.get(sessionId);
    if (!session) return res.status(404).json({ error: 'Session not found' });
    const html = await session.page.content();
    const text = await session.page.evaluate(() => document.body.innerText);
    res.json({ success: true, html, text });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/browser/type', async (req, res) => {
  try {
    const { selector, text, sessionId } = req.body;
    if (!sessionId) return res.status(400).json({ error: 'sessionId is required' });
    if (!selector || !text) return res.status(400).json({ error: 'selector and text are required' });
    const session = sessions.get(sessionId);
    if (!session) return res.status(404).json({ error: 'Session not found' });
    await session.page.fill(selector, text);
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/browser/waitFor', async (req, res) => {
  try {
    const { selector, sessionId, timeout = 30000 } = req.body;
    if (!sessionId) return res.status(400).json({ error: 'sessionId is required' });
    if (!selector) return res.status(400).json({ error: 'selector is required' });
    const session = sessions.get(sessionId);
    if (!session) return res.status(404).json({ error: 'Session not found' });
    await session.page.waitForSelector(selector, { timeout });
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.post('/browser/close', async (req, res) => {
  try {
    const { sessionId } = req.body;
    if (!sessionId) return res.status(400).json({ error: 'sessionId is required' });
    await cleanupSession(sessionId);
    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

app.get('/health', (req, res) => {
  res.json({ status: 'ok', sessions: sessions.size });
});

app.listen(PORT, () => {
  console.log(`Playwright server running on port ${PORT}`);
  console.log(`Endpoints:`);
  console.log(`  POST /browser/navigate  - Navigate to URL`);
  console.log(`  POST /browser/click     - Click element`);
  console.log(`  POST /browser/scrape    - Scrape text from element`);
  console.log(`  POST /browser/screenshot- Take screenshot`);
  console.log(`  POST /browser/extract   - Extract page HTML/text`);
  console.log(`  POST /browser/type      - Type into input`);
  console.log(`  POST /browser/waitFor   - Wait for element`);
  console.log(`  POST /browser/close     - Close session`);
  console.log(`  GET  /health            - Health check`);
});
