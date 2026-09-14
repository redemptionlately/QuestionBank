// 第 5 轮 UI 验证：hover 去文字/去蓝框只提亮图标、active 恢复实心蓝块、
// 左抽屉让位 86px、top-hot 避开左缘、dash.read -> 阅读
const { chromium } = require('playwright-core');
const lines = []; const say = (m) => lines.push(m);
let pass = 0, fail = 0;
const step = async (name, fn) => {
  try { await fn(); pass++; say('PASS ' + name); }
  catch (e) { fail++; say('FAIL ' + name + ' :: ' + (e && e.message ? e.message.split('\n')[0] : e)); }
};
(async () => {
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Allen/.agent-browser/browsers/chrome-153.0.8010.36/chrome.exe',
    headless: true, args: ['--no-sandbox', '--disable-gpu'], timeout: 30000,
  });
  const page = await browser.newPage({ viewport: { width: 1500, height: 900 } });
  const errs = [];
  page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message));
  try {
    await page.goto('http://localhost:3000/#/login', { waitUntil: 'domcontentloaded' });
    await page.fill('#lgUser', 'admin'); await page.fill('#lgPass', 'admin123');
    await page.click('#lgPass'); await page.keyboard.press('Enter');
    await page.waitForFunction(() => /#\/(dashboard|welcome|subjects)/.test(location.hash), null, { timeout: 15000 });

    // 1. 图标轨：nav-item 无 title / 无 data-tip / 有 aria-label
    await step('nav-item: 无 title、无 data-tip、有 aria-label（不浮文字）', async () => {
      const r = await page.evaluate(() => {
        const items = [...document.querySelectorAll('.sidebar .nav-item')];
        return items.map(el => ({
          title: el.getAttribute('title'), tip: el.getAttribute('data-tip'),
          aria: el.getAttribute('aria-label'),
        }));
      });
      if (!r.length) throw new Error('no nav-item');
      const bad = r.filter(x => x.title || x.tip);
      if (bad.length) throw new Error('still has title/data-tip: ' + JSON.stringify(bad[0]));
      const noAria = r.filter(x => !x.aria);
      if (noAria.length) throw new Error('missing aria-label on ' + noAria.length + ' items');
      say('INFO nav items=' + r.length + ' first aria=' + r[0].aria);
    });

    // 2. 图标轨 hover：无 ::after 浮签内容
    await step('nav-item hover: 无 ::after 浮签（不显示文字标签）', async () => {
      const r = await page.evaluate(() => {
        const el = document.querySelector('.sidebar .nav-item');
        const cs = getComputedStyle(el, '::after');
        return { content: cs.content, display: cs.display };
      });
      if (r.content && r.content !== 'none' && r.content !== '""' && r.content !== "''") {
        throw new Error('::after still renders label: ' + JSON.stringify(r));
      }
      say('INFO ::after content=' + r.content);
    });

    // 3. 图标轨 hover：底透明（无丑框），图标本体 brightness 提亮
    await step('nav-item hover: 背景透明 + nav-tile 亮度提亮', async () => {
      await page.goto('http://localhost:3000/#/subjects', { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(900);
      await page.evaluate(() => { try { localStorage.setItem('qb.side.lock', '0'); } catch {} });
      // 侧栏默认是「关锁」（html[data-sidelock=1] 强制 translateX(-103%) !important），
      // 必须先开锁再钉住，否则 nav-item 在视口外（x=-60）根本无法 hover
      await page.evaluate(() => {
        document.documentElement.dataset.sidelock = '0';
        try { localStorage.setItem('qb.side.lock', '0'); } catch {}
        document.getElementById('sidebar').classList.add('pinned');
      });
      await page.waitForTimeout(500);
      const box = await page.evaluate(() => {
        const el = document.querySelectorAll('.sidebar .nav-item')[1] || document.querySelector('.sidebar .nav-item');
        const r = el.getBoundingClientRect();
        return { x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width, h: r.height, active: el.classList.contains('active') };
      });
      // 侧栏有 transform 过渡，Playwright 的 actionability（等待元素稳定）会超时，
      // 这里用真实指针移动触发 :hover，绕开稳定性检查
      await page.mouse.move(box.x, box.y);
      await page.waitForTimeout(400);
      say('INFO hover box=' + JSON.stringify(box));
      const r = await page.evaluate(() => {
        const el = document.querySelector('.sidebar .nav-item:hover');
        if (!el) return null;
        const cs = getComputedStyle(el);
        const tile = el.querySelector('.nav-tile');
        return { bg: cs.backgroundColor, shadow: cs.boxShadow, tileFilter: tile ? getComputedStyle(tile).filter : null };
      });
      if (!r) throw new Error('no :hover element captured');
      const transparent = /rgba\(.*,\s*0\)/.test(r.bg) || r.bg === 'transparent';
      if (!transparent) throw new Error('hover bg not transparent: ' + r.bg);
      if (r.shadow && r.shadow !== 'none') throw new Error('hover still has box-shadow: ' + r.shadow);
      if (!r.tileFilter || !/brightness/.test(r.tileFilter)) throw new Error('nav-tile not brightened: ' + r.tileFilter);
      say('INFO bg=' + r.bg + ' filter=' + r.tileFilter);
    });

    // 4. active 恢复实心蓝块
    await step('active nav tile: 恢复实心品牌蓝块', async () => {
      const st = await page.evaluate(() => {
        const t = document.querySelector('.sidebar .nav-item.active .nav-tile');
        return t ? getComputedStyle(t).backgroundColor : null;
      });
      if (!st) throw new Error('no active nav-tile');
      const m = st.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/);
      if (!m) throw new Error('cannot parse: ' + st);
      const [r, g, b] = [+m[1], +m[2], +m[3]];
      const alpha = m[4] === undefined ? 1 : +m[4];
      const solidBlue = b > 150 && b - r > 60 && alpha >= 0.95;
      if (!solidBlue) throw new Error('active tile not solid blue: ' + st);
      say('INFO active tile bg=' + st);
    });

    // 5. 左抽屉让位：指针在左缘唤出 sidepeek 时 .content padding-left = 86px
    await step('sidepeek 唤出: .content padding-left 86px（左抽屉不压正文）', async () => {
      await page.mouse.move(20, 400);          // 左缘召唤区
      await page.waitForTimeout(600);          // 等召唤 + 过渡
      const r = await page.evaluate(() => {
        const c = document.querySelector('.content');
        return { peek: document.body.classList.contains('sidepeek'), pl: c ? getComputedStyle(c).paddingLeft : null };
      });
      if (!r.peek) throw new Error('sidepeek not raised, bodyClass=' + r.peek);
      if (r.pl !== '86px') throw new Error('padding-left=' + r.pl);
      say('INFO sidepeek=' + r.peek + ' content padding-left=' + r.pl);
    });

    // 5b. 指针移到中间：看门狗应强制收回左抽屉
    await step('指针移到中间: 看门狗强制收回 sidepeek', async () => {
      await page.mouse.move(760, 420);
      await page.waitForTimeout(900);
      const r = await page.evaluate(() => ({
        peek: document.body.classList.contains('sidepeek'),
        bridge: document.body.classList.contains('sidebridge'),
      }));
      if (r.peek || r.bridge) throw new Error('left drawer still open: ' + JSON.stringify(r));
      say('INFO ' + JSON.stringify(r));
    });

    // 6. top-hot 避开左缘 86px
    await step('.top-hot left=86px（顶栏与左抽屉不重叠）', async () => {
      const l = await page.evaluate(() => {
        const el = document.querySelector('.top-hot');
        return el ? getComputedStyle(el).left : null;
      });
      if (l !== '86px') throw new Error('top-hot left=' + l);
      say('INFO top-hot left=' + l);
    });

    // 7. dash.read -> 阅读
    await step('工作台按钮文案为「阅读」', async () => {
      await page.goto('http://localhost:3000/#/dashboard', { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1200);
      // 直接问 i18n 模块（书为空时不渲染该按钮，纯 DOM 断言会假阴性）
      const txt = await page.evaluate(async () => {
        const m = await import('/js/i18n.js');
        return m.t('dash.read');
      });
      if (txt !== '阅读') throw new Error('t(dash.read)=' + txt);
      const has = await page.evaluate(() => {
        const btns = [...document.querySelectorAll('button, a')];
        return {
          read: btns.filter(b => (b.textContent || '').trim() === '阅读').length,
          old: btns.filter(b => (b.textContent || '').includes('打开阅读')).length,
        };
      });
      if (has.old > 0) throw new Error('still has 打开阅读 x' + has.old);
      say('INFO t(dash.read)=' + txt + ' / 阅读按钮数=' + has.read);
    });

    // 8. 顶部栏方向性：往上 / 往左上离开不消失
    await step('顶栏：往上 / 往左上离开不消失（仅往下才收）', async () => {
      await page.goto('http://localhost:3000/#/dashboard', { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(800);
      // 先唤起
      await page.mouse.move(700, 10); await page.waitForTimeout(300);
      const on1 = await page.evaluate(() => document.body.classList.contains('topshow'));
      // 往上（y 变小，仍在上边缘内）——保持
      await page.mouse.move(700, 2); await page.waitForTimeout(400);
      const on2 = await page.evaluate(() => document.body.classList.contains('topshow'));
      // 往左上（x 变小到 200 > 86，y 仍在上边缘）
      await page.mouse.move(200, 4); await page.waitForTimeout(400);
      const on3 = await page.evaluate(() => document.body.classList.contains('topshow'));
      // 离开热区往左出界（x < 86 属左侧栏区，豁免）——保持
      await page.mouse.move(40, 6); await page.waitForTimeout(400);
      const on4 = await page.evaluate(() => document.body.classList.contains('topshow'));
      if (!(on1 && on2 && on3 && on4)) throw new Error('topbar hidden on upward/left-up: ' + JSON.stringify({ on1, on2, on3, on4 }));
      say('INFO topshow=' + JSON.stringify({ on1, on2, on3, on4 }));
    });

    // 9. 左下角三钮无原生 title 气泡
    await step('侧栏底部三钮：无 title 属性（不浮原生气泡）', async () => {
      const r = await page.evaluate(() => ['langBtn', 'themeBtn', 'logoutBtn'].map(id => {
        const el = document.getElementById(id);
        return el ? { id, title: el.getAttribute('title'), aria: el.getAttribute('aria-label') } : { id, missing: true };
      }));
      const bad = r.filter(x => x.missing || x.title || !x.aria);
      if (bad.length) throw new Error(JSON.stringify(bad));
      say('INFO ' + JSON.stringify(r));
    });

    say('ERRORS: ' + (errs.length ? errs.join(' | ') : 'none'));
  } catch (e) {
    say('FATAL ' + (e && e.message ? e.message.split('\n')[0] : e));
  }
  await browser.close();
  say('---');
  say('RESULT pass=' + pass + ' fail=' + fail);
  console.log(lines.join('\n'));
  process.exit(fail > 0 ? 1 : 0);
})();
