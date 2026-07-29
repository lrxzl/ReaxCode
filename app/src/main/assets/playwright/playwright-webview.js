jt = {};
jt.requestFrontendMethod = async (methodName, ...args) => {
  if (methodName === 'evalOnClient') {
    return eval(args[0]);
  }
  alert(methodName + args)
  let result = await callNative(methodName, args ? args : null);
  return result && result.data !== undefined ? result.data : result;
};
/**
 * Playwright-like WebView Automation Tool
 * 基于 jt 注入对象封装，模拟 Playwright API
 */
class Playwright {
    constructor() {

        if (typeof jt === 'undefined' || !jt.requestFrontendMethod) {
            console.error('环境错误：未检测到注入的 jt 对象。');
        }
    }

    /**
     * 打开一个新页面 (相当于 browser.newPage())
     * TODO: 底层调用了 JavaBridge，如需扩展新标签页行为（如拦截请求、注入额外脚本），请在此处或原生侧补充逻辑
     * @param {string} url 目标 URL
     * @param {string} webViewId 指定的 WebView ID
     * @param {object} options 预留参数，例如 { waitUntil: 'load' }
     * @returns {Promise<Page>}
     */
    async newPage(url, webViewId = `pw_${Date.now()}`, options = {}) {
        // 调用原生方法打开页面，原生会阻塞直到加载完成或30s超时
        await jt.requestFrontendMethod('openOrGetWebViewByUrl', url, webViewId);
        return new Page(webViewId);
    }

    /**
     * 获取当前所有打开的页面
     * @returns {Promise<Page[]>}
     */
    async pages() {
        const listJson = await jt.requestFrontendMethod('getCurrentWebViewListInfos');
        try {
            const list = JSON.parse(listJson);
            return list.map(info => new Page(info.id));
        } catch (e) {
            console.error('解析页面列表失败:', e);
            return [];
        }
    }

    /**
     * 关闭指定的页面
     * @param {string[]} ids WebView ID 数组
     */
    async closeWebViews(ids) {
        await jt.requestFrontendMethod('closeWebViews', JSON.stringify(ids));
    }

    /**
     * 关闭所有页面
     */
    async closeAllPages() {
        await jt.requestFrontendMethod('closeWebViews', 'all');
    }

    /**
     * 在智能体聊天界面（宿主环境）执行 JS
     * @param {string} jsCode
     * @returns {any}
     */
    async evalOnClient(jsCode) {
        return await jt.requestFrontendMethod('evalOnClient', jsCode);
    }
}

/**
 * 模拟 Playwright 的 Page 对象
 */
class Page {
    constructor(webViewId) {
        this.webViewId = webViewId;
    }

    /**
     * 内部方法：在当前 WebView 执行 JS 并尝试解析 JSON
     * @param {string} jsCode
     * @returns {Promise<any>}
     */
    async _evaluate(jsCode) {
        const result = await jt.requestFrontendMethod('runJavaScriptOnWebView', this.webViewId, jsCode);
        // 尝试解析 JSON，如果不是合法 JSON 则返回原字符串
        if (typeof result === 'string') {
            try {
                return JSON.parse(result);
            } catch (e) {
                return result;
            }
        }
        return result;
    }

    /**
     * 导航到新 URL
     * TODO: 底层调用 JavaBridge，如果需要监听页面加载生命周期，需原生侧支持回调
     * @param {string} url
     * @param {object} options
     */
    async goto(url, options = {}) {
        // 复用原生打开页面的逻辑，如果 ID 已存在则是重新加载或跳转
        await jt.requestFrontendMethod('openOrGetWebViewByUrl', url, this.webViewId);
        return this;
    }

    /**
     * 执行 JS 脚本 (类似 Playwright 的 page.evaluate)
     * 支持 page.evaluate(() => document.title) 或 page.evaluate("document.title")
     * @param {function|string} pageFunction
     * @param {any} arg 传递给函数的参数
     */
    async evaluate(pageFunction, arg) {
        let jsCode = '';
        if (typeof pageFunction === 'function') {
            const fnStr = pageFunction.toString();
            jsCode = arg !== undefined
                ? `(${fnStr})(${JSON.stringify(arg)})`
                : `(${fnStr})()`;
        } else {
            jsCode = pageFunction;
        }
        return this._evaluate(jsCode);
    }

    /**
     * 等待选择器出现
     * @param {string} selector CSS 选择器
     * @param {number} timeout 超时时间
     */
    async waitForSelector(selector, timeout = 30000) {
        const start = Date.now();
        while (Date.now() - start < timeout) {
            const exists = await this._evaluate(`!!document.querySelector(${JSON.stringify(selector)})`);
            if (exists) return this;
            await new Promise(r => setTimeout(r, 100)); // 轮询间隔 100ms
        }
        throw new Error(`Timeout ${timeout}ms exceeded waiting for selector "${selector}"`);
    }

    /**
     * 点击元素
     * @param {string} selector
     */
    async click(selector) {
        await this.waitForSelector(selector);
        const jsCode = `
            (() => {
                const el = document.querySelector(${JSON.stringify(selector)});
                if (!el) throw new Error('Element not found: ' + ${JSON.stringify(selector)});
                el.scrollIntoView({block: 'center'});
                el.click();
                return true;
            })()
        `;
        await this._evaluate(jsCode);
        return this;
    }

    /**
     * 填写输入框
     * @param {string} selector
     * @param {string} value
     */
    async fill(selector, value) {
        await this.waitForSelector(selector);
        const jsCode = `
            (() => {
                const el = document.querySelector(${JSON.stringify(selector)});
                if (!el) throw new Error('Element not found: ' + ${JSON.stringify(selector)});
                el.focus();
                el.value = ${JSON.stringify(value)};
                // 触发事件以兼容 React/Vue 等框架
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return true;
            })()
        `;
        await this._evaluate(jsCode);
        return this;
    }

    /**
     * 获取元素文本内容
     * @param {string} selector
     */
    async textContent(selector) {
        await this.waitForSelector(selector);
        return this._evaluate(`document.querySelector(${JSON.stringify(selector)})?.textContent`);
    }

    /**
     * 获取元素内部 HTML
     * @param {string} selector
     */
    async innerHTML(selector) {
        await this.waitForSelector(selector);
        return this._evaluate(`document.querySelector(${JSON.stringify(selector)})?.innerHTML`);
    }

    /**
     * 获取元素属性
     * @param {string} selector
     * @param {string} name
     */
    async getAttribute(selector, name) {
        await this.waitForSelector(selector);
        return this._evaluate(`document.querySelector(${JSON.stringify(selector)})?.getAttribute(${JSON.stringify(name)})`);
    }

    /**
     * 获取页面标题
     */
    async title() {
        return this._evaluate('document.title');
    }

    /**
     * 关闭当前页面
     * TODO: 底层调用 JavaBridge
     */
    async close() {
        await jt.requestFrontendMethod('closeWebViews', JSON.stringify([this.webViewId]));
    }

    /**
     * 将当前 WebView 提到前台显示 (如果原生支持)
     * TODO: 需要原生侧提供切换前台的方法，此处预留
     */
    async bringToFront() {
        // jt.requestFrontendMethod('bringWebViewToFront', this.webViewId);
        console.warn('bringToFront 需要原生侧支持，当前为 TODO 状态');
        return this;
    }
}

// 暴露到全局
window.Playwright = Playwright;
