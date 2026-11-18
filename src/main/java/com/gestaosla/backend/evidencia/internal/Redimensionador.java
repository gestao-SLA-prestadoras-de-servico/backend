package com.gestaosla.backend.evidencia.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Gera os derivados da evidência (RN-78): JPEG sem metadados com o lado maior limitado, sem ampliar imagem menor que o
 * alvo e com fundo branco onde havia transparência. Reduções grandes vão em passos de 1/2 (bilinear), que preservam
 * mais detalhe que um único salto. Público só para o teste em unit/.
 */
public final class Redimensionador {

    private static final float QUALIDADE_JPEG = 0.85f;

    private Redimensionador() {
    }

    public static byte[] jpeg(BufferedImage origem, int ladoMaior) {
        int maior = Math.max(origem.getWidth(), origem.getHeight());
        double escala = Math.min(1.0, (double) ladoMaior / maior);
        int largura = Math.max(1, (int) Math.round(origem.getWidth() * escala));
        int altura = Math.max(1, (int) Math.round(origem.getHeight() * escala));

        // O primeiro desenho já achata o alfa sobre o branco; os passos seguintes só reduzem.
        BufferedImage atual = desenhar(origem, origem.getWidth(), origem.getHeight(), RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        while (atual.getWidth() / 2 >= largura && atual.getHeight() / 2 >= altura) {
            atual = desenhar(atual, atual.getWidth() / 2, atual.getHeight() / 2, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        if (atual.getWidth() != largura || atual.getHeight() != altura) {
            atual = desenhar(atual, largura, altura, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }
        try {
            return SanitizadorImagem.escrever(atual, "jpeg", QUALIDADE_JPEG);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao gravar o derivado em JPEG", e);
        }
    }

    private static BufferedImage desenhar(BufferedImage origem, int largura, int altura, Object interpolacao) {
        BufferedImage saida = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = saida.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolacao);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, largura, altura);
            g.drawImage(origem, 0, 0, largura, altura, null);
        } finally {
            g.dispose();
        }
        return saida;
    }
}
