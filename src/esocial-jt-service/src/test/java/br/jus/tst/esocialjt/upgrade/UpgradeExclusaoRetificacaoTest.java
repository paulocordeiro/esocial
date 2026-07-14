package br.jus.tst.esocialjt.upgrade;

import br.jus.tst.esocial.dominio.empregador.IdeEmpregador;
import br.jus.tst.esocial.dominio.exclusao.InfoExclusao;
import br.jus.tst.esocial.ocorrencia.TipoOcorrencia;
import br.jus.tst.esocial.ocorrencia.dados.Admissao;
import br.jus.tst.esocial.ocorrencia.dados.Exclusao;
import br.jus.tst.esocialjt.dominio.Estado;
import br.jus.tst.esocialjt.dominio.Evento;
import br.jus.tst.esocialjt.dominio.Ocorrencia;
import br.jus.tst.esocialjt.dominio.TipoEvento;
import br.jus.tst.esocialjt.ocorrencia.OcorrenciaRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.persistence.EntityManager;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cobre as regras do upgrade de exclusão/retificação sobre massa sintética.
 *
 * Os critérios de memória (SC-001, SC-002) não são verificáveis aqui: dependem de cópia da base
 * real, conforme quickstart.md, Nível 2.
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class UpgradeExclusaoRetificacaoTest {

	@Autowired
	private UpgradeServico upgradeServico;

	@Autowired
	private OcorrenciaRepository ocorrenciaRepository;

	@Autowired
	private ControleUpgradeRepository controleUpgradeRepository;

	@Autowired
	private EntityManager entityManager;

	@Before
	public void limparBase() {
		ocorrenciaRepository.deleteAll();
		controleUpgradeRepository.deleteAll();
	}

	@Test
	public void deveVincularOcorrenciaExcluida() {
		Ocorrencia alvo = criarOcorrenciaProcessada("R001", TipoEvento.ADMISSAO_TRABALHADOR);
		Ocorrencia exclusao = criarExclusaoProcessada("R001");

		upgradeServico.realizaUpgradeExclusaoRetificacao();

		assertThat(recarregar(alvo).getOcorrenciaExclusaoId()).isEqualTo(exclusao.getId());
	}

	@Test
	public void deveVincularOcorrenciaRetificada() {
		Ocorrencia alvo = criarOcorrenciaProcessada("R002", TipoEvento.ADMISSAO_TRABALHADOR);
		Ocorrencia retificacao = criarRetificacaoProcessada("R002");

		upgradeServico.realizaUpgradeExclusaoRetificacao();

		assertThat(recarregar(alvo).getOcorrenciaRetificacaoId()).isEqualTo(retificacao.getId());
	}

	/** FR-003: com mais de uma exclusão para o mesmo recibo, vence a de maior id. */
	@Test
	public void deveDesempatarExclusoesPelaDeMaiorId() {
		Ocorrencia alvo = criarOcorrenciaProcessada("R003", TipoEvento.ADMISSAO_TRABALHADOR);
		criarExclusaoProcessada("R003");
		Ocorrencia exclusaoMaisRecente = criarExclusaoProcessada("R003");

		upgradeServico.realizaUpgradeExclusaoRetificacao();

		assertThat(recarregar(alvo).getOcorrenciaExclusaoId()).isEqualTo(exclusaoMaisRecente.getId());
	}

	/** FR-003: o desempate precisa ser determinístico, e não depender da ordem do banco. */
	@Test
	public void deveDesempatarDeFormaRepetivel() {
		Ocorrencia alvo = criarOcorrenciaProcessada("R004", TipoEvento.ADMISSAO_TRABALHADOR);
		criarExclusaoProcessada("R004");
		Ocorrencia exclusaoMaisRecente = criarExclusaoProcessada("R004");

		upgradeServico.realizaUpgradeExclusaoRetificacao();
		Long primeiraExecucao = recarregar(alvo).getOcorrenciaExclusaoId();

		upgradeServico.realizaUpgradeExclusaoRetificacao();
		Long segundaExecucao = recarregar(alvo).getOcorrenciaExclusaoId();

		assertThat(primeiraExecucao).isEqualTo(exclusaoMaisRecente.getId());
		assertThat(segundaExecucao).isEqualTo(exclusaoMaisRecente.getId());
	}

	/** FR-004: alvo que não está PROCESSADO_COM_SUCESSO não pode ser tocado. */
	@Test
	public void naoDeveVincularAlvoNaoProcessadoComSucesso() {
		Ocorrencia alvo = criarOcorrencia("R005", TipoEvento.ADMISSAO_TRABALHADOR, Estado.EM_FILA);
		criarExclusaoProcessada("R005");

		upgradeServico.realizaUpgradeExclusaoRetificacao();

		assertThat(recarregar(alvo).getOcorrenciaExclusaoId()).isNull();
	}

	@Test
	public void deveVincularExclusaoERetificacaoNaMesmaOcorrencia() {
		Ocorrencia alvo = criarOcorrenciaProcessada("R006", TipoEvento.ADMISSAO_TRABALHADOR);
		Ocorrencia exclusao = criarExclusaoProcessada("R006");
		Ocorrencia retificacao = criarRetificacaoProcessada("R006");

		upgradeServico.realizaUpgradeExclusaoRetificacao();

		Ocorrencia atualizada = recarregar(alvo);
		assertThat(atualizada.getOcorrenciaExclusaoId()).isEqualTo(exclusao.getId());
		assertThat(atualizada.getOcorrenciaRetificacaoId()).isEqualTo(retificacao.getId());
	}

	@Test
	public void deveIgnorarExclusaoComReciboOrfao() {
		Ocorrencia alvo = criarOcorrenciaProcessada("R007", TipoEvento.ADMISSAO_TRABALHADOR);
		criarExclusaoProcessada("RECIBO-INEXISTENTE");

		upgradeServico.realizaUpgradeExclusaoRetificacao();

		assertThat(recarregar(alvo).getOcorrenciaExclusaoId()).isNull();
	}

	@Test
	public void deveConcluirComBaseVazia() {
		upgradeServico.upgradeExclusaoRetificacao();

		assertThat(controleUpgradeRepository.findById(Upgrade.CAMPO_EXCLUSAO_RETIFICACAO.codUpgrade))
				.isPresent();
	}

	/** FR-015: dados ilegíveis não podem abortar o upgrade inteiro. */
	@Test
	public void deveIgnorarOcorrenciaComDadosInvalidosESeguir() {
		criarExclusaoComDadosInvalidos();
		Ocorrencia alvo = criarOcorrenciaProcessada("R008", TipoEvento.ADMISSAO_TRABALHADOR);
		Ocorrencia exclusao = criarExclusaoProcessada("R008");

		upgradeServico.realizaUpgradeExclusaoRetificacao();

		assertThat(recarregar(alvo).getOcorrenciaExclusaoId()).isEqualTo(exclusao.getId());
	}

	/** FR-011: reexecutar recomeça do zero e chega ao mesmo resultado. */
	@Test
	public void deveSerIdempotenteEmDuasExecucoes() {
		Ocorrencia alvo = criarOcorrenciaProcessada("R009", TipoEvento.ADMISSAO_TRABALHADOR);
		Ocorrencia exclusao = criarExclusaoProcessada("R009");

		upgradeServico.realizaUpgradeExclusaoRetificacao();
		upgradeServico.realizaUpgradeExclusaoRetificacao();

		assertThat(recarregar(alvo).getOcorrenciaExclusaoId()).isEqualTo(exclusao.getId());
	}

	// ---------- massa de teste ----------

	private Ocorrencia recarregar(Ocorrencia ocorrencia) {
		entityManager.clear();
		return ocorrenciaRepository.findById(ocorrencia.getId()).orElseThrow(IllegalStateException::new);
	}

	private Ocorrencia criarOcorrenciaProcessada(String nrRecibo, TipoEvento tipoEvento) {
		return criarOcorrencia(nrRecibo, tipoEvento, Estado.PROCESSADO_COM_SUCESSO);
	}

	private Ocorrencia criarOcorrencia(String nrRecibo, TipoEvento tipoEvento, Estado estado) {
		Ocorrencia ocorrencia = new Ocorrencia();
		ocorrencia.setTipoOcorrencia(TipoOcorrencia.ADMISSAO_TRABALHADOR);
		ocorrencia.setReferencia(nrRecibo);
		ocorrencia.setDataOcorrencia(new Date());
		ocorrencia.setDadosOcorrencia(admissao());
		vincularEvento(ocorrencia, nrRecibo, tipoEvento, estado);
		return ocorrenciaRepository.saveAndFlush(ocorrencia);
	}

	private Ocorrencia criarExclusaoProcessada(String nrRecEvtAlvo) {
		Ocorrencia ocorrencia = new Ocorrencia();
		ocorrencia.setTipoOcorrencia(TipoOcorrencia.EXCLUSAO);
		ocorrencia.setReferencia("EXC-" + nrRecEvtAlvo);
		ocorrencia.setDataOcorrencia(new Date());

		Exclusao dados = new Exclusao();
		dados.setIdeEmpregador(ideEmpregador());
		dados.setInfoExclusao(new InfoExclusao());
		dados.getInfoExclusao().setTpEvento("S-2200");
		dados.getInfoExclusao().setNrRecEvt(nrRecEvtAlvo);
		ocorrencia.setDadosOcorrencia(dados);

		// A própria exclusão tem recibo próprio, distinto do que ela referencia.
		vincularEvento(ocorrencia, "REC-EXC-" + System.nanoTime(), TipoEvento.EXCLUSAO,
				Estado.PROCESSADO_COM_SUCESSO);
		return ocorrenciaRepository.saveAndFlush(ocorrencia);
	}

	private Ocorrencia criarRetificacaoProcessada(String reciboRetificado) {
		Ocorrencia ocorrencia = new Ocorrencia();
		ocorrencia.setTipoOcorrencia(TipoOcorrencia.ADMISSAO_TRABALHADOR);
		ocorrencia.setReferencia("RET-" + reciboRetificado);
		ocorrencia.setDataOcorrencia(new Date());
		ocorrencia.setDadosOcorrencia(admissao());
		ocorrencia.setRetificarRecibo(reciboRetificado);
		vincularEvento(ocorrencia, "REC-RET-" + System.nanoTime(), TipoEvento.ADMISSAO_TRABALHADOR,
				Estado.PROCESSADO_COM_SUCESSO);
		return ocorrenciaRepository.saveAndFlush(ocorrencia);
	}

	/** Grava JSON que não corresponde a nenhum tipo conhecido, simulando dado legado ilegível. */
	private void criarExclusaoComDadosInvalidos() {
		Ocorrencia ocorrencia = new Ocorrencia();
		ocorrencia.setTipoOcorrencia(TipoOcorrencia.EXCLUSAO);
		ocorrencia.setReferencia("EXC-INVALIDA");
		ocorrencia.setDataOcorrencia(new Date());
		ocorrencia.setDadosOcorrencia(new Exclusao()); // sem infoExclusao
		vincularEvento(ocorrencia, "REC-EXC-INV-" + System.nanoTime(), TipoEvento.EXCLUSAO,
				Estado.PROCESSADO_COM_SUCESSO);
		ocorrenciaRepository.saveAndFlush(ocorrencia);
	}

	private void vincularEvento(Ocorrencia ocorrencia, String nrRecibo, TipoEvento tipoEvento, Estado estado) {
		Evento evento = new Evento();
		evento.setNrRecibo(nrRecibo);
		evento.setTipoEvento(tipoEvento);
		evento.setEstado(estado);
		evento.setOcorrencia(ocorrencia);
		ocorrencia.setEvento(evento);
	}

	private Admissao admissao() {
		Admissao admissao = new Admissao();
		admissao.setIdeEmpregador(ideEmpregador());
		return admissao;
	}

	private IdeEmpregador ideEmpregador() {
		IdeEmpregador ideEmpregador = new IdeEmpregador();
		ideEmpregador.setNrInsc("00509968000148");
		ideEmpregador.setTpInsc((byte) 1);
		return ideEmpregador;
	}
}
