const { chromium } = require('playwright-core');
const fs = require('fs');
const LOG = 'C:/Project/QuestionBank/output/ui-debug-recite.log';
const lines = []; const say = (m) => { lines.push(m); };
(async () => {
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Allen/.agent-browser/browsers/chrome-153.0.8010.36/chrome.exe',
    headless: true, args: ['--no-sandbox', '--disable-gpu'], timeout: 30000,
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  let errs = [];
  page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text()); });
  await page.goto('http://localhost:3000/#/login', { waitUntil: 'domcontentloaded' });
  await page.fill('#lgUser', 'admin');
  await page.fill('#lgPass', 'admin123');
  await page.click('#lgPass');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => /#\/(dashboard|welcome|subjects)/.test(location.hash), null, { timeout: 15000 });
  const subjId = await page.evaluate(async () => {
    const t = JSON.parse(localStorage.getItem('qb.session') || sessionStorage.getItem('qb.session') || 'null');
    const token = t && (t.token || t.accessToken);
    const r = await fetch('/api/subjects', { headers: { Authorization: 'Bearer ' + token } });
    const list = await r.json();
    const s = (list || []).find(x => /数据结构/.test(x.name || ''));
    return s ? s.id : (list && list[0] && list[0].id);
  });
  await page.goto('http://localhost:3000/#/subjects/' + subjId);
  await page.evaluate(() => { document.documentElement.dataset.sidelock = '0'; });
  await page.waitForSelector('.side-tree .tree-row[data-book]', { timeout: 15000 });
  // 找一本带 recite pane 的书：逐本打开试
  const bids = await page.evaluate(() => [...document.querySelectorAll('.side-tree .tree-row[data-book]')].map(x => Number(x.dataset.book)));
  say('books=' + JSON.stringify(bids));
  let found = null;
  for (const bid of bids) {
    await page.evaluate((b) => {
      const row = document.querySelector(`.side-tree .tree-row[data-book="${b}"]`);
      row.click();
    }, bid);
    await page.waitForTimeout(2500);
    const isRecite = await page.evaluate(() => {
      const r = [...document.querySelectorAll('.book-reader:not([hidden])')].find(x => x.querySelector('[data-pane="recite"]'));
      return !!r;
    });
    if (isRecite) { found = bid; break; }
  }
  say('reciteBook=' + found);
  if (found == null) { say('no recite book'); }
  else {
    const dbg = await page.evaluate(() => {
      const reader = [...document.querySelectorAll('.book-reader:not([hidden])')].find(x => x.querySelector('[data-pane="recite"]'));
      const dw = reader.querySelector('[data-drawer]');
      dw.classList.add('force-open');
      const rows = [...dw.querySelectorAll('.rtoc-row[data-o]')];
      const vis = rows.filter(r => r.offsetParent !== null);
      const modeBtns = [...reader.querySelectorAll('[data-readmode] button')].map(b => b.dataset.rm + ':' + b.classList.contains('on'));
      return {
        rowsTotal: rows.length, rowsVis: vis.length,
        first: vis[0] ? vis[0].dataset.o : null,
        modeBtns,
        cards: reader.querySelectorAll('.kp-card').length,
        openBefore: reader.querySelectorAll('.kp-card[open]').length,
      };
    });
    say('before=' + JSON.stringify(dbg));
    await page.evaluate(() => {
      const reader = [...document.querySelectorAll('.book-reader:not([hidden])')].find(x => x.querySelector('[data-pane="recite"]'));
      const dw = reader.querySelector('[data-drawer]');
      const row = [...dw.querySelectorAll('.rtoc-row[data-o]')].find(r => r.offsetParent !== null);
      row.click();
    });
    await page.waitForTimeout(1200);
    const after = await page.evaluate(() => {
      const reader = [...document.querySelectorAll('.book-reader:not([hidden])')].find(x => x.querySelector('[data-pane="recite"]'));
      return {
        cards: reader.querySelectorAll('.kp-card').length,
        openAfter: reader.querySelectorAll('.kp-card[open]').length,
        flash: reader.querySelectorAll('.flash-target').length,
        toggle: (reader.querySelector('.kp-card .kp-toggle') || {}).textContent || null,
      };
    });
    say('after=' + JSON.stringify(after));
  }
  say('errs=' + JSON.stringify(errs.slice(0, 6)));
  await browser.close();
})().catch((e) => { say('FATAL ' + (e && e.message ? e.message : e)); return browser.close(); }).finally(() => {
  fs.appendFileSync(LOG, '==== run ' + new Date().toISOString() + ' ====\n' + lines.join('\n') + '\n');
  console.log(lines.join('\n'));
});
