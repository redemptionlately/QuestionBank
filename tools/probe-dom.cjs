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
  await page.goto('http://localhost:3000/#/subjects/6');
  await page.waitForTimeout(2000);
  const d = await page.evaluate(() => ({
    treeHTML: (document.querySelector('.side-tree') || {}).innerHTML?.slice(0, 500) || 'NO .side-tree',
    treeW: document.querySelector('.side-tree')?.getBoundingClientRect().width,
    hasRail: !!document.querySelector('#treeRail'),
    readBtns: document.querySelectorAll('[data-act="read"]').length,
    bookCards: document.querySelectorAll('[data-book]').length,
    contentHTML: (document.querySelector('.content') || {}).innerHTML?.slice(0, 600),
  }));
  console.log(JSON.stringify(d, null, 1));
  await browser.close();
})();
