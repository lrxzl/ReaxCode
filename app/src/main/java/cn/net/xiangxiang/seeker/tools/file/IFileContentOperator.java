package cn.net.xiangxiang.seeker.tools.file;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.List;

public interface IFileContentOperator {

    // ==================== 数据类 ====================

    class NumberedLine {
        private final int row;
        private final String text;

        public NumberedLine(int row, String text) {
            this.row = row;
            this.text = text;
        }

        public int row() { return row; }
        public String text() { return text; }

        @Override
        public String toString() {
            return String.format("%4d: %s", row, text);
        }
    }

    class SearchResult {
        private final String path;
        private final int row;
        private final String regex;
        private final String match;
        private final List<NumberedLine> context;

        public SearchResult(String path, int row, String regex,
                            String match, List<NumberedLine> context) {
            this.path = path;
            this.row = row;
            this.regex = regex;
            this.match = match;
            this.context = context;
        }

        public String path() { return path; }
        public int row() { return row; }
        public String regex() { return regex; }
        public String match() { return match; }
        public List<NumberedLine> context() { return context; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("path=%s, row=%d, regex=%s, match=%s",
                    path, row, regex, match));
            for (NumberedLine nl : context) {
                sb.append("\n");
                if (nl.row() == row) {
                    sb.append(">>").append(nl);
                } else {
                    sb.append("  ").append(nl);
                }
            }
            return sb.toString();
        }
    }

    class WriteResult {
        private final String filePath;
        private final int originalStartRow;
        private final int originalEndRow;
        private final int deletedLineCount;
        private final int insertedLineCount;
        private final String oldContent;
        private final String newContent;
        private final int newStartRow;
        private final int newEndRow;
        private final int totalLinesAfter;

        public WriteResult(String filePath,
                           int originalStartRow, int originalEndRow,
                           int deletedLineCount, int insertedLineCount,
                           String oldContent, String newContent,
                           int newStartRow, int newEndRow,
                           int totalLinesAfter) {
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
        }

        public String filePath() { return filePath; }
        public int originalStartRow() { return originalStartRow; }
        public int originalEndRow() { return originalEndRow; }
        public int deletedLineCount() { return deletedLineCount; }
        public int insertedLineCount() { return insertedLineCount; }
        public String oldContent() { return oldContent; }
        public String newContent() { return newContent; }
        public int newStartRow() { return newStartRow; }
        public int newEndRow() { return newEndRow; }
        public int totalLinesAfter() { return totalLinesAfter; }

        @Override
        public String toString() {
            if (insertedLineCount == 0) {
                return String.format(
                        "file=%s, deleted rows [%d~%d] (%d lines), totalLines=%d",
                        filePath, originalStartRow, originalEndRow,
                        deletedLineCount, totalLinesAfter);
            } else if (deletedLineCount == 0) {
                return String.format(
                        "file=%s, inserted %d lines at row %d, newRows=[%d~%d], totalLines=%d",
                        filePath, insertedLineCount, originalStartRow,
                        newStartRow, newEndRow, totalLinesAfter);
            } else {
                return String.format(
                        "file=%s, replaced rows [%d~%d] (%d -> %d lines), newRows=[%d~%d], totalLines=%d",
                        filePath, originalStartRow, originalEndRow,
                        deletedLineCount, insertedLineCount,
                        newStartRow, newEndRow, totalLinesAfter);
            }
        }
    }

    class ReadResult {
        private final String filePath;
        private final int startRow;
        private final int endRow;
        private final int totalLines;
        private final List<NumberedLine> lines;

        public ReadResult(String filePath, int startRow, int endRow,
                          int totalLines, List<NumberedLine> lines) {
            this.filePath = filePath;
            this.startRow = startRow;
            this.endRow = endRow;
            this.totalLines = totalLines;
            this.lines = lines;
        }

        public String filePath() { return filePath; }
        public int startRow() { return startRow; }
        public int endRow() { return endRow; }
        public int totalLines() { return totalLines; }
        public List<NumberedLine> lines() { return lines; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("file=%s, rows=[%d~%d], totalLines=%d",
                    filePath, startRow, endRow, totalLines));
            for (NumberedLine nl : lines) {
                sb.append("\n").append(nl);
            }
            return sb.toString();
        }
    }

    // ==================== 异常 ====================

    class RowCheckFailedException extends IOException {
        private final String filePath;
        private final int checkedRow;
        private final String expectedText;
        private final String actualContent;
        private final int suggestedRow;

        public RowCheckFailedException(
                String filePath, int checkedRow,
                String expectedText, String actualContent,
                int suggestedRow) {
            super(buildMessage(filePath, checkedRow, expectedText, actualContent, suggestedRow));
            this.filePath = filePath;
            this.checkedRow = checkedRow;
            this.expectedText = expectedText;
            this.actualContent = actualContent;
            this.suggestedRow = suggestedRow;
        }

        private static String buildMessage(
                String filePath, int row, String expectedText, String actual, int suggested) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Row check failed in %s at row %d", filePath, row));
            sb.append(String.format("\n  expected text: %s", expectedText));
            sb.append(String.format("\n  actual content: %s", actual));
            if (suggested > 0) {
                sb.append(String.format("\n  SUGGESTION: text found at row %d, please retry", suggested));
            } else {
                sb.append("\n  SUGGESTION: text not found anywhere, please re-read the file");
            }
            return sb.toString();
        }

        public String getFilePath() { return filePath; }
        public int getCheckedRow() { return checkedRow; }
        public String getExpectedText() { return expectedText; }
        public String getActualContent() { return actualContent; }
        public int getSuggestedRow() { return suggestedRow; }
    }

    class AnchorNotFoundException extends IOException {
        public AnchorNotFoundException(String message) { super(message); }
    }

    class AmbiguousAnchorException extends IOException {
        private final List<Integer> matchedRows;

        public AmbiguousAnchorException(String message, List<Integer> matchedRows) {
            super(message);
            this.matchedRows = matchedRows;
        }

        public List<Integer> getMatchedRows() { return matchedRows; }
    }

    // ==================== 方法 ====================

    List<SearchResult> search(
            String rootDir,
            @Nullable String filePattern,
            String contentRegex,
            int contextLineCount) throws IOException;

    ReadResult readLines(String filePath, int startRow, int endRow) throws IOException;

    List<String> listFiles(String directory, @Nullable String filePattern, int maxDepth) throws IOException;

    WriteResult replaceLines(
            String filePath, @Nullable String newContent,
            String startLineText, String endLineText,
            int startRow, int endRow) throws IOException;

    WriteResult insertLines(String filePath, int afterRow, String newContent) throws IOException;

    WriteResult replaceByAnchor(
            String filePath,
            String anchorText,
            int beforeCount, int afterCount,
            @Nullable String newContent) throws IOException;

    WriteResult insertAfterAnchor(
            String filePath, String anchorText,
            String newContent) throws IOException;

    WriteResult insertBeforeAnchor(
            String filePath, String anchorText,
            String newContent) throws IOException;
}
