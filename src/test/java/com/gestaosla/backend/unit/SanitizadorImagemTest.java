package com.gestaosla.backend.unit;

import static com.gestaosla.backend.FotoTeste.AMARELO;
import static com.gestaosla.backend.FotoTeste.AZUL;
import static com.gestaosla.backend.FotoTeste.VERDE;
import static com.gestaosla.backend.FotoTeste.VERMELHO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import com.gestaosla.backend.FotoTeste;
import com.gestaosla.backend.evidencia.internal.SanitizadorImagem;
import com.gestaosla.backend.shared.api.CodigoErro;
import com.gestaosla.backend.shared.api.RegraNegocioException;

class SanitizadorImagemTest {

    private final SanitizadorImagem sanitizador = new SanitizadorImagem(40);

    // Cantos da imagem exibida (topo-esq, topo-dir, baixo-esq, baixo-dir) para cada orientação EXIF, a partir de
    // vermelho/verde/azul/amarelo no armazenado. Os valores vêm da tabela da especificação EXIF, não do código.
    static Stream<Arguments> orientacoes() {
        return Stream.of(
                Arguments.of(1, false, VERMELHO, VERDE, AZUL, AMARELO),
                Arguments.of(2, false, VERDE, VERMELHO, AMARELO, AZUL),
                Arguments.of(3, false, AMARELO, AZUL, VERDE, VERMELHO),
                Arguments.of(4, false, AZUL, AMARELO, VERMELHO, VERDE),
                Arguments.of(5, true, VERMELHO, AZUL, VERDE, AMARELO),
                Arguments.of(6, true, AZUL, VERMELHO, AMARELO, VERDE),
                Arguments.of(7, true, AMARELO, VERDE, AZUL, VERMELHO),
                Arguments.of(8, true, VERDE, AMARELO, VERMELHO, AZUL));
    }

    @ParameterizedTest
    @MethodSource("orientacoes")
    void shouldApplyExifOrientationToPixels(int orientacao, boolean trocaEixos, int topoEsq, int topoDir, int baixoEsq, int baixoDir)
            throws IOException {
        SanitizadorImagem.Resultado r = sanitizador.sanitizar(FotoTeste.jpegComExif(orientacao, false));

        BufferedImage saida = ler(r.bytes());
        assertThat(saida.getWidth()).isEqualTo(trocaEixos ? FotoTeste.ALTURA : FotoTeste.LARGURA);
        assertThat(saida.getHeight()).isEqualTo(trocaEixos ? FotoTeste.LARGURA : FotoTeste.ALTURA);
        int w = saida.getWidth();
        int h = saida.getHeight();
        assertThat(maisProxima(saida.getRGB(w / 4, h / 4))).as("topo-esquerda").isEqualTo(topoEsq);
        assertThat(maisProxima(saida.getRGB(3 * w / 4, h / 4))).as("topo-direita").isEqualTo(topoDir);
        assertThat(maisProxima(saida.getRGB(w / 4, 3 * h / 4))).as("baixo-esquerda").isEqualTo(baixoEsq);
        assertThat(maisProxima(saida.getRGB(3 * w / 4, 3 * h / 4))).as("baixo-direita").isEqualTo(baixoDir);
    }

