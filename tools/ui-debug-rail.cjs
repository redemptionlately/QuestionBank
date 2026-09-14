const { chromium } = require('playwright-core');
const fs = require('fs');
const lines = []; const say = (m) => lines.push(m);
(async () => {
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Allen/.agent-browser/browsers/chrome-153.0.8010.36/chrome.exe',
    headless: true, args: ['--no-sandbox', '--disable-gpu'], timeout: 30000,
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto('http://localhost:3000/#/login', { waitUntil: 'domcontentloaded' });
  await page.fill('#lgUser', 'admin'); await page.fill('#lgPass', 'admin123');
  await page.click('#lgPass'); await page.keyboard.press('Enter');
  await page.waitForFunction(() => /#\/(dashboard|welcome|subjects)/.test(location.hash), null, { timeout: 15000 });
  await page.goto('http://localhost:3000/#/subjects');
  /* 侧栏默认关锁（隐藏）：开锁让图标轨显形 */
  await page.evaluate(() => { try { localStorage.setItem('qb.side.lock', '0'); } catch {} });
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.evaluate(() => { try { localStorage.setItem('qb.side.lock', '0'); } catch {} });
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  /* 侧栏平时藏在 -103%，钉住让它显形再 hover 图标 */
  await page.evaluate(() => document.getElementById('sidebar').classList.add('pinned'));
  await page.waitForTimeout(400);
  const info = await page.evaluate(() => {
    const items = [...document.querySelectorAll('.sidebar .nav-item')];
    return { count: items.length, hasTitle: items.map(i => i.hasAttribute('title')), tips: items.map(i => i.dataset.tip) };
  });
  say('navItems=' + JSON.stringify(info));
  // hover 第二个图标，截 hover 态
  const second = page.locator('.sidebar .nav-item').nth(1);
  await second.hover();
  await page.waitForTimeout(400);
  const vis = await page.evaluate(() => {
    const it = document.querySelectorAll('.sidebar .nav-item')[1];
    const af = getComputedStyle(it, '::after');
    return { opacity: af.opacity, content: af.content, bg: getComputedStyle(it).backgroundColor };
  });
  say('hoverState=' + JSON.stringify(vis));
  await page.screenshot({ path: 'C:/Project/QuestionBank/output/ui-smoke-rail-hover.png' });
  const after2 = await page.evaluate(() => {
    const it = document.querySelectorAll('.sidebar .nav-item')[1];
    const r = it.getBoundingClientRect();
    const af = getComputedStyle(it, '::after');
    return { rect: { x: r.x, y: r.y, w: r.width }, op: af.opacity };
  });
  say('postShot=' + JSON.stringify(after2));
  } catch (e) { say('FATAL ' + (e && e.message)); }
  await browser.close();
})().finally(() => {
  console.log(lines.join('\n'));
  fs.appendFileSync('C:/Project/QuestionBank/output/ui-smoke-subjtree.log', '==== rail hover ' + new Date().toISOString() + ' ====\n' + lines.join('\n') + '\n');
});
