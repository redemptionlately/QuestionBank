/* QB 科目页改动冒烟：左树/右抽屉行为 + 选中样式统一 + 背诵/答题目录联动 */
const { chromium } = require('playwright-core');
const fs = require('fs');
const LOG = 'C:/Project/QuestionBank/output/ui-smoke-subjtree.log';
const SHOT = 'C:/Project/QuestionBank/output';
const lines = [];
const say = (m) => { lines.push(m); };

(async () => {
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Allen/.agent-browser/browsers/chrome-153.0.8010.36/chrome.exe',
    headless: true, args: ['--no-sandbox', '--disable-gpu'], timeout: 30000,
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  let errs = [];
  page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errs.push('CONSOLE: ' + m.text()); });

  const step = async (name, fn) => {
    try { await fn(); say('PASS ' + name); }
    catch (e) { say('FAIL ' + name + ' :: ' + (e && e.message ? e.message.split('\n')[0] : e)); }
  };

  // 登录（admin）
  await page.goto('http://localhost:3000/#/login', { waitUntil: 'domcontentloaded' });
  await page.fill('#lgUser', 'admin');
  await page.fill('#lgPass', 'admin123');
  await page.click('#lgPass');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => /#\/(dashboard|welcome|subjects)/.test(location.hash), null, { timeout: 15000 });

  // 找“数据结构与算法”科目 id
  const subjId = await page.evaluate(async () => {
    const t = JSON.parse(localStorage.getItem('qb.session') || sessionStorage.getItem('qb.session') || 'null');
    const token = t && (t.token || t.accessToken);
    const r = await fetch('/api/subjects', { headers: { Authorization: 'Bearer ' + token } });
    const list = await r.json();
    const s = (list || []).find(x => /数据结构/.test(x.name || ''));
    return s ? s.id : (list && list[0] && list[0].id);
  });
  say('INFO subjectId=' + subjId);
  errs.length = 0;

  await page.goto('http://localhost:3000/#/subjects/' + subjId);
  /* 桌面端左树默认被 🔒 收起（data-sidelock=1）：解锁 = 展开目录（与应用交互一致） */
  await page.evaluate(() => { document.documentElement.dataset.sidelock = '0'; });
  await page.waitForSelector('.side-tree .tree-row', { timeout: 15000 });
  await page.waitForFunction(() => {
    const r = document.querySelector('.side-tree .tree-row');
    return r && r.offsetParent !== null;
  }, null, { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(800);

  // 1. 顶行科目名：左树保留（悬停下拉切科），右抽屉不重复大标题
  await step('top subject row kept in left tree with hover switch', async () => {
    const n = await page.evaluate(() => document.querySelectorAll('.side-tree .tree-line[data-line="__top"]').length);
    if (n !== 1) throw new Error('__top rows=' + n);
    const chips = await page.evaluate(() => document.querySelectorAll('.side-tree .course-chip').length);
    if (chips < 1) throw new Error('no course-chip switch entries');
  });
  await step('drawer has no big title head', async () => {
    const st = await page.evaluate(() => {
      const dw = document.querySelector('[data-subjdrawer]');
      return { head: dw.querySelectorAll('.rdr-head').length, topRow: dw.querySelectorAll('.tree-line[data-line="__top"]').length };
    });
    if (st.head !== 0) throw new Error('drawer still has .rdr-head big title');
    if (st.topRow !== 1) throw new Error('drawer __top row=' + st.topRow);
  });
  await step('hovering __top shows subject switch dropdown', async () => {
    await page.hover('.side-tree .tree-line[data-line="__top"] .tree-row');
    await page.waitForTimeout(250);
    const vis = await page.evaluate(() => {
      const sw = document.querySelector('.side-tree .sub-switch');
      return sw && getComputedStyle(sw).display !== 'none';
    });
    if (!vis) throw new Error('sub-switch not visible on hover');
  });
  await step('rename toolbar pen removed', async () => {
    const n = await page.evaluate(() => document.querySelectorAll('#renameModeBtn, .side-tree .tree-op[data-cren], .side-tree .tree-op[data-renbook]').length);
    if (n !== 0) throw new Error('pens=' + n);
  });
  // 1b. 左/右 hover 一致：面板同底色，行 hover 同规则
  await step('left panel matches drawer surface (hover looks same)', async () => {
    const st = await page.evaluate(() => {
      const side = getComputedStyle(document.querySelector('.side-tree'));
      const dw = getComputedStyle(document.querySelector('[data-subjdrawer]'));
      return { sideBg: side.backgroundColor, dwBg: dw.backgroundColor };
    });
    if (st.sideBg !== st.dwBg) throw new Error(`side=${st.sideBg} drawer=${st.dwBg}`);
  });
  // 1c. 右抽屉缩进：多级箭头逐级右移（跟左树一致），不再挤成一列
  await step('drawer nested arrows indent per level', async () => {
    const st = await page.evaluate(() => {
      const dw = document.querySelector('[data-subjdrawerbody]');
      const tws = [...dw.querySelectorAll('.tree-twisty[data-tw]')].filter(t => t.offsetParent !== null);
      if (tws.length < 2) return { skip: true };
      const xs = tws.map(t => Math.round(t.getBoundingClientRect().left));
      return { min: Math.min(...xs), max: Math.max(...xs) };
    });
    if (st.skip) { say('INFO drawer has <2 twisties, indent check skipped'); return; }
    if (st.max === st.min) throw new Error('all drawer arrows at x=' + st.min + ' (no per-level indent)');
    say('INFO drawer arrow x range=' + st.min + '..' + st.max);
  });

  // 2. 左树文件夹：点击只折叠/展开，不产生选中
  await step('left folder click toggles collapse, no selection', async () => {
    const row = page.locator('.side-tree .tree-row[data-cat]').first();
    const line = page.locator('.side-tree .tree-line').first();
    const twBefore = await line.locator('.tree-twisty').count();
    const hiddenBefore = await page.evaluate(() => [...document.querySelectorAll('.side-tree .tree-line')].filter(x => x.hidden && x.querySelector('.tree-row[data-book],.tree-row[data-paper]')).length);
    await row.click();
    await page.waitForTimeout(250);
    const st = await page.evaluate(() => {
      const tw = document.querySelector('.side-tree .tree-twisty[data-tw]');
      const onRows = [...document.querySelectorAll('.side-tree .tree-row.on')].filter(x => x.dataset.cat !== undefined).length;
      const leaves = [...document.querySelectorAll('.side-tree .tree-line')].filter(x => x.hidden && x.querySelector('.tree-row[data-book],.tree-row[data-paper]')).length;
      return { twClosed: tw ? tw.classList.contains('closed') : null, onCatRows: onRows, leaves };
    });
    if (twBefore > 0 && st.twClosed == null) throw new Error('no twisty state');
    if (st.onCatRows !== 0) throw new Error('folder got selected (.on=' + st.onCatRows + ')');
    if (twBefore > 0 && st.leaves === hiddenBefore) throw new Error('collapse did not change leaf visibility');
    // 再点一次恢复
    await row.click(); await page.waitForTimeout(200);
  });

  // 3. 右抽屉：先打开（右缘热区 hover / rail 点击）
  await step('right drawer opens with tree', async () => {
    await page.hover('body', { position: { x: 1435, y: 400 } });
    await page.waitForTimeout(400);
    const vis = await page.evaluate(() => {
      const dw = document.querySelector('[data-subjdrawer]');
      return dw && dw.classList.contains('force-open');
    });
    if (!vis) { // 兜底：rail 点击
      await page.evaluate(() => document.getElementById('rdrRail') && document.getElementById('rdrRail').click());
      await page.waitForTimeout(300);
    }
    const vis2 = await page.evaluate(() => !!document.querySelector('[data-subjdrawer].force-open'));
    if (!vis2) throw new Error('drawer not force-open');
  });

  // 4. 右抽屉点书行 → 弹改名框，且不打开阅读器
  await step('drawer book row click opens rename modal', async () => {
    const info = await page.evaluate(() => {
      const dw = document.querySelector('[data-subjdrawer].force-open') || document.querySelector('[data-subjdrawer]');
      const rows = [...dw.querySelectorAll('.tree-row[data-book]')];
      const vis = rows.find(r => r.offsetParent !== null);
      return vis ? vis.dataset.book : null;
    });
    if (!info) throw new Error('no visible book row in drawer');
    await page.evaluate((bid) => {
      const dw = document.querySelector('[data-subjdrawer]');
      const row = [...dw.querySelectorAll(`.tree-row[data-book="${bid}"]`)].find(r => r.offsetParent !== null);
      row.click();
    }, info);
    await page.waitForSelector('.modal-scrim #renInput', { timeout: 6000 });
    const opened = await page.evaluate(() => document.querySelectorAll('.book-reader:not([hidden])').length);
    if (opened !== 0) throw new Error('reader opened instead of rename');
    // 取消关掉
    await page.evaluate(() => {
      const btn = [...document.querySelectorAll('.modal-scrim button')].find(b => b.textContent.includes('取消'));
      if (btn) btn.click();
    });
    await page.waitForTimeout(200);
  });

  // 5. 右抽屉箭头独立可折叠（多级箭头跟左树一致联动）
  await step('drawer twisty mirrors left collapse', async () => {
    const st = await page.evaluate(() => {
      const dw = document.querySelector('[data-subjdrawer]');
      const tws = [...dw.querySelectorAll('.tree-twisty[data-tw]')].filter(t => t.offsetParent !== null);
      if (!tws.length) return { skip: true };
      const id = tws[0].dataset.tw;
      const lt = document.querySelector(`.side-tree .tree-twisty[data-tw="${id}"]`);
      const before = lt.classList.contains('closed');
      tws[0].click();
      return { id, before };
    });
    if (st.skip) { say('INFO no visible drawer twisty, skip'); return; }
    await page.waitForTimeout(300);
    const after = await page.evaluate((id) => {
      const lt = document.querySelector(`.side-tree .tree-twisty[data-tw="${id}"]`);
      return lt.classList.contains('closed');
    }, st.id);
    if (after === st.before) throw new Error('drawer twisty did not toggle left (before=' + st.before + ' after=' + after + ')');
    // 还原
    await page.evaluate((id) => document.querySelector(`.side-tree .tree-twisty[data-tw="${id}"]`).click(), st.id);
    await page.waitForTimeout(200);
  });

  // 6. 左树点书 → 打开阅读器 + 书行高亮（胶囊样式统一：无 inset 指示条）
  await step('left book click opens reader, tree-row.on pill style', async () => {
    await page.evaluate(() => {
      const row = [...document.querySelectorAll('.side-tree .tree-row[data-book]')].find(r => r.offsetParent !== null);
      row.click();
    });
    await page.waitForSelector('.book-reader:not([hidden]) .reader-layout', { timeout: 20000 });
    await page.waitForTimeout(1200);
    const st = await page.evaluate(() => {
      const on = [...document.querySelectorAll('.side-tree .tree-row.on')];
      const cs = on.length ? getComputedStyle(on[0]) : null;
      return { onCount: on.length, shadow: cs ? cs.boxShadow : '', bg: cs ? cs.backgroundColor : '' };
    });
    if (st.onCount < 1) throw new Error('no .on book row after open');
    if (/inset/.test(st.shadow)) throw new Error('.on still uses inset indicator: ' + st.shadow);
  });

  await page.screenshot({ path: SHOT + '/ui-smoke-subj-reader.png', fullPage: false });

  // 7. 背诵书（若有）：背诵模式点目录→答案展开；答题模式点目录→不展开
  const hasRecite = await page.evaluate(() => !!document.querySelector('.book-reader:not([hidden]) [data-pane="recite"]'));
  say('INFO recite pane=' + hasRecite);
  if (hasRecite) {
    await step('recite mode: outline click expands answer', async () => {
      await page.evaluate(() => {
        const dw = document.querySelector('.book-reader:not([hidden]) [data-drawer]');
        dw.classList.add('force-open');
        const row = [...dw.querySelectorAll('.rtoc-row[data-o]')].find(r => r.offsetParent !== null);
        row.dataset.o && row.click();
      });
      await page.waitForTimeout(600);
      const open = await page.evaluate(() => {
        const c = document.querySelector('.book-reader:not([hidden]) .kp-card[open]');
        return !!c;
      });
      if (!open) throw new Error('recite mode: card not open after outline click');
    });
    await step('exam mode: outline click keeps answers collapsed', async () => {
      await page.evaluate(() => {
        const r = document.querySelector('.book-reader:not([hidden]) [data-readmode] button[data-rm="exam"]');
        r.click();
      });
      await page.waitForTimeout(300);
      await page.evaluate(() => {
        const dw = document.querySelector('.book-reader:not([hidden]) [data-drawer]');
        const row = [...dw.querySelectorAll('.rtoc-row[data-o]')].find(r => r.offsetParent !== null);
        row.click();
      });
      await page.waitForTimeout(600);
      const st = await page.evaluate(() => {
        const pane = document.querySelector('.book-reader:not([hidden]) [data-pane="recite"]');
        const open = pane.querySelectorAll('.kp-card[open]').length;
        return open;
      });
      if (st !== 0) throw new Error('exam mode: ' + st + ' cards auto-expanded on outline click');
    });
    await page.screenshot({ path: SHOT + '/ui-smoke-subj-exammode.png', fullPage: false });
    // 掌握度三态 hover：出现对应颜色底（不能是灰框盖掉本色）
    await step('mastery button hover keeps level color bg', async () => {
      const st = await page.evaluate(() => {
        const r = document.querySelector('.book-reader:not([hidden])');
        const btn = r.querySelector('.bseg button[data-lv="PROFICIENT"]');
        return btn ? btn.className : null;
      });
      if (!st) { say('INFO no bseg button visible, skip'); return; }
      await page.hover('.book-reader:not([hidden]) .bseg button[data-lv="PROFICIENT"]');
      await page.waitForTimeout(150);
      const bg = await page.evaluate(() => getComputedStyle(document.querySelector('.book-reader:not([hidden]) .bseg button[data-lv="PROFICIENT"]')).backgroundColor);
      // ok-bg 期望绿色系：g 分量显著高于 b
      const m = bg.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
      if (!m) throw new Error('bad bg ' + bg);
      const [, rr, gg, bb] = m.map(Number);
      if (!(gg > bb + 20 && gg >= rr)) throw new Error('PROFICIENT hover bg not green: ' + bg);
    });
  }

  // 打开书状态下，右缘热区应弹「书大纲」抽屉而非文件夹树（跳转逻辑不变）
  await step('book outline drawer rows jump (click scrolls, no rename modal)', async () => {
    const beforeModal = await page.evaluate(() => document.querySelectorAll('.modal-scrim').length);
    await page.evaluate(() => {
      const dw = document.querySelector('.book-reader:not([hidden]) [data-drawer]');
      dw.classList.add('force-open');
      const row = [...dw.querySelectorAll('.rtoc-row')].find(r => r.offsetParent !== null);
      if (row) row.click();
    });
    await page.waitForTimeout(400);
    const afterModal = await page.evaluate(() => document.querySelectorAll('.modal-scrim').length);
    if (afterModal > beforeModal) throw new Error('outline click triggered rename modal!');
  });

  say('INFO pageerrors=' + JSON.stringify(errs.slice(0, 8)));
  await browser.close();
})().catch(async (e) => {
  say('FATAL ' + (e && e.stack ? e.stack.split('\n').slice(0, 4).join(' | ') : e));
  try { require('playwright-core'); } catch {}
  process.exitCode = 1;
}).finally(() => {
  fs.appendFileSync(LOG, '==== run ' + new Date().toISOString() + ' ====\n' + lines.join('\n') + '\n');
  console.log(lines.join('\n'));
});
