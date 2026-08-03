/*file: ./nodeTools.js*/
const fs = require('fs/promises');
const path = require('path');
const { exec } = require('child_process');

// ==================== NodeTools 主类 ====================
class NodeTools {

    constructor({ historyEdit } = {}) {
        this.MAX_LIST_FILES_COUNT = 10000;
        this.MAX_LIST_FILES_DEPTH = 20;
        this.MAX_READ_LINES_COUNT = 20000;
        this.MAX_WRITE_FILE_SIZE_BYTES = 50 * 1024 * 1024;
        this.MAX_APPEND_FILE_SIZE_BYTES = 50 * 1024 * 1024;
        this.MAX_HISTORY = 20;
        this.historyEdit = Array.isArray(historyEdit) ? historyEdit : [];
    }

    // ==================== 参数校验辅助方法 ====================
    _requireString(value, paramName, methodName) {
        if (value === null || value === undefined || typeof value !== 'string' || value === '') {
            const actualType = value === null ? 'null' : value === undefined ? 'undefined' : typeof value;
            throw new Error(`参数错误 [${methodName}]: 参数 "${paramName}" 必须是非空字符串，但传入值为 ${JSON.stringify(value)} (类型: ${actualType})。`);
        }
    }

    _requireOptionalString(value, paramName, methodName) {
        if (value != null && (typeof value !== 'string')) {
            throw new Error(`参数错误 [${methodName}]: 参数 "${paramName}" 必须是字符串或 null，但传入值为 ${JSON.stringify(value)} (类型: ${typeof value})。`);
        }
    }

    _requirePositiveInt(value, paramName, methodName) {
        if (value === null || value === undefined || !Number.isInteger(value) || value < 1) {
            const actualType = value === null ? 'null' : value === undefined ? 'undefined' : typeof value;
            throw new Error(`参数错误 [${methodName}]: 参数 "${paramName}" 必须是正整数(>=1)，但传入值为 ${JSON.stringify(value)} (类型: ${actualType})。`);
        }
    }

    _requireNonNegativeInt(value, paramName, methodName) {
        if (value === null || value === undefined || !Number.isInteger(value) || value < 0) {
            const actualType = value === null ? 'null' : value === undefined ? 'undefined' : typeof value;
            throw new Error(`参数错误 [${methodName}]: 参数 "${paramName}" 必须是非负整数(>=0)，但传入值为 ${JSON.stringify(value)} (类型: ${actualType})。`);
        }
    }

