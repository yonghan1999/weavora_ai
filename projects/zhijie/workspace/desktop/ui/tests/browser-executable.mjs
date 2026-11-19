import { accessSync, constants, statSync } from 'node:fs';
import { posix, win32 } from 'node:path';

const candidates = {
  win32: ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'],
  darwin: ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/Applications/Chromium.app/Contents/MacOS/Chromium', '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'],
  linux: ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser', '/snap/bin/chromium', '/usr/bin/microsoft-edge'],
};

function executable(path) {
  try {
    accessSync(path, constants.X_OK);
    return statSync(path).isFile();
  } catch { return false; }
}

export function browserExecutable(platform = process.platform, environment = process.env, available = executable) {
  const override = environment.ZHIJIE_TEST_CHROME;
  if (override !== undefined) {
    if (!(platform === 'win32' ? win32 : posix).isAbsolute(override) || !available(override)) {
      throw new Error('ZHIJIE_TEST_CHROME 必须指向已安装浏览器的可执行文件（绝对路径）');
    }
    return override;
  }
  const browser = candidates[platform]?.find(available);
  if (!browser) throw new Error('未找到用于页面回归的浏览器，请用 ZHIJIE_TEST_CHROME 指定 Chrome、Chromium 或 Edge');
  return browser;
}
