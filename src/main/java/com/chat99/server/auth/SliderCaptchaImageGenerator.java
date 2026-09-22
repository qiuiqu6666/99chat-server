package com.chat99.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class SliderCaptchaImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(SliderCaptchaImageGenerator.class);

    private static final int TARGET_WIDTH = 320;
    private static final int TARGET_HEIGHT = 170;

    private final List<BufferedImage> backgrounds = new ArrayList<>();

    public SliderCaptchaImageGenerator() {
        loadBackgrounds();
    }

    private void loadBackgrounds() {
        String[] names = {"static/bg1.png", "static/bg2.png", "static/bg3.png", "static/bg4.png"};
        for (String name : names) {
            try {
                ClassPathResource resource = new ClassPathResource(name);
                BufferedImage original = ImageIO.read(resource.getInputStream());
                BufferedImage scaled = scaleTo(original, TARGET_WIDTH, TARGET_HEIGHT);
                backgrounds.add(scaled);
                log.debug("loaded background: {} -> {}x{}", name, TARGET_WIDTH, TARGET_HEIGHT);
            } catch (IOException e) {
                log.warn("failed to load background: {}: {}", name, e.getMessage());
            }
        }
        if (backgrounds.isEmpty()) {
            log.error("no backgrounds loaded, using fallback");
            backgrounds.add(createFallbackBackground());
        }
    }

    private BufferedImage scaleTo(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private BufferedImage createFallbackBackground() {
        BufferedImage img = new BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 210, 230));
        g.fillRect(0, 0, TARGET_WIDTH, TARGET_HEIGHT);
        g.dispose();
        return img;
    }

    /**
     * 生成验证码背景图。
     *
     * 后端只负责生成随机背景图，不在图上绘制缺口或拼图块。
     * 真缺口、假缺口、拼图块等视觉元素由前端根据接口返回的坐标数据自行渲染。
     *
     * @return 背景图字节数组
     */
    public byte[] generateBackground() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        BufferedImage bg = backgrounds.get(rnd.nextInt(backgrounds.size()));
        return toBytes(bg);
    }

    private byte[] toBytes(BufferedImage img) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("failed to encode image", e);
        }
    }
}
