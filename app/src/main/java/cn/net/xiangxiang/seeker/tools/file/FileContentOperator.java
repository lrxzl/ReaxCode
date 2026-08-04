package cn.net.xiangxiang.seeker.tools.file;

import android.os.Build;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FileContentOperator implements IFileContentOperator {

    private static final long MAX_SEARCH_FILE_SIZE = 10L * 1024 * 1024;
    private static final String CHARSET = "UTF-8";

    // ================================================================ 文件 IO 工具

    /** 读取文件所有行（纯 java.io 实现） */
    private static List<String> readAllLines(File file) throws IOException {
        List<String> lines = new ArrayList<String>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), CHARSET));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
        return lines;
    }

    /** 将行列表写入文件（每行末尾 \n） */
    private static void writeLines(File file, List<String> lines) throws IOException {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), CHARSET));
            for (int i = 0; i < lines.size(); i++) {
                writer.write(lines.get(i));
                writer.write('\n');
            }
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** 计算文件行数 */
    private static long countLines(File file) throws IOException {
        long count = 0;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), CHARSET));
            while (reader.readLine() != null) {
                count++;
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
        return count;
    }

    // ================================================================ search

    @Override
    public List<SearchResult> search(
        String rootDir,
        @Nullable String filePattern,
        String contentRegex,
        int contextLineCount) throws IOException {

        if (rootDir == null || isBlank(rootDir)) {
            throw new IllegalArgumentException("rootDir 不能为空");
        }
        if (contentRegex == null || isBlank(contentRegex)) {
            throw new IllegalArgumentException("contentRegex 不能为空");
        }

        int ctx = Math.max(0, contextLineCount);
        Pattern contentPattern = Pattern.compile(contentRegex);
        Pattern fileP = compileOptionalPattern(filePattern);

        List<SearchResult> results = new ArrayList<SearchResult>();
        File root = new File(rootDir).getAbsoluteFile();

        if (!root.isDirectory()) {
            throw new IllegalArgumentException("rootDir 不存在或不是目录: " + rootDir);
        }

        PathIgnoreMatcher ignoreMatcher = buildIgnoreMatcher(root);

        walkFileTree(root, root, ignoreMatcher, fileP, contentPattern, contentRegex, ctx, results);

        return results;
    }

    /**
     * 用 BFS 遍历目录树（替代 Files.walkFileTree）
     */
    private void walkFileTree(
        File root, File current,
        PathIgnoreMatcher ignoreMatcher,
        @Nullable Pattern filePattern,
        Pattern contentPattern, String contentRegex,
        int ctx, List<SearchResult> results) {

        Queue<File> queue = new LinkedList<File>();
        queue.add(current);

        while (!queue.isEmpty()) {
            File dir = queue.poll();
            File[] children = dir.listFiles();
            if (children == null) continue;

            for (File child : children) {
                String relPath = toRelPath(root, child);

                if (child.isDirectory()) {
                    if (!ignoreMatcher.isIgnored(relPath, true)) {
                        queue.add(child);
                    }
                } else if (child.isFile()) {
                    if (ignoreMatcher.isIgnored(relPath, false)) continue;
                    if (filePattern != null && !filePattern.matcher(child.getName()).find()) continue;
                    if (child.length() > MAX_SEARCH_FILE_SIZE) continue;

                    searchInFile(child, relPath, contentPattern, contentRegex, ctx, results);
                }
            }
        }
    }

    private void searchInFile(
        File file, String relPath,
        Pattern contentPattern, String contentRegex,
        int ctx, List<SearchResult> results) {
        List<String> lines;
        try {
            lines = readAllLines(file);
        } catch (IOException e) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = contentPattern.matcher(lines.get(i));
            if (!m.find()) continue;
            int row = i + 1;
            int from = Math.max(0, i - ctx);
            int to = Math.min(lines.size() - 1, i + ctx);
            List<NumberedLine> context = new ArrayList<NumberedLine>(to - from + 1);
            for (int j = from; j <= to; j++) {
                context.add(new NumberedLine(j + 1, lines.get(j)));
            }
            results.add(new SearchResult(relPath, row, contentRegex, m.group(), context));
        }
    }

    // ============================================================= readLines

    @Override
    public ReadResult readLines(String filePath, int startRow, int endRow) throws IOException {
        requireFilePath(filePath);
        requireFileExists(filePath);

        List<String> lines = readAllLines(new File(filePath));
        int totalLines = lines.size();
        if (totalLines == 0) {
            return new ReadResult(filePath, 0, 0, 0, Collections.<NumberedLine>emptyList());
        }

        int s = clamp(startRow, 1, totalLines);
        int e = clamp(endRow, s, totalLines);

        List<NumberedLine> result = new ArrayList<NumberedLine>(e - s + 1);
        for (int i = s - 1; i < e; i++) {
            result.add(new NumberedLine(i + 1, lines.get(i)));
        }
        return new ReadResult(filePath, s, e, totalLines, result);
    }

    // ============================================================= listFiles

    @Override
    public List<String> listFiles(
        String directory, @Nullable String filePattern, int maxDepth) throws IOException {
        File dirFile = new File(directory);
        if (!dirFile.exists() || !dirFile.isDirectory()) {
            throw new IOException("目录不存在或不是目录: " + directory);
        }
        int depth = Math.max(1, maxDepth);
        Pattern pattern = compileOptionalPattern(filePattern);
        List<String> result = new ArrayList<String>();
        collectFiles(dirFile, dirFile, pattern, depth, 0, result);
        return result;
    }

    /**
     * 递归收集文件（替代 Files.walk）
     */
    private void collectFiles(File baseDir, File currentDir,
                              @Nullable Pattern pattern, int maxDepth, int currentDepth,
                              List<String> result) {
        if (currentDepth > maxDepth) return;
        File[] children = currentDir.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.isFile()) {
                if (pattern != null && !pattern.matcher(child.getName()).matches()) {
                    continue;
                }
                String relativePath = toRelPath(baseDir, child);
                try {
                    long lineCount = countLines(child);
                    result.add(relativePath + " (" + lineCount + "行)");
                } catch (IOException e) {
                    result.add(relativePath + " (行数未知)");
                }
            } else if (child.isDirectory()) {
                collectFiles(baseDir, child, pattern, maxDepth, currentDepth + 1, result);
            }
        }
    }

    // ========================================== replaceLines

    @Override
    public WriteResult replaceLines(
        String filePath, @Nullable String newContent,
        String startLineText, String endLineText,
        int startRow, int endRow) throws IOException {

        requireFilePath(filePath);
        requireFileExists(filePath);

        if (startLineText == null || isBlank(startLineText)) {
            throw new IllegalArgumentException("startLineText 不能为空");
        }
        if (endLineText == null || isBlank(endLineText)) {
            throw new IllegalArgumentException("endLineText 不能为空");
        }

        List<String> lines = new ArrayList<String>(readAllLines(new File(filePath)));
        int totalLines = lines.size();
        if (totalLines == 0) {
            throw new IllegalArgumentException("文件为空，无法替换行: " + filePath);
        }

        int actualStart = findClosestMatch(lines, startLineText, startRow, filePath);
        int actualEnd = findClosestMatch(lines, endLineText, endRow, filePath);

        if (actualStart > actualEnd) {
            throw new RowCheckFailedException(
                filePath, actualStart,
                startLineText,
                String.format("Resolved startRow=%d > endRow=%d. "
                        + "startLineText=\"%s\" matched row %d, endLineText=\"%s\" matched row %d.",
                    actualStart, actualEnd, startLineText, actualStart, endLineText, actualEnd),
                -1);
        }

        return doReplace(filePath, lines, actualStart, actualEnd, newContent);
    }

    private int findClosestMatch(List<String> lines, String text, int hintRow, String filePath)
        throws RowCheckFailedException {

        List<Integer> matched = new ArrayList<Integer>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                matched.add(i + 1);
            }
        }

        if (matched.isEmpty()) {
            int clampedHint = clamp(hintRow, 1, lines.size());
            throw new RowCheckFailedException(
                filePath, clampedHint,
                text, lines.get(clampedHint - 1), -1);
        }

        if (matched.size() == 1) {
            return matched.get(0);
        }

        int closest = matched.get(0);
        int minDist = Math.abs(closest - hintRow);
        for (int i = 1; i < matched.size(); i++) {
            int dist = Math.abs(matched.get(i) - hintRow);
            if (dist < minDist) {
                minDist = dist;
                closest = matched.get(i);
            }
        }
        return closest;
    }

    // =========================================================== insertLines

    @Override
    public WriteResult insertLines(
        String filePath, int afterRow, String newContent) throws IOException {
        requireFilePath(filePath);
        ensureFileExists(filePath);

        List<String> lines = new ArrayList<String>(readAllLines(new File(filePath)));
        int totalLines = lines.size();
        int insertPos = clamp(afterRow, 0, totalLines);

        List<String> newLines = splitContent(newContent);
        if (newLines.isEmpty()) {
            return new WriteResult(filePath, insertPos + 1, insertPos,
                0, 0, "", "", -1, -1, totalLines);
        }

        lines.addAll(insertPos, newLines);
        writeLines(new File(filePath), lines);

        int newStartRow = insertPos + 1;
        int newEndRow = insertPos + newLines.size();
        return new WriteResult(filePath, insertPos + 1, insertPos,
            0, newLines.size(), "", newContent == null ? "" : newContent,
            newStartRow, newEndRow, lines.size());
    }

    // ========================================================= replaceByAnchor

    @Override
    public WriteResult replaceByAnchor(
        String filePath, String anchorText,
        int beforeCount, int afterCount,
        @Nullable String newContent) throws IOException {

        requireFilePath(filePath);
        requireFileExists(filePath);
        requireNonBlank(anchorText, "anchorText");

        List<String> lines = new ArrayList<String>(readAllLines(new File(filePath)));
        int totalLines = lines.size();

        int anchorRow = findUniqueAnchor(lines, anchorText, filePath);

        int before = Math.max(0, beforeCount);
        int after = Math.max(0, afterCount);
        int s = clamp(anchorRow - before, 1, totalLines);
        int e = clamp(anchorRow + after, 1, totalLines);

        return doReplace(filePath, lines, s, e, newContent);
    }

    // ======================================================= insertAfterAnchor

    @Override
    public WriteResult insertAfterAnchor(
        String filePath, String anchorText,
        String newContent) throws IOException {

        requireFilePath(filePath);
        requireFileExists(filePath);
        requireNonBlank(anchorText, "anchorText");

        List<String> lines = new ArrayList<String>(readAllLines(new File(filePath)));
        int anchorRow = findUniqueAnchor(lines, anchorText, filePath);

        List<String> newLines = splitContent(newContent);
        if (newLines.isEmpty()) {
            return new WriteResult(filePath, anchorRow + 1, anchorRow,
                0, 0, "", "", -1, -1, lines.size());
        }

        lines.addAll(anchorRow, newLines);
        writeLines(new File(filePath), lines);

        int newStartRow = anchorRow + 1;
        int newEndRow = anchorRow + newLines.size();
        return new WriteResult(filePath, anchorRow + 1, anchorRow,
            0, newLines.size(), "", newContent == null ? "" : newContent,
            newStartRow, newEndRow, lines.size());
    }

    // ====================================================== insertBeforeAnchor

    @Override
    public WriteResult insertBeforeAnchor(
        String filePath, String anchorText,
        String newContent) throws IOException {

        requireFilePath(filePath);
        requireFileExists(filePath);
        requireNonBlank(anchorText, "anchorText");

        List<String> lines = new ArrayList<String>(readAllLines(new File(filePath)));
        int anchorRow = findUniqueAnchor(lines, anchorText, filePath);

        List<String> newLines = splitContent(newContent);
        if (newLines.isEmpty()) {
            return new WriteResult(filePath, anchorRow, anchorRow - 1,
                0, 0, "", "", -1, -1, lines.size());
        }

        lines.addAll(anchorRow - 1, newLines);
        writeLines(new File(filePath), lines);

        int newStartRow = anchorRow;
        int newEndRow = anchorRow + newLines.size() - 1;
        return new WriteResult(filePath, anchorRow, anchorRow - 1,
            0, newLines.size(), "", newContent == null ? "" : newContent,
            newStartRow, newEndRow, lines.size());
    }

    // ========================================================== 核心内部方法

    private WriteResult doReplace(
        String filePath, List<String> lines,
        int s, int e, @Nullable String newContent) throws IOException {

        List<String> oldSlice = new ArrayList<String>(lines.subList(s - 1, e));
        String oldText = join("\n", oldSlice);
        int deletedCount = oldSlice.size();

        List<String> newLines = splitContent(newContent);
        lines.subList(s - 1, e).clear();
        lines.addAll(s - 1, newLines);

        writeLines(new File(filePath), lines);

        int insertedCount = newLines.size();
        int newStartRow = insertedCount == 0 ? -1 : s;
        int newEndRow = insertedCount == 0 ? -1 : s + insertedCount - 1;

        return new WriteResult(
            filePath, s, e,
            deletedCount, insertedCount,
            oldText, newContent == null ? "" : newContent,
            newStartRow, newEndRow,
            lines.size());
    }

    private int findUniqueAnchor(List<String> lines, String anchorText, String filePath) throws IOException {
        List<Integer> matched = new ArrayList<Integer>();

        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(anchorText)) {
                matched.add(i + 1);
            }
        }

        if (matched.isEmpty()) {
            throw new AnchorNotFoundException(String.format(
                "Anchor text \"%s\" matched no lines in %s. "
                    + "Please verify the text or re-read the file.",
                anchorText, filePath));
        }

        if (matched.size() > 1) {
            String preview = buildAnchorPreview(lines, matched);
            throw new AmbiguousAnchorException(String.format(
                "Anchor text \"%s\" matched %d lines in %s at rows %s. "
                    + "Please use a more specific text.\n%s",
                anchorText, matched.size(), filePath,
                matched.size() > 10 ? matched.subList(0, 10) + "..." : matched.toString(),
                preview),
                matched);
        }

        return matched.get(0);
    }

    @SuppressWarnings("unused")
    private int findTextInFile(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i + 1;
            }
        }
        return -1;
    }

    private String buildAnchorPreview(List<String> lines, List<Integer> matchedRows) {
        StringBuilder sb = new StringBuilder("Matched lines preview:");
        int limit = Math.min(matchedRows.size(), 10);
        for (int i = 0; i < limit; i++) {
            int row = matchedRows.get(i);
            sb.append(String.format("\n  row %d: %s", row, lines.get(row - 1)));
        }
        if (matchedRows.size() > 10) {
            sb.append(String.format("\n  ... and %d more", matchedRows.size() - 10));
        }
        return sb.toString();
    }

    // ========================================================== 通用工具方法

    private int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private Pattern compileOptionalPattern(@Nullable String regex) {
        if (regex == null || isBlank(regex)) return null;
        String converted = regex;
        if (regex.contains("*") || regex.contains("?")) {
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                converted = convertGlobToRegex(regex);
            }
        }
        return Pattern.compile(converted);
    }

    private String convertGlobToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        sb.append("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*': sb.append(".*"); break;
                case '?': sb.append("."); break;
                case '.': case '(': case ')': case '+':
                case '|': case '^': case '$': case '@': case '%':
                    sb.append('\\');
                    sb.append(c);
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        sb.append("$");
        return sb.toString();
    }

    private void requireFilePath(String filePath) {
        if (filePath == null || isBlank(filePath))
            throw new IllegalArgumentException("filePath 不能为空");
    }

    private void requireFileExists(String filePath) {
        if (!new File(filePath).exists())
            throw new IllegalArgumentException("文件不存在: " + filePath);
    }

    private void requireNonBlank(String value, String name) {
        if (value == null || isBlank(value))
            throw new IllegalArgumentException(name + " 不能为空");
    }

    private void ensureFileExists(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                if (!parent.mkdirs()) {
                    throw new IOException("无法创建父目录: " + parent.getAbsolutePath());
                }
            }
            if (!file.createNewFile()) {
                throw new IOException("无法创建文件: " + filePath);
            }
        }
    }

    private PathIgnoreMatcher buildIgnoreMatcher(File root) throws IOException {
        PathIgnoreMatcher ignoreMatcher = new PathIgnoreMatcher();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ignoreMatcher.loadFromFile(new File(root, ".gitignore").toPath().toFile());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ignoreMatcher.loadFromFile(new File(root, ".ignore").toPath().toFile());
        }
        ignoreMatcher.addPattern(".git/");
        return ignoreMatcher;
    }

    private String toRelPath(File root, File target) {
        String rootPath = root.getAbsolutePath();
        String targetPath = target.getAbsolutePath();
        if (targetPath.startsWith(rootPath)) {
            String rel = targetPath.substring(rootPath.length());
            if (rel.startsWith(File.separator)) {
                rel = rel.substring(1);
            }
            return rel.replace('\\', '/');
        }
        return targetPath.replace('\\', '/');
    }

    private List<String> splitContent(@Nullable String content) {
        if (content == null || content.isEmpty()) return Collections.<String>emptyList();
        return new ArrayList<String>(Arrays.asList(content.split("\n", -1)));
    }

    /** JDK 1.8 / Android 兼容的 String.join */
    private static String join(String delimiter, List<String> parts) {
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** 替代 String.isBlank()（JDK 11+） */
    private static boolean isBlank(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
