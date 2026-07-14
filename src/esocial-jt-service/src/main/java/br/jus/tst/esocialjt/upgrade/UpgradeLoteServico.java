package br.jus.tst.esocialjt.upgrade;

import br.jus.tst.esocial.ocorrencia.dados.Exclusao;
import br.jus.tst.esocialjt.dominio.Estado;
import br.jus.tst.esocialjt.dominio.Ocorrencia;
import br.jus.tst.esocialjt.ocorrencia.OcorrenciaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Processa um lote de upgrade por transação.
 *
 * Precisa ser um bean separado, e não métodos do {@link UpgradeServico}: {@code @Transactional}
 * é aplicado por proxy e seria silenciosamente ignorado em auto-invocação — a mesma armadilha
 * que anulava o {@code @Async} desta funcionalidade.
 *
 * Cada método encerra limpando o contexto de persistência: paginar sem limpar acumula as
 * entidades de todos os lotes e esgota a memória, que é o defeito original.
 */
@Service
public class UpgradeLoteServico {

	private static final Logger LOGGER = LoggerFactory.getLogger(UpgradeLoteServico.class);

	@Autowired
	OcorrenciaRepository ocorrenciaRepository;

	@PersistenceContext
	EntityManager entityManager;

	/**
	 * Vincula cada ocorrência excluída à sua exclusão. As exclusões precisam ser carregadas
	 * porque o recibo referenciado vive dentro do JSON, e não em uma coluna.
	 */
	@Transactional
	public int processarLoteExclusoes(List<Long> idsExclusoes) {
		int vinculos = 0;

		for (Ocorrencia exclusao : carregarEmOrdemDeId(idsExclusoes)) {
			String reciboExcluido = extrairReciboExcluido(exclusao);
			if (reciboExcluido != null) {
				vinculos += ocorrenciaRepository.vincularExclusao(
						exclusao.getId(), reciboExcluido, Estado.PROCESSADO_COM_SUCESSO);
			}
		}

		entityManager.clear();
		return vinculos;
	}

	/**
	 * Vincula cada ocorrência retificada à sua retificação. Recebe pares (id, retificarRecibo):
	 * ambos são colunas, então nenhuma entidade é carregada.
	 */
	@Transactional
	public int processarLoteRetificacoes(List<Object[]> retificacoes) {
		int vinculos = 0;

		for (Object[] retificacao : retificacoes) {
			Long idRetificacao = (Long) retificacao[0];
			String reciboRetificado = (String) retificacao[1];

			if (reciboRetificado != null) {
				vinculos += ocorrenciaRepository.vincularRetificacao(
						idRetificacao, reciboRetificado, Estado.PROCESSADO_COM_SUCESSO);
			}
		}

		entityManager.clear();
		return vinculos;
	}

	@Transactional
	public int processarLoteCpfMatricula(List<Long> ids) {
		List<Ocorrencia> paraAtualizar = new ArrayList<>();

		for (Ocorrencia ocorrencia : carregarEmOrdemDeId(ids)) {
			if (ocorrencia.getDadosOcorrencia() == null) {
				LOGGER.warn("Ocorrência {} tem dados ilegíveis; CPF e matrícula não atualizados",
						ocorrencia.getId());
				continue;
			}
			ocorrencia.setMatricula(ocorrencia.getDadosOcorrencia().getMatricula());
			ocorrencia.setCpf(ocorrencia.getDadosOcorrencia().getCpf());
			paraAtualizar.add(ocorrencia);
		}

		ocorrenciaRepository.saveAll(paraAtualizar);
		entityManager.flush();
		entityManager.clear();
		return paraAtualizar.size();
	}

	/** findAllById não garante ordem, e a ordem crescente de id é o que resolve o desempate. */
	private List<Ocorrencia> carregarEmOrdemDeId(List<Long> ids) {
		List<Ocorrencia> ocorrencias = new ArrayList<>(ocorrenciaRepository.findAllById(ids));
		ocorrencias.sort(Comparator.comparing(Ocorrencia::getId));
		return ocorrencias;
	}

	/** Devolve null quando os dados são ilegíveis ou não trazem o recibo — sem abortar o lote. */
	private String extrairReciboExcluido(Ocorrencia exclusao) {
		if (!(exclusao.getDadosOcorrencia() instanceof Exclusao)) {
			LOGGER.warn("Ocorrência {} está marcada como exclusão mas seus dados não são legíveis; ignorada",
					exclusao.getId());
			return null;
		}

		Exclusao dadosExclusao = (Exclusao) exclusao.getDadosOcorrencia();
		if (dadosExclusao.getInfoExclusao() == null || dadosExclusao.getInfoExclusao().getNrRecEvt() == null) {
			LOGGER.warn("Exclusão {} não informa o recibo excluído; ignorada", exclusao.getId());
			return null;
		}

		return dadosExclusao.getInfoExclusao().getNrRecEvt();
	}

}