    // ==================== 通用辅助方法 ====================
    _truncate(s, maxLen) {
        if (s == null) return "null";
        if (s.length <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    _byteLength(str) {
        if (str == null) return 0;
        return Buffer.byteLength(str, 'utf-8');
    }

    _getLogicalLines(content) {
        if (!content) return [];
        const normalized = content.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
        let lines = normalized.split('\n');
        if (lines.length > 0 && lines[lines.length - 1] === '') lines.pop();
        return lines;
    }

    _countLines(content) {
        return this._getLogicalLines(content).length;
    }

    // ==================== 原生文件系统辅助方法 ====================
    async _fileExists(filePath) {
        try {
            await fs.access(filePath);
            return true;
        } catch (e) {
            return false;
        }
    }

    async _isDirectory(dirPath) {
        try {
            const stat = await fs.stat(dirPath);
            return stat.isDirectory();
        } catch (e) {
            return false;
        }
    }

    /**
     * 【新增核心方法】智能读取文件内容，自动处理 UTF-8 和 GBK 乱码
     * 原理：先按 UTF-8 解码，如果出现替换字符 \uFFFD，说明不是合法 UTF-8，回退尝试 GBK 解码。
     */
    async _readFileContent(filePath) {
        const buffer = await fs.readFile(filePath);

        // 尝试 UTF-8 解码
        let utf8Content = buffer.toString('utf-8');

        // 检查是否含有替换字符 \uFFFD，如果有则极大概率是 GBK 等非 UTF-8 编码
        if (utf8Content.includes('\uFFFD')) {
            try {
                // Node.js 环境全局支持 TextDecoder，内置 ICU 支持 GBK
                const decoder = new TextDecoder('gbk');
                const gbkContent = decoder.decode(buffer);

                // 如果 GBK 解码后不再包含替换字符，则认为它是 GBK 编码
                if (!gbkContent.includes('\uFFFD')) {
                    return gbkContent;
                }
            } catch (e) {
                // 如果当前 Node.js 环境不支持 GBK 解码，只能退回返回 UTF-8 (带乱码)
            }
        }

        return utf8Content;
    }

    async _fileLineCount(filePath) {
        try {
            const content = await this._readFileContent(filePath);
            return this._getLogicalLines(content).length;
        } catch (e) {
            try {
                const buffer = await fs.readFile(filePath);
                if (!buffer || buffer.length === 0) return 0;
                let count = 0;
                for (let i = 0; i < buffer.length; i++) if (buffer[i] === 0x0A) count++;
                if (buffer[buffer.length - 1] !== 0x0A) count++;
                return count;
            } catch (err) {
                return 0;
            }
        }
    }

    async _ensureFileExists(filePath) {
        const dir = path.dirname(filePath);
        await fs.mkdir(dir, { recursive: true }).catch(() => {});
        const exists = await this._fileExists(filePath);
        if (!exists) await fs.writeFile(filePath, '', 'utf-8');
    }

    // ==================== 行文本校验核心 ====================
    async _verifyLineByText(filePath, text, expectedRow) {
        const content = await this._readFileContent(filePath);
        const lines = this._getLogicalLines(content);
        const totalLines = lines.length;
        let actualContentAtRow = "";

        if (expectedRow != null && Number.isInteger(expectedRow) && expectedRow >= 1 && expectedRow <= totalLines) {
            const lineContent = lines[expectedRow - 1];
            if (lineContent != null) {
                actualContentAtRow = this._truncate(lineContent, 200);
                if (lineContent.includes(text)) return expectedRow; // 校验通过
            }
        }

        const matchedRows = [];
        for (let i = 0; i < totalLines; i++) {
            if (lines[i].includes(text)) matchedRows.push(i + 1);
        }

        throw new RowCheckFailedException(filePath, expectedRow, this._truncate(text, 100), actualContentAtRow, matchedRows);
    }

    // ==================== 撤销机制核心 ====================
    async _backupFileBeforeEdit(filePath) {
        const exists = await this._fileExists(filePath);
        let backupPath = null;

        if (exists) {
            const dir = path.dirname(filePath);
            backupPath = path.join(dir, `.jbridge_undo_${Date.now()}_${Math.floor(Math.random() * 1000000)}`);
            await fs.copyFile(filePath, backupPath);
        }

        this.historyEdit.push({ filePath, backupPath, existedBefore: exists });

        if (this.historyEdit.length > this.MAX_HISTORY) {
            const removed = this.historyEdit.shift();
            if (removed.backupPath) {
                try { await fs.unlink(removed.backupPath); } catch (e) {}
            }
        }
    }

    async undoEdit() {
        if (this.historyEdit.length === 0) {
            throw new Error("撤销失败: 历史记录为空，没有可撤销的操作。");
        }

        const lastEdit = this.historyEdit.pop();
        const { filePath, backupPath, existedBefore } = lastEdit;

        try {
            if (!existedBefore) {
                try { await fs.unlink(filePath); } catch (e) {}
            } else {
                await fs.rename(backupPath, filePath);
            }
            return `成功撤销对文件 ${filePath} 的最近一次修改。`;
        } catch (e) {
            this.historyEdit.push(lastEdit);
            throw new Error(`撤销文件 ${filePath} 失败: ${e.message}`);
        }
    }

    // ==================== 核心写入操作（内部方法） ====================
    async _doReplaceLines(filePath, startRow, endRow, newContent) {
        const content = await this._readFileContent(filePath);
        const lines = this._getLogicalLines(content);
        const newLines = this._getLogicalLines(newContent);

        const before = lines.slice(0, startRow - 1);
        const after = lines.slice(endRow);
        const result = [...before, ...newLines, ...after];

        const outputTmpPath = filePath + ".jbridge_tmp_" + Date.now() + "_" + Math.floor(Math.random() * 1000000);
        try {
            await fs.writeFile(outputTmpPath, result.join('\n') + '\n', 'utf-8');
            await fs.rename(outputTmpPath, filePath);
        } catch (e) {
            try { await fs.unlink(outputTmpPath); } catch (_) {}
            throw e;
        }
    }

    async _doInsertAfter(filePath, afterRow, newContent) {
        if (!newContent) return;

        const content = await this._readFileContent(filePath);
        const lines = this._getLogicalLines(content);
        const newLines = this._getLogicalLines(newContent);

        const before = lines.slice(0, afterRow);
        const after = lines.slice(afterRow);
        const result = [...before, ...newLines, ...after];

        const outputTmpPath = filePath + ".jbridge_tmp_" + Date.now() + "_" + Math.floor(Math.random() * 1000000);
        try {
            await fs.writeFile(outputTmpPath, result.join('\n') + '\n', 'utf-8');
            await fs.rename(outputTmpPath, filePath);
        } catch (e) {
            try { await fs.unlink(outputTmpPath); } catch (_) {}
            throw e;
        }
    }

    async _buildWriteResultAndSnippet(filePath, originalStartRow, originalEndRow, deletedCount, insertedCount, oldContent, newContent, newStartRow, newEndRow) {
        const newTotalLines = await this._fileLineCount(filePath);
        const writeResult = new WriteResult(filePath, originalStartRow, originalEndRow, deletedCount, insertedCount, oldContent, newContent, newStartRow, newEndRow, newTotalLines);

        const SNIPPET_CONTEXT_LINES = 8;
        const snippetCenter = insertedCount > 0 ? newStartRow : originalStartRow;
        if (snippetCenter < 1) return writeResult;

        const snippetStart = Math.max(1, snippetCenter - SNIPPET_CONTEXT_LINES);
        const snippetEnd = Math.min(newTotalLines, snippetCenter + SNIPPET_CONTEXT_LINES);

        let snippet = "";
        if (snippetStart <= snippetEnd) {
            try {
                const readResult = await this.readLines(filePath, snippetStart, snippetEnd);
                snippet = readResult.toString();
            } catch (e) {
                snippet = `(读取上下文快照失败: ${e.message})`;
            }
        }
        return writeResult.withReviewSnippet(snippet);
    }

    // ==================== 公开方法 ====================
    async exec(command, options) {
        this._requireString(command, 'command', 'exec');
        const opts = options || {};
        const timeoutMs = opts.timeoutMs || 30000;
        const maxResultChars = opts.maxResultChars || 100000;

        let cmd = command;
        if (process.platform === 'win32') {
            cmd = `chcp 65001 >nul && ${cmd}`;
        }

        return new Promise((resolve, reject) => {
            exec(cmd, { timeout: timeoutMs, maxBuffer: maxResultChars * 2 }, (error, stdout, stderr) => {
                let result = stdout || '';
                if (result.length > maxResultChars) result = result.substring(0, maxResultChars) + '...';

                if (error) {
                    if (error.killed) {
                        reject(new Error('Command timed out.'));
                    } else {
                        reject(new Error(stderr || error.message));
                    }
                } else {
                    resolve(result);
                }
            });
        });
    }

    async sleep(millis) {
        return new Promise(resolve => setTimeout(resolve, millis));
    }

    async listFiles(directory, filePattern, maxDepth) {
        this._requireString(directory, 'directory', 'listFiles');
        this._requireOptionalString(filePattern, 'filePattern', 'listFiles');
        if (maxDepth == null) maxDepth = 1;
        else if (!Number.isInteger(maxDepth) || maxDepth < 1) throw new Error(`参数错误 [listFiles]: maxDepth必须是正整数`);

        if (maxDepth > this.MAX_LIST_FILES_DEPTH) throw new Error(`参数错误 [listFiles]: maxDepth(${maxDepth}) 超过最大深度 ${this.MAX_LIST_FILES_DEPTH}`);
        if (!(await this._isDirectory(directory))) throw new Error(`目录不存在: ${directory}`);

        const excludeDirs = new Set([".git", "node_modules", "__pycache__", ".idea", ".vscode", "dist", "build", ".gradle", ".settings", "target", "bin", "obj", ".cache", ".next", ".nuxt", "coverage", ".nyc_output", ".svn", ".hg", "vendor", "venv", ".venv", "env", ".tox", ".pytest_cache", ".mypy_cache", ".eggs"]);
        const excludeFileRegex = [/^\.DS_Store$/, /\.pyc$/, /\.pyo$/, /\.class$/, /\.log$/, /\.tmp$/, /\.swp$/, /\.swo$/, /~$/, /\.bak$/, /\.o$/, /\.so$/, /\.dll$/, /\.exe$/, /jbridge_tmp_/, /jbridge_content_tmp_/, /\.egg-info$/];

        let regexPattern = null;
        if (filePattern) {
            try { regexPattern = new RegExp(filePattern); } catch (e) { regexPattern = null; }
        }

        let results = [];
        let queue = [{ dir: directory, depth: 1 }];

        while (queue.length > 0) {
            const current = queue.shift();
            if (current.depth > maxDepth) continue;

            let entries;
            try {
                entries = await fs.readdir(current.dir, { withFileTypes: true });
            } catch (e) {
                continue;
            }

            for (const entry of entries) {
                const fullPath = path.join(current.dir, entry.name);
                if (entry.isDirectory()) {
                    if (current.depth < maxDepth && !excludeDirs.has(entry.name)) {
                        queue.push({ dir: fullPath, depth: current.depth + 1 });
                    }
                } else if (entry.isFile()) {
                    if (excludeFileRegex.some(rx => rx.test(entry.name))) continue;
                    if (regexPattern && !regexPattern.test(fullPath)) continue;

                    let lineCount = 0;
                    try {
                        const content = await this._readFileContent(fullPath);
                        lineCount = this._getLogicalLines(content).length;
                    } catch (e) {
                        try {
                            const buffer = await fs.readFile(fullPath);
                            if (buffer && buffer.length > 0) {
                                let c = 0;
                                for (let i = 0; i < buffer.length; i++) if (buffer[i] === 0x0A) c++;
                                if (buffer[buffer.length - 1] !== 0x0A) c++;
                                lineCount = c;
                            }
                        } catch (err) {}
                    }

                    results.push(`${fullPath} (${lineCount} rows)`);
                    if (results.length >= this.MAX_LIST_FILES_COUNT) return results;
                }
            }
        }
        return results;
    }

    async readLines(filePath, startRow, endRow) {
        this._requireString(filePath, 'filePath', 'readLines');
        this._requirePositiveInt(startRow, 'startRow', 'readLines');
        this._requirePositiveInt(endRow, 'endRow', 'readLines');

        if (startRow > endRow) throw new Error(`参数错误 [readLines]: startRow(${startRow}) 不能大于 endRow(${endRow})。文件: ${filePath}`);
        if (endRow - startRow + 1 > this.MAX_READ_LINES_COUNT) throw new Error(`参数错误 [readLines]: 请求行数超过最大值 ${this.MAX_READ_LINES_COUNT}`);

        if (!(await this._fileExists(filePath))) throw new Error(`文件不存在: ${filePath}`);

        const content = await this._readFileContent(filePath);
        const lines = this._getLogicalLines(content);
        const totalLines = lines.length;

        const s = Math.max(1, startRow);
        const e = Math.min(totalLines, endRow);

        if (s > e) return new ReadResult(filePath, s, e, totalLines, []);

        const lineList = [];
        for (let i = s - 1; i < e; i++) {
            lineList.push(new NumberedLine(i + 1, lines[i]));
        }
        return new ReadResult(filePath, s, e, totalLines, lineList);
    }

    async replaceLines(filePath, startRow, endRow, startRowText, endRowText, newContent) {
        this._requireString(filePath, 'filePath', 'replaceLines');
        this._requirePositiveInt(startRow, 'startRow', 'replaceLines');
        this._requirePositiveInt(endRow, 'endRow', 'replaceLines');
        this._requireString(startRowText, 'startRowText', 'replaceLines');
        this._requireString(endRowText, 'endRowText', 'replaceLines');

        if (startRow > endRow) throw new Error(`参数错误 [replaceLines]: startRow(${startRow}) 不能大于 endRow(${endRow})`);
        if (newContent == null) newContent = "";
        if (!(await this._fileExists(filePath))) throw new Error(`文件不存在: ${filePath}`);

        const totalLines = await this._fileLineCount(filePath);
        if (startRow > totalLines || endRow > totalLines) throw new Error(`参数错误 [replaceLines]: 行号超出文件总行数(${totalLines})`);

        const actualStartRow = await this._verifyLineByText(filePath, startRowText, startRow);
        const actualEndRow = await this._verifyLineByText(filePath, endRowText, endRow);
        if (actualStartRow > actualEndRow) throw new Error(`校验后 startRow(${actualStartRow}) > endRow(${actualEndRow})，请检查文本。`);

        const lines = this._getLogicalLines(await this._readFileContent(filePath));
        let oldContent = lines.slice(actualStartRow - 1, actualEndRow).join('\n');

        await this._backupFileBeforeEdit(filePath);
        await this._doReplaceLines(filePath, actualStartRow, actualEndRow, newContent);

        const deletedCount = actualEndRow - actualStartRow + 1;
        const insertedCount = this._countLines(newContent);

        return await this._buildWriteResultAndSnippet(filePath, actualStartRow, actualEndRow, deletedCount, insertedCount, oldContent, newContent, actualStartRow, actualStartRow + insertedCount - 1);
    }

    async insertLines(filePath, afterRow, afterRowText, newContent) {
        return await this.insertAfter(filePath, afterRow, afterRowText, newContent);
    }

    async insertAfter(filePath, afterRow, afterRowText, newContent) {
        this._requireString(filePath, 'filePath', 'insertAfter');
        this._requireNonNegativeInt(afterRow, 'afterRow', 'insertAfter');
        if (afterRow > 0) this._requireString(afterRowText, 'afterRowText', 'insertAfter');
        if (newContent == null) newContent = "";

        if (!(await this._fileExists(filePath))) throw new Error(`文件不存在: ${filePath}`);

        const totalLines = await this._fileLineCount(filePath);
        if (afterRow > totalLines) throw new Error(`参数错误 [insertAfter]: afterRow(${afterRow}) 超出总行数(${totalLines})`);

        if (afterRow > 0) {
            await this._verifyLineByText(filePath, afterRowText, afterRow);
        }

        if (newContent === "") {
            return await this._buildWriteResultAndSnippet(filePath, afterRow + 1, afterRow, 0, 0, "", "", afterRow + 1, afterRow);
        }

        await this._backupFileBeforeEdit(filePath);
        await this._doInsertAfter(filePath, afterRow, newContent);

        const insertedCount = this._countLines(newContent);
        return await this._buildWriteResultAndSnippet(filePath, afterRow + 1, afterRow, 0, insertedCount, "", newContent, afterRow + 1, afterRow + insertedCount);
    }

    async createFile(pathStr, content) {
        this._requireString(pathStr, 'path', 'createFile');
        if (content == null) content = "";
        if (typeof content !== 'string') throw new Error(`参数错误 [createFile]: content 必须是字符串`);

        if (await this._fileExists(pathStr)) {
            let firstLines = '\n该文件前几行内容：\n' + await this.readLines(pathStr, 1, 8);
            throw new Error(`文件已存在: ${pathStr}\n如需覆盖，请使用 readLines + replaceLines 替换全部内容。${firstLines}`);
        }
        if (this._byteLength(content) > this.MAX_WRITE_FILE_SIZE_BYTES) throw new Error(`内容大小超过上限 ${this.MAX_WRITE_FILE_SIZE_BYTES}`);

        const dir = path.dirname(pathStr);
        await fs.mkdir(dir, { recursive: true }).catch(() => {});

        await this._backupFileBeforeEdit(pathStr);

        let newNormalized = content.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
        if (newNormalized !== "" && !newNormalized.endsWith('\n')) newNormalized += '\n';

        const tmpPath = pathStr + ".jbridge_tmp_" + Date.now() + "_" + Math.floor(Math.random() * 1000000);
        try {
            await fs.writeFile(tmpPath, newNormalized, 'utf-8');
            await fs.rename(tmpPath, pathStr);
        } catch (e) {
            try { await fs.unlink(tmpPath); } catch (_) {}
            throw e;
        }

        const insertedCount = this._countLines(content);
        return await this._buildWriteResultAndSnippet(pathStr, 1, 0, 0, insertedCount, "", content, 1, Math.max(1, insertedCount));
    }

    async appendLines(filePath, newContent) {
        this._requireString(filePath, 'filePath', 'appendLines');
        if (newContent == null) newContent = "";
        if (typeof newContent !== 'string') throw new Error(`参数错误 [appendLines]: newContent 必须是字符串`);

        if (!(await this._fileExists(filePath))) await this._ensureFileExists(filePath);

        const beforeTotalLines = await this._fileLineCount(filePath);

        if (newContent === "") {
            return await this._buildWriteResultAndSnippet(filePath, beforeTotalLines + 1, beforeTotalLines, 0, 0, "", "", beforeTotalLines + 1, beforeTotalLines);
        }

        if (this._byteLength(newContent) > this.MAX_APPEND_FILE_SIZE_BYTES) throw new Error(`内容大小超过上限 ${this.MAX_APPEND_FILE_SIZE_BYTES}`);

        await this._backupFileBeforeEdit(filePath);

        let newNormalized = newContent.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
        if (newNormalized !== "" && !newNormalized.endsWith('\n')) newNormalized += '\n';

        const oldContent = await this._readFileContent(filePath).catch(() => "");
        let combined = oldContent;
        if (combined !== '' && !combined.endsWith('\n')) combined += '\n';
        combined += newNormalized;

        const tmpPath = filePath + ".jbridge_tmp_" + Date.now() + "_" + Math.floor(Math.random() * 1000000);
        try {
            await fs.writeFile(tmpPath, combined, 'utf-8');
            await fs.rename(tmpPath, filePath);
        } catch (e) {
            try { await fs.unlink(tmpPath); } catch (_) {}
            throw e;
        }

        const insertedCount = this._countLines(newContent);
        const newStartRow = beforeTotalLines + 1;
        const newEndRow = beforeTotalLines + insertedCount;

        return await this._buildWriteResultAndSnippet(filePath, newStartRow, beforeTotalLines, 0, insertedCount, "", newContent, newStartRow, newEndRow);
    }
}

// ==================== 数据模型类 ====================
class NumberedLine {
    constructor(row, text) {
        this.row = row;
        this.text = text;
    }
    toString() { return String(this.row).padStart(4, ' ') + ": " + this.text; }
}

class ReadResult {
    constructor(filePath, startRow, endRow, totalLines, lines) {
        this.filePath = filePath;
        this.startRow = startRow;
        this.endRow = endRow;
        this.totalLines = totalLines;
        this.lines = lines;
    }
    toString() {
        let str = `file=${this.filePath}, rows=[${this.startRow}~${this.endRow}], totalLines=${this.totalLines}`;
        for (const nl of this.lines) str += "\n" + nl.toString();
        return str;
    }
}

class WriteResult {
    constructor(filePath, originalStartRow, originalEndRow, deletedLineCount, insertedLineCount, oldContent, newContent, newStartRow, newEndRow, totalLinesAfter, reviewSnippet = null) {
        this.filePath = filePath;
        this.originalStartRow = originalStartRow;
        this.originalEndRow = originalEndRow;
        this.deletedLineCount = deletedLineCount;
        this.insertedLineCount = insertedLineCount;
        this.oldContent = oldContent;
        this.newContent = newContent;
        this.newStartRow = newStartRow;
        this.newEndRow = newEndRow;
        this.totalLinesAfter = totalLinesAfter;
        this.reviewSnippet = reviewSnippet;
    }
    withReviewSnippet(reviewSnippet) {
        return new WriteResult(this.filePath, this.originalStartRow, this.originalEndRow, this.deletedLineCount, this.insertedLineCount, this.oldContent, this.newContent, this.newStartRow, this.newEndRow, this.totalLinesAfter, reviewSnippet);
    }
    toString() {
        let base;
        if (this.insertedLineCount === 0) base = `file=${this.filePath}, deleted rows [${this.originalStartRow}~${this.originalEndRow}] (${this.deletedLineCount} lines), totalLines=${this.totalLinesAfter}`;
        else if (this.deletedLineCount === 0) base = `file=${this.filePath}, inserted ${this.insertedLineCount} lines at row ${this.originalStartRow}, newRows=[${this.newStartRow}~${this.newEndRow}], totalLines=${this.totalLinesAfter}`;
        else base = `file=${this.filePath}, replaced rows [${this.originalStartRow}~${this.originalEndRow}] (${this.deletedLineCount} -> ${this.insertedLineCount} lines), newRows=[${this.newStartRow}~${this.newEndRow}], totalLines=${this.totalLinesAfter}`;
        return base + (this.reviewSnippet == null ? "" : "\n===reviewSnippet after edit===\n" + this.reviewSnippet);
    }
}

class RowCheckFailedException extends Error {
    constructor(filePath, checkedRow, expectedText, actualContent, matchedRows) {
        const lines = [];
        lines.push(`[RowCheckFailed] 行内容校验失败，文件可能已被修改或行号记忆错误。`);
        lines.push(`  文件路径: ${filePath}`);
        lines.push(`  期望行号: ${checkedRow == null ? '未提供' : checkedRow}`);
        lines.push(`  期望该行包含文本: "${expectedText}"`);
        if (actualContent) lines.push(`  该行实际内容: "${actualContent}"`);
        else lines.push(`  该行实际内容: (行号无效或超出文件范围)`);

        if (matchedRows == null) lines.push(`  修正建议: 未知错误，请使用 readLines 重新读取文件。`);
        else if (matchedRows.length === 0) lines.push(`  修正建议: 该文本在整个文件中均未找到。请使用 readLines 重新读取文件确认内容。`);
        else if (matchedRows.length === 1) lines.push(`  修正建议: 该文本实际出现在第 ${matchedRows[0]} 行。请使用正确的行号 ${matchedRows[0]} 重试。`);
        else lines.push(`  修正建议: 该文本匹配了多行: ${JSON.stringify(matchedRows)}。请提供更长/更精确的文本以唯一标识。`);

        super(lines.join('\n'));
        this.name = "RowCheckFailedException";
    }
}

module.exports = { NodeTools, NumberedLine, ReadResult, WriteResult, RowCheckFailedException };