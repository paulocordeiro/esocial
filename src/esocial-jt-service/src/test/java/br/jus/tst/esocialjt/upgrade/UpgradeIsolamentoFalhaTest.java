package br.jus.tst.esocialjt.upgrade;

import br.jus.tst.esocialjt.ocorrencia.OcorrenciaRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Garante que uma falha em um upgrade não deixe o serviço inerte.
 *
 * Antes desta correção, a exceção subia até o OnStartup e impedia o agendamento do ciclo de
 * envio: o container subia saudável, mas nenhum evento era transmitido.
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class UpgradeIsolamentoFalhaTest {

	@Autowired
	private UpgradeServico upgradeServico;

	@Autowired
	private ControleUpgradeRepository controleUpgradeRepository;

	@MockBean
	private OcorrenciaRepository ocorrenciaRepository;

	@Before
	public void limparControle() {
		controleUpgradeRepository.deleteAll();
	}

	/** FR-007: nenhuma falha pode escapar para o restante da inicialização. */
	@Test
	public void naoDevePropagarExcecaoDeUpgrade() {
		when(ocorrenciaRepository.buscarIdsOcorrenciasAposId(anyLong(), any(Pageable.class)))
				.thenThrow(new IllegalStateException("falha simulada de upgrade"));

		assertThatCode(() -> upgradeServico.realizarUpgrades()).doesNotThrowAnyException();
	}

	/**
	 * FR-007: o caso real é OutOfMemoryError, que é Error e não Exception — escaparia de um
	 * catch (Exception). É este teste que justifica a captura de Throwable.
	 */
	@Test
	public void naoDevePropagarErrorDeUpgrade() {
		when(ocorrenciaRepository.buscarIdsOcorrenciasAposId(anyLong(), any(Pageable.class)))
				.thenThrow(new OutOfMemoryError("Java heap space"));

		assertThatCode(() -> upgradeServico.realizarUpgrades()).doesNotThrowAnyException();
	}

	/** FR-009: upgrade que falha não pode ser marcado como concluído. */
	@Test
	public void naoDeveRegistrarUpgradeQueFalhou() {
		when(ocorrenciaRepository.buscarIdsOcorrenciasAposId(anyLong(), any(Pageable.class)))
				.thenThrow(new OutOfMemoryError("Java heap space"));

		upgradeServico.realizarUpgrades();

		assertThat(controleUpgradeRepository.findById(Upgrade.CAMPO_CPF_MATRICULA.codUpgrade))
				.isNotPresent();
	}

	/** Um upgrade que falha não pode impedir os seguintes de executar. */
	@Test
	public void deveExecutarDemaisUpgradesAposUmaFalha() {
		when(ocorrenciaRepository.buscarIdsOcorrenciasAposId(anyLong(), any(Pageable.class)))
				.thenThrow(new OutOfMemoryError("Java heap space"));
		when(ocorrenciaRepository.buscarIdsExclusoesAposId(any(), any(), anyLong(), any(Pageable.class)))
				.thenReturn(Collections.emptyList());
		when(ocorrenciaRepository.buscarRetificacoesAposId(any(), anyLong(), any(Pageable.class)))
				.thenReturn(Collections.emptyList());
		when(ocorrenciaRepository.findIdsParaUpgradePeriodoApuracao(any(), any()))
				.thenReturn(Collections.emptyList());

		upgradeServico.realizarUpgrades();

		// CPF/matrícula falhou, mas os dois seguintes concluíram e foram registrados.
		assertThat(controleUpgradeRepository.findById(Upgrade.CAMPO_CPF_MATRICULA.codUpgrade)).isNotPresent();
		assertThat(controleUpgradeRepository.findById(Upgrade.CAMPO_EXCLUSAO_RETIFICACAO.codUpgrade)).isPresent();
		assertThat(controleUpgradeRepository.findById(Upgrade.CAMPO_PERIODO_APURACAO.codUpgrade)).isPresent();
	}

	/** FR-010: upgrade já registrado não roda de novo. */
	@Test
	public void naoDeveReexecutarUpgradeJaRegistrado() {
		ControleUpgrade concluido = new ControleUpgrade();
		concluido.codUpgrade = Upgrade.CAMPO_CPF_MATRICULA.codUpgrade;
		concluido.desUpgrade = Upgrade.CAMPO_CPF_MATRICULA.desUpgrade;
		controleUpgradeRepository.save(concluido);

		// Se o upgrade rodasse, este stub lançaria OutOfMemoryError e o registro sumiria do teste.
		when(ocorrenciaRepository.buscarIdsOcorrenciasAposId(anyLong(), any(Pageable.class)))
				.thenThrow(new OutOfMemoryError("não deveria ser chamado"));
		when(ocorrenciaRepository.buscarIdsExclusoesAposId(any(), any(), anyLong(), any(Pageable.class)))
				.thenReturn(Collections.emptyList());
		when(ocorrenciaRepository.buscarRetificacoesAposId(any(), anyLong(), any(Pageable.class)))
				.thenReturn(Collections.emptyList());
		when(ocorrenciaRepository.findIdsParaUpgradePeriodoApuracao(any(), any()))
				.thenReturn(Collections.emptyList());

		upgradeServico.realizarUpgrades();

		assertThat(controleUpgradeRepository.findById(Upgrade.CAMPO_CPF_MATRICULA.codUpgrade)).isPresent();
	}
}
