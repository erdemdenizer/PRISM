package prism.srm.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The application icon (packaging/icons/README.md): every window size is in the resources at its own size, and the
 * files the images pass to jpackage are there and well formed. Read without JavaFX, so it runs in the plain gate.
 */
class AppIconTest {

    private static final Path ICONS = Path.of("packaging", "icons");

    @Test
    void everyWindowIconIsThereAtItsOwnSize() throws IOException {
        for (int size : MainWindow.ICON_SIZES) {
            try (InputStream in = MainWindow.class.getResourceAsStream("icon/prism-" + size + ".png")) {
                assertThat(in).as("icon/prism-%d.png", size).isNotNull();
                BufferedImage image = ImageIO.read(in);
                assertThat(image.getWidth()).as("width of %d", size).isEqualTo(size);
                assertThat(image.getHeight()).as("height of %d", size).isEqualTo(size);
                // The tile is opaque in its centre and transparent nowhere it should be: the centre pixel is drawn.
                int alpha = image.getRGB(size / 2, size / 2) >>> 24;
                assertThat(alpha).as("centre of %d is opaque", size).isEqualTo(0xFF);
            }
        }
    }

    @Test
    void theWindowsIconHoldsEverySizeTheTaskbarAsksFor() throws IOException {
        ByteBuffer ico =
                ByteBuffer.wrap(Files.readAllBytes(ICONS.resolve("prism.ico"))).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(ico.getShort(0)).as("reserved").isZero();
        assertThat(ico.getShort(2)).as("type icon").isEqualTo((short) 1);
        int count = ico.getShort(4);
        java.util.Set<Integer> sizes = new java.util.TreeSet<>();
        for (int i = 0; i < count; i++) {
            int width = Byte.toUnsignedInt(ico.get(6 + 16 * i));
            sizes.add(width == 0 ? 256 : width);
            int length = ico.getInt(6 + 16 * i + 8);
            int offset = ico.getInt(6 + 16 * i + 12);
            assertThat(offset + length).as("entry %d inside the file", i).isLessThanOrEqualTo(ico.capacity());
        }
        assertThat(sizes).contains(16, 24, 32, 48, 256);
    }

    @Test
    void theMacIconAndTheLinuxIconAreThere() throws IOException {
        byte[] icns = Files.readAllBytes(ICONS.resolve("prism.icns"));
        assertThat(new String(icns, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("icns");
        assertThat(ByteBuffer.wrap(icns, 4, 4).order(ByteOrder.BIG_ENDIAN).getInt())
                .isEqualTo(icns.length);

        BufferedImage linux = ImageIO.read(ICONS.resolve("prism.png").toFile());
        assertThat(linux.getWidth()).isEqualTo(512);
        assertThat(linux.getHeight()).isEqualTo(512);
    }

    @Test
    void bothImagesPassTheIconToJpackage() throws IOException {
        assertThat(Files.readString(Path.of("packaging", "windows", "PackageImage.java")))
                .contains("\"--icon\"")
                .contains("\"prism.ico\"");
        assertThat(Files.readString(Path.of("packaging", "linux", "PackageImage.java")))
                .contains("\"--icon\"")
                .contains("\"prism.png\"");
    }
}
