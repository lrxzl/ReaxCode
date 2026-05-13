package cn.net.xiangxiang.reaction.frontend.tools.file;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * .gitignore 风格的路径匹配器
 */
public class PathIgnoreMatcher {

    private final List<IgnoreRule> rules = new ArrayList<IgnoreRule>();

    public PathIgnoreMatcher() {
        addHardcodedPatterns();
    }

    private void addHardcodedPatterns() {
        addPattern(".git/");
        addPattern(".svn/");
        addPattern(".hg/");
        addPattern(".bzr/");
        addPattern(".cvs/");

        addPattern("target/");
        addPattern("build/");
        addPattern("out/");
        addPattern("bin/");
        addPattern("dist/");
        addPattern("classes/");

        addPattern(".idea/");
        addPattern(".vscode/");
        addPattern("*.iml");
        addPattern("*.ipr");
        addPattern("*.iws");
        addPattern(".project");
        addPattern(".classpath");
        addPattern(".settings/");

        addPattern(".DS_Store");
        addPattern("Thumbs.db");
        addPattern("desktop.ini");

        addPattern("*.tmp");
        addPattern("*.temp");
        addPattern("*.log");
        addPattern("*.bak");
        addPattern("*.swp");
        addPattern("*.swo");
        addPattern("*~");

        addPattern("node_modules/");
        addPattern("bower_components/");
        addPattern(".gradle/");
        addPattern(".mvn/");

        addPattern("*.class");
        addPattern("*.jar");
        addPattern("*.war");
        addPattern("*.ear");
        addPattern("*.exe");
        addPattern("*.dll");
        addPattern("*.so");
        addPattern("*.dylib");

        addPattern("docs/_build/");
        addPattern("site/");

        addPattern("coverage/");
        addPattern(".nyc_output/");
    }

    /** 从 ignore 文件加载规则（纯 java.io 实现） */
    public void loadFromFile(File ignoreFile) throws IOException {
        if (ignoreFile == null || !ignoreFile.exists() || !ignoreFile.isFile()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(ignoreFile), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                addPattern(line);
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** 添加单条 ignore 模式 */
    public void addPattern(String pattern) {
        if (pattern == null) return;
        String trimmed = pattern.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }

        boolean negated = false;
        if (trimmed.startsWith("!")) {
            negated = true;
            trimmed = trimmed.substring(1);
        }

        boolean dirOnly = trimmed.endsWith("/");
        if (dirOnly) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        rules.add(new IgnoreRule(trimmed, negated, dirOnly));
    }

    /** 判断给定相对路径是否应被忽略 */
    public boolean isIgnored(String relativePath, boolean isDirectory) {
        if (rules.isEmpty()) {
            return false;
        }

        String normalized = relativePath.replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        boolean ignored = false;
        for (IgnoreRule rule : rules) {
            if (rule.dirOnly && !isDirectory) {
                continue;
            }
            if (matches(rule.pattern, normalized)) {
                ignored = !rule.negated;
            }
        }
        return ignored;
    }

    private boolean matches(String pattern, String path) {
        if (pattern.contains("/")) {
            String p = pattern;
            if (p.startsWith("/")) {
                p = p.substring(1);
            }
            return globMatch(p, path);
        } else {
            String fileName = path.contains("/")
                ? path.substring(path.lastIndexOf('/') + 1)
                : path;
            if (globMatch(pattern, fileName)) {
                return true;
            }
            String[] segments = path.split("/");
            for (String seg : segments) {
                if (globMatch(pattern, seg)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean globMatch(String pattern, String text) {
        String regex = globToRegex(pattern);
        return text.matches(regex);
    }

    private String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        sb.append("(.*/)?");
                        i += 3;
                    } else {
                        sb.append(".*");
                        i += 2;
                    }
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else if (c == '.') {
                sb.append("\\.");
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        sb.append("$");
        return sb.toString();
    }

    public boolean hasRules() {
        return !rules.isEmpty();
    }

    /** 替代原 record（JDK 14+），改为普通内部类 */
    private static class IgnoreRule {
        final String pattern;
        final boolean negated;
        final boolean dirOnly;

        IgnoreRule(String pattern, boolean negated, boolean dirOnly) {
            this.pattern = pattern;
            this.negated = negated;
            this.dirOnly = dirOnly;
        }
    }
}
