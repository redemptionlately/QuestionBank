/* 浏览器冒烟测试：playwright-core + Chrome 153（headless）
 * 覆盖：登录 → 主界面 → 试卷 → 学生答题一页流 → 提交 → 成绩环
 * 产物：output/ui_smoke.log（文本断言）+ output/ui_*.png（截图，供人工查看）
 */
const path = require('path');
const fs = require('fs');

const ROOT = String.raw`C:\Users\Allen\.workbuddy\binaries\node\workspace`;
const CHROME = String.raw`C:\Users\Allen\.agent-browser\browsers\chrome-153.0.8010.36\chrome.exe`;
const { chromium } = require(path.join(ROOT, 'node_modules', 'playwright-core'));

const BASE = 'http://localhost:3000';
const OUT = String.raw`C:\Project\QuestionBank\output`;
const lines = [];
const log = (s) => { lines.push(s); console.log(s); };

(async () => {
  const browser = await chromium.launch({
    executablePath: CHROME,
    headless: true,
    timeout: 30000,
    args: ['--no-sandbox', '--disable-gpu'],
  });
  const page = await browser.newPage({ viewport: { width: 1360, height: 860 } });
  const consoleErrors = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('PAGEERROR: ' + e.message));

  const step = async (name, fn) => {
    try { await fn(); log(`PASS ${name}`); }
    catch (e) { log(`FAIL ${name}: ${e.message.split('\n')[0]}`); }
  };

  await step('open login page', async () => {
    await page.goto(BASE, { waitUntil: 'load', timeout: 15000 });
    await page.waitForSelector('.login-card', { timeout: 8000 });
  });

  await step('admin login renders shell', async () => {
    await page.fill('#lgUser', 'admin');
    await page.fill('#lgPass', 'admin123');
    await page.click('#loginBtn');
    await page.waitForSelector('.sidebar .nav-item', { timeout: 8000 });
    await page.waitForSelector('.user-chip', { timeout: 4000 });
  });
  await page.screenshot({ path: path.join(OUT, 'ui_admin_home.png') });

  await step('admin sees bank menu, opens papers', async () => {
    const texts = await page.$$eval('.nav-item', els => els.map(e => e.textContent));
    if (!texts.some(t => t.includes('题库与出卷'))) throw new Error('menu missing: ' + texts.join('|'));
    await page.click('.nav-item[data-path="/papers"]');
    await page.waitForSelector('.paper-card', { timeout: 8000 });
  });
  await page.screenshot({ path: path.join(OUT, 'ui_papers.png') });

  await step('paper detail modal shows questions', async () => {
    await page.click('.paper-card');
    await page.waitForSelector('.modal .q-editor', { timeout: 8000 });
    await page.click('.modal-foot .btn'); /* 点「关闭」按钮（Modal 无 Escape 快捷键） */
    await page.waitForSelector('.modal', { state: 'detached', timeout: 4000 });
  });

  await step('logout to login page', async () => {
    await page.click('#logoutBtn');
    await page.waitForSelector('.modal-foot .btn-primary', { timeout: 4000 });
    await page.click('.modal-foot .btn-primary');
    await page.waitForSelector('.login-card', { timeout: 6000 });
  });

  await step('student login + start exam flow', async () => {
    await page.fill('#lgUser', 'student');
    await page.fill('#lgPass', 'student123');
    await page.click('#loginBtn');
    await page.waitForSelector('.nav-item[data-path="/papers"]', { timeout: 8000 });
    await page.click('.nav-item[data-path="/papers"]');
    await page.waitForSelector('.paper-card', { timeout: 8000 });
    /* 点第一张试卷卡片上的"开始练习"按钮 */
    await page.click('.paper-card [data-act="open"]');
    await page.waitForSelector('.modal', { timeout: 6000 });
    const startBtn = await page.$('.modal-foot .btn-primary');
    if (!startBtn) throw new Error('no start-practice button (role mismatch?)');
    await startBtn.click();
    await page.waitForSelector('.exam-toolbar', { timeout: 10000 });
  });
  await page.screenshot({ path: path.join(OUT, 'ui_exam.png') });

  await step('answer all questions with autosave', async () => {
    const count = await page.$$eval('.q-card', els => els.length);
    if (!count) throw new Error('no question cards');
    /* 直接 DOM click，绕开遮挡判定 */
    for (let i = 0; i < count; i++) {
      await page.evaluate((idx) => {
        const card = document.querySelectorAll('.q-card')[idx];
        card.querySelector('.opt').click();
        card.scrollIntoView({ block: 'center' });
      }, i);
      await page.waitForTimeout(200);
    }
    /* 等防抖保存完成 */
    await page.waitForFunction(
      () => document.querySelector('#saveState')?.textContent?.includes('已保存'),
      { timeout: 8000 }
    );
  });

  await step('submit and render score ring', async () => {
    /* 诊断：谁在 submitBtn 中心点上 */
    const blocker = await page.evaluate(() => {
      const b = document.querySelector('#submitBtn');
      if (!b) return 'NO BUTTON';
      const r = b.getBoundingClientRect();
      const top = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
      return top === b || b.contains(top) ? 'CLEAR' : (top ? top.className || top.tagName : 'null');
    });
    log(`  submitBtn occlusion: ${blocker}`);
    await page.evaluate(() => document.querySelector('#submitBtn').click());
    await page.waitForSelector('.modal-foot .btn-primary', { timeout: 6000 });
    await page.click('.modal-foot .btn-primary');
    await page.waitForSelector('.score-hero', { timeout: 10000 });
    await page.waitForSelector('.score-ring-label', { timeout: 4000 });
    const label = await page.textContent('.score-ring-label');
    log(`  score ring shows: ${label.trim()}`);
  });
  await page.screenshot({ path: path.join(OUT, 'ui_result.png') });

  await step('wrong-question page renders stats', async () => {
    await page.evaluate(() => document.querySelector('.nav-item[data-path="/wrong"]').click());
    await page.waitForSelector('.stat', { timeout: 8000 });
  });
  await page.screenshot({ path: path.join(OUT, 'ui_wrong.png') });

  await step('theme toggle flips data-theme', async () => {
    const before = await page.evaluate(() => document.documentElement.dataset.theme);
    await page.evaluate(() => document.querySelector('#themeBtn').click());
    const after = await page.evaluate(() => document.documentElement.dataset.theme);
    if (before === after) throw new Error(`theme unchanged: ${before}`);
  });

  log(`console errors: ${consoleErrors.length ? consoleErrors.join(' | ') : 'NONE'}`);
  fs.writeFileSync(path.join(OUT, 'ui_smoke.log'), lines.join('\n') + '\n');
  await browser.close();
  process.exit(0);
})().catch(async (e) => {
  log('FATAL: ' + (e.message || e));
  fs.writeFileSync(path.join(OUT, 'ui_smoke.log'), lines.join('\n') + '\n');
  process.exit(1);
});
