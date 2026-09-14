// 4 组合复现顶栏收起：sidelock 0/1 × 科目列表页/阅读器页
const { chromium } = require('playwright-core');
(async () => {
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Allen/.agent-browser/browsers/chrome-153.0.8010.36/chrome.exe',
    headless: true, args: ['--no-sandbox', '--disable-gpu'], timeout: 30000,
  });
  const page = await browser.newPage({ viewport: { width: 1500, height: 900 } });
  await page.goto('http://localhost:3000/#/login', { waitUntil: 'domcontentloaded' });
  await page.fill('#lgUser', 'admin'); await page.fill('#lgPass', 'admin123');
  await page.click('#lgPass'); await page.keyboard.press('Enter');
  await page.waitForFunction(() => /#\/(dashboard|welcome|subjects)/.test(location.hash), null, { timeout: 15000 });

  const st = () => page.evaluate(() => ({
    shown: document.body.classList.contains('topshow'),
    y: document.querySelector('.topbar')?.getBoundingClientRect().y,
    sidelock: document.documentElement.dataset.sidelock,
    railHot: !!document.querySelector('.rail-hot:hover'),
    topHot: !!document.querySelector('.top-hot:hover'),
    elAt: (document.elementFromPoint(700, 300) || {}).className,
  }));

  for (const lock of ['0', '1']) {
    for (const openBook of [false, true]) {
      await page.evaluate((l) => {
        document.documentElement.dataset.sidelock = l;
        try { localStorage.setItem('qb.side.lock', l); } catch {}
      }, lock);
      await page.goto('http://localhost:3000/#/subjects/6', { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1500);
      if (openBook) {
        await page.waitForSelector('[data-act="read"]', { state: 'attached', timeout: 10000 });
        await page.evaluate(() => document.querySelector('[data-act="read"]').click());
        await page.waitForTimeout(2000);
      }
      await page.mouse.move(700, 6); await page.waitForTimeout(500);
      const a = await st();
      await page.mouse.move(700, 300, { steps: 10 }); await page.waitForTimeout(700);
      const b = await st();
      await page.mouse.move(700, 600, { steps: 10 }); await page.waitForTimeout(900);
      const c = await st();
      console.log(`lock=${lock} book=${openBook} | 顶部:${a.shown}(y=${a.y}) → 下移300:${b.shown}(y=${b.y}) → 下移600:${c.shown}(y=${c.y}) railHot=${c.railHot} topHot=${c.topHot}`);
    }
  }
  await browser.close();
})();
