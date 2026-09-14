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
  await page.goto('http://localhost:3000/#/subjects', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  const r1 = await page.evaluate(() => {
    const sb = document.getElementById('sidebar');
    sb.classList.add('pinned');
    return { cls: sb.className, tr: getComputedStyle(sb).transform, w: getComputedStyle(sb).width };
  });
  console.log('immediately:', JSON.stringify(r1));
  await page.waitForTimeout(700);
  const r2 = await page.evaluate(() => {
    const sb = document.getElementById('sidebar');
    const item = document.querySelector('.sidebar .nav-item');
    return {
      cls: sb.className, tr: getComputedStyle(sb).transform,
      rect: sb.getBoundingClientRect().toJSON(),
      itemRect: item ? item.getBoundingClientRect().toJSON() : null,
      bodyCls: document.body.className,
      sideLock: localStorage.getItem('qb.side.lock'),
    };
  });
  console.log('after 700ms:', JSON.stringify(r2, null, 1));
  // 左缘召唤
  await page.mouse.move(20, 400); await page.waitForTimeout(700);
  const r3 = await page.evaluate(() => ({
    bodyCls: document.body.className,
    elAt: (document.elementFromPoint(20, 400) || {}).className,
    rect: document.getElementById('sidebar').getBoundingClientRect().toJSON(),
  }));
  console.log('after edge summon:', JSON.stringify(r3));
  await browser.close();
})();
