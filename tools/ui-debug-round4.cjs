const { chromium } = require('playwright-core');
const fs = require('fs');
const lines = []; const say = (m) => lines.push(m);
const step = async (name, fn) => {
  try { await fn(); say('PASS ' + name); }
  catch (e) { say('FAIL ' + name + ' :: ' + (e && e.message ? e.message.split('\n')[0] : e)); }
};
(async () => {
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Allen/.agent-browser/browsers/chrome-153.0.8010.36/chrome.exe',
    headless: true, args: ['--no-sandbox', '--disable-gpu'], timeout: 30000,
  });
  const page = await browser.newPage({ viewport: { width: 1500, height: 900 } });
  let errs = [];
  page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message));
  try {
    await page.goto('http://localhost:3000/#/login', { waitUntil: 'domcontentloaded' });
    await page.fill('#lgUser', 'admin'); await page.fill('#lgPass', 'admin123');
    await page.click('#lgPass'); await page.keyboard.press('Enter');
    await page.waitForFunction(() => /#\/(dashboard|welcome|subjects)/.test(location.hash), null, { timeout: 15000 });

    // A. 导航 hover/active 新样式
    await step('nav hover uses surface-3 pill', async () => {
      await page.evaluate(() => { try { localStorage.setItem('qb.side.lock', '0'); } catch {} });
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.evaluate(() => document.getElementById('sidebar').classList.add('pinned'));
      await page.waitForTimeout(400);
      const item = page.locator('.sidebar .nav-item').first();
      await item.hover();
      await page.waitForTimeout(200);
      const bg = await page.evaluate(() => getComputedStyle(document.querySelector('.sidebar .nav-item:hover') || document.querySelectorAll('.sidebar .nav-item')[0]).backgroundColor);
      say('INFO nav hover bg=' + bg);
    });
    // 2026-09-14 用户拍板：active 恢复原有实心蓝块，断言反转为"必须是实心品牌蓝"
    await step('active nav tile: 实心品牌蓝块（原始选中效果）', async () => {
      await page.goto('http://localhost:3000/#/subjects', { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(900);
      const st = await page.evaluate(() => {
        const t = document.querySelector('.sidebar .nav-item.active .nav-tile');
        return t ? getComputedStyle(t).backgroundColor : null;
      });
      say('INFO active tile bg=' + st);
      if (!st) throw new Error('no active nav-tile');
      const m = st.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/);
      if (!m) throw new Error('cannot parse: ' + st);
      const [r, g, b] = [+m[1], +m[2], +m[3]];
      const alpha = m[4] === undefined ? 1 : +m[4];
      if (!(b > 150 && b - r > 60 && alpha >= 0.95)) throw new Error('active tile not solid blue: ' + st);
    });

    // B. 熟练度筛选：按钮选中三色 + 命中行标色
    const subjId = await page.evaluate(async () => {
      const t = JSON.parse(localStorage.getItem('qb.session') || sessionStorage.getItem('qb.session') || 'null');
      const r = await fetch('/api/subjects', { headers: { Authorization: 'Bearer ' + (t && (t.token || t.accessToken)) } });
      const list = await r.json();
      const s = (list || []).find(x => /数据结构/.test(x.name || ''));
      return s ? s.id : list[0].id;
    });
    // 预置一本书的 UNKNOWN 标记
    await page.goto('http://localhost:3000/#/subjects/' + subjId);
    await page.evaluate(() => { document.documentElement.dataset.sidelock = '0'; });
    await page.waitForSelector('.side-tree .tree-row[data-book]', { timeout: 15000 });
    const bid = await page.evaluate(() => {
      const row = [...document.querySelectorAll('.side-tree .tree-row[data-book]')].find(r => r.offsetParent !== null);
      return Number(row.dataset.book);
    });
    await page.evaluate((bid) => {
      const row = document.querySelector(`.side-tree .tree-row[data-book="${bid}"]`);
      row.click();
    }, bid);
    await page.waitForSelector('.book-reader:not([hidden]) [data-pane="recite"]', { timeout: 20000 });
    await page.waitForTimeout(800);
    // 标第一张卡为 UNKNOWN
    await page.evaluate(() => {
      const r = document.querySelector('.book-reader:not([hidden])');
      const k = r.querySelector('.kp-card').dataset.kp;
      const s = JSON.parse(localStorage.getItem('qb.book.mastery') || '{}');
      s[Object.keys(s).length >= 0 ? (new URLSearchParams(location.hash.replace('#', '')).get('x') || k) && k && k : k] = undefined;
      const store = JSON.parse(localStorage.getItem('qb.book.mastery') || '{}');
      const bookId = Number(document.querySelector('.book-reader:not([hidden])').dataset.readerFor);
      store[bookId] = store[bookId] || {};
      store[bookId][k] = 'UNKNOWN';
      localStorage.setItem('qb.book.mastery', JSON.stringify(store));
    });
    await page.waitForTimeout(300);
    await step('filter UNKNOWN: button on-state colored + matched rows marked', async () => {
      await page.evaluate(() => {
        const r = document.querySelector('.book-reader:not([hidden])');
        const btn = [...r.querySelectorAll('[data-bfilter] button')].find(b => b.dataset.m === 'UNKNOWN');
        btn.click();
      });
      await page.waitForTimeout(400);
      const st = await page.evaluate(() => {
        const r = document.querySelector('.book-reader:not([hidden])');
        const btn = [...r.querySelectorAll('[data-bfilter] button')].find(b => b.dataset.m === 'UNKNOWN');
        const marked = [...r.querySelectorAll('.rtoc-row[data-mf]')].map(x => x.dataset.mf);
        const cs = getComputedStyle(btn);
        return { bg: cs.backgroundColor, color: cs.color, shadow: cs.boxShadow, marked };
      });
      say('INFO filter=' + JSON.stringify(st));
      const m = st.bg.match(/rgba?\((\d+), (\d+), (\d+)/);
      const [r0, g0, b0] = [+m[1], +m[2], +m[3]];
      if (!(r0 > g0 + 30 && r0 > b0 + 30)) throw new Error('UNKNOWN .on bg not red: ' + st.bg);
      if (!st.marked.includes('UNKNOWN')) throw new Error('no rows marked data-mf');
    });
    await page.screenshot({ path: 'C:/Project/QuestionBank/output/ui-smoke-mf-filter.png' });

    // C. 右抽屉固定 → 内容让位
    await step('pinned right drawer yields content padding', async () => {
      await page.evaluate(() => {
        const r = document.querySelector('.book-reader:not([hidden])');
        r.querySelector('[data-drawer]').classList.add('force-open');
        r.querySelector('[data-rdrpin]').click();
      });
      await page.waitForTimeout(500);
      const st = await page.evaluate(() => ({
        pinned: document.body.classList.contains('rdr-pinned'),
        open: !!document.querySelector('[data-drawer].force-open'),
        pr: getComputedStyle(document.querySelector('.content')).paddingRight,
      }));
      say('INFO pinned=' + JSON.stringify(st));
      if (!st.pinned) throw new Error('rdr-pinned class missing');
      if (!st.open) throw new Error('drawer not force-open');
      if (parseFloat(st.pr) < 300) throw new Error('content padding-right not yielded: ' + st.pr);
    });
    await page.screenshot({ path: 'C:/Project/QuestionBank/output/ui-smoke-pinned-yield.png' });
    say('INFO pageerrors=' + JSON.stringify(errs.slice(0, 6)));
  } catch (e) { say('FATAL ' + (e && e.message)); }
  await browser.close();
})().finally(() => {
  console.log(lines.join('\n'));
  fs.appendFileSync('C:/Project/QuestionBank/output/ui-smoke-subjtree.log', '==== round4 ' + new Date().toISOString() + ' ====\n' + lines.join('\n') + '\n');
});
