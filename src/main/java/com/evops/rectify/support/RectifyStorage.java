package com.evops.rectify.support;

import com.evops.common.BusinessException;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/** 异常整改照片本地落盘：按 整改单/阶段 分目录，库里只存相对路径。 */
@Component
public class RectifyStorage {

    private final RectifyProperties properties;
    private Path root;

    public RectifyStorage(RectifyProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        root = Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("整改照片存储目录建不起来: " + root, e);
        }
    }

    /** 留证照片：photos/整改单ID/阶段/随机名.扩展名；只从原文件名取扩展名，不接受路径穿越。 */
    public String store(Long rectifyId, String phase, String originalName, byte[] data) {
        String relative = "photos/" + rectifyId + "/" + phase + "/" + UUID.randomUUID() + "-" + safeFileName(originalName);
        write(relative, data);
        return relative;
    }

    public byte[] read(String relativePath) {
        try {
            Path target = resolve(relativePath);
            if (!Files.exists(target)) {
                throw new BusinessException("留证照片在存储里找不到了: " + relativePath);
            }
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("读取留证照片失败: " + relativePath, e);
        }
    }

    /** 单子关闭后的归档删除：物理删文件，调用方须先完成“关闭前不许删”的业务校验。 */
    public void deletePhysically(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("删除留证照片失败: " + relativePath, e);
        }
    }

    private void write(String relative, byte[] data) {
        try {
            Path target = resolve(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new UncheckedIOException("留证照片落盘失败: " + relative, e);
        }
    }

    private Path resolve(String relative) {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("非法的整改照片存储路径");
        }
        return target;
    }

    /** 只保留文件名本体与一个安全扩展名，挡掉 ../ 与绝对路径。 */
    private String safeFileName(String originalName) {
        String name = originalName == null ? "photo.jpg" : Paths.get(originalName).getFileName().toString();
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "jpg";
        if (!ext.matches("[a-z0-9]{1,8}")) {
            ext = "jpg";
        }
        String base = dot >= 0 ? name.substring(0, dot) : name;
        if (base.isEmpty()) {
            base = "photo";
        }
        return base + "." + ext;
    }
}
