package com.gestaosla.backend.relatorio.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.librepdf.openpdf.fonts.Liberation;
import org.springframework.stereotype.Component;

import com.gestaosla.backend.relatorio.internal.RelatorioSlaService.GrupoCliente;
import com.gestaosla.backend.relatorio.internal.RelatorioSlaService.GrupoTecnico;
import com.gestaosla.backend.relatorio.internal.RelatorioSlaService.Metricas;
import com.gestaosla.backend.relatorio.internal.RelatorioSlaService.RelatorioSlaResposta;
import com.gestaosla.backend.relatorio.internal.RelatorioSlaService.RelogioMetricas;
import com.gestaosla.backend.relatorio.internal.RelatorioSlaService.RelatorioSlaResposta;

@Component
class RelatorioSlaExportador {

    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss z");
    private static final DateTimeFormatter DATA_ARQUIVO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Clock clock;

    RelatorioSlaExportador(Clock clock) {
        this.clock = clock;
    }

    byte[] csv(RelatorioSlaDadosExportacao dados) {
        RelatorioSlaResposta relatorio = dados.relatorio();
        StringBuilder csv = new StringBuilder(
                "\uFEFFSeção;Cliente;Cliente UUID;Técnico;Técnico UUID;Relógio;Cumpridos;Violados;Percentual\r\n");
        relatorio.geral().forEach(l -> linhaCsv(csv, "geral", "", "", "", "", l));
        relatorio.porCliente().forEach(g -> g.relogios().forEach(l ->
                linhaCsv(csv, "cliente", nome(dados.razoesSociais(), g.clienteId()), g.clienteId().toString(), "", "", l)));
        relatorio.porTecnico().forEach(g -> g.relogios().forEach(l ->
                linhaCsv(csv, "tecnico", "", "", nome(dados.nomesTecnicos(), g.tecnicoSub()),
                        g.tecnicoSub().toString(), l)));
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void linhaCsv(StringBuilder csv, String secao, String cliente, String clienteId, String tecnico,
            String tecnicoSub, RelogioMetricas relogio) {
        Metricas m = relogio.metricas();
        csv.append(campoCsv(secao)).append(';').append(campoCsv(cliente)).append(';').append(campoCsv(clienteId)).append(';')
                .append(campoCsv(tecnico)).append(';').append(campoCsv(tecnicoSub)).append(';')
                .append(campoCsv(relogio.tipo())).append(';')
                .append(m.cumpridos()).append(';').append(m.violados()).append(';')
                .append(m.percentual().toPlainString().replace('.', ',')).append("\r\n");
    }

    private String campoCsv(String valor) {
        if (valor.indexOf(';') < 0 && valor.indexOf('"') < 0 && valor.indexOf('\r') < 0 && valor.indexOf('\n') < 0) {
            return valor;
        }
        return '"' + valor.replace("\"", "\"\"") + '"';
    }

    byte[] pdf(RelatorioSlaDadosExportacao dados, Instant inicio, Instant fim) {
        RelatorioSlaResposta relatorio = dados.relatorio();
        try (ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            Document documento = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(documento, saida);
            documento.open();
            Font titulo = Liberation.SANS_BOLD.create(16);
            Font texto = Liberation.SANS.create(9);
            Font secao = Liberation.SANS_BOLD.create(12);
            Font cabecalho = Liberation.SANS_BOLD.create(8);

            Paragraph tituloRelatorio = new Paragraph("Relatório de cumprimento de SLA", titulo);
            tituloRelatorio.setSpacingAfter(10);
            documento.add(tituloRelatorio);
            documento.add(new Paragraph("Período: " + DATA_HORA.format(inicio.atZone(clock.getZone())) + " até "
                    + DATA_HORA.format(fim.atZone(clock.getZone())), texto));
            documento.add(new Paragraph("Cliente: " + valorFiltro(dados.razoesSociais(), dados.clienteFiltro()), texto));
            documento.add(new Paragraph("Técnico: " + valorFiltro(dados.nomesTecnicos(), dados.tecnicoFiltro()), texto));
            documento.add(new Paragraph("Gerado em: " + DATA_HORA.format(Instant.now(clock).atZone(clock.getZone())), texto));
            documento.add(new Paragraph(" "));

            adicionarSecao(documento, "Resultado geral", secao);
            adicionarTabelaGeral(documento, relatorio.geral(), texto, cabecalho);
            adicionarSecao(documento, "Por cliente", secao);
            adicionarTabelaGrupos(documento, relatorio.porCliente(), dados.razoesSociais(), texto, cabecalho);
            adicionarSecao(documento, "Por técnico", secao);
            adicionarTabelaTecnicos(documento, relatorio.porTecnico(), dados.nomesTecnicos(), texto, cabecalho);
            documento.close();
            return saida.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Não foi possível gerar o PDF do relatório de SLA.", e);
        }
    }

    private void adicionarSecao(Document documento, String nome, Font fonte) throws DocumentException {
        Paragraph tituloSecao = new Paragraph(nome, fonte);
        tituloSecao.setSpacingBefore(10);
        tituloSecao.setSpacingAfter(5);
        documento.add(tituloSecao);
    }

    private void adicionarTabelaGeral(Document documento, List<RelogioMetricas> linhas, Font texto, Font cabecalho)
            throws DocumentException {
        PdfPTable tabela = tabela(new float[] { 2.2f, 1f, 1f, 1.2f });
        cabecalhos(tabela, cabecalho, "Relógio", "Cumpridos", "Violados", "Percentual");
        linhas.forEach(l -> {
            celula(tabela, l.tipo(), texto);
            metricas(tabela, l.metricas(), texto);
        });
        if (linhas.isEmpty()) celula(tabela, "Sem resultados", texto, 4);
        documento.add(tabela);
    }

    private void adicionarTabelaGrupos(Document documento, List<GrupoCliente> grupos, java.util.Map<UUID, String> nomes,
            Font texto, Font cabecalho)
            throws DocumentException {
        PdfPTable tabela = tabela(new float[] { 3.1f, 1.5f, 1.5f, 1.5f, 1.7f });
        cabecalhos(tabela, cabecalho, "Cliente (nome e UUID)", "Relógio", "Cumpridos", "Violados", "Percentual");
        for (GrupoCliente grupo : grupos) {
            for (RelogioMetricas linha : grupo.relogios()) {
                celula(tabela, identificacao(nomes, grupo.clienteId()), texto);
                celula(tabela, linha.tipo(), texto);
                metricas(tabela, linha.metricas(), texto);
            }
        }
        if (grupos.isEmpty()) celula(tabela, "Sem resultados", texto, 5);
        documento.add(tabela);
    }

    private void adicionarTabelaTecnicos(Document documento, List<GrupoTecnico> grupos, java.util.Map<UUID, String> nomes,
            Font texto, Font cabecalho)
            throws DocumentException {
        PdfPTable tabela = tabela(new float[] { 3.1f, 1.5f, 1.5f, 1.5f, 1.7f });
        cabecalhos(tabela, cabecalho, "Técnico (nome e UUID)", "Relógio", "Cumpridos", "Violados", "Percentual");
        for (GrupoTecnico grupo : grupos) {
            for (RelogioMetricas linha : grupo.relogios()) {
                celula(tabela, identificacao(nomes, grupo.tecnicoSub()), texto);
                celula(tabela, linha.tipo(), texto);
                metricas(tabela, linha.metricas(), texto);
            }
        }
        if (grupos.isEmpty()) celula(tabela, "Sem resultados", texto, 5);
        documento.add(tabela);
    }

    private PdfPTable tabela(float[] larguras) throws DocumentException {
        PdfPTable tabela = new PdfPTable(larguras.length);
        tabela.setWidthPercentage(100);
        tabela.setWidths(larguras);
        tabela.setHeaderRows(1);
        return tabela;
    }

    private void cabecalhos(PdfPTable tabela, Font fonte, String... nomes) {
        for (String nome : nomes) celula(tabela, nome, fonte);
    }

    private void metricas(PdfPTable tabela, Metricas m, Font fonte) {
        celula(tabela, Long.toString(m.cumpridos()), fonte);
        celula(tabela, Long.toString(m.violados()), fonte);
        celula(tabela, m.percentual().toPlainString() + "%", fonte);
    }

    private void celula(PdfPTable tabela, String valor, Font fonte) {
        celula(tabela, valor, fonte, 1);
    }

    private void celula(PdfPTable tabela, String valor, Font fonte, int colspan) {
        org.openpdf.text.pdf.PdfPCell celula = new org.openpdf.text.pdf.PdfPCell(new Phrase(valor, fonte));
        celula.setPadding(4);
        celula.setColspan(colspan);
        celula.setHorizontalAlignment(Element.ALIGN_LEFT);
        tabela.addCell(celula);
    }

    private String identificacao(java.util.Map<UUID, String> nomes, UUID id) {
        return nome(nomes, id) + "\nUUID: " + id;
    }

    private String nome(java.util.Map<UUID, String> nomes, UUID id) {
        return nomes.getOrDefault(id, "Nome não localizado");
    }

    private String valorFiltro(java.util.Map<UUID, String> nomes, UUID id) {
        return id == null ? "Todos" : identificacao(nomes, id);
    }

    static String nomeArquivo(String extensao, Instant inicio, Instant fim, ZoneId fuso) {
        return "relatorio-sla_" + DATA_ARQUIVO.format(inicio.atZone(fuso)) + "_a_"
                + DATA_ARQUIVO.format(fim.atZone(fuso)) + "." + extensao;
    }
}
