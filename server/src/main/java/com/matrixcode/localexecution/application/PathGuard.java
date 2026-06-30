package com.matrixcode.localexecution.application;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PathGuard {

    public Path resolveExisting(String rootPath, String relativePath) {
        var root = root(rootPath);
        var target = root.resolve(relative(relativePath)).normalize();
        ensureInside(root, target);
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("路径不存在");
        }
        try {
            var realTarget = target.toRealPath();
            ensureInside(root.toRealPath(), realTarget);
            return realTarget;
        } catch (IOException exception) {
            throw new IllegalArgumentException("路径无法访问");
        }
    }

    public Path resolveWritable(String rootPath, String relativePath) {
        var root = root(rootPath);
        var target = root.resolve(relative(relativePath)).normalize();
        ensureInside(root, target);
        var parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException("父目录不存在");
        }
        try {
            ensureInside(root.toRealPath(), parent.toRealPath());
        } catch (IOException exception) {
            throw new IllegalArgumentException("父目录无法访问");
        }
        return target;
    }

    private Path root(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalArgumentException("工作区路径不能为空");
        }
        var root = Path.of(rootPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("工作区路径必须是已存在目录");
        }
        return root;
    }

    private Path relative(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("相对路径不能为空");
        }
        var relative = Path.of(relativePath.trim());
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("只能使用相对路径");
        }
        return relative;
    }

    private void ensureInside(Path root, Path target) {
        if (!target.normalize().startsWith(root.normalize())) {
            throw new IllegalArgumentException("路径不能离开授权工作区");
        }
    }
}
