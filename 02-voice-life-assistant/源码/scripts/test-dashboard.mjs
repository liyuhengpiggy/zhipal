// Browser integration checks use a mock Android bridge. They do not verify BLE or Android services.
// Usage: node scripts/test-dashboard.mjs [absolute path to installed playwright package] [version]
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.argv[2] || 'playwright');
const root = fileURLToPath(new URL('..', import.meta.url));
const version = process.argv[3] || '0.8.0';
assert.match(version, /^\d+\.\d+\.\d+$/);
const evidence = resolve(root, 'release', version, 'evidence');
await mkdir(evidence, { recursive: true });
const browser = await chromium.launch({ headless: true, ...(process.platform === 'win32' ? { channel: 'msedge' } : {}) });
let checks = 0;
const check = (condition, message) => { assert.ok(condition, message); checks++; };
try {
  const page = await browser.newPage({ viewport: { width: 393, height: 852 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.addInitScript(() => {
    window.qaNativeCalls = [];
    window.AndroidApp = { postMessage: (raw) => window.qaNativeCalls.push(JSON.parse(raw)) };
  });
  await page.goto(pathToFileURL(resolve(root, 'app/src/main/assets/voice-life-demo.html')).href);
  await page.waitForFunction(() => typeof window.onNativeMessage === 'function');
  const send = (state) => page.evaluate((value) => window.onNativeMessage({ type: 'state', ...value }), state);
  const called = async (type, predicate = () => true) => {
    const calls = await page.evaluate(() => window.qaNativeCalls);
    check(calls.some((call) => call.type === type && predicate(call)), `Bridge call: ${type}`);
  };
  await called('get_state');
  check(!(await page.locator('.demo-sidebar').isVisible()), 'Native mode hides developer controls');
  check(!(await page.locator('.inspector').isVisible()), 'Native mode hides debugging logs');
  check(!(await page.locator('.workspace-header').isVisible()), 'Native mode hides desktop preview header');
  await send({ connected: true, bleStage: '硬件已就绪 · 可双向收发', backgroundHardwareEnabled: true,
    backgroundHardwareRunning: true, bleAutoReconnect: true, voiceStage: 'receiving',
    voiceProgressText: '正在接收 A 硬件录音', voiceReceivedBytes: 32768, voiceTotalBytes: 65536,
    voiceBytesPerSecond: 8192, voicePercent: 50, logs: ['仅用于测试的联调日志'],
    bleRememberedName: 'ESP32-Korvo', setupComplete: true, missingPermissions: [],
    permissionSummary: '必要权限和系统开关已准备好',
    permissions: { ble: true, call: true, notifications: true, accessibility: true, setupComplete: true, missing: [] } });
  check((await page.locator('[data-permission-recovery]').count()) === 0, 'Ready home does not display a repair warning');
  check((await page.locator('[role="progressbar"]').getAttribute('aria-valuenow')) === '50', 'Receive percentage is displayed');
  check((await page.locator('.transfer-numbers').innerText()).includes('8.0 KB/s'), 'Actual transfer rate is displayed');
  check(!(await page.locator('#appScreen').innerText()).includes('仅用于测试的联调日志'), 'Log is absent from home content');
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Home fits a phone viewport');
  check(await page.locator('#appNav').evaluate((el) => {
    const box = el.getBoundingClientRect();
    return box.top >= 0 && box.bottom <= innerHeight + 1;
  }), 'Navigation is visible without scrolling home');
  await page.screenshot({ path: resolve(evidence, 'dashboard-receiving-mock.png'), fullPage: true });

  await send({ setupComplete: false, missingPermissions: ['通知提醒', '<img id="permissionInjected" src=x>'],
    permissionSummary: '需要设置：通知提醒和无障碍页面帮助' });
  check((await page.locator('[data-permission-recovery]').count()) === 1, 'Missing permissions appear prominently on home');
  check((await page.locator('[data-permission-recovery]').innerText()).includes('通知提醒和无障碍页面帮助'), 'Live permission summary is shown');
  check((await page.locator('[data-permission-recovery]').innerText()).includes('<img id="permissionInjected" src=x>'), 'Missing names are displayed as literal text');
  check((await page.locator('#permissionInjected').count()) === 0, 'Permission names cannot inject HTML');
  await page.locator('[data-action="repair-permissions"]').click();
  await called('repair_permissions');
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Permission repair card fits a phone viewport');
  await page.screenshot({ path: resolve(evidence, 'dashboard-permissions-mock.png'), fullPage: true });
  await send({ setupComplete: true, missingPermissions: [], permissionSummary: '必要权限和系统开关已准备好' });
  check((await page.locator('[data-permission-recovery]').count()) === 0, 'Home clears repaired permissions without a reload');

  const stages = { validating: '正在校验录音', model: '正在准备语音模型', transcribing: '正在转成文字',
    understanding: '正在理解你的指令', executing: '正在处理指令', vision: '正在等待照片或识别答案', waiting_confirm: '等待你的语音确认',
    done: '本次处理已完成', error: '本次处理未完成' };
  for (const [voiceStage, title] of Object.entries(stages)) {
    await send({ voiceStage, voiceProgressText: `检查 ${voiceStage}`, voicePercent: null });
    check((await page.locator('.live-stage h3').innerText()) === title, `Stage ${voiceStage}`);
  }
  await send({ voiceStage: 'done', voiceProgressText: '已请求打开微信', transcriptText: '<打开微信>',
    lastResult: { title: '手机操作', detail: '已请求打开微信', status: 'intent_requested' } });
  check((await page.locator('#appScreen').innerText()).includes('最近识别：<打开微信>'), 'Transcription displayed as text');
  await send({ lastResult: null });
  check((await page.locator('#appScreen').innerText()).includes('还没有处理结果'), 'Explicitly empty result clears stale content');

  await page.locator('#appNav [data-page="settings"]').click();
  check((await page.locator('[data-permission-recovery]').count()) === 1, 'Settings always offers permission inspection');
  check((await page.locator('[data-action="repair-permissions"]').innerText()) === '检查 / 修复权限', 'Ready permissions retain a repair entry');
  await page.locator('[data-action="repair-permissions"]').click();
  await called('repair_permissions');
  await send({ bleDevices: [{ name: 'ESP32-Korvo', address: 'AA:BB:CC:DD:EE:FF' }], bleScanning: false,
    asrReady: true, asrStatus: '离线语音模型已就绪' });
  await page.locator('[data-action="scan-hardware"]').click();
  await called('scanHardware');
  await page.locator('[data-action="connect-hardware"]').click();
  await called('connectHardware', (call) => call.address === 'AA:BB:CC:DD:EE:FF');
  await page.locator('#bleAutoReconnect').uncheck();
  await called('setAutoReconnect', (call) => call.enabled === false);
  await page.locator('[data-action="stop-background-hardware"]').click();
  await called('stopBackgroundHardware');
  await send({ backgroundHardwareEnabled: false, backgroundHardwareRunning: false });
  await page.locator('[data-action="start-background-hardware"]').click();
  await called('startBackgroundHardware');
  await page.locator('[data-action="open-diagnostics"]').click();
  await called('openDiagnostics');
  await page.locator('[data-action="open-background-settings"]').click();
  await called('openBackgroundSettings');
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Settings fits a phone viewport');
  await page.evaluate(() => window.scrollTo(0, 0));
  check(await page.locator('#appNav').evaluate((el) => {
    const box = el.getBoundingClientRect();
    return box.top >= 0 && box.bottom <= innerHeight + 1;
  }), 'Navigation stays visible at the top of long settings');
  await page.screenshot({ path: resolve(evidence, 'dashboard-settings-mock.png'), fullPage: true });

  await page.locator('#appNav [data-page="contacts"]').click();
  await page.locator('#contactName').fill('新的联系人');
  await page.locator('#contactPhone').fill('13800138001');
  await send({ voiceStage: 'receiving', voicePercent: 65 });
  check((await page.locator('#contactName').inputValue()) === '新的联系人', 'Live progress preserves unsaved name');
  check((await page.locator('#contactPhone').inputValue()) === '13800138001', 'Live progress preserves unsaved number');
  check(errors.length === 0, `No browser JavaScript errors: ${errors.join('; ')}`);
  console.log(`PASS: ${checks} dashboard bridge/layout checks (mock Android bridge; no phone/hardware validation).`);
} finally {
  await browser.close();
}
