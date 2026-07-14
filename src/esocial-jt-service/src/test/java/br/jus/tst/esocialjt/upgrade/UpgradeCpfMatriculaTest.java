package br.jus.tst.esocialjt.upgrade;

import br.jus.tst.esocial.dominio.empregador.IdeEmpregador;
import br.jus.tst.esocial.dominio.trabalhador.Trabalhador;
import br.jus.tst.esocial.dominio.vinculo.Vinculo;
import br.jus.tst.esocial.ocorrencia.TipoOcorrencia;
import br.jus.tst.esocial.ocorrencia.dados.Admissao;
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
 * Garante que o upgrade de CPF/matrícula preserve o resultado ao passar a percorrer a base por
 * keyset, com transação e limpeza de contexto por lote.
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class UpgradeCpfMatriculaTest {

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
	public void devePreencherCpfEMatriculaAPartirDosDados() {
		Ocorrencia ocorrencia = criarOcorrencia("12345678901", "MAT-001");

		upgradeServico.realizaUpgradeCpfMatricula();

		Ocorrencia atualizada = recarregar(ocorrencia);
		assertThat(atualizada.getCpf()).isEqualTo("12345678901");
		assertThat(atualizada.getMatricula()).isEqualTo("MAT-001");
	}

	/** O upgrade percorre a base inteira, e não apenas o primeiro lote. */
	@Test
	public void devePercorrerAlemDoPrimeiroLote() {
		int totalOcorrencias = 250; // TAMANHO_LOTE é 100: força três lotes
		for (int i = 0; i < totalOcorrencias; i++) {
			criarOcorrencia(String.format("%011d", i), "MAT-" + i);
		}

		upgradeServico.realizaUpgradeCpfMatricula();

		entityManager.clear();
		long semCpf = ocorrenciaRepository.findAll().stream()
				.filter(o -> o.getCpf() == null)
				.count();
		assertThat(semCpf).isZero();
	}

	@Test
	public void deveConcluirComBaseVazia() {
		upgradeServico.upgradeCpfMatricula();

		assertThat(controleUpgradeRepository.findById(Upgrade.CAMPO_CPF_MATRICULA.codUpgrade)).isPresent();
	}

	@Test
	public void deveSerIdempotenteEmDuasExecucoes() {
		Ocorrencia ocorrencia = criarOcorrencia("98765432100", "MAT-999");

		upgradeServico.realizaUpgradeCpfMatricula();
		upgradeServico.realizaUpgradeCpfMatricula();

		assertThat(recarregar(ocorrencia).getCpf()).isEqualTo("98765432100");
	}

	private Ocorrencia recarregar(Ocorrencia ocorrencia) {
		entityManager.clear();
		return ocorrenciaRepository.findById(ocorrencia.getId()).orElseThrow(IllegalStateException::new);
	}

	private Ocorrencia criarOcorrencia(String cpf, String matricula) {
		Ocorrencia ocorrencia = new Ocorrencia();
		ocorrencia.setTipoOcorrencia(TipoOcorrencia.ADMISSAO_TRABALHADOR);
		ocorrencia.setReferencia(matricula);
		ocorrencia.setDataOcorrencia(new Date());

		Admissao admissao = new Admissao();
		IdeEmpregador ideEmpregador = new IdeEmpregador();
		ideEmpregador.setNrInsc("00509968000148");
		ideEmpregador.setTpInsc((byte) 1);
		admissao.setIdeEmpregador(ideEmpregador);

		Trabalhador trabalhador = new Trabalhador();
		trabalhador.setCpfTrab(cpf);
		admissao.setTrabalhador(trabalhador);

		Vinculo vinculo = new Vinculo();
		vinculo.setMatricula(matricula);
		admissao.setVinculo(vinculo);

		ocorrencia.setDadosOcorrencia(admissao);

		Evento evento = new Evento();
		evento.setNrRecibo("REC-" + matricula);
		evento.setTipoEvento(TipoEvento.ADMISSAO_TRABALHADOR);
		evento.setEstado(Estado.PROCESSADO_COM_SUCESSO);
		evento.setOcorrencia(ocorrencia);
		ocorrencia.setEvento(evento);

		return ocorrenciaRepository.saveAndFlush(ocorrencia);
	}
}
