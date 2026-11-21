// Преобразование SVG-диаграмм в PNG с помощью установленного Chromium (Playwright).
// Использование: node render.js <каталог с .svg> <каталог для .png>
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const [src, dst] = process.argv.slice(2);
  fs.mkdirSync(dst, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({ deviceScaleFactor: 2 });
  const page = await context.newPage();
  for (const name of fs.readdirSync(src).filter((f) => f.endsWith('.svg')).sort()) {
    const svg = fs.readFileSync(path.join(src, name), 'utf8');
    const size = /width="(\d+)" height="(\d+)"/.exec(svg);
    await page.setViewportSize({ width: Number(size[1]), height: Number(size[2]) });
    await page.setContent(`<html><body style="margin:0;background:#fff">${svg}</body></html>`);
    const out = path.join(dst, name.replace(/\.svg$/, '.png'));
    await page.screenshot({ path: out });
    console.log(out);
  }
  await browser.close();
})();
