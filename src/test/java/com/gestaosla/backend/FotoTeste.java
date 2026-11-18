package com.gestaosla.backend;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/**
 * Imagens de teste geradas em memória. A "foto de celular" é um JPEG de quatro quadrantes (vermelho, verde, azul e
 * amarelo) com um segmento APP1 Exif montado à mão, com a orientação pedida e, opcionalmente, GPS: dá para conferir
 * pelas cores se a rotação foi aplicada, e pelos bytes se o EXIF sumiu.
 */
public final class FotoTeste {

    public static final int LARGURA = 48;
    public static final int ALTURA = 32;
    public static final int VERMELHO = 0xFF0000;
    public static final int VERDE = 0x00FF00;
    public static final int AZUL = 0x0000FF;
    public static final int AMARELO = 0xFFFF00;

    private FotoTeste() {
    }

    /** 48x32: topo-esquerda vermelho, topo-direita verde, baixo-esquerda azul, baixo-direita amarelo. */
    public static BufferedImage quadrantes() {
        BufferedImage imagem = new BufferedImage(LARGURA, ALTURA, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < ALTURA; y++) {
            for (int x = 0; x < LARGURA; x++) {
                boolean direita = x >= LARGURA / 2;
                boolean baixo = y >= ALTURA / 2;
                imagem.setRGB(x, y, baixo ? (direita ? AMARELO : AZUL) : (direita ? VERDE : VERMELHO));
            }
        }
        return imagem;
    }

    public static byte[] jpegComExif(int orientacao, boolean comGps) {
        byte[] jpeg = escrever(quadrantes(), "jpeg");
        byte[] exif = app1Exif(orientacao, comGps);
        ByteBuffer saida = ByteBuffer.allocate(jpeg.length + exif.length);
        saida.put(jpeg, 0, 2).put(exif).put(jpeg, 2, jpeg.length - 2); // logo depois do SOI
        return saida.array();
    }

    public static byte[] png(BufferedImage imagem) {
        return escrever(imagem, "png");
    }

    /** Cabeçalho PNG válido (assinatura + IHDR) declarando as dimensões; não há pixels: decodificar falharia. */
    public static byte[] pngCabecalhoDeclarando(int largura, int altura) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(largura).putInt(altura).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update("IHDR".getBytes());
        crc.update(ihdr.array());
        return ByteBuffer.allocate(8 + 4 + 4 + 13 + 4)
                .put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})
                .putInt(13).put("IHDR".getBytes()).put(ihdr.array()).putInt((int) crc.getValue()).array();
    }

    /** SOI + SOF0 declarando as dimensões (até 65535), sem dados de imagem. */
    public static byte[] jpegCabecalhoDeclarando(int largura, int altura) {
        return ByteBuffer.allocate(2 + 2 + 17)
                .put(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0})
                .putShort((short) 17).put((byte) 8).putShort((short) altura).putShort((short) largura).put((byte) 3)
                .put(new byte[] {1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1}).array();
    }

    private static byte[] escrever(BufferedImage imagem, String formato) {
        try {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            ImageIO.write(imagem, formato, saida);
            return saida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** APP1 "Exif": TIFF little-endian, IFD0 com Orientation (e GPSInfo) e, se pedido, um GPS IFD com latitude e longitude. */
    private static byte[] app1Exif(int orientacao, boolean comGps) {
        ByteBuffer tiff = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        int entradas = comGps ? 2 : 1;
        tiff.putShort((short) entradas);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientacao).putShort((short) 0); // SHORT
        int gpsIfd = 8 + 2 + entradas * 12 + 4;
        if (comGps) {
            tiff.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsIfd); // GPSInfo: LONG com o offset
        }
        tiff.putInt(0); // sem IFD1
        if (comGps) {
            int dadosGps = gpsIfd + 2 + 4 * 12 + 4;
            tiff.putShort((short) 4);
            tiff.putShort((short) 1).putShort((short) 2).putInt(2).put((byte) 'S').put((byte) 0).putShort((short) 0);
            tiff.putShort((short) 2).putShort((short) 5).putInt(3).putInt(dadosGps);
            tiff.putShort((short) 3).putShort((short) 2).putInt(2).put((byte) 'W').put((byte) 0).putShort((short) 0);
            tiff.putShort((short) 4).putShort((short) 5).putInt(3).putInt(dadosGps + 24);
            tiff.putInt(0);
            // 3°43'56" S e 38°31'38" W (Fortaleza), em racionais graus/minutos/segundos
            for (int valor : new int[] {3, 43, 56, 38, 31, 38}) {
                tiff.putInt(valor).putInt(1);
            }
        }
        byte[] dados = java.util.Arrays.copyOf(tiff.array(), tiff.position());
        ByteBuffer app1 = ByteBuffer.allocate(2 + 2 + 6 + dados.length);
        app1.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (2 + 6 + dados.length));
        app1.put(new byte[] {'E', 'x', 'i', 'f', 0, 0}).put(dados);
        return app1.array();
    }
}
