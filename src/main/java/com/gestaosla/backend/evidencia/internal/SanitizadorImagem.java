package com.gestaosla.backend.evidencia.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RegraNegocioException;

/**
 * Valida e sanitiza a imagem recebida (RN-75, RN-76): o tipo vem da assinatura dos bytes, nunca do nome nem do
 * Content-Type; as dimensões vêm do cabeçalho e são conferidas contra o teto antes de decodificar; a orientação do EXIF
 * é aplicada aos pixels e a imagem é regravada do zero, sem nenhum metadado (EXIF, GPS, miniatura embutida).
 * JPEG e WebP saem em JPEG (qualidade 0,92, fundo branco onde havia transparência); PNG sai em PNG. Público só para o
 * teste em unit/.
 */
@Component
public class SanitizadorImagem {

    /** Bytes finais da imagem e o que se grava com eles. */
    public record Resultado(byte[] bytes, String tipoConteudo, String extensao) {
    }

    private static final float QUALIDADE_JPEG = 0.92f;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final long tetoPixels;

    public SanitizadorImagem(@Value("${app.evidencia.teto-megapixels}") int tetoMegapixels) {
        this.tetoPixels = tetoMegapixels * 1_000_000L;
    }

    public Resultado sanitizar(byte[] bytes) {
        String formato = formato(bytes);
        BufferedImage imagem = decodificar(bytes, formato);
        AffineTransform transformacao = orientacao(bytes, imagem.getWidth(), imagem.getHeight());
        boolean png = formato.equals("png");
        boolean girada = transformacao.getShearX() != 0 || transformacao.getShearY() != 0;
        int largura = girada ? imagem.getHeight() : imagem.getWidth();
        int altura = girada ? imagem.getWidth() : imagem.getHeight();
        boolean alfa = png && imagem.getColorModel().hasAlpha();
        BufferedImage saida = new BufferedImage(largura, altura, alfa ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = saida.createGraphics();
        try {
            if (!png) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, largura, altura);
            }
            g.drawImage(imagem, transformacao, null);
        } finally {
            g.dispose();
        }
        try {
            return png ? new Resultado(escrever(saida, "png", null), "image/png", "png")
                    : new Resultado(escrever(saida, "jpeg", QUALIDADE_JPEG), "image/jpeg", "jpg");
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao regravar a imagem sanitizada", e);
        }
    }

    /** JPEG (FF D8 FF), PNG (89 50 4E 47 0D 0A 1A 0A) ou WebP ("RIFF" + 4 bytes + "WEBP"). */
    private static String formato(byte[] b) {
        if (b.length >= 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF) {
            return "jpeg";
        }
        if (b.length >= PNG.length && java.util.Arrays.equals(b, 0, PNG.length, PNG, 0, PNG.length)) {
            return "png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        throw new RegraNegocioException(CodigoErro.FORMATO_NAO_SUPORTADO, "Só são aceitas imagens JPEG, PNG ou WebP.");
    }

    private BufferedImage decodificar(byte[] bytes, String formato) {
        try (ImageInputStream entrada = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> leitores = ImageIO.getImageReadersByFormatName(formato);
            if (!leitores.hasNext()) {
                throw new IllegalStateException("Nenhum leitor de imagem para " + formato);
            }
            ImageReader leitor = leitores.next();
            try {
                leitor.setInput(entrada, false, true);
                // Só o cabeçalho: um arquivo pequeno pode declarar bilhões de pixels (bomba de descompressão).
                long pixels = (long) leitor.getWidth(0) * leitor.getHeight(0);
                if (pixels > tetoPixels) {
                    throw new RegraNegocioException(CodigoErro.IMAGEM_MUITO_GRANDE,
                            "A imagem tem mais de " + tetoPixels / 1_000_000 + " megapixels.");
                }
                return leitor.read(0);
            } finally {
                leitor.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof RegraNegocioException regra) {
                throw regra;
            }
            throw new RegraNegocioException(CodigoErro.IMAGEM_INVALIDA, "A imagem está corrompida ou não pôde ser lida.");
        }
    }

    /** Transformação que leva os pixels armazenados à posição de exibição, conforme a orientação EXIF (1 a 8). */
    private static AffineTransform orientacao(byte[] bytes, int w, int h) {
        int valor = 1;
        try {
            ExifIFD0Directory exif = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes))
                    .getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                valor = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            // EXIF ilegível não invalida a foto: sem orientação conhecida, fica como está.
        }
        return switch (valor) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
    }

    /** Também usado pelo {@link Redimensionador}: JPEG ou PNG regravado do zero, sem metadados. */
    static byte[] escrever(BufferedImage imagem, String formato, Float qualidade) throws IOException {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        ImageWriter escritor = ImageIO.getImageWritersByFormatName(formato).next();
        try (ImageOutputStream destino = ImageIO.createImageOutputStream(saida)) {
            escritor.setOutput(destino);
            ImageWriteParam parametros = escritor.getDefaultWriteParam();
            if (qualidade != null) {
                parametros.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parametros.setCompressionQuality(qualidade);
            }
            escritor.write(null, new IIOImage(imagem, null, null), parametros);
        } finally {
            escritor.dispose();
        }
        return saida.toByteArray();
    }
}