    @Test
    void shouldRemoveExifAndGpsFromJpeg() throws Exception {
        byte[] original = FotoTeste.jpegComExif(6, true);
        Metadata antes = ImageMetadataReader.readMetadata(new ByteArrayInputStream(original));
        assertThat(antes.getFirstDirectoryOfType(GpsDirectory.class).getGeoLocation()).isNotNull();
        assertThat(antes.getFirstDirectoryOfType(ExifIFD0Directory.class).getInt(ExifIFD0Directory.TAG_ORIENTATION)).isEqualTo(6);

        SanitizadorImagem.Resultado r = sanitizador.sanitizar(original);

        Metadata depois = ImageMetadataReader.readMetadata(new ByteArrayInputStream(r.bytes()));
        assertThat(depois.getDirectoriesOfType(ExifIFD0Directory.class)).isEmpty();
        assertThat(depois.getDirectoriesOfType(GpsDirectory.class)).isEmpty();
        assertThat(new String(r.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        assertThat(r.tipoConteudo()).isEqualTo("image/jpeg");
        assertThat(r.extensao()).isEqualTo("jpg");
    }

    @Test
    void shouldConvertWebpToJpeg() throws Exception {
        byte[] webp = getClass().getResourceAsStream("/evidencia/vermelho-azul.webp").readAllBytes();

        SanitizadorImagem.Resultado r = sanitizador.sanitizar(webp);

        assertThat(r.tipoConteudo()).isEqualTo("image/jpeg");
        assertThat(r.extensao()).isEqualTo("jpg");
        BufferedImage saida = ler(r.bytes());
        assertThat(saida.getWidth()).isEqualTo(40);
        assertThat(maisProxima(saida.getRGB(10, 10))).isEqualTo(VERMELHO);
        assertThat(maisProxima(saida.getRGB(30, 10))).isEqualTo(AZUL);
    }

    @Test
    void shouldKeepPngAsPngWithTransparency() throws Exception {
        BufferedImage translucida = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        translucida.setRGB(2, 2, 0x80FF0000);

        SanitizadorImagem.Resultado r = sanitizador.sanitizar(FotoTeste.png(translucida));

        assertThat(r.tipoConteudo()).isEqualTo("image/png");
        assertThat(r.extensao()).isEqualTo("png");
        BufferedImage saida = ler(r.bytes());
        assertThat(saida.getColorModel().hasAlpha()).isTrue();
        assertThat(saida.getRGB(2, 2) >>> 24).isBetween(0x7F, 0x81);
        assertThat(saida.getRGB(0, 0) >>> 24).isZero();
    }

    @Test
    void shouldDecideFormatBySignatureAndRejectTheRest() {
        byte[] pdf = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
        // O tipo não depende de nome nem de Content-Type: o sanitizador só recebe bytes.
        for (byte[] naoImagem : new byte[][] {pdf, "texto".getBytes(), new byte[0], new byte[] {(byte) 0xFF, (byte) 0xD8}}) {
            assertThatThrownBy(() -> sanitizador.sanitizar(naoImagem)).isInstanceOfSatisfying(RegraNegocioException.class,
                    e -> assertThat(e.getCodigo()).isEqualTo(CodigoErro.FORMATO_NAO_SUPORTADO));
        }
    }

    @Test
    void shouldRejectCorruptedImageWithValidSignature() {
        byte[] truncada = FotoTeste.jpegComExif(1, false);
        byte[] corrompida = java.util.Arrays.copyOf(truncada, 40);
        byte[] soAssinaturaPng = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        byte[] riffSemWebp = "RIFF\0\0\0\0WEBPjunk".getBytes(StandardCharsets.ISO_8859_1);
        for (byte[] bytes : new byte[][] {corrompida, soAssinaturaPng, riffSemWebp}) {
            assertThatThrownBy(() -> sanitizador.sanitizar(bytes)).isInstanceOfSatisfying(RegraNegocioException.class,
                    e -> assertThat(e.getCodigo()).isEqualTo(CodigoErro.IMAGEM_INVALIDA));
        }
    }

    // Um arquivo de poucas dezenas de bytes declara 10 bilhões de pixels: decodificar pediria dezenas de GB. A rejeição
    // pelo cabeçalho é imediata; se o código decodificasse, o teste estouraria a memória ou o tempo.
    @Test
    void shouldRejectHugeDimensionsFromHeaderWithoutDecoding() {
        for (byte[] bytes : new byte[][] {FotoTeste.pngCabecalhoDeclarando(100_000, 100_000), FotoTeste.jpegCabecalhoDeclarando(65_535, 65_535)}) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThatThrownBy(() -> sanitizador.sanitizar(bytes)).isInstanceOfSatisfying(RegraNegocioException.class,
                            e -> assertThat(e.getCodigo()).isEqualTo(CodigoErro.IMAGEM_MUITO_GRANDE)));
        }
    }

    @Test
    void shouldApplyTheMegapixelCeilingExactly() {
        SanitizadorImagem umMegapixel = new SanitizadorImagem(1);

        assertThat(umMegapixel.sanitizar(FotoTeste.png(new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB))).extensao()).isEqualTo("png");
        assertThatThrownBy(() -> umMegapixel.sanitizar(FotoTeste.png(new BufferedImage(1001, 1000, BufferedImage.TYPE_INT_RGB))))
                .isInstanceOfSatisfying(RegraNegocioException.class, e -> assertThat(e.getCodigo()).isEqualTo(CodigoErro.IMAGEM_MUITO_GRANDE));
    }

    private static BufferedImage ler(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /** Qual das quatro cores do fixture está mais perto do pixel (o JPEG é com perdas). */
    private static int maisProxima(int rgb) {
        int melhor = 0;
        long menor = Long.MAX_VALUE;
        for (int cor : new int[] {VERMELHO, VERDE, AZUL, AMARELO}) {
            long d = 0;
            for (int deslocamento : new int[] {16, 8, 0}) {
                long diferenca = ((rgb >> deslocamento) & 0xFF) - ((cor >> deslocamento) & 0xFF);
                d += diferenca * diferenca;
            }
            if (d < menor) {
                menor = d;
                melhor = cor;
            }
        }
        return melhor;
    }
}
