package br.jus.tst.esocialjt.upgrade;

import br.jus.tst.esocial.ocorrencia.dados.DadosFechamentoFolha;
import br.jus.tst.esocial.ocorrencia.dados.DadosFolha;
import br.jus.tst.esocialjt.dominio.Estado;
import br.jus.tst.esocialjt.dominio.Ocorrencia;
import br.jus.tst.esocialjt.dominio.TipoEvento;
import br.jus.tst.esocialjt.ocorrencia.OcorrenciaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Upgrades de dados executados na inicialização.
 *
 * Todos percorrem a base em lotes por keyset e delegam cada lote ao {@link UpgradeLoteServico},
 * que abre uma transação e limpa o contexto de persistência ao final. É o que mantém o consumo
 * de memória constante, independente do volume da base.
 */
@Service
public class UpgradeServico {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpgradeServico.class);

    private static final int TAMANHO_LOTE = 100;
    private static final long ID_INICIAL = 0L;

    @Autowired
    ControleUpgradeRepository upgradeRepository;

    @Autowired
    OcorrenciaRepository ocorrenciaRepository;

    @Autowired
    UpgradeLoteServico upgradeLoteServico;

    /**
     * Cada upgrade é isolado: um que falhe não impede os seguintes, nem o restante da
     * inicialização. Sem isso, uma falha aqui deixa o serviço no ar sem nunca agendar o ciclo
     * de envio.
     */
    public void realizarUpgrades() {
        executarIsolado(Upgrade.CAMPO_CPF_MATRICULA, this::upgradeCpfMatricula);
        executarIsolado(Upgrade.CAMPO_EXCLUSAO_RETIFICACAO, this::upgradeExclusaoRetificacao);
        executarIsolado(Upgrade.CAMPO_PERIODO_APURACAO, this::upgradePeriodoApuracao);
    }

    /** Captura Throwable, e não Exception: OutOfMemoryError é Error e escaparia da captura. */
    private void executarIsolado(Upgrade upgrade, Runnable execucao) {
        try {
            execucao.run();
        } catch (Throwable falha) {
            LOGGER.error("Upgrade '{}' (código {}) falhou e não foi registrado como concluído; "
                            + "será tentado novamente na próxima inicialização",
                    upgrade.desUpgrade, upgrade.codUpgrade, falha);
        }
    }

    public void upgradeCpfMatricula() {
        Optional<ControleUpgrade> upgrade = upgradeRepository.findById(Upgrade.CAMPO_CPF_MATRICULA.codUpgrade);
        if (!upgrade.isPresent()) {
            realizaUpgradeCpfMatricula();
            registrarUpgrade(Upgrade.CAMPO_CPF_MATRICULA);
        }
    }

    public void realizaUpgradeCpfMatricula() {
        LOGGER.info("Upgrade: " + Upgrade.CAMPO_CPF_MATRICULA.desUpgrade);

        long ultimoId = ID_INICIAL;
        int totalAtualizacoes = 0;
        int totalPercorrido = 0;
        List<Long> ids;

        do {
            ids = ocorrenciaRepository.buscarIdsOcorrenciasAposId(ultimoId, lote());

            if (!ids.isEmpty()) {
                totalAtualizacoes += upgradeLoteServico.processarLoteCpfMatricula(ids);
                totalPercorrido += ids.size();
                ultimoId = ids.get(ids.size() - 1);
                LOGGER.info("Atualização de CPF e Matrícula: {} ocorrências percorridas, {} atualizadas",
                        totalPercorrido, totalAtualizacoes);
            }
        } while (ids.size() == TAMANHO_LOTE);

        LOGGER.info("Atualização de CPF e Matrícula concluída. Total de {} ocorrências atualizadas",
                totalAtualizacoes);
    }

    public void upgradeExclusaoRetificacao() {
        Optional<ControleUpgrade> upgrade = upgradeRepository.findById(Upgrade.CAMPO_EXCLUSAO_RETIFICACAO.codUpgrade);
        if (!upgrade.isPresent()) {
            realizaUpgradeExclusaoRetificacao();
            registrarUpgrade(Upgrade.CAMPO_EXCLUSAO_RETIFICACAO);
        }
    }

    /**
     * Percorre as origens (exclusões e retificações) e atinge cada alvo por um UPDATE
     * direcionado pelo recibo, que é indexado. Nenhuma ocorrência-alvo é carregada em memória.
     */
    public void realizaUpgradeExclusaoRetificacao() {
        LOGGER.info("Upgrade: " + Upgrade.CAMPO_EXCLUSAO_RETIFICACAO.desUpgrade);

        int vinculosExclusao = processarExclusoes();
        int vinculosRetificacao = processarRetificacoes();

        LOGGER.info("Processamento de exclusões e retificações concluído. {} vínculos de exclusão e "
                + "{} de retificação criados", vinculosExclusao, vinculosRetificacao);
    }

    private int processarExclusoes() {
        LOGGER.info("Processando exclusões...");

        long ultimoId = ID_INICIAL;
        int vinculos = 0;
        int percorridas = 0;
        List<Long> idsExclusoes;

        do {
            idsExclusoes = ocorrenciaRepository.buscarIdsExclusoesAposId(
                    Estado.PROCESSADO_COM_SUCESSO, TipoEvento.EXCLUSAO, ultimoId, lote());

            if (!idsExclusoes.isEmpty()) {
                vinculos += upgradeLoteServico.processarLoteExclusoes(idsExclusoes);
                percorridas += idsExclusoes.size();
                ultimoId = idsExclusoes.get(idsExclusoes.size() - 1);
                LOGGER.info("Exclusões: {} percorridas, {} vínculos criados", percorridas, vinculos);
            }
        } while (idsExclusoes.size() == TAMANHO_LOTE);

        return vinculos;
    }

    private int processarRetificacoes() {
        LOGGER.info("Processando retificações...");

        long ultimoId = ID_INICIAL;
        int vinculos = 0;
        int percorridas = 0;
        List<Object[]> retificacoes;

        do {
            retificacoes = ocorrenciaRepository.buscarRetificacoesAposId(
                    Estado.PROCESSADO_COM_SUCESSO, ultimoId, lote());

            if (!retificacoes.isEmpty()) {
                vinculos += upgradeLoteServico.processarLoteRetificacoes(retificacoes);
                percorridas += retificacoes.size();
                ultimoId = (Long) retificacoes.get(retificacoes.size() - 1)[0];
                LOGGER.info("Retificações: {} percorridas, {} vínculos criados", percorridas, vinculos);
            }
        } while (retificacoes.size() == TAMANHO_LOTE);

        return vinculos;
    }

    public void upgradePeriodoApuracao() {
        Optional<ControleUpgrade> upgrade = upgradeRepository.findById(Upgrade.CAMPO_PERIODO_APURACAO.codUpgrade);
        if (!upgrade.isPresent()) {
            realizaUpgradePeriodoApuracao();
            registrarUpgrade(Upgrade.CAMPO_PERIODO_APURACAO);
        }
    }

    /**
     * Fora do escopo desta correção: já processa em lotes a partir de uma lista de
     * identificadores. Mantido como estava, de propósito.
     */
    public void realizaUpgradePeriodoApuracao() {
        LOGGER.info("Upgrade: " + Upgrade.CAMPO_PERIODO_APURACAO.desUpgrade);

        // Tipos de eventos de folha: S1200, S1202, S1207, S1210, S1298, S1299
        List<TipoEvento> tiposFolha = Arrays.asList(
                TipoEvento.S1200, TipoEvento.S1202, TipoEvento.S1207,
                TipoEvento.S1210, TipoEvento.S1298, TipoEvento.S1299
        );

        // PASSO 1: Buscar todos os IDs que precisam ser atualizados (apenas IDs, não as entidades)
        LOGGER.info("Buscando IDs das ocorrências sem período de apuração...");
        List<Long> idsParaAtualizar = ocorrenciaRepository.findIdsParaUpgradePeriodoApuracao(
                Estado.PROCESSADO_COM_SUCESSO,
                tiposFolha
        );

        LOGGER.info("Total de {} ocorrências para atualizar", idsParaAtualizar.size());

        if (idsParaAtualizar.isEmpty()) {
            LOGGER.info("Nenhuma ocorrência para atualizar");
            return;
        }

        // PASSO 2: Processar em batch usando os IDs
        int totalAtualizacoes = 0;

        for (int i = 0; i < idsParaAtualizar.size(); i += TAMANHO_LOTE) {
            int fim = Math.min(i + TAMANHO_LOTE, idsParaAtualizar.size());
            List<Long> batchIds = idsParaAtualizar.subList(i, fim);

            LOGGER.info("Processando batch {}-{} de {}", i + 1, fim, idsParaAtualizar.size());

            // Buscar as entidades completas apenas deste batch
            List<Ocorrencia> ocorrencias = ocorrenciaRepository.findAllById(batchIds);
            List<Ocorrencia> ocorrenciasParaAtualizar = new ArrayList<>();

            for (Ocorrencia ocorrencia : ocorrencias) {
                String periodoApuracao = extrairPeriodoApuracao(ocorrencia);

                if (periodoApuracao != null) {
                    ocorrencia.setPeriodoApuracao(periodoApuracao);
                    ocorrenciasParaAtualizar.add(ocorrencia);
                }
            }

            if (!ocorrenciasParaAtualizar.isEmpty()) {
                ocorrenciaRepository.saveAll(ocorrenciasParaAtualizar);
                totalAtualizacoes += ocorrenciasParaAtualizar.size();
                LOGGER.info("Atualizadas {} ocorrências neste batch", ocorrenciasParaAtualizar.size());
            }
        }

        LOGGER.info("Processamento de período de apuração concluído. Total de {} ocorrências atualizadas", totalAtualizacoes);
    }

    private String extrairPeriodoApuracao(Ocorrencia ocorrencia) {
        if (ocorrencia.getDadosOcorrencia() == null) {
            return null;
        }

        // Verifica se implementa DadosFolha
        if (ocorrencia.getDadosOcorrencia() instanceof DadosFolha) {
            DadosFolha dadosFolha = (DadosFolha) ocorrencia.getDadosOcorrencia();
            if (dadosFolha.getIdeEvento() != null) {
                return dadosFolha.getIdeEvento().getPerApur();
            }
        }

        // Verifica se implementa DadosFechamentoFolha
        if (ocorrencia.getDadosOcorrencia() instanceof DadosFechamentoFolha) {
            DadosFechamentoFolha dadosFechamentoFolha = (DadosFechamentoFolha) ocorrencia.getDadosOcorrencia();
            if (dadosFechamentoFolha.getIdeEvento() != null) {
                return dadosFechamentoFolha.getIdeEvento().getPerApur();
            }
        }

        return null;
    }

    private Pageable lote() {
        return PageRequest.of(0, TAMANHO_LOTE);
    }

    private void registrarUpgrade(Upgrade upgrade) {
        ControleUpgrade controleUpgrade = new ControleUpgrade();
        controleUpgrade.codUpgrade = upgrade.codUpgrade;
        controleUpgrade.desUpgrade = upgrade.desUpgrade;
        upgradeRepository.save(controleUpgrade);
    }

}
