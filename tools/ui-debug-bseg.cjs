const { chromium } = require('playwright-core');
const fs = require('fs');
const lines = []; const say = (m) => lines.push(m);
(async () => {
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Allen/.agent-browser/browsers/chrome-153.0.8010.36/chrome.exe',
    headless: true, args: ['--no-sandbox', '--disable-gpu'], timeout: 30000,
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto('http://localhost:3000/#/login', { waitUntil: 'domcontentloaded' });
  await page.fill('#lgUser', 'admin'); await page.fill('#lgPass', 'admin123');
  await page.click('#lgPass'); await page.keyboard.press('Enter');
  await page.waitForFunction(() => /#\/(dashboard|welcome|subjects)/.test(location.hash), null, { timeout: 15000 });
  const subjId = await page.evaluate(async () => {
    const t = JSON.parse(localStorage.getItem('qb.session') || sessionStorage.getItem('qb.session') || 'null');
    const r = await fetch('/api/subjects', { headers: { Authorization: 'Bearer ' + (t && (t.token || t.accessToken)) } });
    const list = await r.json();
    const s = (list || []).find(x => /数据结构/.test(x.name || ''));
    return s ? s.id : list[0].id;
  });
  await page.goto('http://localhost:3000/#/subjects/' + subjId);
  await page.evaluate(() => { document.documentElement.dataset.sidelock = '0'; });
  await page.waitForSelector('.side-tree .tree-row[data-book]', { timeout: 15000 });
  // 打开第一本 recite 书
  const bids = await page.evaluate(() => [...document.querySelectorAll('.side-tree .tree-row[data-book]')].map(x => Number(x.dataset.book)));
  for (const bid of bids) {
    await page.evaluate((b) => document.querySelector(`.side-tree .tree-row[data-book="${b}"]`).click(), bid);
    await page.waitForTimeout(2500);
    const ok = await page.evaluate(() => {
      const r = [...document.querySelectorAll('.book-reader:not([hidden])')].find(x => x.querySelector('.bseg button'));
      return !!r;
    });
    if (ok) { say('bookWithBseg=' + bid); break; }
  }
  for (const lv of ['PROFICIENT', 'FUZZY', 'UNKNOWN']) {
    await page.hover(`.book-reader:not([hidden]) .bseg button[data-lv="${lv}"]`);
    await page.waitForTimeout(200);
    const bg = await page.evaluate((lv) => {
      const b = document.querySelector(`.book-reader:not([hidden]) .bseg button[data-lv="${lv}"]`);
      return getComputedStyle(b).backgroundColor;
    }, lv);
    say(`hover ${lv} bg=${bg}`);
  }
  await page.screenshot({ path: 'C:/Project/QuestionBank/output/ui-smoke-bseg-hover.png' });
  await browser.close();
})().catch((e) => { say('FATAL ' + (e && e.message)); return browser.close().catch(() => {}); }).finally(() => {
  console.log(lines.join('\n'));
  fs.appendFileSync('C:/Project/QuestionBank/output/ui-smoke-subjtree.log', '==== bseg hover ' + new Date().toISOString() + ' ====\n' + lines.join('\n') + '\n');
});
