// 复现：① 顶栏往下离开是否收起 ② 左抽屉/右抽屉是否让位（push）还是覆盖（overlay）
const { chromium } = require('playwright-core');
const out = []; const say = (m) => out.push(m);
(async () => {
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Allen/.agent-browser/browsers/chrome-153.0.8010.36/chrome.exe',
    headless: true, args: ['--no-sandbox', '--disable-gpu'], timeout: 30000,
  });
  const page = await browser.newPage({ viewport: { width: 1500, height: 900 } });
  page.on('pageerror', (e) => say('PAGEERROR ' + e.message));
  await page.goto('http://localhost:3000/#/login', { waitUntil: 'domcontentloaded' });
  await page.fill('#lgUser', 'admin'); await page.fill('#lgPass', 'admin123');
  await page.click('#lgPass'); await page.keyboard.press('Enter');
  await page.waitForFunction(() => /#\/(dashboard|welcome|subjects)/.test(location.hash), null, { timeout: 15000 });

  const snap = async (tag) => {
    const s = await page.evaluate(() => {
      const tb = document.querySelector('.topbar');
      const ct = document.querySelector('.content');
      const sb = document.getElementById('sidebar');
      const rd = document.querySelector('.reader-drawer, [data-drawer], [data-subjdrawer]');
      const tr = document.querySelector('.side-tree');
      const r = (el) => el ? el.getBoundingClientRect().toJSON() : null;
      return {
        body: document.body.className,
        hash: location.hash,
        topbarShown: document.body.classList.contains('topshow'),
        topbarY: tb ? r(tb).y : null,
        topbarH: tb ? r(tb).height : null,
        contentPL: ct ? getComputedStyle(ct).paddingLeft : null,
        contentPR: ct ? getComputedStyle(ct).paddingRight : null,
        sidebarX: sb ? r(sb).x : null,
        sidebarVis: sb ? getComputedStyle(sb).visibility : null,
        treeX: tr ? r(tr).x : null, treeW: tr ? r(tr).width : null,
        drawerRight: rd ? r(rd).right : null,
        drawerW: rd ? r(rd).width : null,
        vw: innerWidth,
      };
    });
    say(tag + ' ' + JSON.stringify(s));
    return s;
  };

  // 进科目页并打开一本书（阅读器场景）
  const subjId = await page.evaluate(async () => {
    const t = JSON.parse(localStorage.getItem('qb.session') || sessionStorage.getItem('qb.session') || 'null');
    const r = await fetch('/api/subjects', { headers: { Authorization: 'Bearer ' + (t && (t.token || t.accessToken)) } });
    const list = await r.json();
    const s = (list || []).find(x => /数据结构/.test(x.name || '')) || (list || [])[0];
    return s ? s.id : null;
  });
  say('subjId=' + subjId);
  await page.goto('http://localhost:3000/#/subjects/' + subjId);
  await page.waitForTimeout(1500);
  await snap('A 进入科目页');

  // 打开一本书
  try {
    await page.waitForSelector('[data-act="read"]', { timeout: 10000, state: 'attached' });
    await page.evaluate(() => document.querySelector('[data-act="read"]').click());
    await page.waitForTimeout(2500);
  } catch (e) { say('open book failed: ' + e.message.split('\n')[0]); }
  await snap('B 打开书');

  // ① 顶栏：靠近顶部唤出 → 往下离开 → 应收起
  await page.mouse.move(800, 8); await page.waitForTimeout(400);
  await snap('C1 指针到顶部');
  await page.mouse.move(800, 300, { steps: 12 }); await page.waitForTimeout(600);
  await snap('C2 往下离开(300)');
  await page.mouse.move(800, 700, { steps: 12 }); await page.waitForTimeout(900);
  await snap('C3 往下离开(700)');

  // ② 左抽屉：左缘唤出 → 内容是否让位
  await page.mouse.move(10, 400); await page.waitForTimeout(700);
  await snap('D 左缘唤出');
  await page.mouse.move(900, 500, { steps: 10 }); await page.waitForTimeout(1000);
  await snap('E 移到中间');

  // ③ 右抽屉：打开（不固定）→ 是否覆盖正文
  await page.evaluate(() => {
    const d = document.querySelector('[data-drawer], [data-subjdrawer], .reader-drawer');
    if (d) d.classList.add('force-open');
  });
  await page.waitForTimeout(700);
  await snap('F 右抽屉打开(未固定)');

  // ④ 右抽屉固定
  try {
    await page.evaluate(() => {
      const b = document.querySelector('[data-rdrpin]');
      if (b) b.click();
    });
    await page.waitForTimeout(800);
    await snap('G 右抽屉固定');
  } catch (e) { say('pin failed ' + e.message.split('\n')[0]); }

  await browser.close();
  console.log(out.join('\n'));
})();
