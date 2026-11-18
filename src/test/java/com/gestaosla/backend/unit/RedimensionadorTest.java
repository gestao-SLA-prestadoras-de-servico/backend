package com.gestaosla.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.gestaosla.backend.evidencia.internal.Redimensionador;

class RedimensionadorTest {

    @Test
    void shouldLimitTheLongestSideKeepingTheAspectRatio() throws Exception {
        assertThat(tamanho(Redimensionador.jpeg(liso(4000, 3000, Color.RED), 1280))).containsExactly(1280, 960);
        assertThat(tamanho(Redimensionador.jpeg(liso(4000, 3000, Color.RED), 320))).containsExactly(320, 240);
        assertThat(tamanho(Redimensionador.jpeg(liso(3000, 4000, Color.RED), 320))).containsExactly(240, 320);
    }

    @Test
    void shouldNotEnlargeAnImageSmallerThanTheTarget() throws Exception {
        assertThat(tamanho(Redimensionador.jpeg(liso(200, 100, Color.RED), 320))).containsExactly(200, 100);
        assertThat(tamanho(Redimensionador.jpeg(liso(320, 100, Color.RED), 320))).containsExactly(320, 100);
    }

    @Test
    void shouldKeepAtLeastOnePixelOnTheShortSide() throws Exception {
        assertThat(tamanho(Redimensionador.jpeg(liso(20000, 10, Color.RED), 1280))).containsExactly(1280, 1);
    }

    @Test
    void shouldFlattenTransparencyOnWhiteAndKeepColors() throws Exception {
        BufferedImage png = new BufferedImage(1000, 500, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = png.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(500, 0, 500, 500); // esquerda fica transparente
        g.dispose();

        BufferedImage saida = ImageIO.read(new ByteArrayInputStream(Redimensionador.jpeg(png, 320)));

        assertThat(saida.getRGB(10, 10) & 0xFFFFFF).as("transparente vira branco").isGreaterThan(0xF8F8F8);
        assertThat(saida.getRGB(300, 100) & 0xFF).as("azul preservado").isGreaterThan(200);
        assertThat((saida.getRGB(300, 100) >> 16) & 0xFF).isLessThan(40);
    }

    @Test
    void shouldWriteAPlainJpegWithoutMetadata() {
        byte[] jpeg = Redimensionador.jpeg(liso(800, 600, Color.GREEN), 320);

        assertThat(jpeg[0]).isEqualTo((byte) 0xFF);
        assertThat(jpeg[1]).isEqualTo((byte) 0xD8);
        assertThat(new String(jpeg, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    private static BufferedImage liso(int largura, int altura, Color cor) {
        BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagem.createGraphics();
        g.setColor(cor);
        g.fillRect(0, 0, largura, altura);
        g.dispose();
        return imagem;
    }

    private static int[] tamanho(byte[] jpeg) throws Exception {
        BufferedImage imagem = ImageIO.read(new ByteArrayInputStream(jpeg));
        return new int[] {imagem.getWidth(), imagem.getHeight()};
    }
}
